#!/usr/bin/env bash
# بيحط Three.js (مكتبة الـ ٣D، رخصة MIT) جوّه ملفات التطبيق عشان استوديو المطبخ يشتغل من غير إنترنت.
set -euo pipefail
WWW="$1"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
( cd "$TMP" && npm pack --silent three@0.128.0 >/dev/null && tar xzf three-*.tgz )
mkdir -p "$WWW/vendor"
cp "$TMP/package/build/three.min.js" "$WWW/vendor/three.min.js"
cp "$TMP/package/LICENSE" "$WWW/vendor/three.LICENSE.txt"
echo "vendor-three: OK"
# QR على الإيصالات (qrcode-generator، رخصة MIT) وقراءته من الصور (jsQR، رخصة Apache-2.0)
( cd "$TMP" && npm pack --silent qrcode-generator@1.4.4 jsqr@1.4.0 >/dev/null && mkdir q j && tar xzf qrcode-generator-*.tgz -C q && tar xzf jsqr-*.tgz -C j )
cp "$TMP/q/package/qrcode.js" "$WWW/vendor/qrcode.js"
cp "$TMP/j/package/dist/jsQR.js" "$WWW/vendor/jsQR.js"
cp "$TMP/j/package/LICENSE" "$WWW/vendor/jsQR.LICENSE.txt"
echo "vendor-qr: OK"
