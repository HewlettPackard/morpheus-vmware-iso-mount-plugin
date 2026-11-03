package com.morpheus.cddvdmanager

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/**
 * CD-DVD Manager tab, shared by the instance page and the server page - Modern popup-based UI
 * for mounting ISO files to VM CD/DVD drives.
 *
 * The drives shown are those of the primary VM. On an instance with several VMs the tab says so,
 * and mount / eject are applied to all of them.
 *
 * Features:
 * - Clean, minimal tab showing only CD/DVD status
 * - Modal popup for browsing datastores (lazy loaded)
 * - SVG icons for professional look
 * - Only fetches data when user clicks "Browse Datastores"
 */
@Slf4j
class CdDvdManagerTabRenderer {

    static final String SCOPE_INSTANCE = 'instance'
    static final String SCOPE_SERVER = 'server'

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    // Set by a provider's show(), read by its render(); only drives which buttons are drawn.
    private static final ThreadLocal<String> currentPermissionLevel = new ThreadLocal<String>()

    CdDvdManagerTabRenderer(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    static void rememberPermission(String level) { currentPermissionLevel.set(level) }

    static void forgetPermission() { currentPermissionLevel.remove() }

    HTMLResponse render(String scope, Long objectId, String objectName, Closure<VmTargets.Resolved> resolver) {
        log.debug("CdDvdManagerTabRenderer.render: ${scope}=${objectId}")

        // Permission level from ThreadLocal (set by show()); fail closed when it is missing.
        // It only decides which buttons are drawn; the controller enforces it again on every call.
        String permLevel = currentPermissionLevel.get() ?: 'read'
        boolean canWrite = (permLevel == 'full')
        currentPermissionLevel.remove()

        def b = new StringBuilder()

        boolean hasVm = false
        List<Map> cdromDevices = []
        List<String> vmNames = []
        List<Map> vmOptions = []
        boolean dynamic = false
        String primaryName = ''
        String errorMsg = ""
        boolean certUntrusted = false

        String ref = SecurityUtil.newRef()
        VCenterApiClient client = null
        try {
            VmTargets.Resolved resolved = resolver.call()
            VCenterCredentials creds = VCenterCredentials.resolve(morpheusContext, plugin, resolved.cloudId)
            if (creds == null) {
                errorMsg = "vCenter is not available for this cloud. Ensure the vCenter cloud integration has credentials set."
            } else {
                vmNames = resolved.targets.collect { VmTargets.Target t -> t.name }
                primaryName = resolved.primary.name
                if (scope == SCOPE_INSTANCE && resolved.targets.size() > 1) {
                    // Several VMs: the user picks one, and the drives are loaded from vmData; no vCenter call yet.
                    dynamic = true
                    vmOptions = resolved.targets.collect { VmTargets.Target t -> [id: t.serverId, name: t.name] }
                } else {
                    client = new VCenterApiClient(creds.host, creds.user, creds.password, creds.allowUntrusted)
                    client.login()
                    certUntrusted = client.isCertUntrusted()

                    // Only confirm the VM and read its CD-ROM devices - no datastore browsing
                    String vmMor = client.verifyVmMor(resolved.primary.vmMor)
                    if (vmMor) {
                        hasVm = true
                        cdromDevices = client.getCdroms(vmMor)
                        log.debug("CdDvdManagerTabRenderer: ${cdromDevices.size()} CD-ROM device(s) for ${scope} ${objectId}")
                    } else {
                        errorMsg = "VM not found in vCenter"
                    }
                }
            }
        } catch (IllegalStateException e) {
            log.warn("CdDvdManagerTabRenderer: refused ref=${ref} ${scope}=${objectId} reason='${e.message}'")
            errorMsg = e.message
        } catch (Exception e) {
            log.error("CdDvdManagerTabRenderer: load failed ref=${ref} ${scope}=${objectId}", e)
            errorMsg = SecurityUtil.describeFailure(e, ref)
        } finally {
            if (client != null) {
                try { client.logout() } catch (Exception ignored) {}
            }
        }

        // JSON embedded in a script element must not be able to close it
        String cdromsJson = SecurityUtil.jsonForScript(cdromDevices)

        // Build HTML with embedded CSS and JS
        b.append(buildStyles())
        b.append(buildHtml(scope, objectId, vmNames, vmOptions, dynamic, primaryName, hasVm, cdromDevices, errorMsg, canWrite, certUntrusted))
        b.append(buildScript(scope, objectId, hasVm, cdromsJson, canWrite, vmNames.size(), dynamic))

        return HTMLResponse.success(b.toString())
    }
    
    private String buildStyles() {
        return '''<style>
/* ===== vCenter-Style CSS Variables ===== */
:root {
  --vc-primary: #0079b8;
  --vc-primary-hover: #006ba1;
  --vc-success: #60b515;
  --vc-danger: #f54f47;
  --vc-warning: #fdc32b;
  --vc-bg-light: #ffffff;
  --vc-bg-gray: #f5f5f5;
  --vc-bg-hover: #e8f4fc;
  --vc-bg-selected: #d9edf7;
  --vc-text: #1b1b1b;
  --vc-text-muted: #6d6d6d;
  --vc-text-link: #0079b8;
  --vc-border: #d8d8d8;
  --vc-border-dark: #ccc;
}

/* ===== Container ===== */
.cd-container {
  padding: 16px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  color: var(--vc-text);
  max-width: 100%;
  background: var(--vc-bg-light);
}

/* ===== Header ===== */
.cd-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--vc-border);
}

.cd-header-icon {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cd-header-icon svg {
  width: 22px;
  height: 22px;
  fill: var(--vc-text-muted);
}

.cd-header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--vc-text);
}

/* ===== Status Card ===== */
.cd-status-card {
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  margin-bottom: 16px;
}

.cd-drive-row {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--vc-border);
}

.cd-drive-row:last-child {
  border-bottom: none;
}

.cd-drive-row:hover {
  background: var(--vc-bg-hover);
}

.cd-drive-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}

.cd-drive-icon svg {
  width: 24px;
  height: 24px;
  fill: var(--vc-text-muted);
}

.cd-drive-info {
  flex: 1;
  min-width: 0;
}

.cd-drive-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--vc-text);
  margin-bottom: 2px;
}

.cd-drive-iso {
  font-size: 12px;
  color: var(--vc-text-muted);
  word-break: break-all;
}

.cd-drive-iso.empty {
  color: var(--vc-text-muted);
  font-style: italic;
}

.cd-drive-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 12px;
}

.cd-status-badge {
  padding: 3px 8px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 500;
}

.cd-status-badge.connected {
  background: #e6f4ea;
  color: #1e7e34;
}

.cd-status-badge.disconnected {
  background: var(--vc-bg-gray);
  color: var(--vc-text-muted);
}

/* ===== Action Buttons ===== */
.cd-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.cd-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 3px;
  font-size: 13px;
  font-weight: 400;
  cursor: pointer;
  border: 1px solid var(--vc-border-dark);
  transition: all 0.15s ease;
  background: var(--vc-bg-light);
  color: var(--vc-text);
}

.cd-btn svg {
  width: 14px;
  height: 14px;
}

.cd-btn:hover {
  background: var(--vc-bg-gray);
}

.cd-btn-primary {
  background: var(--vc-primary);
  color: white;
  border-color: var(--vc-primary);
}

.cd-btn-primary:hover {
  background: var(--vc-primary-hover);
  border-color: var(--vc-primary-hover);
}

.cd-btn-danger {
  color: var(--vc-danger);
  border-color: var(--vc-danger);
  background: var(--vc-bg-light);
}

.cd-btn-danger:hover {
  background: #fef0ef;
}

.cd-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ===== Error Banner ===== */
.cd-error {
  background: #fef0ef;
  border: 1px solid var(--vc-danger);
  border-radius: 3px;
  padding: 12px;
  color: var(--vc-danger);
  margin-bottom: 16px;
  font-size: 13px;
}

/* ===== Modal ===== */
.cd-modal-overlay {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 10000;
  justify-content: center;
  align-items: center;
}

.cd-modal-overlay.active {
  display: flex;
}

.cd-modal {
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  width: 95%;
  max-width: 900px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.cd-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--vc-border);
  background: var(--vc-bg-light);
}

.cd-modal-title {
  font-size: 18px;
  font-weight: 400;
  color: var(--vc-text);
  display: flex;
  align-items: center;
  gap: 8px;
}

.cd-modal-title svg {
  display: none;
}

.cd-modal-close {
  width: 28px;
  height: 28px;
  border-radius: 3px;
  border: none;
  background: transparent;
  color: var(--vc-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
  font-size: 20px;
}

.cd-modal-close:hover {
  background: var(--vc-bg-gray);
  color: var(--vc-text);
}

.cd-modal-body {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* ===== Back Link ===== */
.cd-back-link {
  display: none;
  padding: 12px 20px;
  border-bottom: 1px solid var(--vc-border);
  background: var(--vc-bg-light);
}

.cd-back-link.active {
  display: block;
}

.cd-back-link a {
  color: var(--vc-text-link);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.cd-back-link a:hover {
  text-decoration: underline;
}

/* ===== Split Pane Layout ===== */
.cd-split-pane {
  display: none;
  flex: 1;
  overflow: hidden;
}

.cd-split-pane.active {
  display: flex;
}

/* Left Panel - Folder Tree */
.cd-left-panel {
  width: 280px;
  border-right: 1px solid var(--vc-border);
  display: flex;
  flex-direction: column;
  background: var(--vc-bg-light);
  flex-shrink: 0;
}

.cd-filter-box {
  padding: 8px 12px;
  border-bottom: 1px solid var(--vc-border);
}

.cd-filter-input {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  font-size: 12px;
  color: var(--vc-text);
  background: var(--vc-bg-light);
}

.cd-filter-input::placeholder {
  color: var(--vc-text-muted);
}

.cd-filter-input:focus {
  outline: none;
  border-color: var(--vc-primary);
}

.cd-tree-container {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.cd-tree-item {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
  color: var(--vc-text);
  border-left: 3px solid transparent;
}

.cd-tree-item:hover {
  background: var(--vc-bg-hover);
}

.cd-tree-item.selected {
  background: var(--vc-bg-selected);
  border-left-color: var(--vc-primary);
}

.cd-tree-toggle {
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 4px;
  flex-shrink: 0;
  color: var(--vc-text-muted);
}

.cd-tree-toggle svg {
  width: 10px;
  height: 10px;
  fill: currentColor;
  transition: transform 0.15s;
}

.cd-tree-toggle.expanded svg {
  transform: rotate(90deg);
}

.cd-tree-icon {
  width: 18px;
  height: 18px;
  margin-right: 6px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cd-tree-icon svg {
  width: 16px;
  height: 16px;
}

.cd-tree-icon.datastore svg { fill: #607d8b; }
.cd-tree-icon.folder svg { fill: #ffc107; }

.cd-tree-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cd-tree-children {
  display: none;
  padding-left: 20px;
}

.cd-tree-children.expanded {
  display: block;
}

/* Right Panel - File Table */
.cd-right-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--vc-bg-light);
}

.cd-table-header {
  display: flex;
  justify-content: flex-end;
  padding: 8px 12px;
  border-bottom: 1px solid var(--vc-border);
}

.cd-file-type-select {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--vc-text);
}

.cd-file-type-select select {
  padding: 4px 8px;
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  font-size: 12px;
  background: var(--vc-bg-light);
  color: var(--vc-text);
}

.cd-table-wrapper {
  flex: 1;
  overflow: auto;
}

.cd-file-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.cd-file-table thead {
  position: sticky;
  top: 0;
  background: var(--vc-bg-gray);
  z-index: 1;
}

.cd-file-table th {
  text-align: left;
  padding: 10px 12px;
  font-weight: 500;
  color: var(--vc-text);
  border-bottom: 1px solid var(--vc-border);
  white-space: nowrap;
}

.cd-file-table th .sort-icon {
  margin-left: 4px;
  color: var(--vc-text-muted);
}

.cd-file-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #eee;
  color: var(--vc-text);
}

.cd-file-table tbody tr:hover {
  background: var(--vc-bg-hover);
}

.cd-file-table tbody tr.selected {
  background: var(--vc-bg-selected);
}

.cd-file-table .col-radio {
  width: 40px;
  text-align: center;
}

.cd-file-table .col-name {
  min-width: 200px;
}

.cd-file-table .col-size {
  width: 100px;
  text-align: right;
}

.cd-file-table .col-modified {
  width: 160px;
}

.cd-radio {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.cd-file-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cd-file-name-cell svg {
  width: 18px;
  height: 18px;
  fill: var(--vc-text-muted);
  flex-shrink: 0;
}

/* ===== Datastore List (initial view) ===== */
.cd-ds-list {
  padding: 16px;
  flex: 1;
  overflow-y: auto;
  max-height: calc(100vh - 250px);
}

.cd-ds-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.cd-ds-item:hover {
  background: var(--vc-bg-hover);
  border-color: var(--vc-primary);
}

.cd-ds-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}

.cd-ds-icon svg {
  width: 24px;
  height: 24px;
  fill: #607d8b;
}

.cd-ds-info {
  flex: 1;
}

.cd-ds-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--vc-text-link);
}

.cd-ds-meta {
  font-size: 11px;
  color: var(--vc-text-muted);
  margin-top: 2px;
}

.cd-ds-arrow {
  color: var(--vc-text-muted);
  margin-left: 8px;
}

/* ===== Modal Footer ===== */
.cd-modal-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--vc-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--vc-bg-gray);
}

.cd-footer-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.cd-manage-columns {
  color: var(--vc-text-link);
  font-size: 12px;
  text-decoration: none;
  cursor: pointer;
}

.cd-manage-columns:hover {
  text-decoration: underline;
}

.cd-item-count {
  font-size: 12px;
  color: var(--vc-text-muted);
}

.cd-footer-right {
  display: flex;
  gap: 8px;
}

.cd-btn-cancel {
  background: var(--vc-bg-light);
  color: var(--vc-text);
  border: 1px solid var(--vc-border-dark);
}

.cd-btn-cancel:hover {
  background: var(--vc-bg-gray);
}

.cd-btn-ok {
  background: var(--vc-primary);
  color: white;
  border-color: var(--vc-primary);
  min-width: 60px;
}

.cd-btn-ok:hover {
  background: var(--vc-primary-hover);
}

.cd-btn-ok:disabled {
  background: #b3d4e8;
  border-color: #b3d4e8;
  cursor: not-allowed;
}

/* ===== Warning Modal (vCenter style) ===== */
.cd-warning-modal {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 10001;
  justify-content: center;
  align-items: center;
}

.cd-warning-modal.active {
  display: flex;
}

.cd-warning-box {
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  width: 90%;
  max-width: 480px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
}

.cd-warning-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--vc-bg-light);
  border-bottom: 1px solid var(--vc-border);
}

.cd-warning-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--vc-text);
}

.cd-warning-close {
  width: 28px;
  height: 28px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--vc-text-muted);
  border-radius: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}

.cd-warning-close:hover {
  background: var(--vc-bg-gray);
  color: var(--vc-text);
}

.cd-warning-body {
  padding: 20px 16px;
}

.cd-warning-text {
  font-size: 13px;
  color: var(--vc-text);
  line-height: 1.6;
  margin-bottom: 16px;
}

.cd-warning-list {
  background: var(--vc-bg-gray);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  padding: 12px 16px;
  margin-bottom: 16px;
}

.cd-warning-list-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--vc-text);
  margin-bottom: 8px;
}

.cd-warning-list-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  color: var(--vc-text-muted);
  margin-bottom: 6px;
  padding-left: 4px;
}

.cd-warning-list-item:last-child {
  margin-bottom: 0;
}

.cd-warning-list-item::before {
  content: "•";
  color: var(--vc-text-muted);
  flex-shrink: 0;
}

.cd-warning-note {
  font-size: 12px;
  color: var(--vc-danger);
  font-style: italic;
}

.cd-warning-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--vc-border);
  background: var(--vc-bg-gray);
}

/* ===== Loading ===== */
.cd-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: var(--vc-text-muted);
  font-size: 13px;
}

.cd-spinner {
  width: 32px;
  height: 32px;
  border: 2px solid var(--vc-border);
  border-top-color: var(--vc-primary);
  border-radius: 50%;
  animation: cd-spin 1s linear infinite;
  margin-bottom: 12px;
}

@keyframes cd-spin {
  to { transform: rotate(360deg); }
}

/* ===== Empty State ===== */
.cd-empty {
  text-align: center;
  padding: 32px 20px;
  color: var(--vc-text-muted);
  font-size: 13px;
}

.cd-empty-icon {
  width: 48px;
  height: 48px;
  margin: 0 auto 12px;
  opacity: 0.5;
}

/* ===== vCenter Question Modal ===== */
.cd-question-modal {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 10002;
  justify-content: center;
  align-items: center;
}

.cd-question-modal.active {
  display: flex;
}

.cd-question-box {
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-primary);
  border-radius: 3px;
  width: 90%;
  max-width: 480px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.cd-question-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  background: var(--vc-bg-light);
  border-radius: 3px 3px 0 0;
  border-bottom: 1px solid var(--vc-border);
}

.cd-question-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.cd-question-icon svg {
  width: 24px;
  height: 24px;
  fill: var(--vc-primary);
}

.cd-question-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--vc-primary);
}

.cd-question-body {
  padding: 16px;
}

.cd-question-text {
  font-size: 13px;
  color: var(--vc-text);
  line-height: 1.5;
  margin-bottom: 16px;
  white-space: pre-wrap;
}

.cd-question-choices {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cd-question-choice {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  cursor: pointer;
  transition: all 0.15s;
}

.cd-question-choice:hover {
  border-color: var(--vc-primary);
  background: var(--vc-bg-hover);
}

.cd-question-choice.selected {
  border-color: var(--vc-success);
  background: #e6f4ea;
}

.cd-question-radio {
  width: 16px;
  height: 16px;
  border: 2px solid var(--vc-border);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.cd-question-choice.selected .cd-question-radio {
  border-color: var(--vc-success);
}

.cd-question-choice.selected .cd-question-radio::after {
  content: "";
  width: 8px;
  height: 8px;
  background: var(--vc-success);
  border-radius: 50%;
}

.cd-question-label {
  font-size: 13px;
  color: var(--vc-text);
}

.cd-question-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--vc-border);
  background: var(--vc-bg-gray);
}

.cd-btn-answer {
  background: var(--vc-success);
  color: white;
  border-color: var(--vc-success);
}

.cd-btn-answer:hover {
  background: #4ca311;
}

.cd-btn-answer:disabled {
  background: var(--vc-border);
  color: var(--vc-text-muted);
  border-color: var(--vc-border);
}

/* ===== Inline vCenter Alert ===== */
.cd-vcenter-alert {
  display: none;
  margin-top: 16px;
  padding: 12px 16px;
  background: #fef0ef;
  border: 1px solid var(--vc-danger);
  border-radius: 3px;
}

.cd-vcenter-alert.active {
  display: block;
}

.cd-alert-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.cd-alert-icon {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cd-alert-icon svg {
  width: 20px;
  height: 20px;
  fill: var(--vc-danger);
}

.cd-alert-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--vc-danger);
}

.cd-alert-text {
  color: var(--vc-text);
  font-size: 12px;
  line-height: 1.5;
  margin-bottom: 12px;
  padding: 10px;
  background: var(--vc-bg-gray);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
}

.cd-alert-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.cd-btn-yes {
  background: var(--vc-success);
  color: white;
  border-color: var(--vc-success);
}

.cd-btn-yes:hover {
  background: #4ca311;
}

.cd-btn-no {
  background: var(--vc-bg-light);
  color: var(--vc-text);
  border: 1px solid var(--vc-border);
}

.cd-btn-no:hover {
  background: var(--vc-bg-gray);
}

.cd-btn-check {
  background: var(--vc-bg-light);
  color: var(--vc-text-muted);
  border: 1px solid var(--vc-border);
}

.cd-btn-check:hover {
  background: var(--vc-bg-gray);
  color: var(--vc-text);
}

.cd-btn-check:disabled {
  opacity: 0.5;
  cursor: wait;
}

.cd-btn-check svg.spin {
  animation: spinAnim 1s linear infinite;
}

@keyframes spinAnim {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Question Modal (Morpheus style) */
.question-modal {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 10002;
  justify-content: center;
  align-items: center;
}

.question-modal.active {
  display: flex;
}

.question-box {
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  width: 90%;
  max-width: 600px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
}

.question-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--vc-border);
}

.question-header-title {
  font-size: 18px;
  font-weight: 400;
  color: var(--vc-text);
}

.question-header-title span {
  color: var(--vc-text-muted);
  font-size: 14px;
  margin-left: 8px;
  padding-left: 12px;
  border-left: 1px solid var(--vc-border);
}

.question-close {
  width: 28px;
  height: 28px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--vc-text-muted);
  border-radius: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

.question-close:hover {
  background: var(--vc-bg-gray);
  color: var(--vc-text);
}

.question-body {
  padding: 20px;
}

.question-text {
  font-size: 14px;
  color: var(--vc-text);
  line-height: 1.6;
  margin-bottom: 20px;
}

.question-choices {
  margin-bottom: 16px;
}

.question-choice {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  cursor: pointer;
}

.question-choice input[type="radio"] {
  width: 18px;
  height: 18px;
  accent-color: var(--vc-primary);
  cursor: pointer;
}

.question-choice label {
  font-size: 14px;
  color: var(--vc-text);
  cursor: pointer;
}

.question-info {
  background: #e3f2fd;
  border: 1px solid #90caf9;
  border-radius: 3px;
  padding: 12px 16px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 16px;
}

.question-info-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  fill: var(--vc-primary);
}

.question-info-text {
  font-size: 13px;
  color: var(--vc-text);
  line-height: 1.5;
}

.question-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 16px 20px;
  border-top: 1px solid var(--vc-border);
  background: var(--vc-bg-gray);
}

/* ===== Toast Notifications ===== */
.cd-toast-container {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 20000;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 400px;
}

.cd-toast {
  background: var(--vc-bg-light);
  border-radius: 4px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  padding: 12px 16px;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  animation: cd-toast-slide-in 0.3s ease;
  border-left: 4px solid var(--vc-primary);
}

.cd-toast.success { border-left-color: var(--vc-success); }
.cd-toast.error { border-left-color: var(--vc-danger); }
.cd-toast.warning { border-left-color: var(--vc-warning); }
.cd-toast.info { border-left-color: var(--vc-primary); }

@keyframes cd-toast-slide-in {
  from { transform: translateX(100%); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

@keyframes cd-toast-slide-out {
  from { transform: translateX(0); opacity: 1; }
  to { transform: translateX(100%); opacity: 0; }
}

.cd-toast.hiding {
  animation: cd-toast-slide-out 0.3s ease forwards;
}

.cd-toast-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.cd-toast-icon svg {
  width: 100%;
  height: 100%;
}

.cd-toast.success .cd-toast-icon svg { fill: var(--vc-success); }
.cd-toast.error .cd-toast-icon svg { fill: var(--vc-danger); }
.cd-toast.warning .cd-toast-icon svg { fill: var(--vc-warning); }
.cd-toast.info .cd-toast-icon svg { fill: var(--vc-primary); }

.cd-toast-content {
  flex: 1;
  min-width: 0;
}

.cd-toast-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--vc-text);
  margin-bottom: 2px;
}

.cd-toast-message {
  font-size: 13px;
  color: var(--vc-text-muted);
  line-height: 1.4;
  word-break: break-word;
}

.cd-toast-close {
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--vc-text-muted);
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 3px;
  flex-shrink: 0;
}

.cd-toast-close:hover {
  background: var(--vc-bg-gray);
  color: var(--vc-text);
}

.cd-toast-details {
  font-size: 11px;
  color: var(--vc-text-muted);
  margin-top: 6px;
  padding: 6px 8px;
  background: var(--vc-bg-gray);
  border-radius: 3px;
  font-family: monospace;
  max-height: 100px;
  overflow-y: auto;
}

.cd-toast-retry {
  margin-top: 8px;
  padding: 4px 10px;
  font-size: 12px;
  background: var(--vc-bg-light);
  border: 1px solid var(--vc-border);
  border-radius: 3px;
  cursor: pointer;
  color: var(--vc-text);
}

.cd-toast-retry:hover {
  background: var(--vc-bg-gray);
}
</style>
'''
    }
    
    private String buildHtml(String scope, Long objectId, List<String> vmNames, List<Map> vmOptions, boolean dynamic, String primaryName, boolean hasVm, List<Map> cdromDevices, String errorMsg, boolean canWrite, boolean certUntrusted) {
        def b = new StringBuilder()
        
        // Toast notification container
        b.append('<div id="cdToastContainer" class="cd-toast-container"></div>\n')
        
        b.append('<div id="cdContainer" class="cd-container" data-scope="').append(esc(scope))
        b.append('" data-id="').append(objectId)
        b.append('" data-can-write="').append(canWrite).append('">\n')
        
        // Header
        b.append('''
<div class="cd-header">
  <div class="cd-header-icon">
    <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm0-6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
  </div>
  <div class="cd-header-title">CD/DVD Drive</div>
</div>
''')
        
        if (certUntrusted || (dynamic && !errorMsg)) {
            // vCenter reached over HTTPS but the certificate could not be verified (self-signed / untrusted).
            // In picker mode the warning starts hidden and is shown once vCenter has been contacted.
            b.append('<div id="cdCertWarning" class="cd-warning" style="').append(certUntrusted ? '' : 'display:none;').append('margin:0 0 12px;padding:10px 14px;border:1px solid #f0c36d;border-left:4px solid #e0a800;background:#fcf3d6;color:#7a5c00;border-radius:4px;font-size:13px;">')
            b.append('<strong>&#9888; vCenter certificate not verified.</strong> ')
            b.append('The connection is encrypted (HTTPS) but the certificate is self-signed or untrusted, so the server identity cannot be confirmed. ')
            b.append('This is permitted because &ldquo;Allow untrusted vCenter certificate&rdquo; is enabled in the plugin settings.')
            b.append('</div>\n')
        }
        
        if (errorMsg) {
            b.append('<div class="cd-error">').append(esc(errorMsg)).append('</div>\n')
        }

        if (!errorMsg && dynamic) {
            b.append('<div id="cdMultiVmBanner" style="margin:0 0 12px;padding:10px 14px;border:1px solid #a9c8ec;border-left:4px solid #1d6fc4;background:#eaf3fc;color:#164a80;border-radius:4px;font-size:13px;">')
            b.append('<strong>This instance has ').append(vmNames.size()).append(' VMs.</strong> ')
            b.append('Select the VM to view and change, or choose All VMs to change every VM at once. When mounting on All VMs, a VM that already has a disc mounted is skipped.')
            b.append('</div>\n')
            b.append('<div class="cd-vm-picker" style="margin:0 0 16px;"><label for="cdVmSelect" style="display:block;font-size:12px;font-weight:600;margin-bottom:4px;">Virtual machine</label>')
            b.append('<select id="cdVmSelect" style="min-width:280px;max-width:100%;padding:6px 8px;font-size:13px;" data-primary-name="').append(esc(primaryName)).append('">')
            b.append('<option value="" selected>Select a VM...</option>')
            vmOptions.each { Map o ->
                b.append('<option value="').append(esc(o.id)).append('">').append(esc(o.name)).append('</option>')
            }
            b.append('<option value="all">All VMs</option></select>')
            b.append('<div id="cdSelectNote" style="margin-top:6px;font-size:12px;color:#6d6d6d;"></div></div>\n')
        }
        
        // Status Card
        b.append('<div class="cd-status-card" id="cdStatusCard">\n')
        
        if (dynamic) {
            b.append('<div class="cd-empty">Select a VM to view its CD/DVD drive</div>\n')
        } else if (cdromDevices.isEmpty()) {
            b.append('<div class="cd-empty">No CD/DVD drives found on this VM</div>\n')
        } else {
            cdromDevices.each { cd ->
                def backing = cd.backing as Map
                String mountedIso = backing?.iso_file ?: ''
                String state = cd.state ?: 'NOT_CONNECTED'
                boolean connected = state?.toUpperCase()?.contains('CONNECTED') && !state?.toUpperCase()?.contains('NOT_')
                
                b.append('<div class="cd-drive-row">\n')
                b.append('  <div class="cd-drive-icon">\n')
                b.append('    <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm0-6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>\n')
                b.append('  </div>\n')
                b.append('  <div class="cd-drive-info">\n')
                b.append('    <div class="cd-drive-label">').append(esc(cd.label ?: 'CD/DVD Drive')).append('</div>\n')
                if (mountedIso) {
                    b.append('    <div class="cd-drive-iso">').append(esc(mountedIso)).append('</div>\n')
                } else {
                    b.append('    <div class="cd-drive-iso empty">No disc inserted</div>\n')
                }
                b.append('  </div>\n')
                b.append('  <div class="cd-drive-status">\n')
                if (connected) {
                    b.append('    <span class="cd-status-badge connected">Connected</span>\n')
                } else {
                    b.append('    <span class="cd-status-badge disconnected">Disconnected</span>\n')
                }
                b.append('  </div>\n')
                b.append('</div>\n')
            }
        }
        
        b.append('</div>\n') // end status card
        
        // Check if any disc is mounted
        boolean hasDiscMounted = cdromDevices.any { (it.backing as Map)?.iso_file }
        
        // Action buttons - only show Browse Datastores, Eject, Check vCenter if user has full permission
        if (((hasVm && !cdromDevices.isEmpty()) || (dynamic && !errorMsg)) && canWrite) {
            b.append("""
<div class=\"cd-actions\">
  <button id=\"btnBrowse\" class=\"cd-btn cd-btn-primary\"${dynamic ? ' disabled' : ''}>
    <svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z\"/></svg>
    Browse Datastores
  </button>
  <button id=\"btnUnmount\" class=\"cd-btn cd-btn-danger\"${(hasDiscMounted && !dynamic) ? '' : ' disabled'}>
    <svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M5 17h14v2H5zm7-12L5.33 15h13.34z\"/></svg>
    Eject
  </button>
  <button id=\"btnCheckStatus\" class=\"cd-btn cd-btn-check\"${dynamic ? ' disabled' : ''}>
    <svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z\"/></svg>
    Check vCenter
  </button>
</div>

<!-- Question Modal (Morpheus style) -->
<div id=\"questionModal\" class=\"question-modal\">
  <div class=\"question-box\">
    <div class=\"question-header\">
      <div class=\"question-header-title\">Answer Question</div>
      <button id=\"questionClose\" class=\"question-close\">&times;</button>
    </div>
    <div class=\"question-body\">
      <div id=\"questionText\" class=\"question-text\"></div>
      <div id=\"questionChoices\" class=\"question-choices\"></div>
      <div class=\"question-info\">
        <svg class=\"question-info-icon\" viewBox=\"0 0 24 24\"><path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z\"/></svg>
        <div class=\"question-info-text\">There are no other virtual machines with the same pending question that need your attention.</div>
      </div>
    </div>
    <div class=\"question-footer\">
      <button id=\"questionCancel\" class=\"cd-btn cd-btn-cancel\">CANCEL</button>
      <button id=\"questionAnswer\" class=\"cd-btn cd-btn-ok\" disabled>ANSWER</button>
    </div>
  </div>
</div>
""")
        }
        
        // Modal for browsing - vCenter style
        b.append('''
<div id="cdModal" class="cd-modal-overlay">
  <div class="cd-modal">
    <div class="cd-modal-header">
      <div class="cd-modal-title">
        <span id="modalTitle">Select File</span>
      </div>
      <button id="modalClose" class="cd-modal-close">&times;</button>
    </div>
    
    <!-- Back to datastores link -->
    <div id="backLink" class="cd-back-link">
      <a href="#" id="btnBackToDatastores">&lt; GO BACK TO DATASTORES</a>
    </div>
    
    <div id="modalBody" class="cd-modal-body">
      <!-- Initial loading / datastore list -->
      <div id="dsListView" class="cd-ds-list">
        <div class="cd-loading">
          <div class="cd-spinner"></div>
          <span>Loading datastores...</span>
        </div>
      </div>
      
      <!-- Split pane view (shown when browsing datastore) -->
      <div id="splitPane" class="cd-split-pane">
        <!-- Left panel - folder tree -->
        <div class="cd-left-panel">
          <div class="cd-filter-box">
            <input type="text" id="folderFilter" class="cd-filter-input" placeholder="Filter by a folder name">
          </div>
          <div id="treeContainer" class="cd-tree-container"></div>
        </div>
        
        <!-- Right panel - file table -->
        <div class="cd-right-panel">
          <div class="cd-table-header">
            <div class="cd-file-type-select">
              <label>File Type:</label>
              <select id="fileTypeFilter">
                <option value="*.iso">ISO Image (*.iso)</option>
              </select>
            </div>
          </div>
          <div class="cd-table-wrapper">
            <table class="cd-file-table">
              <thead>
                <tr>
                  <th class="col-radio"></th>
                  <th class="col-name">Name <span class="sort-icon">▼</span></th>
                  <th class="col-size">Size <span class="sort-icon">▼</span></th>
                  <th class="col-modified">Modified <span class="sort-icon">▼</span></th>
                </tr>
              </thead>
              <tbody id="fileTableBody"></tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
    
    <div id="modalFooter" class="cd-modal-footer">
      <div class="cd-footer-left">
        <a href="#" class="cd-manage-columns">Manage Columns</a>
        <span id="itemCount" class="cd-item-count">0 items</span>
      </div>
      <div class="cd-footer-right">
        <button id="btnCancelModal" class="cd-btn cd-btn-cancel">CANCEL</button>
        <button id="btnMountIso" class="cd-btn cd-btn-ok" disabled>OK</button>
      </div>
    </div>
  </div>
</div>

<!-- Eject Warning Modal (vCenter style) -->
<div id="cdWarningModal" class="cd-warning-modal">
  <div class="cd-warning-box">
    <div class="cd-warning-header">
      <div class="cd-warning-title">Eject Disc</div>
      <button id="btnWarningClose" class="cd-warning-close">&times;</button>
    </div>
    <div class="cd-warning-body">
      <div class="cd-warning-text" id="cdWarningText">
        Are you sure you want to eject the disc from this VM's CD/DVD drive?
      </div>
      <div class="cd-warning-list">
        <div class="cd-warning-list-title">Please verify before ejecting:</div>
        <div class="cd-warning-list-item">The VM is not currently rebooting or mid-install</div>
        <div class="cd-warning-list-item">The guest OS is not actively reading from this disc</div>
        <div class="cd-warning-list-item">No software installation is in progress</div>
        <div class="cd-warning-list-item">The VM was not booted from this ISO</div>
      </div>
      <div class="cd-warning-note">
        Ejecting a disc while in use may cause system instability or data loss.
      </div>
    </div>
    <div class="cd-warning-footer">
      <button id="btnCancelEject" class="cd-btn cd-btn-cancel">CANCEL</button>
      <button id="btnConfirmEject" class="cd-btn cd-btn-ok">EJECT</button>
    </div>
  </div>
</div>
''')
        
        b.append('</div>\n') // end container
        
        return b.toString()
    }
    
    private String buildScript(String scope, Long objectId, boolean hasVm, String cdromsJson, boolean canWrite, int vmCount, boolean dynamic) {
        def nonce = getNonce()
        def b = new StringBuilder()
        
        b.append('<script')
        if (nonce) b.append(' nonce="').append(esc(nonce)).append('"')
        b.append('>\n')
        
        b.append('window.CD_DATA = { idParam: ').append(scope == SCOPE_SERVER ? "'serverId'" : "'instanceId'")
        b.append(', objectId: ').append(objectId)
        b.append(', vmCount: ').append(vmCount)
        b.append(', dynamic: ').append(dynamic)
        b.append(', hasVm: ').append(hasVm)
        b.append(', cdroms: ').append(cdromsJson)
        b.append(', canWrite: ').append(canWrite).append(' };\n')
        
        b.append('''
(function() {
  'use strict';
  
  var DATA = window.CD_DATA || {};
  var currentDatastore = '';
  var currentPath = '/';
  var selectedIsoPath = '';
  var folderCache = {};
  var BASE_URL = '/plugin/isoMount';
  var usePost = true;
  var currentTarget = '';   // '' (nothing picked), a server id, or 'all'; only used when DATA.dynamic

  // Calls a plugin route with the instance or server id only; the server resolves the cloud, vCenter and VMs.
  // Sends X-Requested-With and the Morpheus CSRF token. If Morpheus rejects the POST, repeats it once as GET.
  function pluginCall(action, params) {
    var meta = function(n) { var e = document.querySelector('meta[name="' + n + '"]'); return e ? e.getAttribute('content') : ''; };
    var csrfToken = meta('_csrf');
    var csrfHeader = meta('_csrf_header') || 'X-CSRF-TOKEN';
    var csrfParam = meta('_csrf_parameter') || '_csrf';
    var base = Object.assign({}, params || {});
    base[DATA.idParam] = DATA.objectId;
    if (DATA.dynamic) base.target = currentTarget;
    var postParams = Object.assign({}, base);
    if (csrfToken) postParams[csrfParam] = csrfToken;
    var query = new URLSearchParams(base).toString();
    var baseHeaders = { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
    var postHeaders = Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, baseHeaders);
    if (csrfToken) postHeaders[csrfHeader] = csrfToken;

    function parse(r) {
      return r.text().then(function(t) {
        var json = null;
        try { json = JSON.parse(t); } catch (e) { json = null; }
        return { status: r.status, json: json };
      });
    }

    function viaGet() {
      usePost = false;
      return fetch(BASE_URL + '/' + action + '?' + query, { method: 'GET', headers: baseHeaders, credentials: 'same-origin' })
        .then(parse).then(function(res2) {
          if (res2.json) return res2.json;
          throw new Error('Unexpected response (HTTP ' + res2.status + ')');
        });
    }

    if (!usePost) return viaGet();

    return fetch(BASE_URL + '/' + action, {
      method: 'POST',
      headers: postHeaders,
      credentials: 'same-origin',
      body: new URLSearchParams(postParams).toString()
    }).then(parse).then(function(res) {
      if (res.json) return res.json;
      if (res.status === 403 || res.status === 404 || res.status === 405) return viaGet();
      throw new Error('Unexpected response (HTTP ' + res.status + ')');
    });
  }
  
  // SVG Icons
  var ICONS = {
    datastore: '<svg viewBox="0 0 24 24"><path d="M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z"/></svg>',
    folder: '<svg viewBox="0 0 24 24"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>',
    iso: '<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm0-6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>',
    file: '<svg viewBox="0 0 24 24"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>',
    arrow: '<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z"/></svg>',
    success: '<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>',
    error: '<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>',
    warning: '<svg viewBox="0 0 24 24"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>',
    info: '<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>'
  };
  
  // ========== Toast Notification System ==========
  var toastIdCounter = 0;
  
  /**
   * Show a toast. options: type (success|error|warning|info), title, message, details (shown in a code block),
   * retryable + onRetry (adds a Retry button), duration in ms (0 = stay until closed).
   */
  function showToast(options) {
    var container = document.getElementById('cdToastContainer');
    if (!container) return;
    
    var type = options.type || 'info';
    var title = options.title || '';
    var message = options.message || '';
    var details = options.details || '';
    var retryable = options.retryable || false;
    var duration = options.duration !== undefined ? options.duration : (type === 'error' ? 8000 : 5000);
    
    var toastId = 'cd-toast-' + (++toastIdCounter);
    var iconSvg = ICONS[type] || ICONS.info;
    
    var html = '<div id="' + toastId + '" class="cd-toast ' + type + '">' +
      '<div class="cd-toast-icon">' + iconSvg + '</div>' +
      '<div class="cd-toast-content">' +
        (title ? '<div class="cd-toast-title">' + escapeHtml(title) + '</div>' : '') +
        '<div class="cd-toast-message">' + escapeHtml(message) + '</div>' +
        (details ? '<div class="cd-toast-details">' + escapeHtml(details) + '</div>' : '') +
        (retryable ? '<button class="cd-toast-retry" data-toast-id="' + toastId + '">Retry</button>' : '') +
      '</div>' +
      '<button class="cd-toast-close" data-toast-id="' + toastId + '">&times;</button>' +
    '</div>';
    
    container.insertAdjacentHTML('beforeend', html);
    
    var toast = document.getElementById(toastId);
    if (!toast) return;
    
    var closeBtn = toast.querySelector('.cd-toast-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', function() { dismissToast(toastId); });
    }
    
    if (retryable && options.onRetry) {
      var retryBtn = toast.querySelector('.cd-toast-retry');
      if (retryBtn) {
        retryBtn.addEventListener('click', function() {
          dismissToast(toastId);
          options.onRetry();
        });
      }
    }
    
    if (duration > 0) {
      setTimeout(function() { dismissToast(toastId); }, duration);
    }
    
    return toastId;
  }
  
  function dismissToast(toastId) {
    var toast = document.getElementById(toastId);
    if (!toast) return;
    toast.classList.add('hiding');
    setTimeout(function() {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 300);
  }
  
  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  
  function notifySuccess(title, message) {
    showToast({ type: 'success', title: title, message: message, duration: 4000 });
  }
  
  function notifyError(title, message, details, retryable, onRetry) {
    showToast({
      type: 'error',
      title: title,
      message: message,
      details: details,
      retryable: retryable,
      onRetry: onRetry,
      duration: retryable ? 0 : 8000
    });
  }
  
  function notifyWarning(title, message) {
    showToast({ type: 'warning', title: title, message: message, duration: 6000 });
  }
  
  function notifyInfo(title, message) {
    showToast({ type: 'info', title: title, message: message, duration: 5000 });
  }
  
  /** Show a failed API response (data.msg) as an error toast, with Retry when a retry function is given. */
  function handleApiError(data, operation, retryFn) {
    var message = (data && data.msg) || 'An unexpected error occurred';
    notifyError(operation + ' Failed', message, '', !!retryFn, retryFn);
  }
  // ========== End Toast Notification System ==========
  
  function init() {
    var browseBtn = document.getElementById('btnBrowse');
    if (browseBtn) browseBtn.addEventListener('click', openModal);
    
    var closeBtn = document.getElementById('modalClose');
    if (closeBtn) closeBtn.addEventListener('click', closeModal);
    
    var cancelBtn = document.getElementById('btnCancelModal');
    if (cancelBtn) cancelBtn.addEventListener('click', closeModal);
    
    var overlay = document.getElementById('cdModal');
    if (overlay) overlay.addEventListener('click', function(e) {
      if (e.target === overlay) closeModal();
    });
    
    var backLink = document.getElementById('btnBackToDatastores');
    if (backLink) backLink.addEventListener('click', function(e) {
      e.preventDefault();
      showDatastoreList();
    });
    
    var unmountBtn = document.getElementById('btnUnmount');
    if (unmountBtn) unmountBtn.addEventListener('click', showEjectWarning);
    
    var mountBtn = document.getElementById('btnMountIso');
    if (mountBtn) mountBtn.addEventListener('click', doMount);
    
    // Wire up warning modal buttons
    var cancelEject = document.getElementById('btnCancelEject');
    if (cancelEject) cancelEject.addEventListener('click', hideEjectWarning);
    
    var confirmEject = document.getElementById('btnConfirmEject');
    if (confirmEject) confirmEject.addEventListener('click', doUnmount);
    
    var warningClose = document.getElementById('btnWarningClose');
    if (warningClose) warningClose.addEventListener('click', hideEjectWarning);
    
    var warningOverlay = document.getElementById('cdWarningModal');
    if (warningOverlay) warningOverlay.addEventListener('click', function(e) {
      if (e.target === warningOverlay) hideEjectWarning();
    });
    
    // Wire up Check vCenter button
    var checkBtn = document.getElementById('btnCheckStatus');
    if (checkBtn) checkBtn.addEventListener('click', function() {
      checkPendingQuestion();
    });
    
    // Wire up question modal buttons
    var questionClose = document.getElementById('questionClose');
    if (questionClose) questionClose.addEventListener('click', hideQuestion);
    
    var questionCancel = document.getElementById('questionCancel');
    if (questionCancel) questionCancel.addEventListener('click', hideQuestion);
    
    var questionAnswer = document.getElementById('questionAnswer');
    if (questionAnswer) questionAnswer.addEventListener('click', submitAnswer);
    
    var questionModal = document.getElementById('questionModal');
    if (questionModal) questionModal.addEventListener('click', function(e) {
      if (e.target === questionModal) hideQuestion();
    });
    
    // Check on load for any pending vCenter question (only users who can act on it, and only when a VM is known)
    if (DATA.canWrite && !DATA.dynamic) checkPendingQuestion();

    var vmSelect = document.getElementById('cdVmSelect');
    if (vmSelect) vmSelect.addEventListener('change', function() { selectTarget(vmSelect.value); });
  }

  // ===== VM picker (instance with several VMs): drives come from the vmData route =====
  function setDriveButtons(enabled, hasDisc) {
    var browse = document.getElementById('btnBrowse');
    var check = document.getElementById('btnCheckStatus');
    var eject = document.getElementById('btnUnmount');
    if (browse) browse.disabled = !enabled;
    if (check) check.disabled = !enabled || currentTarget === 'all';
    if (eject) eject.disabled = !enabled || !hasDisc;
  }

  function renderStatus(message) {
    var card = document.getElementById('cdStatusCard');
    if (!card) return;
    card.textContent = '';
    var cdroms = DATA.cdroms || [];
    if (message || cdroms.length === 0) {
      var empty = document.createElement('div');
      empty.className = 'cd-empty';
      empty.textContent = message || 'No CD/DVD drives found on this VM';
      card.appendChild(empty);
      return;
    }
    cdroms.forEach(function(cd) {
      var iso = (cd.backing && cd.backing.iso_file) ? cd.backing.iso_file : '';
      var state = String(cd.state || 'NOT_CONNECTED').toUpperCase();
      var connected = state.indexOf('CONNECTED') >= 0 && state.indexOf('NOT_') < 0;

      var row = document.createElement('div');
      row.className = 'cd-drive-row';
      var icon = document.createElement('div');
      icon.className = 'cd-drive-icon';
      icon.innerHTML = ICONS.iso;
      var info = document.createElement('div');
      info.className = 'cd-drive-info';
      var label = document.createElement('div');
      label.className = 'cd-drive-label';
      label.textContent = cd.label || 'CD/DVD Drive';
      var isoEl = document.createElement('div');
      isoEl.className = iso ? 'cd-drive-iso' : 'cd-drive-iso empty';
      isoEl.textContent = iso || 'No disc inserted';
      info.appendChild(label);
      info.appendChild(isoEl);
      var status = document.createElement('div');
      status.className = 'cd-drive-status';
      var badge = document.createElement('span');
      badge.className = 'cd-status-badge ' + (connected ? 'connected' : 'disconnected');
      badge.textContent = connected ? 'Connected' : 'Disconnected';
      status.appendChild(badge);
      row.appendChild(icon);
      row.appendChild(info);
      row.appendChild(status);
      card.appendChild(row);
    });
  }

  function selectTarget(value) {
    currentTarget = value;
    folderCache = {};
    selectedIsoPath = '';
    DATA.cdroms = [];
    DATA.hasVm = false;
    setDriveButtons(false, false);
    hideQuestion();

    var note = document.getElementById('cdSelectNote');
    var sel = document.getElementById('cdVmSelect');
    if (!value) {
      if (note) note.textContent = '';
      renderStatus('Select a VM to view its CD/DVD drive');
      return;
    }
    if (note && sel) {
      note.textContent = value === 'all'
        ? 'Showing the drive of the primary VM ' + (sel.getAttribute('data-primary-name') || '') + '. Mount and eject apply to all VMs.'
        : 'Mount and eject apply to ' + sel.options[sel.selectedIndex].text + ' only.';
    }
    loadVmData();
  }

  function loadVmData() {
    var asked = currentTarget;
    renderStatus('Loading...');
    pluginCall('vmData', {})
      .then(function(data) {
        if (asked !== currentTarget) return;   // another VM was picked meanwhile
        if (!data || data.success === false) {
          renderStatus((data && data.msg) || 'The drive could not be loaded.');
          return;
        }
        DATA.cdroms = data.cdroms || [];
        DATA.hasVm = true;
        var warn = document.getElementById('cdCertWarning');
        if (warn && data.certUntrusted) warn.style.display = '';
        renderStatus();
        var hasDisc = DATA.cdroms.some(function(cd) { return cd.backing && cd.backing.iso_file; });
        setDriveButtons(DATA.cdroms.length > 0, hasDisc);
        if (DATA.canWrite && currentTarget !== 'all') checkPendingQuestion();
      })
      .catch(function(err) {
        if (asked !== currentTarget) return;
        renderStatus('Request failed: ' + (err && err.message ? err.message : err));
      });
  }

  // After a change: redraw the selected VM in place, or reload the page when there is no picker
  function refreshView() {
    if (DATA.dynamic) { loadVmData(); } else { location.reload(); }
  }
  
  // ===== Pending vCenter question =====
  function checkPendingQuestion() {
    if (!DATA.hasVm) return;
    pluginCall('question', {})
      .then(function(data) {
        if (data.success && data.question) {
          showQuestion(data.question);
        } else {
          hideQuestion();
        }
      })
      .catch(function(err) { 
        console.error('checkPendingQuestion error:', err);
      });
  }

  var currentQuestionId = '';
  var selectedAnswer = '';

  function showQuestion(question) {
    var modal = document.getElementById('questionModal');
    var textEl = document.getElementById('questionText');
    var choicesEl = document.getElementById('questionChoices');
    var answerBtn = document.getElementById('questionAnswer');
    
    if (!modal || !textEl || !choicesEl) {
      console.error('Question elements not found');
      return;
    }

    currentQuestionId = question.id;
    selectedAnswer = '';
    if (answerBtn) answerBtn.disabled = true;

    textEl.textContent = question.text || 'vCenter needs confirmation before continuing.';
    choicesEl.innerHTML = '';

    var choices = question.choices || [];
    choices.forEach(function(choice, index) {
      var div = document.createElement('div');
      div.className = 'question-choice';
      
      var radio = document.createElement('input');
      radio.type = 'radio';
      radio.name = 'questionChoice';
      radio.id = 'choice-' + index;
      radio.value = choice.key;
      
      var label = document.createElement('label');
      label.htmlFor = 'choice-' + index;
      label.textContent = choice.label || choice.key;
      
      radio.addEventListener('change', function() {
        selectedAnswer = this.value;
        if (answerBtn) answerBtn.disabled = false;
      });
      
      div.appendChild(radio);
      div.appendChild(label);
      choicesEl.appendChild(div);
    });

    modal.classList.add('active');
  }

  function hideQuestion() {
    var modal = document.getElementById('questionModal');
    if (modal) modal.classList.remove('active');
    currentQuestionId = '';
    selectedAnswer = '';
  }

  function submitAnswer() {
    if (!currentQuestionId || !selectedAnswer) {
      notifyWarning('Selection Required', 'Please select an answer before continuing.');
      return;
    }
    
    pluginCall('answerQuestion', { questionId: currentQuestionId, answer: selectedAnswer })
      .then(function(data) {
        if (data.success) {
          hideQuestion();
          notifySuccess('Answer Submitted', 'vCenter question answered successfully. Refreshing...');
          setTimeout(refreshView, 1500);
        } else {
          handleApiError(data, 'Answer Question', function() { submitAnswer(); });
        }
      })
      .catch(function(err) {
        notifyError('Answer Failed', 'Could not submit answer to vCenter.', err.message, true, function() { submitAnswer(); });
      });
  }
  
  function showEjectWarning() {
    if (!DATA.hasVm) {
      notifyError('VM Not Found', 'Cannot eject disc because the VM was not found. Try refreshing the page.');
      return;
    }
    var cdroms = DATA.cdroms || [];
    if (cdroms.length === 0) {
      notifyWarning('No Drive Found', 'This VM has no CD/DVD drive configured.');
      return;
    }
    var modal = document.getElementById('cdWarningModal');
    var warnText = document.getElementById('cdWarningText');
    if (warnText) {
      warnText.textContent = (DATA.dynamic && currentTarget === 'all')
        ? 'Are you sure you want to eject the disc from the CD/DVD drive of all ' + DATA.vmCount + ' VMs?'
        : "Are you sure you want to eject the disc from this VM's CD/DVD drive?";
    }
    if (modal) modal.classList.add('active');
  }
  
  function hideEjectWarning() {
    var modal = document.getElementById('cdWarningModal');
    if (modal) modal.classList.remove('active');
  }
  
  function openModal() {
    var modal = document.getElementById('cdModal');
    if (modal) modal.classList.add('active');
    
    // Reset to datastore list view
    showDatastoreList();
    loadDatastores();
  }
  
  function showDatastoreList() {
    document.getElementById('modalTitle').textContent = 'Select File';
    document.getElementById('backLink').classList.remove('active');
    document.getElementById('dsListView').style.display = 'block';
    document.getElementById('splitPane').classList.remove('active');
    document.getElementById('itemCount').textContent = '0 items';
    
    currentDatastore = '';
    currentPath = '/';
    selectedIsoPath = '';
    updateSelection();
  }
  
  function closeModal() {
    var modal = document.getElementById('cdModal');
    if (modal) modal.classList.remove('active');
    selectedIsoPath = '';
  }
  
  function loadDatastores() {
    var dsListView = document.getElementById('dsListView');
    dsListView.innerHTML = '<div class="cd-loading"><div class="cd-spinner"></div><span>Loading datastores...</span></div>';
    
    pluginCall('datastores', {})
      .then(function(data) {
        if (data.success && data.datastores) {
          renderDatastoreList(data.datastores);
        } else {
          dsListView.innerHTML = '<div class="cd-error">' + escHtml(data.msg || 'Failed to load datastores') + '</div>';
        }
      })
      .catch(function(err) {
        dsListView.innerHTML = '<div class="cd-error">Error: ' + escHtml(err.message) + '</div>';
      });
  }
  
  function renderDatastoreList(datastores) {
    var dsListView = document.getElementById('dsListView');
    
    if (!datastores || datastores.length === 0) {
      dsListView.innerHTML = '<div class="cd-empty">No datastores found</div>';
      return;
    }
    
    var html = '';
    datastores.forEach(function(ds) {
      html += '<div class="cd-ds-item" data-name="' + escAttr(ds.name) + '">' +
              '<div class="cd-ds-icon">' + ICONS.datastore + '</div>' +
              '<div class="cd-ds-info">' +
              '<div class="cd-ds-name">' + escHtml(ds.name) + '</div>' +
              '<div class="cd-ds-meta">' + escHtml(ds.type || 'VMFS') + '</div>' +
              '</div>' +
              '<div class="cd-ds-arrow">' + ICONS.arrow + '</div>' +
              '</div>';
    });
    dsListView.innerHTML = html;
    document.getElementById('itemCount').textContent = datastores.length + ' items';
    
    var items = dsListView.querySelectorAll('.cd-ds-item');
    items.forEach(function(item) {
      item.addEventListener('click', function() {
        var name = this.getAttribute('data-name');
        openDatastore(name);
      });
    });
  }
  
  function openDatastore(dsName) {
    currentDatastore = dsName;
    currentPath = '/';
    selectedIsoPath = '';
    updateSelection();
    
    // Switch to split pane view
    document.getElementById('modalTitle').textContent = 'Select File';
    document.getElementById('backLink').classList.add('active');
    document.getElementById('dsListView').style.display = 'none';
    document.getElementById('splitPane').classList.add('active');
    
    // Build tree and load files
    buildFolderTree(dsName);
    loadFolder();
  }
  
  function buildFolderTree(dsName) {
    var treeContainer = document.getElementById('treeContainer');
    treeContainer.innerHTML = '<div class="cd-tree-item selected" data-path="/">' +
      '<div class="cd-tree-toggle expanded">' + ICONS.arrow + '</div>' +
      '<div class="cd-tree-icon datastore">' + ICONS.datastore + '</div>' +
      '<div class="cd-tree-label">' + escHtml(dsName) + '</div>' +
      '</div>' +
      '<div id="treeRoot" class="cd-tree-children expanded"></div>';
    
    // Load root folders
    loadTreeFolders('/');
  }
  
  function loadTreeFolders(path) {
    pluginCall('browse', { datastore: currentDatastore, path: path })
      .then(function(data) {
        if (data.success) {
          renderTreeFolders(path, data.folders || []);
        }
      })
      .catch(function(err) {
        console.error('Error loading tree folders:', err);
      });
  }
  
  function renderTreeFolders(path, folders) {
    var containerId = path === '/' ? 'treeRoot' : 'tree-' + path.replace(/[^a-zA-Z0-9]/g, '_');
    var container = document.getElementById(containerId);
    if (!container) return;
    
    var html = '';
    folders.forEach(function(folder) {
      if (folder.indexOf('[') === 0 || folder.indexOf('.') === 0) return;
      var folderPath = path === '/' ? '/' + folder : path + '/' + folder;
      var folderId = 'tree-' + folderPath.replace(/[^a-zA-Z0-9]/g, '_');
      
      html += '<div class="cd-tree-item" data-path="' + escAttr(folderPath) + '">' +
              '<div class="cd-tree-toggle">' + ICONS.arrow + '</div>' +
              '<div class="cd-tree-icon folder">' + ICONS.folder + '</div>' +
              '<div class="cd-tree-label">' + escHtml(folder) + '</div>' +
              '</div>' +
              '<div id="' + folderId + '" class="cd-tree-children"></div>';
    });
    container.innerHTML = html;
    
    // Wire up click handlers
    var items = container.querySelectorAll('.cd-tree-item');
    items.forEach(function(item) {
      item.addEventListener('click', function(e) {
        e.stopPropagation();
        var path = this.getAttribute('data-path');
        selectTreeFolder(path, this);
      });
    });
  }
  
  function selectTreeFolder(path, treeItem) {
    // Update selection
    document.querySelectorAll('.cd-tree-item').forEach(function(el) {
      el.classList.remove('selected');
    });
    treeItem.classList.add('selected');
    
    // Toggle expand/collapse
    var toggle = treeItem.querySelector('.cd-tree-toggle');
    var childrenId = 'tree-' + path.replace(/[^a-zA-Z0-9]/g, '_');
    var children = document.getElementById(childrenId);
    
    if (toggle && children) {
      toggle.classList.toggle('expanded');
      children.classList.toggle('expanded');
      
      // Load subfolders if not loaded
      if (children.classList.contains('expanded') && children.innerHTML === '') {
        loadTreeFolders(path);
      }
    }
    
    // Update current path and load files
    currentPath = path;
    selectedIsoPath = '';
    updateSelection();
    loadFolder();
  }
  
  function loadFolder() {
    var cacheKey = currentDatastore + ':' + currentPath;
    
    if (folderCache[cacheKey]) {
      renderFileTable(folderCache[cacheKey]);
      return;
    }
    
    var tableBody = document.getElementById('fileTableBody');
    tableBody.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#6d6d6d;">Loading...</td></tr>';
    
    pluginCall('browse', { datastore: currentDatastore, path: currentPath })
      .then(function(data) {
        if (data.success) {
          var result = { folders: data.folders || [], files: data.files || [] };
          folderCache[cacheKey] = result;
          renderFileTable(result);
        } else {
          var tableBody = document.getElementById('fileTableBody');
          tableBody.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#f54f47;">Error: ' + escHtml(data.msg || 'Failed to load folder') + '</td></tr>';
        }
      })
      .catch(function(err) {
        var tableBody = document.getElementById('fileTableBody');
        tableBody.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#f54f47;">Error: ' + escHtml(err.message) + '</td></tr>';
      });
  }
  
  function renderFileTable(data) {
    var tableBody = document.getElementById('fileTableBody');
    var files = data.files || [];
    
    // Filter to only show ISO files
    var isoFiles = files.filter(function(file) {
      var fileName = typeof file === 'string' ? file : file.name;
      return fileName.toLowerCase().endsWith('.iso');
    });
    
    if (isoFiles.length === 0) {
      tableBody.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#6d6d6d;">No ISO files found in this folder</td></tr>';
      document.getElementById('itemCount').textContent = '0 items';
      return;
    }
    
    var html = '';
    isoFiles.forEach(function(file) {
      var fileName = typeof file === 'string' ? file : file.name;
      var fileSize = typeof file === 'object' ? file.size : 0;
      var modifiedDate = typeof file === 'object' && file.modified ? formatDate(file.modified) : '';
      
      html += '<tr data-file="' + escAttr(fileName) + '">' +
              '<td class="col-radio"><input type="radio" name="isoFile" class="cd-radio" value="' + escAttr(fileName) + '"></td>' +
              '<td class="col-name"><div class="cd-file-name-cell">' + ICONS.iso + '<span>' + escHtml(fileName) + '</span></div></td>' +
              '<td class="col-size">' + formatSize(fileSize) + '</td>' +
              '<td class="col-modified">' + escHtml(modifiedDate) + '</td>' +
              '</tr>';
    });
    tableBody.innerHTML = html;
    document.getElementById('itemCount').textContent = isoFiles.length + ' items';
    
    // Wire up row clicks
    var rows = tableBody.querySelectorAll('tr');
    rows.forEach(function(row) {
      row.addEventListener('click', function() {
        var fileName = this.getAttribute('data-file');
        selectIsoFile(fileName, this);
      });
    });
  }
  
  function selectIsoFile(fileName, row) {
    // Update visual selection
    document.querySelectorAll('#fileTableBody tr').forEach(function(el) {
      el.classList.remove('selected');
    });
    row.classList.add('selected');
    
    // Check the radio button
    var radio = row.querySelector('input[type="radio"]');
    if (radio) radio.checked = true;
    
    // Build full datastore path
    var pathPart = currentPath === '/' ? fileName : currentPath.replace(/^\\//, '') + '/' + fileName;
    selectedIsoPath = '[' + currentDatastore + '] ' + pathPart;
    
    updateSelection();
  }
  
  function formatDate(dateStr) {
    if (!dateStr) return '';
    try {
      var date = new Date(dateStr);
      return date.toLocaleDateString() + ', ' + date.toLocaleTimeString();
    } catch (e) {
      return dateStr;
    }
  }

  function updateSelection() {
    var okBtn = document.getElementById('btnMountIso');
    if (selectedIsoPath) {
      if (okBtn) {
        okBtn.disabled = false;
        okBtn.classList.add('enabled');
      }
    } else {
      if (okBtn) {
        okBtn.disabled = true;
        okBtn.classList.remove('enabled');
      }
    }
  }
  
  function doMount() {
    if (!selectedIsoPath || !DATA.hasVm) {
      notifyWarning('Selection Required', 'Please select an ISO file first.');
      return;
    }
    
    var cdroms = DATA.cdroms || [];
    if (cdroms.length === 0) {
      notifyWarning('No Drive Found', 'This VM has no CD/DVD drive configured.');
      return;
    }
    
    // The server finds each VM's CD/DVD drive itself
    pluginCall('mount', { iso: selectedIsoPath })
      .then(function(data) {
        if (data.success && data.mounted === 0) {
          closeModal();
          notifyWarning('Nothing Mounted', data.msg || 'No VM needed the ISO.');
        } else if (data.success) {
          notifySuccess('ISO Mounted', (data.msg || 'The ISO file has been mounted successfully.') + ' Refreshing...');
          closeModal();
          setTimeout(refreshView, 1500);
        } else {
          handleApiError(data, 'Mount ISO', function() { doMount(); });
        }
      })
      .catch(function(err) {
        notifyError('Mount Failed', 'Could not mount the ISO file.', err.message, true, function() { doMount(); });
      });
  }
  
  function doUnmount() {
    // Close the warning modal first
    hideEjectWarning();
    
    pluginCall('unmount', {})
      .then(function(data) {
        if (data.success && data.ejected === 0) {
          notifyInfo('Nothing To Eject', data.msg || 'No disc was mounted.');
        } else if (data.success) {
          notifySuccess('Disc Ejected', (data.msg || 'The disc has been ejected successfully.') + ' Refreshing...');
          setTimeout(refreshView, 1500);
        } else {
          // Eject failed - check if vCenter is asking a question (e.g. locked CD-ROM door)
          handleApiError(data, 'Eject Disc');
          notifyInfo('Checking vCenter', 'Looking for pending prompts from vCenter...');
          setTimeout(function() {
            checkPendingQuestion();
          }, 800);
        }
      })
      .catch(function(err) {
        // Also check for pending question on error
        notifyError('Eject Failed', 'Could not eject the disc.', err.message);
        notifyInfo('Checking vCenter', 'Looking for pending prompts from vCenter...');
        setTimeout(function() {
          checkPendingQuestion();
        }, 800);
      });
  }
  
  function formatSize(bytes) {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }
  
  function escHtml(str) {
    var div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
  }
  
  function escAttr(str) {
    return (str || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    setTimeout(init, 50);
  }
})();
''')
        
        b.append('</script>\n')
        return b.toString()
    }

    private String getNonce() {
        try {
            def webReq = morpheusContext.getWebRequest()
            return webReq?.getNonceToken()?.toString() ?: ''
        } catch (Exception ignored) {
            return ''
        }
    }

    private static String esc(Object v) {
        if (v == null) return ''
        v.toString()
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#39;')
    }
}
