# Release packaging — NexaChat

Everything here is local-machine packaging. `release/` is gitignored; the
keystore and `signing.properties` **never** leave this machine and are never
inside the submission zip.

## 1. One-time: local upload keystore

Already generated for this dev machine — `release/keystore/nexachat-upload.jks`
(alias `nexachat`, RSA-2048, 10000-day validity, documented DEV passwords).
To regenerate:

```bash
mkdir -p release/keystore
"$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore release/keystore/nexachat-upload.jks \
    -alias nexachat -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass nexachat-demo-store -keypass nexachat-demo-store \
    -dname "CN=NexaChat Dev, OU=Mobile, O=NexaChat, L=Hanoi, ST=Hanoi, C=VN" \
    -storetype JKS
```

Credentials live in `release/keystore/signing.properties`
(`storeFile` / `keyAlias` / `keystore.storePassword` / `keyPassword`) —
consumed by `tools/sign-release.sh`. **Dev-only password**: for a real
Play-Store key, use strong unique passwords and keep them off the repo.

## 2. Build + sign the release APK

```bash
tools/sign-release.sh            # → release/nexachat-v1.0.0.apk (+ -unsigned.apk)
tools/sign-release.sh v1.0.1     # custom tag
```

The script runs `./gradlew assembleRelease`, zipaligns, then signs with the
newest `apksigner` under `$ANDROID_HOME/build-tools/` (falls back to
`D:\Android\Sdk`) and prints `verify --print-certs`.

### Optional: sign inside Gradle instead

`tools/sign-release.sh` is the recommended path (no credentials in Gradle
files). If you prefer `assembleRelease` to emit a signed APK directly, add
this block to `android/app/build.gradle` — **manual step, not committed by the
packaging tooling**:

```groovy
// top of file, next to the localProps block
def signingProps = new Properties()
def signingPropsFile = rootProject.file("../release/keystore/signing.properties")
if (signingPropsFile.exists()) {
    signingPropsFile.withInputStream { signingProps.load(it) }
}

android {
    signingConfigs {
        release {
            if (signingProps.getProperty("storeFile")) {
                storeFile rootProject.file("../" + signingProps.getProperty("storeFile"))
                keyAlias signingProps.getProperty("keyAlias")
                storePassword signingProps.getProperty("keystore.storePassword")
                keyPassword signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // only sign when the local keystore exists — CI/grader machines
            // without release/keystore/ still build an unsigned APK
            if (signingProps.getProperty("storeFile")) {
                signingConfig signingConfigs.release
            }
            // …existing minifyEnabled/proguard lines unchanged…
        }
    }
}
```

> `signing.properties` lives under gitignored `release/`, so the committed
> `build.gradle` never contains credentials.

## 3. Build the submission zip

```bash
tools/package-submission.sh      # → release/nexachat-submission-YYYYMMDD.zip
```

The zip is a **fresh `git clone` of HEAD** (tracked files + full `.git/`
history — build dirs, `.env`, `local.properties`, `demo/backups/`,
`server/uploads/` and `release/` itself are untracked and therefore absent by
construction) plus `apk/` containing the signed + unsigned APKs. The clone's
`remote.origin.url` is removed so no local path leaks into `.git/config`.
`.env.example` / `local.properties.example` ship deliberately — tracked
templates, placeholder values only.

Zip backend order: `zip` → bundled `bsdtar`
(`C:\Windows\System32\tar.exe -a`) → python `zipfile`. If all are missing:

```powershell
powershell -NoProfile -Command "Compress-Archive -Force -Path '<stage>\*' -DestinationPath '<out.zip>'"
```

The script then verifies the archive: `.git/` present, `apk/*.apk` present,
zero forbidden entries (`build/`, `*.jks`, `keystore.properties`,
`local.properties`, `.env` other than `.env.example`, `release/`,
`demo/backups/` …).

## 4. Before submitting (manual checklist)

- [ ] **Commit first.** The zip mirrors `HEAD` — uncommitted files
      (including `tools/` itself) are NOT inside. Commit, then re-run §3.
- [ ] `git tag -a v1.0.0 -m "NexaChat submission"` — required by checklist C1.
- [ ] Rename the zip to `GroupXX_MSSV1_MSSV2_….zip` (course convention).
- [ ] `docs/report.docx` must exist (checklist C1) — the packager warns if absent.
- [x] `versionName` in `android/app/build.gradle` is `1.0.0`, matching the
      `v1.0.0` artifact tag.
- [ ] Fresh-grader check on a clean machine/VM: unzip → `git status` clean →
      `git log` shows history → `docker compose up -d db` → `mvn -B verify` →
      `demo/seed.sql` → install `apk/nexachat-v1.0.0.apk` on 2 devices →
      LAN chat (see `docs/demo-runbook.md`).

## 5. Verify the signed APK

```bash
java -jar "$ANDROID_HOME/build-tools/<newest>/lib/apksigner.jar" \
    verify --print-certs release/nexachat-v1.0.0.apk
```

Expected signer DN: `CN=NexaChat Dev, OU=Mobile, O=NexaChat, L=Hanoi, ST=Hanoi, C=VN`
(SHA-256 `aaa96b96…` on the current dev keystore — regenerating the keystore
changes it).
