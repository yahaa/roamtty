param([string]$Api = '26', [int]$Port = 5554)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
$name = "itermux-api$Api"
if (!(Test-Path "$env:ANDROID_AVD_HOME/$name.avd")) {
    $variant = if ($Api.StartsWith('37')) { 'google_apis_ps16k' } else { 'google_apis' }
    $manager = if ($Api.StartsWith('37')) { "$projectRoot/.tools/current-cmdtools/cmdline-tools/bin/avdmanager.bat" } else { "$env:ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat" }
    'no' | & $manager create avd --name $name --package "system-images;android-$Api;$variant;x86_64" --device pixel_2 --path "$env:ANDROID_AVD_HOME/$name.avd"
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed' }
    Add-Content "$env:ANDROID_AVD_HOME/$name.avd/config.ini" "`nhw.keyboard=yes`nshowDeviceFrame=no`n"
}
[IO.File]::WriteAllText("$env:ANDROID_AVD_HOME/$name.ini", "avd.ini.encoding=UTF-8`npath=$env:ANDROID_AVD_HOME/$name.avd`ntarget=android-$Api`n")
$variant = if ($Api.StartsWith('37')) { 'google_apis_ps16k' } else { 'google_apis' }
$avdConfig = Get-Content "$env:ANDROID_AVD_HOME/$name.avd/config.ini" -Raw
$avdConfig = [regex]::Replace($avdConfig, '(?m)^image\.sysdir\.1=.*$', "image.sysdir.1=$env:ANDROID_HOME/system-images/android-$Api/$variant/x86_64/")
[IO.File]::WriteAllText("$env:ANDROID_AVD_HOME/$name.avd/config.ini", $avdConfig)
$process = Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList @('-avd', $name, '-port', $Port, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader', '-memory', '2048') -PassThru -WindowStyle Hidden -RedirectStandardOutput "$projectRoot/.local/emulator-$Api.log" -RedirectStandardError "$projectRoot/.local/emulator-$Api.err"
Write-Output "Started $name as PID $($process.Id), emulator-$Port"
