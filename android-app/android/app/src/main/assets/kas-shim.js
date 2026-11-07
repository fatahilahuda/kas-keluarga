/*
 * Kas Keluarga, Android wrapper shim.
 *
 * Injected at document start, only on https://fatahilahuda.github.io, by KasShimPlugin.
 * Android WebView has no Web Share API and ignores <a download href="blob:...">, so the
 * web app's backup export, CSV report and family invite would fail silently. This file
 * routes them to the native share sheet through Capacitor's Share and Filesystem plugins,
 * makes the hardware back button close an open sheet instead of quitting, and tells the
 * web app it is already installed so it hides the "Pasang di layar utama" card.
 *
 * The Capacitor bridge is injected after this script, so plugins are looked up lazily.
 */
(function () {
  'use strict';
  if (window.__kasShim) return;
  window.__kasShim = true;

  var plugins = function () {
    return (window.Capacitor && window.Capacitor.Plugins) || {};
  };

  try {
    Object.defineProperty(navigator, 'standalone', { get: function () { return true; }, configurable: true });
  } catch (e) { /* read-only on some WebViews; the install card then just stays visible */ }

  function abortError() {
    try { return new DOMException('Share canceled', 'AbortError'); } catch (e) {
      var err = new Error('Share canceled');
      err.name = 'AbortError';
      return err;
    }
  }

  function fileToBase64(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        var url = String(reader.result);
        resolve(url.slice(url.indexOf(',') + 1));
      };
      reader.onerror = function () { reject(reader.error); };
      reader.readAsDataURL(file);
    });
  }

  function safeFileName(name) {
    var clean = String(name || '').replace(/[^\w.\-]+/g, '_').slice(-120);
    return clean || 'file';
  }

  async function writeToCache(file) {
    var fs = plugins().Filesystem;
    if (!fs) throw new Error('Filesystem plugin unavailable');
    var written = await fs.writeFile({
      path: 'shared/' + safeFileName(file.name),
      data: await fileToBase64(file),
      directory: 'CACHE',
      recursive: true
    });
    return written.uri;
  }

  navigator.canShare = function (data) {
    if (!data) return false;
    var hasFiles = !!(data.files && data.files.length);
    return hasFiles || !!(data.text || data.url || data.title);
  };

  navigator.share = async function (data) {
    data = data || {};
    var share = plugins().Share;
    if (!share) throw new Error('Share plugin unavailable');

    var options = {};
    if (data.title) { options.title = data.title; options.dialogTitle = data.title; }
    if (data.text) options.text = data.text;
    if (data.url) options.url = data.url;
    if (data.files && data.files.length) {
      options.files = [];
      for (var i = 0; i < data.files.length; i++) options.files.push(await writeToCache(data.files[i]));
    }

    try {
      await share.share(options);
    } catch (e) {
      // The web app treats AbortError as "user closed the sheet" and anything else as a failure.
      if (/cancel/i.test(String((e && e.message) || e))) throw abortError();
      throw e;
    }
  };

  // Fallback for downloads: WebView drops <a download> clicks on blob:/data: links.
  document.addEventListener('click', function (event) {
    var link = event.target && event.target.closest ? event.target.closest('a[download]') : null;
    if (!link) return;
    var href = link.getAttribute('href') || '';
    if (!/^(blob:|data:)/.test(href)) return;
    event.preventDefault();
    var name = link.getAttribute('download') || 'file';
    fetch(href)
      .then(function (response) { return response.blob(); })
      .then(function (blob) {
        var file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
        return navigator.share({ files: [file], title: name });
      })
      .catch(function () {});
  }, true);

  // Back button: close the open sheet or celebration first (the app already does this on Escape).
  function onBackButton() {
    var sheet = document.getElementById('sheet');
    if (document.getElementById('celeb') || (sheet && !sheet.hidden)) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return;
    }
    var app = plugins().App;
    if (app && app.minimizeApp) app.minimizeApp();
  }

  function listenForBackButton() {
    var app = plugins().App;
    if (app && app.addListener) app.addListener('backButton', onBackButton);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', listenForBackButton);
  } else {
    listenForBackButton();
  }
})();
