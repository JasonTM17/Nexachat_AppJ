#!/usr/bin/env bash
# tools/package-submission.sh — build the course-submission zip.
#
#   tools/package-submission.sh [version-tag]     (default v1.0.0)
#
# Produces: release/nexachat-submission-YYYYMMDD.zip
#
# Zip layout (repo contents at archive root — grader unzips and is IN the repo):
#   .git/            full history (contribution evidence, checklist C1)
#   README.md, docs/, demo/, android/, server/, .github/, docker-compose.yml …
#                    — everything `git` tracks, NOTHING else
#   apk/nexachat-<tag>.apk            release-signed APK
#   apk/nexachat-<tag>-unsigned.apk   pre-signing build for comparison
#
# How the exclusion checklist is enforced — a fresh `git clone` carries ONLY
# tracked files, so all gitignored junk is absent by construction:
#   build/ **/build/ .gradle/ node_modules/ .env local.properties
#   *.jks *.keystore keystore.properties signing.properties server/uploads/
#   data/ logs/ demo/backups/ release/ itself (keystore, this zip — never ship)
# `.env.example` and `android/local.properties.example` ARE tracked on purpose
# (see .gitignore `!.env.example`); they ship as templates, content-checked
# below to contain no real secrets.
#
# Zip backends tried in order: zip(1) → bsdtar (C:\Windows\System32\tar.exe
# supports -a zip) → python3 zipfile → instruct PowerShell:
#   powershell -NoProfile -Command "Compress-Archive -Force -Path '<stage>\*' `
#       -DestinationPath '<out.zip>'"
#   (Compress-Archive is the last resort: it can silently skip very large
#    archives and has historically had issues with files >2GB.)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION_TAG="${1:-v1.0.0}"
DATE="$(date +%Y%m%d)"
OUT="release/nexachat-submission-${DATE}.zip"
SIGNED="release/nexachat-${VERSION_TAG}.apk"
UNSIGNED="release/nexachat-${VERSION_TAG}-unsigned.apk"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

to_unix() {
    local p="$1"
    if [[ "$p" =~ ^[A-Za-z]:[\\/] ]]; then
        command -v cygpath >/dev/null 2>&1 && { cygpath -u "$p"; return; }
        local drive="${p:0:1}"; echo "/${drive,,}${p:2}" | tr '\\' '/'
    else echo "$p"; fi
}
to_win() { command -v cygpath >/dev/null 2>&1 && cygpath -w "$1" || echo "$1"; }

[[ -d .git ]] || fail "run from a git checkout — .git missing."
[[ -f "$SIGNED" && -f "$UNSIGNED" ]] || {
    say "APKs missing — running tools/sign-release.sh $VERSION_TAG first"
    bash tools/sign-release.sh "$VERSION_TAG"
}
[[ -f "$SIGNED" ]] || fail "signed APK still missing — cannot package."

# ---- 1. Stage a pristine tracked tree ---------------------------------------
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
ROOT_DIR="$STAGE/repo"

say "cloning tracked tree (full .git history, zero untracked junk)…"
git clone --no-local --quiet . "$ROOT_DIR"

# The clone's .git/config would leak this machine's absolute path via
# remote.origin.url — drop the remote; history and refs are untouched.
git -C "$ROOT_DIR" remote remove origin

[[ -z "$(git -C "$ROOT_DIR" status --porcelain)" ]] || fail "staged clone is dirty — aborting."

# ---- 2. Add the APKs (outside release/, which must NOT be in the zip) --------
mkdir -p "$ROOT_DIR/apk"
cp "$SIGNED"   "$ROOT_DIR/apk/"
cp "$UNSIGNED" "$ROOT_DIR/apk/"

# ---- 3. Pre-flight content checks on the staged tree ------------------------
say "pre-flight exclusion checks…"
bad="$(find "$ROOT_DIR" \( -name build -o -name node_modules -o -name '.gradle' \
      -o -name '*.jks' -o -name '*.keystore' -o -name keystore.properties \
      -o -name signing.properties -o -name 'local.properties' \
      -o -name '.env' -o -name '.env.*' -o -name '*.log' -o -name uploads \
      -o -name backups -o -name release \) ! -name '.env.example' \
      -print | grep -v '/\.git/' || true)"
[[ -z "$bad" ]] || fail "forbidden entries staged:\n$bad"

# .env.example ships intentionally — prove it contains placeholders only.
if [[ -f "$ROOT_DIR/.env.example" ]] && grep -nEi '(BEGIN |PRIVATE KEY|AKIA|xox[bap]-|ghp_|glpat-)' "$ROOT_DIR/.env.example" >/dev/null; then
    fail ".env.example appears to contain real secrets — refusing to package."
fi

say "staged tree: $(find "$ROOT_DIR" -type f | wc -l) files, $(du -sh "$ROOT_DIR" | cut -f1)"

# ---- 4. Zip it ---------------------------------------------------------------
mkdir -p release
rm -f "$OUT"

zip_tree() {  # $@ = output zip; contents of $ROOT_DIR land at archive root
    local out="$1"
    if command -v zip >/dev/null 2>&1; then
        (cd "$ROOT_DIR" && zip -qr "$out" .)
    elif [[ -x "$(to_unix 'C:\Windows\System32\tar.exe')" ]] \
         && "$(to_unix 'C:\Windows\System32\tar.exe')" --version 2>/dev/null | grep -q bsdtar; then
        # Windows-bundled bsdtar writes real zips via -a; dotglob catches .git/.
        (cd "$ROOT_DIR" && shopt -s dotglob nullglob \
            && "$(to_unix 'C:\Windows\System32\tar.exe')" -a -c -f "$(to_win "$out")" -- *)
    elif command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1; then
        local PY; PY="$(command -v python3 || command -v python)"
        "$PY" - "$ROOT_DIR" "$(to_win "$out")" <<'PYEOF'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, dirs, files in os.walk(root):
        for d in dirs:
            p = os.path.join(base, d)
            z.write(p, os.path.relpath(p, root) + "/")
        for f in files:
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, root))
PYEOF
    else
        fail "no zip backend. Run instead:
  powershell -NoProfile -Command \"Compress-Archive -Force -Path '$(to_win "$ROOT_DIR")\\*' -DestinationPath '$(to_win "$OUT")'\""
    fi
}

say "writing $OUT …"
zip_tree "$(pwd)/$OUT"
[[ -s "$OUT" ]] || fail "zip was not created."

# ---- 5. Verify the archive ---------------------------------------------------
list_zip() {
    if command -v unzip >/dev/null 2>&1; then unzip -Z1 "$1"
    elif [[ -x "$(to_unix 'C:\Windows\System32\tar.exe')" ]]; then "$(to_unix 'C:\Windows\System32\tar.exe')" -tf "$1"
    else python3 -c "import zipfile,sys; [print(n) for n in zipfile.ZipFile(sys.argv[1]).namelist()]" "$1"
    fi
}

say "verifying archive contents…"
LIST="$(list_zip "$OUT")"
TOTAL=$(echo "$LIST" | grep -c .)

echo "$LIST" | grep -qE '(^|/)\.git/'      || fail ".git/ missing from zip"
echo "$LIST" | grep -qE '^apk/.*\.apk$'   || fail "signed APK missing from zip"
echo "$LIST" | grep -qE '^README\.md$'    || fail "README.md missing from zip"
echo "$LIST" | grep -qE '^docs/'          || fail "docs/ missing from zip"

VIOL="$(echo "$LIST" | grep -nEi '(^|/)(build|node_modules|\.gradle)(/|$)|\.jks$|\.keystore$|keystore\.properties|signing\.properties|(^|/)local\.properties$|(^|/)\.env$|(^|/)\.env\.[^e]|\.env\.ex[^a]|(^|/)release(/|$)|(^|/)demo/backups(/|$)' || true)"
[[ -z "$VIOL" ]] || fail "forbidden entries inside zip:\n$VIOL"
# .env.example is the only allowed .env* — verify nothing else slipped in.
ENVHITS="$(echo "$LIST" | grep -Ei '(^|/)\.env' || true)"
[[ -z "$ENVHITS" || "$ENVHITS" == ".env.example" ]] || fail "unexpected env files in zip: $ENVHITS"

say "PASS — submission zip verified"
echo
echo "  archive : $OUT"
echo "  size    : $(du -h "$OUT" | cut -f1)  ($(stat -c%s "$OUT") bytes)"
echo "  entries : $TOTAL"
echo
echo "  top-level entries:"
echo "$LIST" | cut -d/ -f1 | sort -u | sed 's/^/    /' | head -20
echo
echo "  submission checklist (docs/packaging-checklist.md):"
echo "    [x] .git/ history included        [x] release-signed APK in apk/"
echo "    [x] no build/ keystores .env      [x] docs/ + demo/ included"
echo "    [ ] rename to GroupXX_MSSV….zip   [ ] tag v1.0.0 (git tag -a v1.0.0)"
[[ -f docs/report.docx ]] || warn "docs/report.docx is NOT present — required by checklist C1. Add it before submitting."
