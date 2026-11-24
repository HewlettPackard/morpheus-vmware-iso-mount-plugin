package com.morpheus.cddvdmanager

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.User
import com.morpheusdata.model.projection.DatastoreIdentity
import groovy.util.logging.Slf4j

import java.util.regex.Pattern

/**
 * The vCenter datastores a Morpheus user may use, decided from Morpheus's own datastore permissions:
 * the user's role access to Infrastructure: Storage, the tenant and group sharing of each datastore
 * (as Morpheus lists accessible resources for the user's tenant and the instance's group), and the
 * datastore being active and online. A vCenter datastore Morpheus has no record of is not allowed.
 */
@Slf4j
final class DatastoreAccess {

    static final String STORAGE_PERMISSION = 'infrastructure-storage'

    private static final Pattern MOREF = Pattern.compile('^datastore-[0-9]{1,18}$')

    private final Set<String> morefs
    private final Set<String> names
    private final boolean matchByName

    private DatastoreAccess(Set<String> morefs, Set<String> names, boolean matchByName) {
        this.morefs = morefs
        this.names = names
        this.matchByName = matchByName
    }

    /** Matched by vCenter reference (datastore-123), and by name only when Morpheus keeps no such references for the cloud. */
    boolean allows(String datastoreMor, String datastoreName) {
        if (matchByName) return datastoreName != null && names.contains(datastoreName)
        return datastoreMor != null && morefs.contains(datastoreMor)
    }

    /** Throws IllegalStateException with a short public message when access cannot be determined. */
    static DatastoreAccess resolve(MorpheusContext morpheus, User user, Long cloudId, Long siteId) {
        String level = user?.permissions?.get(STORAGE_PERMISSION)?.toString()?.toLowerCase()
        if (level == 'none') {
            log.warn("DatastoreAccess: user=${user?.id} has no access to ${STORAGE_PERMISSION}")
            return new DatastoreAccess([] as Set, [] as Set, false)
        }

        Long accountId = user?.account?.id
        if (accountId == null || cloudId == null) {
            throw new IllegalStateException('Datastore access could not be determined')
        }

        try {
            List<DatastoreIdentity> inCloud = morpheus.async.cloud.datastore.listSyncProjections(cloudId).toList().blockingGet()
            Set<Long> accessible = new HashSet<Long>(morpheus.async.resourcePermission
                .listAccessibleResources(accountId, ResourcePermission.ResourceType.Datastore, siteId, null).toList().blockingGet())

            List<Long> candidateIds = inCloud.findAll { DatastoreIdentity d -> accessible.contains(d.id) }.collect { DatastoreIdentity d -> d.id }
            List<Datastore> usable = []
            if (!candidateIds.isEmpty()) {
                usable = morpheus.async.cloud.datastore.listById(candidateIds).toList().blockingGet()
                    .findAll { Datastore d -> d.active != false && d.online != false && d.allowRead != false }
            }

            boolean hasMorefs = inCloud.any { DatastoreIdentity d -> MOREF.matcher(d.externalId ?: '').matches() }
            if (!hasMorefs) {
                log.warn("DatastoreAccess: cloud ${cloudId} datastores carry no vCenter references; matching by name")
            }
            Set<String> morefs = usable.collect { Datastore d -> d.externalId }.findAll { String e -> e != null } as Set
            Set<String> names = usable.collect { Datastore d -> d.name }.findAll { String n -> n != null } as Set
            return new DatastoreAccess(morefs, names, !hasMorefs)
        } catch (Exception e) {
            log.error("DatastoreAccess: lookup failed for user=${user?.id} cloud=${cloudId}", e)
            throw new IllegalStateException('Datastore access could not be determined')
        }
    }
}
