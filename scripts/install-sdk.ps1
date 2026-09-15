$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
$manager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
(1..100 | ForEach-Object { 'y' }) | & $manager --sdk_root=$env:ANDROID_HOME --licenses
if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed' }
& $manager --sdk_root=$env:ANDROID_HOME 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'emulator' 'system-images;android-26;google_apis;x86_64' 'system-images;android-36;google_apis;x86_64'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), 'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/') + "`n")
