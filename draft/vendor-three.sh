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
