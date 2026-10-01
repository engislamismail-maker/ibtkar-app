/* ============================================================
   استرجاع نسخة الويب جوّه تطبيق الأندرويد — مرة واحدة بس.
   النسخة الاحتياطية متشفّرة (web-backup.enc.json) لأن الريبو عام، وبتتفك بكود من ١٢ رقم.
   البيانات بتتضاف على اللي موجود (دمج حسب الـ id) — مفيش أي حاجة موجودة بتتمسح أو تتغيّر.
   بعد الاسترجاع بيتسجّل state.webBackupImported فالموبايل التاني مش هيسأل تاني.
   ============================================================ */
(function () {
  'use strict';
  var FLAG = 'webBackupImported';
  var SNOOZE_KEY = 'ibtkar-web-backup-snooze';
  var shown = false;

  function isPlainObj(v) { return v && typeof v === 'object' && !Array.isArray(v); }

  function mergeBackup(cur, bak) {
    var added = 0;
    Object.keys(bak).forEach(function (k) {
      var b = bak[k], c = cur[k];
      if (k === 'counters' && isPlainObj(b)) {
        cur.counters = cur.counters || {};
        Object.keys(b).forEach(function (ck) {
          cur.counters[ck] = Math.max(Number(cur.counters[ck]) || 0, Number(b[ck]) || 0);
        });
        return;
      }
      if (c === undefined || c === null || (Array.isArray(c) && c.length === 0)) {
        cur[k] = b;
        if (Array.isArray(b)) added += b.length;
        return;
      }
      if (Array.isArray(c) && Array.isArray(b)) {
        if (b.every(function (x) { return isPlainObj(x) && x.id; })) {
          var ids = {};
          c.forEach(function (x) { if (x && x.id) ids[x.id] = true; });
          b.forEach(function (x) { if (!ids[x.id]) { c.push(x); added++; } });
        } else if (b.every(function (x) { return typeof x !== 'object'; })) {
          b.forEach(function (x) { if (c.indexOf(x) === -1) c.push(x); });
        }
        return;
      }
      if (isPlainObj(c) && isPlainObj(b)) {
        Object.keys(b).forEach(function (kk) { if (c[kk] === undefined) c[kk] = b[kk]; });
      }
    });
    return added;
  }

  function b64(s) { return Uint8Array.from(atob(s), function (ch) { return ch.charCodeAt(0); }); }

  function decrypt(pkg, code) {
    var enc = new TextEncoder();
    return crypto.subtle.importKey('raw', enc.encode(code), 'PBKDF2', false, ['deriveKey'])
      .then(function (base) {
        return crypto.subtle.deriveKey(
          { name: 'PBKDF2', salt: b64(pkg.salt), iterations: pkg.iterations, hash: 'SHA-256' },
          base, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
      })
      .then(function (key) { return crypto.subtle.decrypt({ name: 'AES-GCM', iv: b64(pkg.iv) }, key, b64(pkg.data)); })
      .then(function (buf) { return JSON.parse(new TextDecoder().decode(buf)); });
  }

  function showPrompt(pkg) {
    shown = true;
    var ov = document.createElement('div');
    ov.className = 'overlay show';
    ov.id = 'overlay-web-backup-import';
    ov.innerHTML =
      '<div class="modal">' +
      '<h2>📥 ترجيع بيانات نسخة الويب</h2>' +
      '<p style="color:var(--muted);font-size:14px;line-height:1.7;margin:6px 0 14px;">' +
      'فيه نسخة احتياطية جاهزة جوّه التطبيق (' + pkg.label + '). اكتب الكود اللي عندك (١٢ رقم) ' +
      'والبيانات هتتضاف على اللي موجود من غير ما يتمسح أي حاجة، وهتوصل للموبايل التاني لوحدها.</p>' +
      '<div class="field"><input id="wbi-code" type="tel" inputmode="numeric" autocomplete="off" ' +
      'placeholder="الكود — ١٢ رقم" style="text-align:center;letter-spacing:3px;font-size:20px;direction:ltr;"></div>' +
      '<div id="wbi-msg" style="min-height:22px;font-size:13.5px;font-weight:700;color:var(--rose);margin:4px 0 8px;"></div>' +
      '<button type="button" class="btn btn-gold btn-full" id="wbi-go">✅ رجّع البيانات</button>' +
      '<div style="display:flex;gap:8px;margin-top:10px;">' +
      '<button type="button" class="btn btn-ghost" style="flex:1;" id="wbi-later">مش دلوقتي</button>' +
      '<button type="button" class="btn btn-ghost" style="flex:1;" id="wbi-never">متسألنيش تاني</button>' +
      '</div></div>';
    document.body.appendChild(ov);

    var input = ov.querySelector('#wbi-code');
    var msg = ov.querySelector('#wbi-msg');
    var go = ov.querySelector('#wbi-go');
    function close() { ov.remove(); }

    ov.querySelector('#wbi-later').onclick = function () {
      try { sessionStorage.setItem(SNOOZE_KEY, '1'); } catch (e) { /* ignore */ }
      close();
    };
    ov.querySelector('#wbi-never').onclick = function () {
      state[FLAG] = 'skipped';
      saveState();
      close();
    };
    go.onclick = function () {
      var code = (input.value || '').replace(/[^0-9٠-٩]/g, '')
        .replace(/[٠-٩]/g, function (d) { return String('٠١٢٣٤٥٦٧٨٩'.indexOf(d)); });
      if (code.length !== 12) { msg.textContent = 'الكود لازم يبقى ١٢ رقم'; return; }
      go.disabled = true;
      msg.style.color = 'var(--muted)';
      msg.textContent = 'جاري فك النسخة...';
      decrypt(pkg, code).then(function (bak) {
        if (!isPlainObj(bak)) throw new Error('bad');
        var added = mergeBackup(state, bak);
        state[FLAG] = new Date().toISOString();
        renderEverything();
        saveState(); // بيحفظ على الموبايل فورًا، والرفع لـ Firebase بيكمل في الخلفية
        close();
        showToast('✅ اترجّعت البيانات — اتضاف ' + added + ' عنصر');
      }).catch(function () {
        go.disabled = false;
        msg.style.color = 'var(--rose)';
        msg.textContent = 'الكود غلط، راجعه وجرب تاني';
      });
    };
  }

  function check() {
    if (shown) return;
    try {
      /* global state, fbReady, saveState, renderEverything, showToast */
      if (typeof fbReady === 'undefined' || !fbReady) return; // استنى أول مزامنة مع Firebase
      var lock = document.getElementById('app-lock-screen');
      if (lock && getComputedStyle(lock).display !== 'none') return;
      if (state[FLAG]) { shown = true; return; }
      try { if (sessionStorage.getItem(SNOOZE_KEY)) return; } catch (e) { /* ignore */ }
    } catch (e) { return; }
    shown = true;
    fetch('web-backup.enc.json').then(function (r) { return r.ok ? r.json() : null; })
      .then(function (pkg) { if (pkg && !state[FLAG]) showPrompt(pkg); })
      .catch(function () { /* no bundled backup */ });
  }

  setInterval(check, 1500);
})();
