package com.morpheus.cddvdmanager

import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HostnameVerifier
import java.nio.charset.StandardCharsets
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * vSphere API client supporting both REST and SOAP operations.
 *
 * REST API (vSphere 7.0+):
 *   - listDatastores()     → GET /api/vcenter/datastore
 *   - getCdroms()          → GET /api/vcenter/vm/{vm}/hardware/cdrom
 *   - mountIso()           → PATCH /api/vcenter/vm/{vm}/hardware/cdrom/{cdrom}
 *   - unmountIso()         → PATCH (disconnect)
 *
 * SOAP API (HostDatastoreBrowser):
 *   - browseDatastore()    → SearchDatastore_Task (single folder, non-recursive)
 *
 * Session lifecycle:
 *   login() → use operations → logout()
 *
 * All callers should wrap in try/finally to ensure logout() is called.
 */
@Slf4j
class VCenterApiClient {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final String vcHost
    private final String vcUser
    private final String vcPassword
    private final String sdkUrl
    private final String restBaseUrl
    private final boolean allowUntrusted
    /** Set when allowUntrusted is on, so the tab can warn that the certificate was not verified. */
    private final TlsSupport.TrustOutcome trustOutcome
    private final SSLSocketFactory recordingFactory
    private final HostnameVerifier recordingVerifier
    private static final int MAX_FOLDER_SCANS = 25

    /** vim25 SOAP session cookie */
    private String sessionCookie

    /** REST API session token */
    private String restSessionToken

    /** API version from RetrieveServiceContent */
    private String apiVersion = '8.0.2.0'

    /** Public accessor for the vCenter API version reported by RetrieveServiceContent (populated after login()). */
    String getApiVersion() { return apiVersion }

    /** MOR values from RetrieveServiceContent */
    private String propertyCollectorRef = 'propertyCollector'
    private String sessionManagerRef = 'SessionManager'
    private String rootFolderRef = 'group-d1'

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    VCenterApiClient(String vcHost, String vcUser, String vcPassword, boolean allowUntrusted) {
        if (!SecurityUtil.isValidHost(vcHost)) {
            throw new IllegalArgumentException('Invalid vCenter host')
        }
        this.vcHost = vcHost
        this.vcUser = vcUser
        this.vcPassword = vcPassword
        this.allowUntrusted = allowUntrusted
        if (allowUntrusted) {
            this.trustOutcome = new TlsSupport.TrustOutcome()
            this.recordingFactory = TlsSupport.recordingTrustAllFactory(trustOutcome)
            this.recordingVerifier = TlsSupport.recordingVerifier(trustOutcome)
        } else {
            this.trustOutcome = null
            this.recordingFactory = null
            this.recordingVerifier = null
        }
        this.sdkUrl = "https://${vcHost}/sdk"
        this.restBaseUrl = "https://${vcHost}/api"
        log.debug("VCenterApiClient: client created for host='${vcHost}'")
        if (allowUntrusted) {
            log.warn("VCenterApiClient: certificate verification is disabled for '${vcHost}' (plugin setting allowUntrustedCert)")
        }
    }

    /** Opens a connection to vCenter; trust is relaxed for this connection only when the plugin setting allows it. */
    private HttpsURLConnection openConn(String target) {
        // Strictly HTTPS only — never issue a plain-text HTTP request to vCenter.
        if (target == null || !target.toLowerCase(Locale.ROOT).startsWith('https://')) {
            throw new IllegalArgumentException('Refusing non-HTTPS vCenter URL')
        }
        URLConnection raw = new URL(target).openConnection()
        if (!(raw instanceof HttpsURLConnection)) {
            throw new IllegalArgumentException('Refusing non-HTTPS vCenter connection')
        }
        HttpsURLConnection conn = (HttpsURLConnection) raw
        if (allowUntrusted) {
            conn.setSSLSocketFactory(recordingFactory)
            conn.setHostnameVerifier(recordingVerifier)
        } else {
            TlsSupport.apply(conn, false)
        }
        return conn
    }

    /** True when allowUntrusted is on and the vCenter certificate could not be verified (self-signed or host-name mismatch). */
    boolean isCertUntrusted() {
        return allowUntrusted && trustOutcome?.untrusted
    }

    private static String requireVmId(String vmId) {
        if (!SecurityUtil.isValidVmId(vmId)) throw new IllegalArgumentException('Invalid VM identifier')
        return vmId
    }

    private static String requireCdromId(String cdromId) {
        String id = SecurityUtil.cleanCdromId(cdromId)
        if (id == null) throw new IllegalArgumentException('Invalid CD/DVD device identifier')
        return id
    }

    // -----------------------------------------------------------------------
    // Session Management
    // -----------------------------------------------------------------------

    /** Authenticate via SOAP and REST. */
    void login() {
        log.debug("VCenterApiClient: login started")
        
        // SOAP login for HostDatastoreBrowser operations
        loginSoap()
        
        // REST login for VM hardware operations
        loginRest()
    }

    private void loginSoap() {
        log.debug("VCenterApiClient: SOAP login")
        def scXml = soapCall(buildRetrieveServiceContentEnvelope(), null)
        def sc = parseXml(scXml)
        sessionManagerRef = sc.'**'.find { it.name() == 'sessionManager' }?.text() ?: 'SessionManager'
        propertyCollectorRef = sc.'**'.find { it.name() == 'propertyCollector' }?.text() ?: 'propertyCollector'
        rootFolderRef = sc.'**'.find { it.name() == 'rootFolder' }?.text() ?: 'group-d1'
        apiVersion = sc.'**'.find { it.name() == 'apiVersion' }?.text() ?: '8.0.2.0'
        log.debug("VCenterApiClient: ServiceContent retrieved, apiVersion='${apiVersion}'")

        soapCall(buildLoginEnvelope(vcUser, vcPassword, sessionManagerRef), null)
        log.debug("VCenterApiClient: SOAP session established")
    }

    private void loginRest() {
        log.debug("VCenterApiClient: REST login started")
        String encoded = Base64.encoder.encodeToString(
            "${vcUser}:${vcPassword}".getBytes(StandardCharsets.UTF_8))
        
        HttpsURLConnection conn = openConn("${restBaseUrl}/session")
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(30000)
        try {
            conn.setRequestMethod('POST')
            conn.setDoOutput(false)
            conn.setRequestProperty('Authorization', "Basic ${encoded}")
            conn.setRequestProperty('Content-Length', '0')
            int status = conn.responseCode
            log.debug("VCenterApiClient: REST login status=${status}")
            InputStream is = status < 400 ? conn.inputStream : conn.errorStream
            String response = is?.getText(StandardCharsets.UTF_8.name()) ?: ''
            if (status >= 400) {
                log.warn("VCenterApiClient: REST login failed (HTTP ${status})")
                throw new RuntimeException("REST login failed (HTTP ${status})")
            }
            restSessionToken = response.trim().replaceAll('^"|"$', '')
            log.debug("VCenterApiClient: REST session established")
        } finally {
            conn.disconnect()
        }
    }

    /** Gracefully end both sessions. */
    void logout() {
        log.debug("VCenterApiClient: logout()")
        try {
            if (sessionCookie) {
                soapCall(buildLogoutEnvelope(sessionManagerRef), sessionCookie)
                log.debug("VCenterApiClient: SOAP session closed")
            }
        } catch (Exception e) {
            log.warn("VCenterApiClient: SOAP logout failed (${e.class.simpleName})")
        }
        sessionCookie = null

        try {
            if (restSessionToken) {
                HttpsURLConnection conn = openConn("${restBaseUrl}/session")
                conn.setConnectTimeout(5000)
                conn.setReadTimeout(5000)
                try {
                    conn.setRequestMethod('DELETE')
                    conn.setRequestProperty('vmware-api-session-id', restSessionToken)
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
                log.debug("VCenterApiClient: REST session closed")
            }
        } catch (Exception e) {
            log.warn("VCenterApiClient: REST logout failed (${e.class.simpleName})")
        }
        restSessionToken = null
    }

    // -----------------------------------------------------------------------
    // REST API Operations
    // -----------------------------------------------------------------------

    /**
     * List all datastores accessible to the vCenter.
     * @return List of [datastore: String (MOR), name: String, type: String]
     */
    List<Map<String, String>> listDatastores() {
        log.debug("VCenterApiClient: listDatastores")
        String response = restGet('/vcenter/datastore')
        def datastores = new JsonSlurper().parseText(response) as List
        log.debug("VCenterApiClient: ${datastores.size()} datastore(s) found")
        return datastores.collect { ds ->
            String dsId = ds.datastore as String
            String dsUrl = ''
            try {
                String detailResponse = restGet("/vcenter/datastore/${dsId}")
                def detail = new JsonSlurper().parseText(detailResponse) as Map
                dsUrl = detail?.datastore_path as String ?: ''
            } catch (Exception e) {
                log.warn("VCenterApiClient: details unavailable for datastore ${dsId} (${e.class.simpleName})")
            }
            [
                datastore: dsId,
                name: ds.name as String,
                type: ds.type as String,
                url: dsUrl
            ]
        }
    }

    /**
     * List datastores accessible to a specific VM's host.
     * Uses SOAP PropertyCollector to get the actual host-datastore mapping.
     * @param vmId VM identifier (e.g., "vm-123")
     * @return List of [datastore: String (MOR), name: String, type: String, url: String]
     */
    List<Map<String, String>> listDatastoresForVm(String vmId) {
        log.debug("VCenterApiClient: listDatastoresForVm vmId='${vmId}'")
        
        if (!vmId?.trim()) {
            log.warn("VCenterApiClient: No vmId provided, falling back to all datastores")
            return listDatastores()
        }
        requireVmId(vmId)
        
        // Step 1: Find VM's host using REST API
        String hostId = null
        String hostName = null
        
        try {
            String hostsResponse = restGet("/vcenter/host")
            def hosts = new JsonSlurper().parseText(hostsResponse) as List
            log.debug("VCenterApiClient: searching ${hosts?.size()} hosts for VM ${vmId}")
            
            for (def h : hosts) {
                String hId = h.host as String
                String hName = h.name as String
                
                try {
                    String vmsResponse = restGet("/vcenter/vm?hosts=${hId}")
                    def vms = new JsonSlurper().parseText(vmsResponse) as List
                    
                    boolean found = vms?.any { vm -> (vm.vm as String) == vmId }
                    if (found) {
                        hostId = hId
                        hostName = hName
                        log.debug("VCenterApiClient: VM ${vmId} found on host ${hostId}")
                        break
                    }
                } catch (Exception e) {
                    log.warn("VCenterApiClient: could not check VMs on host ${hId} (${e.class.simpleName})")
                }
            }
        } catch (Exception e) {
            log.warn("VCenterApiClient: could not list hosts (${e.class.simpleName}); falling back to all datastores")
            return listDatastores()
        }
        
        if (!hostId || !hostName) {
            log.warn("VCenterApiClient: Could not find host for VM ${vmId}. Falling back to all datastores.")
            return listDatastores()
        }
        
        // Step 2: Get host's accessible datastores via SOAP PropertyCollector
        Set<String> hostDatastoreMors = getHostDatastores(hostId)
        
        if (hostDatastoreMors.isEmpty()) {
            log.warn("VCenterApiClient: Could not get datastores for host ${hostId}. Falling back to all datastores.")
            return listDatastores()
        }
        
        log.debug("VCenterApiClient: host ${hostId} has ${hostDatastoreMors.size()} datastores")
        
        // Step 3: Get all datastores and filter by host's accessible ones
        try {
            String dsResponse = restGet("/vcenter/datastore")
            def allDatastores = new JsonSlurper().parseText(dsResponse) as List
            
            if (allDatastores == null || allDatastores.isEmpty()) {
                log.warn("VCenterApiClient: No datastores returned from vCenter")
                return []
            }
            
            log.debug("VCenterApiClient: filtering ${allDatastores.size()} datastores by host access")
            
            List<Map<String, String>> filteredDatastores = []
            for (def ds : allDatastores) {
                String dsId = ds.datastore as String
                String dsName = ds.name as String
                String dsType = ds.type as String
                
                boolean isAccessible = hostDatastoreMors.contains(dsId)
                
                if (isAccessible) {
                    String dsUrl = ''
                    try {
                        String detailResponse = restGet("/vcenter/datastore/${dsId}")
                        def detail = new JsonSlurper().parseText(detailResponse) as Map
                        dsUrl = detail?.datastore_path as String ?: ''
                    } catch (Exception e) {
                        log.warn("VCenterApiClient: details unavailable for ${dsId} (${e.class.simpleName})")
                    }
                    
                    log.debug("VCenterApiClient: datastore ${dsId} accessible by host")
                    filteredDatastores << [
                        datastore: dsId,
                        name: dsName,
                        type: dsType,
                        url: dsUrl
                    ]
                } else {
                    log.debug("VCenterApiClient: datastore ${dsId} not accessible by host ${hostId}")
                }
            }
            
            log.debug("VCenterApiClient: ${filteredDatastores.size()} datastores accessible to VM ${vmId}")
            return filteredDatastores
            
        } catch (Exception e) {
            log.warn("VCenterApiClient: could not list datastores (${e.class.simpleName}); falling back to all datastores")
            return listDatastores()
        }
    }
    
    /**
     * Get datastores accessible to a host using SOAP PropertyCollector.
     * @param hostId Host identifier (e.g., "host-53")
     * @return Set of datastore MORs accessible to this host
     */
    private Set<String> getHostDatastores(String hostId) {
        log.debug("VCenterApiClient: getHostDatastores hostId='${hostId}'")
        Set<String> datastoreMors = [] as Set
        
        try {
            String xml = soapCall(buildGetHostDatastoresEnvelope(hostId, propertyCollectorRef), sessionCookie)
            GPathResult root = parseXml(xml)
            
            root.'**'.findAll { it.name() == 'val' }.each { val ->
                String type = val.@type?.text() ?: ''
                if (type == 'Datastore' || type == 'ArrayOfManagedObjectReference') {
                    String dsRef = val.text()?.trim()
                    if (dsRef && dsRef.startsWith('datastore-')) {
                        datastoreMors << dsRef
                    }
                    
                    val.'**'.findAll { it.name() == 'ManagedObjectReference' }.each { mor ->
                        String morType = mor.@type?.text() ?: ''
                        String morVal = mor.text()?.trim()
                        if ((morType == 'Datastore' || morVal?.startsWith('datastore-')) && morVal) {
                            datastoreMors << morVal
                        }
                    }
                }
            }
            
            root.'**'.findAll { it.name() == 'ManagedObjectReference' }.each { mor ->
                String morType = mor.@type?.text() ?: ''
                String morVal = mor.text()?.trim()
                if ((morType == 'Datastore' || morVal?.startsWith('datastore-')) && morVal) {
                    datastoreMors << morVal
                }
            }
            
            log.debug("VCenterApiClient: ${datastoreMors.size()} datastores for host ${hostId}")
        } catch (Exception e) {
            log.warn("VCenterApiClient: could not read host datastores (${e.class.simpleName})")
        }
        
        return datastoreMors
    }

    /**
     * Get CD/DVD devices for a VM.
     * @param vmId VM identifier (e.g., "vm-123")
     * @return List of [cdrom: String, type: String, backing: Map, state: String]
     */
    List<Map<String, Object>> getCdroms(String vmId) {
        log.debug("VCenterApiClient: getCdroms vmId='${vmId}'")
        requireVmId(vmId)
        String response = restGet("/vcenter/vm/${vmId}/hardware/cdrom")
        def cdroms = new JsonSlurper().parseText(response) as List
        log.debug("VCenterApiClient: ${cdroms.size()} CD/DVD device(s)")
        
        // Get detailed info for each CD-ROM
        return cdroms.collect { cd ->
            String cdromId = requireCdromId(cd.cdrom as String)
            String detailResponse = restGet("/vcenter/vm/${vmId}/hardware/cdrom/${cdromId}")
            def detail = new JsonSlurper().parseText(detailResponse) as Map
            [
                cdrom: cdromId,
                type: detail.type as String,
                backing: detail.backing as Map,
                state: detail.state as String,
                startConnected: detail.start_connected as Boolean,
                label: detail.label as String
            ]
        }
    }

    /**
     * Mount an ISO file to a VM's CD/DVD drive.
     * @param vmId VM identifier
     * @param cdromId CD/DVD device identifier
     * @param isoPath Datastore path, e.g., "[ISO-Store] vmimages/tools-isoimages/windows.iso"
     */
    void mountIso(String vmId, String cdromId, String isoPath) {
        log.debug("VCenterApiClient: mountIso vmId='${vmId}' cdrom='${cdromId}'")
        requireVmId(vmId)
        requireCdromId(cdromId)
        if (SecurityUtil.parseIsoPath(isoPath) == null) throw new IllegalArgumentException('Invalid ISO path')

        // The vSphere REST API's cdrom update_spec is the request body itself —
        // no 'spec' wrapper. Wrapping it caused HTTP 400 UNEXPECTED_INPUT
        // ("Found unexpected fields [spec] in structure ...cdrom.update_spec").
        def body = [
            backing: [
                type: 'ISO_FILE',
                iso_file: isoPath
            ],
            start_connected: true
        ]

        restPatch("/vcenter/vm/${vmId}/hardware/cdrom/${cdromId}", body)
        
        // Connect the CD-ROM after setting the backing
        restPost("/vcenter/vm/${vmId}/hardware/cdrom/${cdromId}?action=connect", [:])
        
        log.debug("VCenterApiClient: ISO mounted")
    }

    /**
     * Unmount/disconnect ISO from a VM's CD/DVD drive.
     *
     * The primary safety guardrail for this action is the confirmation dialog shown
     * client-side before this endpoint is ever called (see CdDvdManagerInstanceTabProvider's
     * doUnmount()). This method intentionally stays a single REST call — earlier revisions
     * added extra pre-check REST calls here (CD-ROM state read, VM power-state read) and
     * the combined round-trip latency was long enough to trip a network/proxy timeout,
     * surfacing as "Failed to fetch" on the client with no server-side error to debug.
     *
     * @param vmId VM identifier
     * @param cdromId CD/DVD device identifier
     */
    void unmountIso(String vmId, String cdromId) {
        log.debug("VCenterApiClient: unmountIso vmId='${vmId}' cdrom='${cdromId}'")
        requireVmId(vmId)
        requireCdromId(cdromId)

        // Disconnect the CD-ROM
        restPost("/vcenter/vm/${vmId}/hardware/cdrom/${cdromId}?action=disconnect", [:])

        log.debug("VCenterApiClient: ISO unmounted")
    }

    /**
     * Check whether vCenter has a pending interactive VM Question for this VM —
     * e.g. "the guest OS has locked the CD-ROM door, disconnect anyway and override
     * the lock?". This happens when a disconnect/reconfigure task is blocked waiting
     * for a human answer via the vSphere Client. Returns null if no question is
     * pending.
     *
     * IMPORTANT: this is surfaced so a human can answer it from the Morpheus tab
     * instead of switching to the vSphere Client — it must never be auto-answered.
     * Forcing an override without confirming guest OS state is what caused the
     * Danfoss PCE incident (INC6172000) this plugin's safety warnings exist for.
     *
     * @param vmMor VM managed object reference (e.g. "vm-1234")
     * @return null, or a map: [id: String, text: String, choices: [[key, label], ...]]
     */
    Map getVmQuestion(String vmMor) {
        log.debug("VCenterApiClient: getVmQuestion vmMor='${vmMor}'")
        requireVmId(vmMor)
        String xml = soapCall(buildGetVmQuestionEnvelope(vmMor, propertyCollectorRef), sessionCookie)
        log.debug("VCenterApiClient: getVmQuestion response received (${xml?.length() ?: 0} chars)")
        
        GPathResult root = parseXml(xml)
        def questionNode = null

        // Method 1: Find propSet with name='runtime.question' and get val element
        def propSet = root.'**'.find { it.name() == 'propSet' }
        if (propSet) {
            def propName = propSet.'**'.find { it.name() == 'name' }?.text()
            log.debug("VCenterApiClient: runtime.question property found")
            if (propName == 'runtime.question') {
                questionNode = propSet.'**'.find { it.name() == 'val' }
                if (questionNode) {
                    log.debug("VCenterApiClient: question value located in runtime.question")
                }
            }
        }
        
        // Method 2: Look for val elements that have id and text children
        if (!questionNode) {
            root.'**'.findAll { it.name() == 'val' }.each { v ->
                def hasId = v.'**'.find { it.name() == 'id' }
                def hasText = v.'**'.find { it.name() == 'text' }
                if (hasId && hasText && !questionNode) {
                    questionNode = v
                    log.debug("VCenterApiClient: question value located by id/text elements")
                }
            }
        }
        
        if (!questionNode) {
            log.debug("VCenterApiClient: no question pending for vmMor='${vmMor}'")
            return null
        }

        String questionId = questionNode.'**'.find { it.name() == 'id' }?.text()
        String text = questionNode.'**'.find { it.name() == 'text' }?.text()
        log.debug("VCenterApiClient: question id='${questionId}'")
        
        if (!questionId) {
            log.debug("VCenterApiClient: question node has no id")
            return null
        }

        List<Map<String, String>> choices = []
        questionNode.'**'.findAll { it.name() == 'choiceInfo' }.each { choiceNode ->
            String key = choiceNode.'**'.find { it.name() == 'key' }?.text()
            String label = choiceNode.'**'.find { it.name() == 'label' }?.text()
            String summary = choiceNode.'**'.find { it.name() == 'summary' }?.text()
            if (key != null) {
                // Use summary as display label if available, otherwise use label
                choices << [key: key, label: (summary ?: label ?: key) as String]
            }
        }

        log.debug("VCenterApiClient: question id='${questionId}' has ${choices.size()} choice(s)")
        return [id: questionId, text: (text ?: 'vCenter is asking for confirmation.') as String, choices: choices]
    }

    /**
     * Answer a pending VM Question (see getVmQuestion). This is called only after
     * a human has explicitly picked one of the real choices vCenter offered — it
     * relocates where the decision is made (Morpheus tab instead of vSphere Client),
     * it does not remove the decision itself.
     *
     * @param vmMor VM managed object reference
     * @param questionId The question id returned by getVmQuestion
     * @param answerChoice The chosen choice key returned by getVmQuestion
     */
    void answerVmQuestion(String vmMor, String questionId, String answerChoice) {
        log.debug("VCenterApiClient: answerVmQuestion vmMor='${vmMor}' questionId='${questionId}'")
        requireVmId(vmMor)
        if (SecurityUtil.cleanToken(questionId) == null || SecurityUtil.cleanToken(answerChoice) == null) {
            throw new IllegalArgumentException('Invalid question answer')
        }
        soapCall(buildAnswerVmEnvelope(vmMor, questionId, answerChoice), sessionCookie)
        log.debug("VCenterApiClient: question answered")
    }

    /**
     * Confirms that vCenter has a VirtualMachine with this managed object reference (e.g. "vm-123").
     * Returns the reference, or null when no such VM exists.
     */
    String verifyVmMor(String vmMor) {
        requireVmId(vmMor)
        String xml
        try {
            xml = soapCall(buildVmExistsEnvelope(vmMor, propertyCollectorRef), sessionCookie)
        } catch (RuntimeException e) {
            if (e.message?.contains('already been deleted')) {
                log.warn("VCenterApiClient: VM ${vmMor} no longer exists in vCenter")
                return null
            }
            throw e
        }
        boolean exists = parseXml(xml).'**'.any { it.name() == 'propSet' }
        if (!exists) {
            log.warn("VCenterApiClient: VM ${vmMor} not found in vCenter")
            return null
        }
        return vmMor
    }

    // -----------------------------------------------------------------------
    // SOAP API Operations - Datastore Browsing
    // -----------------------------------------------------------------------

    /**
     * Browse a datastore folder and list contents.
     * @param datastoreName Datastore name (e.g., "ISO-Store")
     * @param path Path within datastore (e.g., "/" or "vmimages/tools-isoimages")
     * @return Map with [folders: List<String>, files: List<Map>]
     */
    Map<String, Object> browseDatastore(String datastoreName, String path) {
        log.debug("VCenterApiClient: browseDatastore datastore='${datastoreName}' path='${path}'")
        
        // Step 1: Get the datastore MOR
        String datastoreMor = findDatastoreMor(datastoreName)
        if (!datastoreMor) {
            throw new RuntimeException("Datastore '${datastoreName}' not found")
        }
        
        // Step 2: Get the HostDatastoreBrowser for this datastore
        String browserMor = getDatastoreBrowser(datastoreMor)
        if (!browserMor) {
            throw new RuntimeException("Cannot get datastore browser for '${datastoreName}'")
        }
        
        // Step 3: Build the datastore path
        String normalizedPath = path?.trim() ?: ''
        if (normalizedPath == '/' || normalizedPath.isEmpty()) {
            normalizedPath = ''
        }
        String datastorePath = "[${datastoreName}] ${normalizedPath}".trim()
        
        // Step 4: Search the datastore
        return searchDatastore(browserMor, datastorePath)
    }

    /**
     * Browse a datastore folder, filtering to show only folders that contain ISO files.
     * @param datastoreName Datastore name
     * @param path Path within datastore
     * @param filterEmptyFolders If true, hide folders without ISOs (default: true)
     * @param maxScanDepth How deep to scan for ISOs in subfolders (default: 2)
     * @return Map with [folders: List<String>, files: List<Map>]
     */
    Map<String, Object> browseDatastoreFiltered(String datastoreName, String path, boolean filterEmptyFolders = true, int maxScanDepth = 2) {
        log.debug("VCenterApiClient: browseDatastoreFiltered datastore='${datastoreName}' path='${path}' filter=${filterEmptyFolders}")
        
        // Get the datastore and browser MORs
        String datastoreMor = findDatastoreMor(datastoreName)
        if (!datastoreMor) {
            throw new RuntimeException("Datastore '${datastoreName}' not found")
        }
        
        String browserMor = getDatastoreBrowser(datastoreMor)
        if (!browserMor) {
            throw new RuntimeException("Cannot get datastore browser for '${datastoreName}'")
        }
        
        String normalizedPath = path?.trim() ?: ''
        if (normalizedPath == '/' || normalizedPath.isEmpty()) {
            normalizedPath = ''
        }
        String datastorePath = "[${datastoreName}] ${normalizedPath}".trim()
        
        Map<String, Object> result = searchDatastore(browserMor, datastorePath)
        
        // If filtering disabled or no folders, return as-is
        if (!filterEmptyFolders || !result.folders) {
            return result
        }
        
        // Filter folders to only those containing ISOs
        List<String> filteredFolders = []
        int scanned = 0
        for (String folder : result.folders) {
            // Past the cap, folders are listed without checking them, so a large datastore cannot stall the request.
            if (scanned >= MAX_FOLDER_SCANS) {
                filteredFolders << folder
                continue
            }
            scanned++
            String folderFullPath = normalizedPath ? "${normalizedPath}/${folder}" : folder
            if (folderContainsIso(browserMor, datastoreName, folderFullPath, maxScanDepth)) {
                filteredFolders << folder
            } else {
                log.debug("VCenterApiClient: Hiding empty folder '${folder}'")
            }
        }
        
        log.debug("VCenterApiClient: ${filteredFolders.size()} of ${result.folders.size()} folders kept")
        return [folders: filteredFolders, files: result.files]
    }

    /**
     * Check if a folder contains ISO files (directly or in subfolders up to maxDepth).
     * @param browserMor HostDatastoreBrowser MOR
     * @param datastoreName Datastore name
     * @param folderPath Full path to folder (e.g., "vmimages/tools-isoimages")
     * @param maxDepth Maximum recursion depth (default 2)
     * @return true if folder contains ISOs
     */
    private boolean folderContainsIso(String browserMor, String datastoreName, String folderPath, int maxDepth = 2) {
        if (maxDepth <= 0) return false
        
        try {
            String datastoreFullPath = "[${datastoreName}] ${folderPath}".trim()
            Map<String, Object> result = searchDatastoreQuick(browserMor, datastoreFullPath)
            
            // Has ISO files directly?
            if (result.files && result.files.size() > 0) {
                return true
            }
            
            // Check subfolders recursively
            if (result.folders && maxDepth > 1) {
                for (String subfolder : result.folders) {
                    String subPath = folderPath ? "${folderPath}/${subfolder}" : subfolder
                    if (folderContainsIso(browserMor, datastoreName, subPath, maxDepth - 1)) {
                        return true
                    }
                }
            }
            return false
        } catch (Exception e) {
            log.warn("VCenterApiClient: could not check folder '${folderPath}' for ISOs (${e.class.simpleName})")
            return true // On error, assume it might have ISOs (don't hide it)
        }
    }

    /**
     * Quick datastore search with shorter timeout for filtering.
     */
    private Map<String, Object> searchDatastoreQuick(String browserMor, String datastorePath) {
        String taskXml = soapCall(buildSearchDatastoreEnvelope(browserMor, datastorePath), sessionCookie)
        GPathResult taskRoot = parseXml(taskXml)
        
        def taskMor = taskRoot.'**'.find { it.name() == 'returnval' }?.text()
        if (!taskMor) {
            return [folders: [], files: []]
        }
        
        // Quick wait - 5 attempts max (2.5 seconds)
        int maxAttempts = 5
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(500)
            String xml = soapCall(buildGetTaskInfoEnvelope(taskMor, propertyCollectorRef), sessionCookie)
            GPathResult root = parseXml(xml)
            def state = root.'**'.find { it.name() == 'state' }?.text()
            
            if (state == 'success') {
                def resultNode = root.'**'.find { it.name() == 'result' }
                return resultNode ? parseSearchResultsFromNode(resultNode) : [folders: [], files: []]
            } else if (state == 'error') {
                return [folders: [], files: []]
            }
        }
        return [folders: [], files: []]
    }

    /**
     * Find datastore MOR by name.
     */
    private String findDatastoreMor(String datastoreName) {
        String xml = soapCall(buildFindDatastoreEnvelope(rootFolderRef, propertyCollectorRef), sessionCookie)
        GPathResult root = parseXml(xml)
        
        def allReturnvals = root.'**'.findAll { it.name() == 'returnval' }
        for (def returnval in allReturnvals) {
            def nameVal = returnval.'**'.find { it.name() == 'val' }
            def mor = returnval.'**'.find { it.name() == 'obj' }
            if (nameVal && nameVal.text() == datastoreName && mor) {
                String morType = mor.@type?.text() ?: ''
                String morValue = mor.text()
                if (morType == 'Datastore' || morValue.startsWith('datastore-')) {
                    log.debug("VCenterApiClient: datastore '${datastoreName}' is ${morValue}")
                    return morValue
                }
            }
        }
        return null
    }

    /**
     * Get the HostDatastoreBrowser MOR for a datastore.
     */
    private String getDatastoreBrowser(String datastoreMor) {
        String xml = soapCall(buildGetDatastoreBrowserEnvelope(datastoreMor, propertyCollectorRef), sessionCookie)
        GPathResult root = parseXml(xml)
        
        def browserVal = root.'**'.find { it.name() == 'val' && it.@type?.text() == 'HostDatastoreBrowser' }
        if (browserVal) {
            log.debug("VCenterApiClient: datastore browser ${browserVal.text()}")
            return browserVal.text()
        }
        
        // Fallback: look for any val that looks like a browser reference
        def allVals = root.'**'.findAll { it.name() == 'val' }
        for (def val in allVals) {
            String text = val.text()
            if (text?.contains('datastoreBrowser') || text?.startsWith('browser-')) {
                log.debug("VCenterApiClient: datastore browser ${text} (fallback match)")
                return text
            }
        }
        
        return null
    }

    /**
     * Search a single datastore folder (non-recursive) via HostDatastoreBrowser.SearchDatastore_Task
     */
    private Map<String, Object> searchDatastore(String browserMor, String datastorePath) {
        log.debug("VCenterApiClient: searchDatastore browser='${browserMor}' path='${datastorePath}'")
        
        // Start the search task
        String taskXml = soapCall(buildSearchDatastoreEnvelope(browserMor, datastorePath), sessionCookie)
        GPathResult taskRoot = parseXml(taskXml)
        
        def taskMor = taskRoot.'**'.find { it.name() == 'returnval' }?.text()
        if (!taskMor) {
            log.warn("VCenterApiClient: SearchDatastore did not return a task MOR")
            // Try synchronous search
            return searchDatastoreSync(browserMor, datastorePath)
        }
        
        log.debug("VCenterApiClient: search task ${taskMor}")
        
        // Wait for task completion
        Map<String, Object> result = waitForTask(taskMor)
        return result
    }

    /**
     * Synchronous datastore search (fallback).
     */
    private Map<String, Object> searchDatastoreSync(String browserMor, String datastorePath) {
        String xml = soapCall(buildSearchDatastoreSyncEnvelope(browserMor, datastorePath), sessionCookie)
        return parseSearchResults(xml)
    }

    /**
     * Wait for a task to complete and return results.
     */
    private Map<String, Object> waitForTask(String taskMor) {
        int maxAttempts = 30
        int attempt = 0
        
        while (attempt < maxAttempts) {
            attempt++
            Thread.sleep(500)
            
            String xml = soapCall(buildGetTaskInfoEnvelope(taskMor, propertyCollectorRef), sessionCookie)
            GPathResult root = parseXml(xml)
            
            def state = root.'**'.find { it.name() == 'state' }?.text()
            log.debug("VCenterApiClient: Task ${taskMor} state: ${state}")
            
            if (state == 'success') {
                // Get the task result
                def resultNode = root.'**'.find { it.name() == 'result' }
                if (resultNode) {
                    return parseSearchResultsFromNode(resultNode)
                }
                return [folders: [], files: []]
            } else if (state == 'error') {
                def errorMsg = root.'**'.find { it.name() == 'localizedMessage' }?.text() ?: 'Task failed'
                throw new RuntimeException("Datastore search failed: ${errorMsg}")
            }
        }
        
        throw new RuntimeException("Datastore search timed out after ${maxAttempts * 500}ms")
    }

    /**
     * Parse search results from XML response.
     */
    private Map<String, Object> parseSearchResults(String xml) {
        GPathResult root = parseXml(xml)
        return parseSearchResultsFromNode(root)
    }

    private Map<String, Object> parseSearchResultsFromNode(GPathResult node) {
        List<String> folders = []
        List<Map<String, Object>> files = []
        
        // Find all file entries
        node.'**'.findAll { it.name() == 'file' }.each { fileNode ->
            String path = fileNode.path?.text() ?: ''
            def sizeNode = fileNode.fileSize ?: fileNode.size
            Long size = sizeNode?.text()?.isLong() ? sizeNode.text().toLong() : 0L
            String modified = fileNode.modification?.text() ?: ''
            
            // Check if it's a folder
            def typeAttr = fileNode.@'xsi:type'?.text() ?: ''
            boolean isFolder = typeAttr?.contains('FolderFileInfo') || path.endsWith('/')
            
            if (isFolder) {
                String folderName = path.replaceAll('/$', '')
                if (folderName && folderName != '.' && folderName != '..') {
                    folders << folderName
                }
            } else if (path.toLowerCase().endsWith('.iso')) {
                files << [
                    name: path,
                    size: size,
                    modified: modified
                ]
            }
        }
        
        log.debug("VCenterApiClient: search found ${folders.size()} folders, ${files.size()} ISO files")
        return [folders: folders, files: files]
    }

    // -----------------------------------------------------------------------
    // REST HTTP Helpers
    // -----------------------------------------------------------------------

    private RuntimeException restFailure(String method, String path, int status, String body) {
        String errorType = ''
        try {
            errorType = new JsonSlurper().parseText(body)?.error_type?.toString() ?: ''
        } catch (Exception ignored) {}
        errorType = errorType.replaceAll('[^A-Za-z_]', '').take(64)
        log.warn("VCenterApiClient: REST ${method} ${path} failed status=${status} error_type=${errorType ?: 'n/a'}")
        return new RuntimeException("REST API error (${status})")
    }

    private String restGet(String path) {
        HttpsURLConnection conn = openConn("${restBaseUrl}${path}")
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(90000)
        try {
            conn.setRequestMethod('GET')
            conn.setRequestProperty('vmware-api-session-id', restSessionToken)
            conn.setRequestProperty('Accept', 'application/json')
            int status = conn.responseCode
            InputStream is = status < 400 ? conn.inputStream : conn.errorStream
            String response = is?.getText(StandardCharsets.UTF_8.name()) ?: ''
            if (status >= 400) {
                throw restFailure('GET', path, status, response)
            }
            return response
        } finally {
            conn.disconnect()
        }
    }

    private void restPatch(String path, Map body) {
        // HttpURLConnection has no PATCH; the method field is set by reflection below
        HttpsURLConnection conn = openConn("${restBaseUrl}${path}")
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(90000)

        try {
            // Enable PATCH method via reflection
            // First set to POST, then change the internal method field
            conn.setRequestMethod('POST')
            conn.setDoOutput(true)
            
            // Use reflection to change method to PATCH
            try {
                def field = java.net.HttpURLConnection.getDeclaredField('method')
                field.setAccessible(true)
                field.set(conn, 'PATCH')
            } catch (Exception e) {
                log.warn("VCenterApiClient: Could not set PATCH via reflection on conn, trying delegate")
                try {
                    // For HttpsURLConnection, there's a delegate
                    def delegateField = conn.getClass().getDeclaredField('delegate')
                    delegateField.setAccessible(true)
                    def delegate = delegateField.get(conn)
                    def methodField = java.net.HttpURLConnection.getDeclaredField('method')
                    methodField.setAccessible(true)
                    methodField.set(delegate, 'PATCH')
                } catch (Exception e2) {
                    log.warn("VCenterApiClient: Could not set PATCH via delegate, using X-HTTP-Method-Override")
                    conn.setRequestProperty('X-HTTP-Method-Override', 'PATCH')
                }
            }
            
            conn.setRequestProperty('vmware-api-session-id', restSessionToken)
            conn.setRequestProperty('Content-Type', 'application/json')
            
            String jsonBody = JsonOutput.toJson(body)
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8)
            conn.setRequestProperty('Content-Length', bytes.length.toString())
            conn.outputStream.write(bytes)
            conn.outputStream.flush()
            
            int status = conn.responseCode
            if (status >= 400) {
                InputStream es = conn.errorStream
                String response = es?.getText(StandardCharsets.UTF_8.name()) ?: ''
                throw restFailure('PATCH', path, status, response)
            }
            log.debug("VCenterApiClient: REST PATCH ${path} status=${status}")
        } finally {
            conn.disconnect()
        }
    }

    private void restPost(String path, Map body) {
        HttpsURLConnection conn = openConn("${restBaseUrl}${path}")
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(90000)
        try {
            conn.setRequestMethod('POST')
            conn.setDoOutput(true)
            conn.setRequestProperty('vmware-api-session-id', restSessionToken)
            conn.setRequestProperty('Content-Type', 'application/json')
            
            String jsonBody = body ? JsonOutput.toJson(body) : ''
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8)
            conn.setRequestProperty('Content-Length', bytes.length.toString())
            if (bytes.length > 0) {
                conn.outputStream.write(bytes)
                conn.outputStream.flush()
            }
            
            int status = conn.responseCode
            if (status >= 400) {
                InputStream es = conn.errorStream
                String response = es?.getText(StandardCharsets.UTF_8.name()) ?: ''
                throw restFailure('POST', path, status, response)
            }
        } finally {
            conn.disconnect()
        }
    }

    // -----------------------------------------------------------------------
    // SOAP HTTP Helpers
    // -----------------------------------------------------------------------

    private String soapCall(String soapEnvelope, String cookie) {
        HttpsURLConnection conn = openConn(sdkUrl)
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(60000)
        try {
            conn.setRequestMethod('POST')
            conn.setDoOutput(true)
            conn.setRequestProperty('Content-Type', 'text/xml; charset=UTF-8')
            conn.setRequestProperty('SOAPAction', '""')
            if (cookie) {
                conn.setRequestProperty('Cookie', cookie)
            }

            byte[] payload = soapEnvelope.getBytes(StandardCharsets.UTF_8)
            conn.setRequestProperty('Content-Length', payload.length.toString())
            conn.outputStream.write(payload)
            conn.outputStream.flush()

            int status = conn.responseCode
            InputStream is = status < 400 ? conn.inputStream : conn.errorStream
            String response = is.getText(StandardCharsets.UTF_8.name())

            if (!cookie) {
                String setCookie = conn.getHeaderField('Set-Cookie')
                if (setCookie) {
                    sessionCookie = setCookie.split(';')[0].trim()
                }
            }

            if (status >= 400) {
                String faultMsg = parseSoapFaultMessage(response)
                log.warn("VCenterApiClient: SOAP call failed status=${status} fault='${faultMsg ?: 'not parsed'}'")
                throw new RuntimeException(faultMsg ?: "vCenter SOAP error ${status}")
            }
            return response
        } finally {
            conn.disconnect()
        }
    }

    private static String parseSoapFaultMessage(String xml) {
        try {
            if (!xml) return null
            GPathResult root = newSlurper().parseText(xml)
            String priv = root.'**'.find { it.name() == 'privilegeId' }?.text()
            if (priv) return "Permission denied — need '${priv}' privilege"
            String fs = root.'**'.find { it.name() == 'faultstring' }?.text()
            if (fs) return "vCenter fault: ${fs}"
            return null
        } catch (Exception ignored) {
            return null
        }
    }

    private static GPathResult parseXml(String xml) {
        newSlurper().parseText(xml)
    }

    /** Namespace-unaware, non-validating parser that rejects DOCTYPE declarations (no external entities). */
    private static XmlSlurper newSlurper() {
        new XmlSlurper(false, false, false)
    }

    // -----------------------------------------------------------------------
    // SOAP Envelope Builders
    // -----------------------------------------------------------------------

    private static String pvEnvelope(String bodyContent) {
        """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<soapenv:Body>
${bodyContent}
</soapenv:Body>
</soapenv:Envelope>"""
    }

    private static String buildRetrieveServiceContentEnvelope() {
        pvEnvelope("""<RetrieveServiceContent xmlns="urn:vim25">
<_this type="ServiceInstance">ServiceInstance</_this>
</RetrieveServiceContent>""")
    }

    private static String buildLoginEnvelope(String user, String pass, String sessionMgr) {
        pvEnvelope("""<Login xmlns="urn:vim25">
<_this type="SessionManager">${xmlEscape(sessionMgr)}</_this>
<userName>${xmlEscape(user)}</userName>
<password>${xmlEscape(pass)}</password>
</Login>""")
    }

    private static String buildLogoutEnvelope(String sessionMgr) {
        pvEnvelope("""<Logout xmlns="urn:vim25">
<_this type="SessionManager">${xmlEscape(sessionMgr)}</_this>
</Logout>""")
    }

    private static String buildGetVmQuestionEnvelope(String vmMor, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>VirtualMachine</type>
    <all>false</all>
    <pathSet>runtime.question</pathSet>
  </propSet>
  <objectSet>
    <obj type="VirtualMachine">${xmlEscape(vmMor)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String buildAnswerVmEnvelope(String vmMor, String questionId, String answerChoice) {
        pvEnvelope("""<AnswerVM xmlns="urn:vim25">
<_this type="VirtualMachine">${xmlEscape(vmMor)}</_this>
<questionId>${xmlEscape(questionId)}</questionId>
<answerChoice>${xmlEscape(answerChoice)}</answerChoice>
</AnswerVM>""")
    }

    private static String buildVmExistsEnvelope(String vmMor, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>VirtualMachine</type>
    <all>false</all>
    <pathSet>name</pathSet>
  </propSet>
  <objectSet>
    <obj type="VirtualMachine">${xmlEscape(vmMor)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String buildFindDatastoreEnvelope(String rootFolder, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>Datastore</type>
    <all>false</all>
    <pathSet>name</pathSet>
  </propSet>
  <objectSet>
    <obj type="Folder">${xmlEscape(rootFolder)}</obj>
    <skip>false</skip>
    <selectSet xsi:type="TraversalSpec">
      <name>visitFolders</name>
      <type>Folder</type>
      <path>childEntity</path>
      <skip>false</skip>
      <selectSet><name>visitFolders</name></selectSet>
      <selectSet><name>dcToDS</name></selectSet>
    </selectSet>
    <selectSet xsi:type="TraversalSpec">
      <name>dcToDS</name>
      <type>Datacenter</type>
      <path>datastoreFolder</path>
      <skip>false</skip>
      <selectSet><name>visitFolders</name></selectSet>
    </selectSet>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String buildGetDatastoreBrowserEnvelope(String datastoreMor, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>Datastore</type>
    <all>false</all>
    <pathSet>browser</pathSet>
  </propSet>
  <objectSet>
    <obj type="Datastore">${xmlEscape(datastoreMor)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String buildSearchDatastoreEnvelope(String browserMor, String datastorePath) {
        // SearchDatastore_Task lists only the immediate contents of this folder (non-recursive),
        // so a folder view shows just its own ISOs plus its direct subfolders. Using the recursive
        // SearchDatastoreSubFolders_Task here made the datastore root list ISOs from every subfolder.
        pvEnvelope("""<SearchDatastore_Task xmlns="urn:vim25">
<_this type="HostDatastoreBrowser">${xmlEscape(browserMor)}</_this>
<datastorePath>${xmlEscape(datastorePath)}</datastorePath>
<searchSpec>
  <query xsi:type="IsoImageFileQuery"/>
  <query xsi:type="FolderFileQuery"/>
  <details xsi:type="FileQueryFlags">
    <fileType>true</fileType>
    <fileSize>true</fileSize>
    <modification>true</modification>
    <fileOwner>false</fileOwner>
  </details>
  <searchCaseInsensitive>true</searchCaseInsensitive>
</searchSpec>
</SearchDatastore_Task>""")
    }

    private static String buildSearchDatastoreSyncEnvelope(String browserMor, String datastorePath) {
        pvEnvelope("""<SearchDatastore_Task xmlns="urn:vim25">
<_this type="HostDatastoreBrowser">${xmlEscape(browserMor)}</_this>
<datastorePath>${xmlEscape(datastorePath)}</datastorePath>
<searchSpec>
  <query xsi:type="IsoImageFileQuery"/>
  <query xsi:type="FolderFileQuery"/>
  <details xsi:type="FileQueryFlags">
    <fileType>true</fileType>
    <fileSize>true</fileSize>
    <modification>true</modification>
    <fileOwner>false</fileOwner>
  </details>
</searchSpec>
</SearchDatastore_Task>""")
    }

    private static String buildGetTaskInfoEnvelope(String taskMor, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>Task</type>
    <all>false</all>
    <pathSet>info</pathSet>
  </propSet>
  <objectSet>
    <obj type="Task">${xmlEscape(taskMor)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String xmlEscape(String s) {
        s?.replace('&', '&amp;')
          ?.replace('<', '&lt;')
          ?.replace('>', '&gt;')
          ?.replace('"', '&quot;')
          ?.replace("'", '&apos;') ?: ''
    }

    /**
     * Build SOAP envelope to get host's datastore property.
     */
    private static String buildGetHostDatastoresEnvelope(String hostMor, String propCollectorRef) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrieveProperties>
      <vim:_this type="PropertyCollector">${xmlEscape(propCollectorRef)}</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>HostSystem</vim:type>
          <vim:pathSet>datastore</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem">${xmlEscape(hostMor)}</vim:obj>
        </vim:objectSet>
      </vim:specSet>
    </vim:RetrieveProperties>
  </soapenv:Body>
</soapenv:Envelope>"""
    }
}
