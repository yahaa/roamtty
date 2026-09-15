$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
& "$projectRoot/.tools/current-cmdtools/cmdline-tools/bin/sdkmanager.bat" --sdk_root=$env:ANDROID_HOME 'system-images;android-37.1;google_apis_ps16k;x86_64'
if ($LASTEXITCODE -ne 0) { throw 'Current emulator image installation failed' }
