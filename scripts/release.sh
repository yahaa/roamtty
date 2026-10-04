#!/usr/bin/env bash
# Builds the signed release APK (GitHub downloads) and AAB (Google Play upload) into dist/.
# The release key is the Play upload key; it is never generated implicitly here. To create the
# first one, run with ITERMUX_CREATE_RELEASE_KEY=1 and back up .private/signing and signing.properties.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

keystore=.private/signing/itermux-release.p12
properties=signing.properties
if [[ ! -f $keystore ]]; then
    [[ ${ITERMUX_CREATE_RELEASE_KEY:-} == 1 ]] || { echo "Missing $keystore: restore the original release key (or set ITERMUX_CREATE_RELEASE_KEY=1 for the very first release)." >&2; exit 1; }
    [[ ! -f $properties ]] || { echo "$properties exists but the key is missing; restore the original key instead of generating another." >&2; exit 1; }
    mkdir -p "$(dirname "$keystore")"
    ITERMUX_SIGNING_PASSWORD=$(head -c 32 /dev/urandom | base64) && export ITERMUX_SIGNING_PASSWORD
    keytool -genkeypair -noprompt -keystore "$keystore" -storetype PKCS12 \
        -storepass:env ITERMUX_SIGNING_PASSWORD -keypass:env ITERMUX_SIGNING_PASSWORD \
        -alias itermux -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Roamtty, O=Roamtty Open Source, C=CN'
    (umask 077; printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=itermux\nkeyPassword=%s\n' \
        "$keystore" "$ITERMUX_SIGNING_PASSWORD" "$ITERMUX_SIGNING_PASSWORD" > "$properties")
    unset ITERMUX_SIGNING_PASSWORD
fi
[[ -f $properties ]] || { echo "Restore $properties matching the saved release key." >&2; exit 1; }

version=$(sed -n 's/^itermuxVersionName=//p' gradle.properties)
./gradlew --console=plain :app:assembleRelease :app:bundleRelease

sdk=${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties)}
mkdir -p dist
apk=dist/Roamtty-$version.apk
aab=dist/Roamtty-$version.aab
cp app/build/outputs/apk/release/app-release.apk "$apk"
cp app/build/outputs/bundle/release/app-release.aab "$aab"
"$sdk/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk" | tee dist/signature.txt
jarsigner -verify "$aab" | grep -q "^jar verified" || { echo "AAB signature verification failed" >&2; exit 1; }
(cd dist && sha256sum "Roamtty-$version.apk" "Roamtty-$version.aab" > SHA256SUMS.txt)
echo "Signed $apk and $aab. Upload the AAB to Play Console; keep .private/signing and $properties backed up separately."
