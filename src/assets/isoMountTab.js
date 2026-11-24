/**
 * isoMountTab.js
 *
 * Loaded as an external static asset for the CD-DVD Manager plugin.
 *
 * Data is passed via data-* attributes on #iso-container:
 *   data-instance-id   — Morpheus instance ID
 *   data-instance-name — Morpheus instance name (== vCenter VM name)
 *   data-cloud-id      — Morpheus cloud/zone ID
 *
 * API routes: /plugin/isoMount/*
 */
(function () {
  'use strict';

  function init() {
    var container = document.getElementById('iso-container');
    if (!container) return;

    var INSTANCE_ID   = container.getAttribute('data-instance-id')   || '';
    var INSTANCE_NAME = container.getAttribute('data-instance-name') || '';
    var CLOUD_ID      = container.getAttribute('data-cloud-id')      || '';
    var BASE_URL      = '/plugin/isoMount';

    // State
    var currentDatastore = '';
    var currentPath = '/';
    var selectedIsoPath = '';
    var selectedCdrom = '';
    var vmId = '';

    // -----------------------------------------------------------------------
    // Initialize
    // -----------------------------------------------------------------------
    function loadInitialData() {
      loadDatastores();
      loadCdroms();
    }

    // -----------------------------------------------------------------------
    // Datastore operations
    // -----------------------------------------------------------------------
    function loadDatastores() {
      var select = document.getElementById('iso-datastore-select');
      if (!select) return;

      select.innerHTML = '<option value="">-- Loading... --</option>';

      pluginCall('datastores', { cloudId: CLOUD_ID })
        .then(function (res) {
          if (res && res.success && res.datastores) {
            select.innerHTML = '<option value="">-- Select Datastore --</option>';
            res.datastores.forEach(function (ds) {
              var opt = document.createElement('option');
              opt.value = ds.name;
              opt.textContent = ds.name + ' (' + ds.type + ')';
              select.appendChild(opt);
            });
          } else {
            select.innerHTML = '<option value="">Error loading datastores</option>';
            showStatus((res && res.msg) || 'Failed to load datastores', 'error');
          }
        })
        .catch(function (err) {
          select.innerHTML = '<option value="">Error loading datastores</option>';
          showStatus('Error: ' + err, 'error');
        });
    }

    function onDatastoreChange(e) {
      var datastoreName = e.target.value;
      currentDatastore = datastoreName;
      currentPath = '/';
      selectedIsoPath = '';
      updateSelectedFile();
      updateMountButton();

      if (datastoreName) {
        browseFolder('/');
      } else {
        var browser = document.getElementById('iso-file-browser');
        if (browser) {
          browser.innerHTML = '<div class="iso-loading">Select a datastore to browse...</div>';
        }
        updateBreadcrumb('/');
      }
    }

    // -----------------------------------------------------------------------
    // Folder browsing
    // -----------------------------------------------------------------------
    function browseFolder(path) {
      var browser = document.getElementById('iso-file-browser');
      if (!browser) return;

      currentPath = path;
      browser.innerHTML = '<div class="iso-loading"><i class="fa fa-spinner fa-spin"></i> Loading...</div>';
      updateBreadcrumb(path);

      pluginCall('browse', {
        cloudId: CLOUD_ID,
        datastore: currentDatastore,
        path: path
      })
        .then(function (res) {
          if (res && res.success) {
            renderFileBrowser(res.folders || [], res.files || []);
          } else {
            browser.innerHTML = '<div class="iso-error">' + ((res && res.msg) || 'Failed to browse') + '</div>';
          }
        })
        .catch(function (err) {
          browser.innerHTML = '<div class="iso-error">Error: ' + err + '</div>';
        });
    }

    function renderFileBrowser(folders, files) {
      var browser = document.getElementById('iso-file-browser');
      if (!browser) return;

      var html = '<div class="iso-file-list">';

      // Parent folder link (if not at root)
      if (currentPath !== '/' && currentPath !== '') {
        html += '<div class="iso-file-item iso-folder" data-action="parent">' +
                '<i class="fa fa-level-up"></i> <span>..</span></div>';
      }

      // Folders
      folders.forEach(function (folder) {
        html += '<div class="iso-file-item iso-folder" data-action="folder" data-name="' + escapeHtml(folder) + '">' +
                '<i class="fa fa-folder"></i> <span>' + escapeHtml(folder) + '</span></div>';
      });

      // ISO files
      files.forEach(function (file) {
        var size = formatFileSize(file.size || 0);
        html += '<div class="iso-file-item iso-file" data-action="select" data-name="' + escapeHtml(file.name) + '">' +
                '<i class="fa fa-file-o"></i> <span class="iso-filename">' + escapeHtml(file.name) + '</span>' +
                '<span class="iso-filesize">' + size + '</span></div>';
      });

      if (folders.length === 0 && files.length === 0) {
        html += '<div class="iso-empty">No folders or ISO files found</div>';
      }

      html += '</div>';
      browser.innerHTML = html;
    }

    function onFileBrowserClick(e) {
      var item = e.target.closest('[data-action]');
      if (!item) return;

      var action = item.getAttribute('data-action');
      var name = item.getAttribute('data-name') || '';

      if (action === 'parent') {
        // Go up one level
        var parts = currentPath.split('/').filter(function(p) { return p; });
        parts.pop();
        var newPath = '/' + parts.join('/');
        browseFolder(newPath);
      } else if (action === 'folder') {
        // Enter folder
        var newPath = currentPath === '/' ? '/' + name : currentPath + '/' + name;
        browseFolder(newPath);
      } else if (action === 'select') {
        // Select ISO file
        selectIsoFile(name);
      }
    }

    function selectIsoFile(filename) {
      // Build full datastore path: [DatastoreName] path/to/file.iso
      var pathPart = currentPath === '/' ? filename : currentPath.replace(/^\//, '') + '/' + filename;
      selectedIsoPath = '[' + currentDatastore + '] ' + pathPart;
      
      // Highlight selected item
      var items = document.querySelectorAll('.iso-file-item');
      items.forEach(function (item) {
        item.classList.remove('selected');
      });
      var selectedItem = document.querySelector('[data-name="' + filename + '"]');
      if (selectedItem) {
        selectedItem.classList.add('selected');
      }

      updateSelectedFile();
      updateMountButton();
    }

    function updateSelectedFile() {
      var display = document.getElementById('iso-selected-file');
      if (!display) return;

      if (selectedIsoPath) {
        display.innerHTML = '<span class="iso-selected-path">' + escapeHtml(selectedIsoPath) + '</span>';
      } else {
        display.innerHTML = '<span class="iso-no-selection">No ISO selected</span>';
      }
    }

    function updateBreadcrumb(path) {
      var bc = document.getElementById('iso-breadcrumb');
      if (!bc) return;

      var parts = path.split('/').filter(function(p) { return p; });
      var html = '<span class="iso-breadcrumb-item" data-path="/">/</span>';
      
      var cumPath = '';
      parts.forEach(function (part, idx) {
        cumPath += '/' + part;
        html += ' <span class="iso-breadcrumb-sep">&gt;</span> ';
        html += '<span class="iso-breadcrumb-item" data-path="' + escapeHtml(cumPath) + '">' + escapeHtml(part) + '</span>';
      });

      bc.innerHTML = html;
    }

    function onBreadcrumbClick(e) {
      var item = e.target.closest('[data-path]');
      if (!item) return;
      var path = item.getAttribute('data-path');
      browseFolder(path);
    }

    // -----------------------------------------------------------------------
    // CD/DVD operations
    // -----------------------------------------------------------------------
    function loadCdroms() {
      var select = document.getElementById('iso-cdrom-select');
      var status = document.getElementById('iso-cdrom-status');
      if (!select) return;

      select.innerHTML = '<option value="">-- Loading... --</option>';
      if (status) status.innerHTML = '';

      pluginCall('cdroms', { cloudId: CLOUD_ID, instanceName: INSTANCE_NAME })
        .then(function (res) {
          if (res && res.success && res.cdroms) {
            vmId = res.vmId || '';
            
            if (res.cdroms.length === 0) {
              select.innerHTML = '<option value="">No CD/DVD drives found</option>';
              if (status) status.innerHTML = '<span class="iso-warning">This VM has no CD/DVD drive. Add one in vSphere first.</span>';
            } else {
              select.innerHTML = '';
              res.cdroms.forEach(function (cd, idx) {
                var opt = document.createElement('option');
                opt.value = cd.cdrom;
                var label = cd.label || ('CD/DVD ' + (idx + 1));
                var stateInfo = cd.state === 'CONNECTED' ? ' (Connected)' : '';
                var backingInfo = '';
                if (cd.backing && cd.backing.iso_file) {
                  backingInfo = ' - ' + cd.backing.iso_file.split('/').pop();
                }
                opt.textContent = label + stateInfo + backingInfo;
                opt.setAttribute('data-state', cd.state || '');
                opt.setAttribute('data-backing', cd.backing ? JSON.stringify(cd.backing) : '');
                select.appendChild(opt);
              });
              
              // Auto-select first CD-ROM
              if (res.cdroms.length > 0) {
                selectedCdrom = res.cdroms[0].cdrom;
                select.value = selectedCdrom;
                updateCdromStatus(res.cdroms[0]);
              }
            }
          } else {
            select.innerHTML = '<option value="">Error loading CD/DVD drives</option>';
            if (status) status.innerHTML = '<span class="iso-error">' + ((res && res.msg) || 'Failed to load') + '</span>';
          }
          updateMountButton();
        })
        .catch(function (err) {
          select.innerHTML = '<option value="">Error</option>';
          if (status) status.innerHTML = '<span class="iso-error">Error: ' + err + '</span>';
        });
    }

    function onCdromChange(e) {
      selectedCdrom = e.target.value;
      var opt = e.target.options[e.target.selectedIndex];
      if (opt) {
        var state = opt.getAttribute('data-state') || '';
        var backing = opt.getAttribute('data-backing') || '';
        updateCdromStatus({ state: state, backing: backing ? JSON.parse(backing) : null });
      }
      updateMountButton();
    }

    function updateCdromStatus(cdrom) {
      var status = document.getElementById('iso-cdrom-status');
      var unmountBtn = document.getElementById('iso-unmount-btn');
      if (!status) return;

      if (cdrom.state === 'CONNECTED' && cdrom.backing && cdrom.backing.iso_file) {
        status.innerHTML = '<span class="iso-mounted">Currently mounted: ' + escapeHtml(cdrom.backing.iso_file) + '</span>';
        if (unmountBtn) unmountBtn.disabled = false;
      } else {
        status.innerHTML = '<span class="iso-unmounted">No ISO mounted</span>';
        if (unmountBtn) unmountBtn.disabled = true;
      }
    }

    function updateMountButton() {
      var btn = document.getElementById('iso-mount-btn');
      if (!btn) return;
      btn.disabled = !(selectedIsoPath && selectedCdrom);
    }

    // -----------------------------------------------------------------------
    // Mount/Unmount
    // -----------------------------------------------------------------------
    function mountIso() {
      if (!selectedIsoPath || !selectedCdrom) {
        showStatus('Please select an ISO file and CD/DVD drive', 'error');
        return;
      }

      var btn = document.getElementById('iso-mount-btn');
      if (btn) {
        btn.disabled = true;
        btn.textContent = 'Mounting...';
      }

      showStatus('Mounting ISO...', 'info');

      pluginCall('mount', {
        cloudId: CLOUD_ID,
        instanceName: INSTANCE_NAME,
        cdrom: selectedCdrom,
        isoPath: selectedIsoPath
      })
        .then(function (res) {
          if (res && res.success) {
            showStatus('ISO mounted successfully!', 'success');
            // Refresh CD-ROM list to show new state
            setTimeout(loadCdroms, 1000);
          } else {
            showStatus((res && res.msg) || 'Failed to mount ISO', 'error');
          }
        })
        .catch(function (err) {
          showStatus('Error: ' + err, 'error');
        })
        .finally(function () {
          if (btn) {
            btn.disabled = false;
            btn.textContent = 'Mount ISO';
          }
          updateMountButton();
        });
    }

    function unmountIso() {
      if (!selectedCdrom) {
        showStatus('Please select a CD/DVD drive', 'error');
        return;
      }

      var btn = document.getElementById('iso-unmount-btn');
      if (btn) {
        btn.disabled = true;
        btn.textContent = 'Unmounting...';
      }

      showStatus('Unmounting ISO...', 'info');

      pluginCall('unmount', {
        cloudId: CLOUD_ID,
        instanceName: INSTANCE_NAME,
        cdrom: selectedCdrom
      })
        .then(function (res) {
          if (res && res.success) {
            showStatus('ISO unmounted successfully!', 'success');
            setTimeout(loadCdroms, 1000);
          } else {
            showStatus((res && res.msg) || 'Failed to unmount ISO', 'error');
          }
        })
        .catch(function (err) {
          showStatus('Error: ' + err, 'error');
        })
        .finally(function () {
          if (btn) {
            btn.disabled = false;
            btn.textContent = 'Unmount';
          }
        });
    }

    // -----------------------------------------------------------------------
    // Status display
    // -----------------------------------------------------------------------
    function showStatus(msg, type) {
      var el = document.getElementById('iso-status-msg');
      if (!el) return;
      el.className = 'iso-status-msg ' + (type || '');
      el.textContent = msg;
      
      // Auto-hide success messages
      if (type === 'success') {
        setTimeout(function () {
          if (el.textContent === msg) {
            el.className = 'iso-status-msg';
            el.textContent = '';
          }
        }, 5000);
      }
    }

    // -----------------------------------------------------------------------
    // API helper
    // -----------------------------------------------------------------------
    function pluginCall(action, params) {
      var url = BASE_URL + '/' + action + '?' + encodeParams(params);
      return fetch(url, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json' }
      })
      .then(function (resp) {
        return resp.text();
      })
      .then(function (text) {
        try {
          return JSON.parse(text);
        } catch (e) {
          console.error('Failed to parse JSON:', text);
          return { success: false, msg: 'Invalid response from server' };
        }
      });
    }

    function encodeParams(obj) {
      return Object.keys(obj).map(function (k) {
        return encodeURIComponent(k) + '=' + encodeURIComponent(obj[k] || '');
      }).join('&');
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------
    function escapeHtml(str) {
      if (!str) return '';
      return str.replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');
    }

    function formatFileSize(bytes) {
      if (bytes === 0) return '0 B';
      var k = 1024;
      var sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
      var i = Math.floor(Math.log(bytes) / Math.log(k));
      return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    // -----------------------------------------------------------------------
    // Event bindings
    // -----------------------------------------------------------------------
    var datastoreSelect = document.getElementById('iso-datastore-select');
    if (datastoreSelect) datastoreSelect.addEventListener('change', onDatastoreChange);

    var cdromSelect = document.getElementById('iso-cdrom-select');
    if (cdromSelect) cdromSelect.addEventListener('change', onCdromChange);

    var fileBrowser = document.getElementById('iso-file-browser');
    if (fileBrowser) fileBrowser.addEventListener('click', onFileBrowserClick);

    var breadcrumb = document.getElementById('iso-breadcrumb');
    if (breadcrumb) breadcrumb.addEventListener('click', onBreadcrumbClick);

    var mountBtn = document.getElementById('iso-mount-btn');
    if (mountBtn) mountBtn.addEventListener('click', mountIso);

    var unmountBtn = document.getElementById('iso-unmount-btn');
    if (unmountBtn) unmountBtn.addEventListener('click', unmountIso);

    var refreshBtn = document.getElementById('iso-refresh-btn');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', function () {
        loadDatastores();
        loadCdroms();
        selectedIsoPath = '';
        updateSelectedFile();
      });
    }

    // -----------------------------------------------------------------------
    // Start
    // -----------------------------------------------------------------------
    loadInitialData();
  }

  // Wait for DOM or use MutationObserver
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    // DOM already ready, but tab content might be injected later
    if (document.getElementById('iso-container')) {
      init();
    } else {
      // Use MutationObserver to detect when container appears
      var observer = new MutationObserver(function (mutations, obs) {
        if (document.getElementById('iso-container')) {
          obs.disconnect();
          init();
        }
      });
      observer.observe(document.body, { childList: true, subtree: true });
    }
  }
})();
