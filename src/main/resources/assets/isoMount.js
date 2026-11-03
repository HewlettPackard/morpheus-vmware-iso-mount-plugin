(function() {
  'use strict';
  
  var cloudId = 0;
  var currentDatastore = '';
  var currentPath = '/';
  
  function init() {
    // Get cloudId from data attribute on container
    var container = document.getElementById('isoMountContainer');
    if (container) {
      cloudId = parseInt(container.getAttribute('data-cloud-id') || '0', 10);
    }
    
    // Attach click handlers to datastore rows
    var dsRows = document.querySelectorAll('.ds-row');
    for (var i = 0; i < dsRows.length; i++) {
      dsRows[i].addEventListener('click', function() {
        var dsName = this.getAttribute('data-ds-name');
        if (dsName) {
          openDatastore(dsName);
        }
      });
    }
    
    // Attach close button handler
    var closeBtn = document.getElementById('browserCloseBtn');
    if (closeBtn) {
      closeBtn.addEventListener('click', closePanel);
    }
  }
  
  function openDatastore(dsName) {
    currentDatastore = dsName;
    currentPath = '/';
    var panel = document.getElementById('isoBrowserPanel');
    if (panel) {
      panel.classList.add('active');
    }
    var title = document.getElementById('browserTitle');
    if (title) {
      title.textContent = 'Browse: ' + dsName;
    }
    browse(dsName, '/');
  }
  
  function closePanel() {
    var panel = document.getElementById('isoBrowserPanel');
    if (panel) {
      panel.classList.remove('active');
    }
  }
  
  function openFolder(folderName) {
    var newPath = currentPath;
    if (newPath === '/') {
      newPath = '/' + folderName;
    } else {
      newPath = currentPath + '/' + folderName;
    }
    currentPath = newPath;
    browse(currentDatastore, newPath);
  }
  
  function navigateTo(path) {
    currentPath = path;
    browse(currentDatastore, path);
  }
  
  function browse(dsName, path) {
    var content = document.getElementById('browserContent');
    var breadcrumb = document.getElementById('browserBreadcrumb');
    
    if (!content || !breadcrumb) return;
    
    content.innerHTML = '<div class="browser-loading">Loading...</div>';
    
    // Build breadcrumb
    var parts = path.split('/').filter(function(p) { return p.length > 0; });
    
    // Create breadcrumb elements
    breadcrumb.innerHTML = '';
    
    var homeLink = document.createElement('span');
    homeLink.className = 'breadcrumb-link';
    homeLink.textContent = '🏠 ' + dsName;
    homeLink.setAttribute('data-nav-path', '/');
    homeLink.addEventListener('click', function() {
      navigateTo('/');
    });
    breadcrumb.appendChild(homeLink);
    
    var buildPath = '';
    for (var i = 0; i < parts.length; i++) {
      buildPath += '/' + parts[i];
      
      var sep = document.createElement('span');
      sep.className = 'breadcrumb-sep';
      sep.textContent = ' / ';
      breadcrumb.appendChild(sep);
      
      var link = document.createElement('span');
      link.className = 'breadcrumb-link';
      link.textContent = parts[i];
      link.setAttribute('data-nav-path', buildPath);
      (function(p) {
        link.addEventListener('click', function() {
          navigateTo(p);
        });
      })(buildPath);
      breadcrumb.appendChild(link);
    }
    
    // Make API call
    var url = '/plugin/isoMount/browse?cloudId=' + cloudId + '&datastore=' + encodeURIComponent(dsName) + '&path=' + encodeURIComponent(path);
    
    fetch(url, { credentials: 'same-origin' })
      .then(function(res) { return res.text(); })
      .then(function(text) {
        var data;
        try { 
          data = JSON.parse(text); 
        } catch(e) { 
          throw new Error('Invalid JSON response'); 
        }
        
        if (!data.success) {
          content.innerHTML = '<div class="iso-error">' + escHtml(data.msg || 'Failed to browse') + '</div>';
          return;
        }
        
        var folders = data.folders || [];
        var files = data.files || [];
        
        if (folders.length === 0 && files.length === 0) {
          content.innerHTML = '<div class="browser-empty">This folder is empty</div>';
          return;
        }
        
        var list = document.createElement('ul');
        list.className = 'item-list';
        
        // Folders first
        for (var i = 0; i < folders.length; i++) {
          var fname = folders[i];
          // Skip folder names that look like datastore paths (e.g. "[datastore] name")
          if (fname.indexOf('[') === 0) continue;
          
          var li = document.createElement('li');
          li.className = 'item-row folder-row';
          li.setAttribute('data-folder-name', fname);
          li.innerHTML = '<span class="item-icon folder-icon">📁</span><span class="item-name">' + escHtml(fname) + '</span>';
          (function(fn) {
            li.addEventListener('click', function() {
              openFolder(fn);
            });
          })(fname);
          list.appendChild(li);
        }
        
        // Files (ISO files highlighted)
        for (var i = 0; i < files.length; i++) {
          var file = files[i];
          var isIso = file.toLowerCase().endsWith('.iso');
          
          var li = document.createElement('li');
          li.className = 'item-row' + (isIso ? ' iso-file' : '');
          li.innerHTML = '<span class="item-icon file-icon">' + (isIso ? '💿' : '📄') + '</span><span class="item-name">' + escHtml(file) + '</span>';
          list.appendChild(li);
        }
        
        content.innerHTML = '';
        content.appendChild(list);
      })
      .catch(function(err) {
        content.innerHTML = '<div class="iso-error">Error: ' + escHtml(err.message) + '</div>';
      });
  }
  
  function escHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }
  
  // Initialize when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    // Small delay to ensure Morpheus has rendered the tab content
    setTimeout(init, 100);
  }
})();
