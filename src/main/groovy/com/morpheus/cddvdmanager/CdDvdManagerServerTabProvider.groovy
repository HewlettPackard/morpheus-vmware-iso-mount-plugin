package com.morpheus.cddvdmanager

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/** "CD/DVD" tab on the server page of a vSphere VM. Acts on that one VM only. */
@Slf4j
class CdDvdManagerServerTabProvider extends AbstractServerTabProvider {

    protected MorpheusContext morpheusContext
    protected Plugin plugin
    private final CdDvdManagerTabRenderer tabRenderer

    CdDvdManagerServerTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
        this.tabRenderer = new CdDvdManagerTabRenderer(plugin, morpheusContext)
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        return tabRenderer.render(CdDvdManagerTabRenderer.SCOPE_SERVER, server.id, server.name) {
            VmTargets.forServer(morpheusContext, server)
        }
    }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        try {
            // Fails closed: a user without the permission never sees the tab.
            String permLevel = SecurityUtil.permissionLevel(user, CdDvdManagerController.PERMISSION_CODE)
            if (permLevel == 'none') return false

            // Hides hosts and other servers that are not a vCenter VM
            if (!SecurityUtil.isValidVmId(server.externalId?.trim())) return false

            Long cloudId = server.cloud?.id
            if (!cloudId) return false
            Cloud cloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
            if (!SecurityUtil.isVmwareCloud(cloud)) return false

            CdDvdManagerTabRenderer.rememberPermission(permLevel)
            return true
        } catch (Exception e) {
            log.error("CdDvdManagerServerTabProvider.show failed for server ${server?.id}", e)
            CdDvdManagerTabRenderer.forgetPermission()
            return false
        }
    }

    @Override
    MorpheusContext getMorpheus() { return this.morpheusContext }

    @Override
    Plugin getPlugin() { return this.plugin }

    @Override
    String getCode() { return "cd-dvd-serverTab" }

    @Override
    String getName() { return "CD/DVD" }
}
