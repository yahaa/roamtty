$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
$signingDir = Join-Path $projectRoot '.private/signing'
New-Item -ItemType Directory -Force -Path $signingDir,(Join-Path $projectRoot 'dist') | Out-Null
$keyStore = Join-Path $signingDir 'itermux-release.p12'
$properties = Join-Path $projectRoot 'signing.properties'
if (!(Test-Path $keyStore)) {
    if (Test-Path $properties) { throw 'Signing settings exist but key is missing; restore the original key instead of generating another.' }
    $random = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
    $env:ITERMUX_SIGNING_PASSWORD = [Convert]::ToBase64String($random)
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -noprompt -keystore $keyStore -storetype PKCS12 -storepass:env ITERMUX_SIGNING_PASSWORD -keypass:env ITERMUX_SIGNING_PASSWORD -alias itermux -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Roamtty, O=Roamtty Open Source, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed' }
    [IO.File]::WriteAllText($properties, "storeFile=.private/signing/itermux-release.p12`nstorePassword=$env:ITERMUX_SIGNING_PASSWORD`nkeyAlias=itermux`nkeyPassword=$env:ITERMUX_SIGNING_PASSWORD`n")
    $env:ITERMUX_SIGNING_PASSWORD = $null
    $random.Clear()
}
if (!(Test-Path $properties)) { throw 'Restore signing.properties matching the saved release key.' }
$version = (Select-String -Path "$projectRoot/gradle.properties" -Pattern '^itermuxVersionName=(.+)$').Matches[0].Groups[1].Value.Trim()
& "$PSScriptRoot/build.ps1" :app:assembleRelease :app:bundleRelease
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
$apk = "$projectRoot/dist/Roamtty-$version.apk"
$aab = "$projectRoot/dist/Roamtty-$version.aab"
Copy-Item -LiteralPath "$projectRoot/app/build/outputs/apk/release/app-release.apk" -Destination $apk -Force
Copy-Item -LiteralPath "$projectRoot/app/build/outputs/bundle/release/app-release.aab" -Destination $aab -Force
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs $apk | Tee-Object -FilePath "$projectRoot/dist/signature.txt"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
if (-not (& "$env:JAVA_HOME/bin/jarsigner.exe" -verify $aab | Select-String -Quiet '^jar verified')) { throw 'AAB signature verification failed' }
$sums = foreach ($file in $apk, $aab) { "$((Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant())  $(Split-Path -Leaf $file)" }
[IO.File]::WriteAllText("$projectRoot/dist/SHA256SUMS.txt", ($sums -join "`n") + "`n")
Write-Output 'Signed release APK and AAB created in dist/. Upload the AAB to Play Console. Keep .private/signing and signing.properties backed up separately.'
