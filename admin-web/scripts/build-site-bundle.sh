#!/usr/bin/env bash
# Rebuild the committed /admin SPA bundle at site/admin/.
#
# site/admin/ is deployed by the nexachat-site Vercel project as plain static
# files (the site build container cannot reach ../admin-web, and an external
# proxy is undesirable), so the bundle is regenerated and committed whenever
# admin-web sources change. CI verifies it is in sync.
#
# MSYS_NO_PATHCONV / MSYS2_ARG_CONV_EXCL stop Git Bash on Windows from turning
# --base=/admin/ into "C:/Program Files/Git/admin/" — without them the emitted
# index.html references asset URLs that 404 in production.
set -euo pipefail
cd "$(dirname "$0")/.."

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
export VITE_API_URL="${VITE_API_URL:-https://api.nexachat.io.vn}"

rm -rf dist-deploy
npx vite build --base=/admin/ --outDir dist-deploy

SITE_ADMIN="$(cd .. && pwd)/site/admin"
rm -rf "$SITE_ADMIN"
mkdir -p "$SITE_ADMIN"
cp -a dist-deploy/. "$SITE_ADMIN/"
rm -rf dist-deploy

echo "site/admin/ rebuilt (VITE_API_URL=$VITE_API_URL)"
