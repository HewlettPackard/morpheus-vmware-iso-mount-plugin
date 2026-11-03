package com.morpheus.cddvdmanager

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * PluginController for CD-DVD Manager operations. Every route takes exactly one of instanceId or
 * serverId and nothing that identifies a cloud, host or VM: the object, its cloud, the vCenter
 * credentials and the VMs are all resolved on the server.
 *
 *   /plugin/isoMount/vmData          instanceId|serverId, target   (read access)
 *   /plugin/isoMount/datastores      instanceId|serverId, target
 *   /plugin/isoMount/browse          instanceId|serverId, target, datastore, path, filterEmpty
 *   /plugin/isoMount/mount           instanceId|serverId, target, iso
 *   /plugin/isoMount/unmount         instanceId|serverId, target
 *   /plugin/isoMount/question        instanceId|serverId, target
 *   /plugin/isoMount/answerQuestion  instanceId|serverId, target, questionId, answer
 *
 * For an instance with several VMs, target is required: a server id of that instance (that VM only) or
 * 'all' (every VM for mount and unmount, no VM for the pending-question routes). A server id means that VM
 * only. Every request must carry the X-Requested-With header and, when the browser sends an Origin or
 * Referer, it must match this host. Changes need 'full' access on the cd-dvd-tab permission and the caller
 * must belong to the object's account (or the master account).
 */
@Slf4j
class CdDvdManagerController implements PluginController {

    static final String PERMISSION_CODE = 'cd-dvd-tab'

    Plugin plugin
    MorpheusContext morpheus

    CdDvdManagerController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    final String code = 'isoMount-controller'
    final String name = 'CD-DVD Manager Controller'

    @Override String getCode() { return code }
    @Override String getName() { return name }

    @Override
    List<Route> getRoutes() {
        def perm = Permission.build(PERMISSION_CODE, 'full')
        def readPerm = Permission.build(PERMISSION_CODE, 'read')
        // Registered with and without a leading slash, as the platform has accepted both forms.
        List<Route> routes = []
        [
            [path: 'isoMount/vmData',          action: 'vmData',           perm: readPerm],
            [path: 'isoMount/datastores',      action: 'listDatastores',    perm: perm],
            [path: 'isoMount/browse',          action: 'browseDatastore',   perm: perm],
            [path: 'isoMount/mount',           action: 'mountIso',          perm: perm],
            [path: 'isoMount/unmount',         action: 'unmountIso',        perm: perm],
            [path: 'isoMount/question',        action: 'getVmQuestion',     perm: perm],
            [path: 'isoMount/answerQuestion',  action: 'answerVmQuestion',  perm: perm],
        ].each { Map r ->
            routes << Route.build(r.path as String, r.action as String, r.perm as Permission)
            routes << Route.build('/' + r.path, r.action as String, r.perm as Permission)
        }
        return routes
    }

    /** What a route handler works with once the request has been authorized. */
    private static final class Ctx {
        VCenterApiClient client
        VmTargets.Resolved resolved
        String primaryVmMor   // verified in vCenter; only set when the action needs the primary VM
        User user
        String logObject
        boolean allSelected   // an instance with several VMs, with 'all' picked
    }

    // -----------------------------------------------------------------------
    // Routes
    // -----------------------------------------------------------------------

    /** Drives of the selected VM (the primary VM for 'all'), for the instance tab's VM picker. */
    def vmData(ViewModel<Map> model) {
        return run(model, 'vmData', true, false) { Ctx c ->
            return [success: true, certUntrusted: c.client.isCertUntrusted(), vmName: c.resolved.primary.name,
                    cdroms: c.client.getCdroms(c.primaryVmMor)]
        }
    }

    def listDatastores(ViewModel<Map> model) {
        return run(model, 'listDatastores', true) { Ctx c ->
            def datastores = c.client.listDatastoresForVm(c.primaryVmMor).collect { Map ds -> [name: ds.name, type: ds.type] }
            return [success: true, datastores: datastores]
        }
    }

    def browseDatastore(ViewModel<Map> model) {
        String datastoreName = SecurityUtil.cleanDatastoreName(model?.request?.getParameter('datastore'))
        String path = SecurityUtil.cleanRelativePath(model?.request?.getParameter('path'))
        if (datastoreName == null || path == null) {
            return respond([success: false, msg: 'Invalid request parameters'])
        }
        boolean filterEmpty = model.request.getParameter('filterEmpty')?.trim() != 'false'

        return run(model, 'browseDatastore', false) { Ctx c ->
            def result = c.client.browseDatastoreFiltered(datastoreName, path, filterEmpty)
            return [success: true, folders: result.folders, files: result.files, path: path, datastore: datastoreName, filtered: filterEmpty]
        }
    }

    /**
     * Mounts the ISO on the first CD/DVD drive of every VM. On an instance with several VMs a VM whose
     * drive already holds an ISO is skipped; on a single VM the ISO replaces the current one.
     * One VM failing does not stop the others.
     */
    def mountIso(ViewModel<Map> model) {
        List<String> iso = SecurityUtil.parseIsoPath(model?.request?.getParameter('iso'))
        if (iso == null) {
            return respond([success: false, msg: 'Invalid request parameters'])
        }
        String isoPath = "[${iso[0]}] ${iso[1]}".toString()

        return run(model, 'mountIso', false) { Ctx c ->
            List<VmTargets.Target> targets = c.resolved.targets
            boolean several = targets.size() > 1
            List<String> mounted = []
            List<String> skipped = []
            List<String> failed = []

            targets.each { VmTargets.Target t ->
                try {
                    String vmMor = c.client.verifyVmMor(t.vmMor)
                    if (!vmMor) {
                        failed.add("${t.name}: VM not found in vCenter".toString())
                        return
                    }
                    List<Map> cdroms = c.client.getCdroms(vmMor)
                    if (cdroms.isEmpty()) {
                        failed.add("${t.name}: no CD/DVD drive".toString())
                        return
                    }
                    Map drive = cdroms.first()
                    if (several && (drive.backing as Map)?.iso_file) {
                        skipped.add(t.name)
                        return
                    }
                    c.client.mountIso(vmMor, drive.cdrom as String, isoPath)
                    mounted.add(t.name)
                    log.info("AUDIT cdDvd action=mount outcome=success user=${c.user.id} ${c.logObject} vm=${t.serverId} cdrom=${drive.cdrom} iso='${isoPath}'")
                } catch (Exception e) {
                    failed.add("${t.name}: ${failureText(e, 'mount', t, c)}".toString())
                }
            }

            if (!several) {
                return failed.isEmpty() ? [success: true, mounted: mounted.size(), msg: 'ISO mounted successfully']
                                        : [success: false, mounted: 0, msg: failed.first()]
            }
            return [success: failed.isEmpty(), mounted: mounted.size(), msg: summary('Mounted', mounted, targets.size(),
                'skipped, disc already mounted', skipped, failed)]
        }
    }

    /** Disconnects the first CD/DVD drive of every VM that has a disc; a VM without one is a no-op. */
    def unmountIso(ViewModel<Map> model) {
        return run(model, 'unmountIso', false) { Ctx c ->
            List<VmTargets.Target> targets = c.resolved.targets
            boolean several = targets.size() > 1
            List<String> ejected = []
            List<String> nothing = []
            List<String> failed = []

            targets.each { VmTargets.Target t ->
                try {
                    String vmMor = c.client.verifyVmMor(t.vmMor)
                    if (!vmMor) {
                        failed.add("${t.name}: VM not found in vCenter".toString())
                        return
                    }
                    List<Map> cdroms = c.client.getCdroms(vmMor)
                    Map drive = cdroms.isEmpty() ? null : cdroms.first()
                    if (drive == null || !(drive.backing as Map)?.iso_file) {
                        nothing.add(t.name)
                        return
                    }
                    c.client.unmountIso(vmMor, drive.cdrom as String)
                    ejected.add(t.name)
                    log.info("AUDIT cdDvd action=unmount outcome=success user=${c.user.id} ${c.logObject} vm=${t.serverId} cdrom=${drive.cdrom}")
                } catch (Exception e) {
                    failed.add("${t.name}: ${failureText(e, 'unmount', t, c)}".toString())
                }
            }

            if (!several) {
                if (!failed.isEmpty()) return [success: false, ejected: 0, msg: failed.first()]
                return ejected.isEmpty() ? [success: true, ejected: 0, msg: 'No disc is mounted']
                                         : [success: true, ejected: 1, msg: 'ISO unmounted successfully']
            }
            return [success: failed.isEmpty(), ejected: ejected.size(), msg: summary('Ejected', ejected, targets.size(),
                'no disc mounted', nothing, failed)]
        }
    }

    // A pending vCenter question (e.g. the guest has locked the CD-ROM) is shown to a person, who decides.
    // It is checked on the selected VM only; with 'all' there is no single VM, so none is checked.
    def getVmQuestion(ViewModel<Map> model) {
        return run(model, 'getVmQuestion', true) { Ctx c ->
            if (c.allSelected) return [success: true, question: null]
            return [success: true, question: c.client.getVmQuestion(c.primaryVmMor)]
        }
    }

    // Only answers that vCenter itself offered for the question that is pending right now are accepted.
    def answerVmQuestion(ViewModel<Map> model) {
        String questionId = SecurityUtil.cleanToken(model?.request?.getParameter('questionId'))
        String answer = SecurityUtil.cleanToken(model?.request?.getParameter('answer'))
        if (questionId == null || answer == null) {
            return respond([success: false, msg: 'Invalid request parameters'])
        }

        return run(model, 'answerVmQuestion', true) { Ctx c ->
            if (c.allSelected) return [success: false, msg: 'Select a VM first']
            Map pending = c.client.getVmQuestion(c.primaryVmMor)
            boolean offered = pending != null && pending.id == questionId &&
                (pending.choices as List<Map>).any { Map ch -> ch.key == answer }
            if (!offered) {
                return [success: false, msg: 'The question is no longer pending']
            }
            c.client.answerVmQuestion(c.primaryVmMor, questionId, answer)
            log.info("AUDIT cdDvd action=answerQuestion outcome=success user=${c.user.id} ${c.logObject} question=${questionId} answer=${answer}")
            return [success: true, msg: 'Question answered']
        }
    }

    // -----------------------------------------------------------------------
    // Shared request handling
    // -----------------------------------------------------------------------

    private static String failureText(Exception e, String action, VmTargets.Target t, Ctx c) {
        String vmRef = SecurityUtil.newRef()
        log.error("CdDvd action=${action} outcome=failed ref=${vmRef} user=${c.user.id} ${c.logObject} vm=${t.serverId}", e)
        return SecurityUtil.describeFailure(e, vmRef)
    }

    private static String summary(String verb, List<String> done, int total, String skipLabel, List<String> skipped, List<String> failed) {
        List<String> parts = ["${verb} on ${done.size()} of ${total} VMs".toString()]
        if (!skipped.isEmpty()) parts.add("Skipped (${skipLabel}): ${skipped.join(', ')}".toString())
        if (!failed.isEmpty()) parts.add("Failed: ${failed.join('; ')}".toString())
        return parts.join('. ') + '.'
    }

    /**
     * Guards the request, authorizes the caller for the instance or server, resolves its VMs, connects
     * to its vCenter and runs the action. The primary VM is confirmed in vCenter only when the action needs it.
     */
    private HTMLResponse run(ViewModel<Map> model, String action, boolean needPrimaryVm, Closure<Map> body) {
        return run(model, action, needPrimaryVm, true, body)
    }

    private HTMLResponse run(ViewModel<Map> model, String action, boolean needPrimaryVm, boolean requireFull, Closure<Map> body) {
        String ref = SecurityUtil.newRef()
        Long logUserId = null
        String logObject = null
        VCenterApiClient client = null
        try {
            String requestError = SecurityUtil.checkRequest(model?.request)
            if (requestError) {
                log.warn("CdDvd action=${action} outcome=rejected reason='${requestError}'")
                return respond([success: false, msg: requestError])
            }

            User user = model.user
            logUserId = user?.id
            if (user == null) {
                log.warn("CdDvd action=${action} outcome=rejected reason='no authenticated user'")
                return respond([success: false, msg: 'Not authenticated'])
            }
            boolean permitted = requireFull ? SecurityUtil.hasFull(user, PERMISSION_CODE) : SecurityUtil.permissionLevel(user, PERMISSION_CODE) != 'none'
            if (!permitted) {
                log.warn("CdDvd action=${action} outcome=denied user=${user.id} reason='needs ${requireFull ? 'full ' : ''}${PERMISSION_CODE}'")
                return respond([success: false, msg: 'Permission denied'])
            }

            String instanceParam = model.request.getParameter('instanceId')
            String serverParam = model.request.getParameter('serverId')
            boolean hasInstance = instanceParam != null && !instanceParam.isEmpty()
            boolean hasServer = serverParam != null && !serverParam.isEmpty()
            Long instanceId = hasInstance ? SecurityUtil.parseId(instanceParam) : null
            Long serverId = hasServer ? SecurityUtil.parseId(serverParam) : null
            // Exactly one of instanceId / serverId must be given, and it must be a valid id
            boolean idOk = (hasInstance != hasServer) && (hasInstance ? instanceId != null : serverId != null)
            if (!idOk) {
                return respond([success: false, msg: 'Invalid request parameters'])
            }
            logObject = hasInstance ? "instance=${instanceId}".toString() : "server=${serverId}".toString()

            VmTargets.Resolved resolved
            boolean allSelected = false
            if (hasInstance) {
                Instance instance = loadInstance(instanceId)
                if (instance == null || !SecurityUtil.canAccessAccount(user, instance.account)) {
                    log.warn("CdDvd action=${action} outcome=denied user=${user.id} ${logObject} reason='not found or not accessible'")
                    return respond([success: false, msg: 'Instance not found'])
                }
                String target = model.request.getParameter('target')?.trim()
                resolved = VmTargets.select(VmTargets.forInstance(morpheus, instance), target)
                allSelected = resolved.targets.size() > 1
            } else {
                ComputeServer server = loadServer(serverId)
                if (server == null || !SecurityUtil.canAccessAccount(user, server.account)) {
                    log.warn("CdDvd action=${action} outcome=denied user=${user.id} ${logObject} reason='not found or not accessible'")
                    return respond([success: false, msg: 'Server not found'])
                }
                resolved = VmTargets.forServer(morpheus, server)
            }

            VCenterCredentials creds = VCenterCredentials.resolve(morpheus, plugin, resolved.cloudId)
            if (creds == null) {
                return respond([success: false, msg: 'vCenter is not available for this cloud'])
            }

            client = new VCenterApiClient(creds.host, creds.user, creds.password, creds.allowUntrusted)
            client.login()

            Ctx ctx = new Ctx(client: client, resolved: resolved, user: user, logObject: logObject, allSelected: allSelected)
            if (needPrimaryVm) {
                ctx.primaryVmMor = client.verifyVmMor(resolved.primary.vmMor)
                if (!ctx.primaryVmMor) {
                    return respond([success: false, msg: 'VM not found in vCenter'])
                }
            }
            return respond(body.call(ctx))
        } catch (IllegalStateException e) {
            log.warn("CdDvd action=${action} outcome=refused ref=${ref} user=${logUserId} ${logObject} reason='${e.message}'")
            return respond([success: false, msg: e.message])
        } catch (Exception e) {
            log.error("CdDvd action=${action} outcome=failed ref=${ref} user=${logUserId} ${logObject}", e)
            return respond([success: false, msg: SecurityUtil.describeFailure(e, ref)])
        } finally {
            if (client != null) {
                try { client.logout() } catch (Exception ignored) {}
            }
        }
    }

    private Instance loadInstance(Long instanceId) {
        try {
            return morpheus.async.instance.get(instanceId).blockingGet()
        } catch (Exception ignored) {
            return null
        }
    }

    private ComputeServer loadServer(Long serverId) {
        try {
            return morpheus.async.computeServer.get(serverId).blockingGet()
        } catch (Exception ignored) {
            return null
        }
    }

    private static HTMLResponse respond(Map payload) {
        return HTMLResponse.success(SecurityUtil.jsonForScript(payload))
    }
}
