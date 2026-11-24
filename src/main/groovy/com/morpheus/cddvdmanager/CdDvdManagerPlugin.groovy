/*
* Copyright 2024 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.morpheus.cddvdmanager

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * Entry point for the CD-DVD Manager plugin.
 *
 * This plugin adds a CD/DVD tab to both the instance page and the server page. It allows users to:
 *   - Browse vCenter datastores
 *   - Navigate folder hierarchies
 *   - Select ISO files
 *   - Mount/unmount ISOs to VM CD/DVD drives
 *
 * Plugin settings are stored encrypted by Morpheus and surfaced in
 * Admin → Plugins → CD-DVD Manager Plugin → Settings.
 *
 * The tab's JavaScript calls the controller endpoints via AJAX without leaving the page.
 *
 * Route prefix: /plugin/isoMount/
 */
@Slf4j
class CdDvdManagerPlugin extends Plugin {

    private CdDvdManagerInstanceTabProvider instanceTabProvider
    private CdDvdManagerServerTabProvider serverTabProvider
    private CdDvdManagerController controller

    @Override
    String getCode() {
        return 'isoMount'
    }

    @Override
    void initialize() {
        this.setName("CD-DVD Manager Plugin")
        this.setDescription("Manage VM CD/DVD drives - browse vCenter datastores and mount/unmount ISO files directly from the Morpheus instance and server tabs")
        this.setAuthor("LYB Platform Team")

        // ---------------------------------------------------------------
        // Plugin settings
        // The vCenter host and credentials are taken from the Morpheus cloud that owns
        // the instance. The user name and password below are only used for a cloud that
        // has no usable credentials of its own, and are always sent to that cloud's host.
        // ---------------------------------------------------------------
        this.settings << new OptionType(
            name        : 'vCenter Username',
            code        : 'isoMount-vcenter-user',
            fieldName   : 'vCenterUser',
            displayOrder: 0,
            fieldLabel  : 'vCenter Username (fallback)',
            helpText    : 'Optional. Used only when a vCenter cloud has no credentials of its own. Sent only to that cloud\'s vCenter host.',
            required    : false,
            inputType   : OptionType.InputType.TEXT
        )

        this.settings << new OptionType(
            name        : 'vCenter Password',
            code        : 'isoMount-vcenter-password',
            fieldName   : 'vCenterPassword',
            displayOrder: 1,
            fieldLabel  : 'vCenter Password (fallback)',
            helpText    : 'Optional. Used only when a vCenter cloud has no credentials of its own. Sent only to that cloud\'s vCenter host.',
            required    : false,
            inputType   : OptionType.InputType.PASSWORD
        )

        this.settings << new OptionType(
            name        : 'Allow untrusted vCenter certificate',
            code        : 'isoMount-allow-untrusted-cert',
            fieldName   : 'allowUntrustedCert',
            displayOrder: 2,
            fieldLabel  : 'Allow untrusted vCenter certificate (insecure)',
            helpText    : 'Off by default. When off, the vCenter certificate must be trusted by the Morpheus JVM and match the host name. Turn on only if the vCenter certificate cannot be trusted, since it allows an attacker on the network to impersonate vCenter.',
            required    : false,
            inputType   : OptionType.InputType.CHECKBOX
        )

        // ---------------------------------------------------------------
        // Permissions
        // none: hide the tab entirely
        // read: show tab with read-only view (no Browse Datastores, Eject, Check vCenter)
        // full: full access to all features
        // ---------------------------------------------------------------
        this.setPermissions([
            Permission.build('CD/DVD Tab', 'cd-dvd-tab',
                [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full])
        ])

        // ---------------------------------------------------------------
        // Register providers
        // ---------------------------------------------------------------
        log.debug("CdDvdManagerPlugin: registering instance and server tab providers")
        this.instanceTabProvider = new CdDvdManagerInstanceTabProvider(this, this.morpheus)
        this.registerProvider(instanceTabProvider)
        this.serverTabProvider = new CdDvdManagerServerTabProvider(this, this.morpheus)
        this.registerProvider(serverTabProvider)

        // Set a non-null renderer so hasCustomRender() returns true.
        // This prevents PluginManager.registerPlugin() from calling
        // renderer.addTemplateLoader() which throws UnsupportedOperationException
        // on Morpheus 8.1.2 due to an immutable list in DynamicTemplateLoader.
        // The tab provider renders HTML directly; the renderer field is unused.
        this.renderer = new HandlebarsRenderer()

        log.debug("CdDvdManagerPlugin: registering controller")
        this.controller = new CdDvdManagerController(this, this.morpheus)
        this.controllers.add(controller)
        log.debug("CdDvdManagerPlugin: controller registered, routes at /plugin/isoMount/*")

        log.info("CdDvdManagerPlugin: initialized")
    }

    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        log.info("CdDvdManagerPlugin: destroyed")
    }
}
