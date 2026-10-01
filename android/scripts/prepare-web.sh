#!/usr/bin/env bash
# بيجهّز ملفات الويب جوّه الـ APK من index.html الأصلي اللي في أول الريبو:
#  - بينسخ index.html والأيقونات
#  - بينزّل المكتبات (Firebase, jsPDF, html2canvas, SheetJS, خط Tajawal) من npm
#    ويحطها جوّه التطبيق عشان يشتغل من غير ما يحتاج CDN
#  - بيحقن native-bridge.js أول سكريبت في الصفحة
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$ANDROID_DIR/.." && pwd)"
WWW="$ANDROID_DIR/app/src/main/assets/www"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

rm -rf "$WWW"
mkdir -p "$WWW/vendor/fonts/files"
cp "$ROOT/index.html" "$ROOT/manifest.json" "$ROOT"/icon-*.png "$WWW/"
cp "$ANDROID_DIR/web/native-bridge.js" "$WWW/"

(
  cd "$TMP"
  npm pack --silent html2canvas@1.4.1 jspdf@2.5.1 xlsx@0.18.5 firebase@10.13.0 @fontsource/tajawal@5 >/dev/null
  for f in *.tgz; do mkdir -p "${f%.tgz}"; tar xzf "$f" -C "${f%.tgz}"; done
)

cp "$TMP"/html2canvas-*/package/dist/html2canvas.min.js "$WWW/vendor/"
cp "$TMP"/jspdf-*/package/dist/jspdf.umd.min.js "$WWW/vendor/"
cp "$TMP"/xlsx-*/package/dist/xlsx.full.min.js "$WWW/vendor/"
for m in app database storage; do
  cp "$TMP"/firebase-*/package/firebase-$m-compat.js "$WWW/vendor/"
done
FONT_PKG="$(ls -d "$TMP"/fontsource-tajawal-*/package)"
: > "$WWW/vendor/fonts/tajawal.css"
for w in 400 500 700 900; do
  cat "$FONT_PKG/$w.css" >> "$WWW/vendor/fonts/tajawal.css"
  cp "$FONT_PKG"/files/tajawal-*-$w-normal.woff2 "$WWW/vendor/fonts/files/"
done
# الـ woff الاحتياطي مش محتاجينه — WebView بيدعم woff2
sed -i -E "s#, url\(\./files/[^)]*\.woff\) format\('woff'\)##g" "$WWW/vendor/fonts/tajawal.css"

node - "$WWW/index.html" <<'JS'
const fs = require('fs');
const file = process.argv[2];
let html = fs.readFileSync(file, 'utf8');
const swaps = [
  ['https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js', 'vendor/html2canvas.min.js'],
  ['https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js', 'vendor/jspdf.umd.min.js'],
  ['https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.full.min.js', 'vendor/xlsx.full.min.js'],
  ['https://www.gstatic.com/firebasejs/10.13.0/firebase-app-compat.js', 'vendor/firebase-app-compat.js'],
  ['https://www.gstatic.com/firebasejs/10.13.0/firebase-database-compat.js', 'vendor/firebase-database-compat.js'],
  ['https://www.gstatic.com/firebasejs/10.13.0/firebase-storage-compat.js', 'vendor/firebase-storage-compat.js'],
];
for (const [from, to] of swaps) {
  if (!html.includes(from)) { console.error('prepare-web: missing expected script ' + from); process.exit(1); }
  html = html.split(from).join(to);
}
const fontImport = /@import url\('https:\/\/fonts\.googleapis\.com\/css2\?family=Tajawal[^']*'\);/;
if (fontImport.test(html)) {
  html = html.replace(fontImport, '');
  html = html.replace('<style>', '<link rel="stylesheet" href="vendor/fonts/tajawal.css">\n<style>');
}
html = html.replace('<head>', '<head>\n<script src="native-bridge.js"></script>');
fs.writeFileSync(file, html);
console.log('prepare-web: OK');
JS

du -sh "$WWW"
