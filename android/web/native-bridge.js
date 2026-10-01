/* ============================================================
   جسر تطبيق الأندرويد — بيتحط أول سكريبت في index.html جوّه الـ APK بس.
   بيخلّي الحاجات اللي المتصفح كان بيعملها تشتغل جوّه التطبيق:
   - تحميل الملفات (PDF / Excel / صور / نسخة احتياطية) → بتتحفظ في Download/Ibtkar
   - مشاركة الملفات (navigator.share) → قايمة المشاركة بتاعة أندرويد (واتساب وغيره)
   - فتح الصور بحجم كبير جوّه التطبيق، واللينكات الخارجية في المتصفح/التطبيق المناسب
   - زرار الرجوع بيقفل آخر نافذة مفتوحة بدل ما يقفل التطبيق
   ============================================================ */
(function () {
  'use strict';
  var N = window.IbtkarNative;
  if (!N) return;

  var MIME_BY_EXT = {
    pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
    webp: 'image/webp', gif: 'image/gif', json: 'application/json', txt: 'text/plain',
    csv: 'text/csv', mp4: 'video/mp4',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    xls: 'application/vnd.ms-excel'
  };
  function guessMime(name) {
    var m = /\.([a-z0-9]+)$/i.exec(name || '');
    return (m && MIME_BY_EXT[m[1].toLowerCase()]) || 'application/octet-stream';
  }
  function extForMime(mime) {
    for (var k in MIME_BY_EXT) if (MIME_BY_EXT[k] === mime) return '.' + k;
    return '';
  }

  // نحتفظ بكل Blob اتعمله URL، عشان لو الكود عمل revoke بسرعة بعد الـ click نقدر نحفظه برضه
  var blobs = new Map();
  var origCreate = URL.createObjectURL.bind(URL);
  var origRevoke = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var u = origCreate(obj);
    if (obj instanceof Blob) blobs.set(u, obj);
    return u;
  };
  URL.revokeObjectURL = function (u) {
    setTimeout(function () { blobs.delete(u); origRevoke(u); }, 60000);
  };

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { var s = String(r.result); resolve(s.substring(s.indexOf(',') + 1)); };
      r.onerror = function () { reject(r.error); };
      r.readAsDataURL(blob);
    });
  }
  function hrefToBlob(href) {
    if (blobs.has(href)) return Promise.resolve(blobs.get(href));
    return fetch(href).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    });
  }

  function saveHref(href, name) {
    return hrefToBlob(href).then(function (blob) {
      var mime = (blob.type && blob.type !== 'application/octet-stream') ? blob.type : guessMime(name);
      if (!/\.[a-z0-9]+$/i.test(name || '')) name = (name || 'ibtkar-file') + extForMime(mime);
      return blobToBase64(blob).then(function (b64) { N.saveFile(b64, name, mime); });
    }).catch(function () {
      if (/^https?:/i.test(href)) N.openExternal(href);
      else alert('مقدرتش أحفظ الملف');
    });
  }

  // ---------- عارض الصور جوّه التطبيق ----------
  var viewer = null;
  function closeViewer() {
    if (viewer) { viewer.remove(); viewer = null; return true; }
    return false;
  }
  function showImage(src) {
    closeViewer();
    viewer = document.createElement('div');
    viewer.setAttribute('style',
      'position:fixed;inset:0;z-index:2147483000;background:rgba(20,18,19,.96);' +
      'display:flex;flex-direction:column;');
    var bar = document.createElement('div');
    bar.setAttribute('style', 'display:flex;gap:8px;justify-content:space-between;padding:12px;');
    function btn(label, fn) {
      var b = document.createElement('button');
      b.textContent = label;
      b.setAttribute('style', 'border:none;border-radius:20px;padding:9px 16px;font:700 14px Tajawal,sans-serif;' +
        'background:#D59663;color:#fff;');
      b.onclick = fn;
      return b;
    }
    bar.appendChild(btn('✕ قفل', closeViewer));
    bar.appendChild(btn('⬇️ حفظ / مشاركة', function () { saveHref(src, 'ibtkar-image'); }));
    var wrap = document.createElement('div');
    wrap.setAttribute('style', 'flex:1;overflow:auto;display:flex;align-items:center;justify-content:center;touch-action:pan-x pan-y pinch-zoom;');
    var img = document.createElement('img');
    img.src = src;
    img.setAttribute('style', 'max-width:100%;max-height:100%;object-fit:contain;');
    var zoomed = false;
    img.onclick = function () {
      zoomed = !zoomed;
      img.style.maxWidth = zoomed ? 'none' : '100%';
      img.style.maxHeight = zoomed ? 'none' : '100%';
    };
    wrap.appendChild(img);
    viewer.appendChild(bar);
    viewer.appendChild(wrap);
    document.body.appendChild(viewer);
  }

  function isShownImage(u) {
    if (/^data:image\//i.test(u)) return true;
    for (var i = 0; i < document.images.length; i++) {
      if (document.images[i].src === u) return true;
    }
    return /\.(png|jpe?g|webp|gif)(\?|$)/i.test(u);
  }

  function openUrl(u) {
    u = String(u || '');
    if (!u || u === 'about:blank') return;
    if (isShownImage(u)) { showImage(u); return; }
    if (/^(data|blob):/i.test(u)) {
      var mm = /^data:([^;,]+)/i.exec(u);
      saveHref(u, 'ibtkar-file' + extForMime(mm ? mm[1] : ''));
      return;
    }
    if (u.indexOf(location.origin) === 0) { location.href = u; return; }
    N.openExternal(u);
  }

  window.open = function (u) { openUrl(u); return null; };

  // ---------- تحميل الملفات ----------
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && this.href) {
      saveHref(this.href, this.getAttribute('download'));
      return;
    }
    return origClick.call(this);
  };
  var origDispatch = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type === 'click' && this.hasAttribute('download') && this.href && !this.isConnected) {
      saveHref(this.href, this.getAttribute('download'));
      return false;
    }
    return origDispatch.call(this, ev);
  };

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a || e.defaultPrevented) return;
    var href = a.href;
    if (/^javascript:/i.test(a.getAttribute('href') || '') || (a.getAttribute('href') || '').charAt(0) === '#') return;
    if (a.hasAttribute('download')) {
      e.preventDefault();
      saveHref(href, a.getAttribute('download'));
      return;
    }
    if (/^(tel|mailto|sms|whatsapp|intent|geo):/i.test(href)) {
      e.preventDefault();
      N.openExternal(href);
      return;
    }
    if (a.target === '_blank' || href.indexOf(location.origin) !== 0) {
      e.preventDefault();
      openUrl(href);
    }
  }, true);

  // ---------- المشاركة (واتساب وغيره) ----------
  function shareImpl(data) {
    data = data || {};
    var files = Array.prototype.slice.call(data.files || []);
    return Promise.all(files.map(function (f) {
      return blobToBase64(f).then(function (b64) {
        return { b64: b64, name: f.name || 'file', mime: f.type || guessMime(f.name) };
      });
    })).then(function (list) {
      N.share(JSON.stringify({ title: data.title || '', text: data.text || '', url: data.url || '', files: list }));
    });
  }
  try {
    Object.defineProperty(navigator, 'share', { value: shareImpl, configurable: true, writable: true });
    Object.defineProperty(navigator, 'canShare', { value: function () { return true; }, configurable: true, writable: true });
  } catch (err) {
    navigator.share = shareImpl;
    navigator.canShare = function () { return true; };
  }

  // ---------- زرار الرجوع ----------
  function visible(el) {
    return el && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
  }
  window.__ibtkarBack = function () {
    if (closeViewer()) return true;
    if (visible(document.getElementById('app-lock-screen'))) return false;

    var bd = document.getElementById('bd-modal-root');
    if (bd && bd.children.length) {
      if (typeof window.bdCloseModal === 'function') window.bdCloseModal(); else bd.innerHTML = '';
      return true;
    }

    // آخر نافذة اتفتحت هي آخر عنصر في الصفحة (الكود الأصلي بيحركها لآخر الـ body)
    var shown = document.querySelectorAll('.overlay.show');
    if (shown.length) {
      var top = shown[shown.length - 1];
      var close = top.querySelector('.modal-close');
      if (close) close.click();
      if (top.classList.contains('show')) top.classList.remove('show');
      return true;
    }

    try {
      /* global currentView, switchView */
      if (typeof currentView !== 'undefined' && currentView !== 'home' && typeof switchView === 'function') {
        switchView('home');
        window.scrollTo(0, 0);
        return true;
      }
    } catch (err) { /* ignore */ }
    return false;
  };
})();
