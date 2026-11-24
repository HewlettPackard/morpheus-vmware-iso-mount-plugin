package com.morpheus.cddvdmanager

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/** "CD/DVD" tab on the instance page of a vSphere-backed instance. */
@Slf4j
class CdDvdManagerInstanceTabProvider extends AbstractInstanceTabProvider {

    protected MorpheusContext morpheusContext
    protected Plugin plugin
    private final CdDvdManagerTabRenderer tabRenderer

    CdDvdManagerInstanceTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
        this.tabRenderer = new CdDvdManagerTabRenderer(plugin, morpheusContext)
    }

    @Override
    HTMLResponse renderTemplate(Instance instance) {
        return tabRenderer.render(CdDvdManagerTabRenderer.SCOPE_INSTANCE, instance.id, instance.name) {
            VmTargets.forInstance(morpheusContext, instance)
        }
    }

    @Override
    Boolean show(Instance instance, User user, Account account) {
        try {
            // Hidden without at least read access. The level only decides which buttons are drawn; the controller enforces it again.
            String permLevel = SecurityUtil.permissionLevel(user, CdDvdManagerController.PERMISSION_CODE)
            if (permLevel == 'none') return false

            Long zoneId = instance.provisionZoneId
            if (!zoneId) return false
            Cloud cloud = morpheusContext.async.cloud.get(zoneId).blockingGet()
            if (!SecurityUtil.isVmwareCloud(cloud)) return false

            CdDvdManagerTabRenderer.rememberPermission(permLevel)
            return true
        } catch (Exception e) {
            log.error("CdDvdManagerInstanceTabProvider.show failed for instance ${instance?.id}", e)
            CdDvdManagerTabRenderer.forgetPermission()
            return false
        }
    }

    @Override
    MorpheusContext getMorpheus() { return this.morpheusContext }

    @Override
    Plugin getPlugin() { return this.plugin }

    @Override
    String getCode() { return "cd-dvd-instanceTab" }

    @Override
    String getName() { return "CD/DVD" }
}
