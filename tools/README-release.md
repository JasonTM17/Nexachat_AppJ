# Release APK Signing — NexaChat

Everything under `release/` is local-machine release output and is gitignored;
the keystore and `signing.properties` must **never** be committed to the repository.

## 1. One-time: local upload keystore

To generate a local upload keystore (`release/keystore/nexachat-upload.jks`,
alias `nexachat`, RSA-2048, 10000-day validity):

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
consumed by `tools/sign-release.sh` and `tools/sign-release.ps1`. For a
production Play Store key, use strong unique passwords stored in your secrets manager.

## 2. Build + sign the release APK

```bash
tools/sign-release.sh            # → release/nexachat-v1.0.0.apk (+ -unsigned.apk)
tools/sign-release.sh v1.0.1     # custom version tag
```

Or on Windows PowerShell:

```powershell
.\tools\sign-release.ps1
```

The script runs `./gradlew assembleRelease` on JDK 21 LTS, runs `zipalign`,
signs the APK with the newest `apksigner` under `$ANDROID_HOME/build-tools/`,
and prints `apksigner verify --print-certs`.

## 3. Verify the signed APK

```bash
java -jar "$ANDROID_HOME/build-tools/<newest>/lib/apksigner.jar" \
    verify --print-certs release/nexachat-v1.0.0.apk
```
