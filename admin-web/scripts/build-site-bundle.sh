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

# npm has a silent optional-deps flake: it occasionally skips platform
# bindings (@rollup/rollup-<os>-<arch>-*, @esbuild/<os>-<arch>), after which
# vite/rollup fall back to wasm and emit DIFFERENT bytes and asset hashes.
# That makes the committed bundle unverifiable, so refuse to build without
# the native binding — re-running `npm ci` restores it.
node - <<'EOF'
const fs = require("fs");
const { platform, arch } = process;
const have = (scope) =>
  fs.existsSync(`node_modules/${scope}`)
    ? fs.readdirSync(`node_modules/${scope}`).map((n) => `${scope}/${n}`)
    : [];
const pkgs = [...have("@rollup"), ...have("@esbuild")];
const missing = [];
if (!pkgs.some((n) => n.startsWith(`@rollup/rollup-${platform}-${arch}`)))
  missing.push(`@rollup/rollup-${platform}-${arch}-*`);
if (!pkgs.includes(`@esbuild/${platform}-${arch}`))
  missing.push(`@esbuild/${platform}-${arch}`);
if (missing.length) {
  console.error(
    `Missing native binding(s): ${missing.join(", ")} — npm optional-deps flake; re-run 'npm ci' and retry`
  );
  process.exit(1);
}
EOF

rm -rf dist-deploy
npx vite build --base=/admin/ --outDir dist-deploy

SITE_ADMIN="$(cd .. && pwd)/site/admin"
rm -rf "$SITE_ADMIN"
mkdir -p "$SITE_ADMIN"
cp -a dist-deploy/. "$SITE_ADMIN/"
rm -rf dist-deploy

echo "site/admin/ rebuilt (VITE_API_URL=$VITE_API_URL)"
