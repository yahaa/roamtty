param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
# This destructive clean-install test is restricted to disposable emulators.
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Clean-install smoke tests are emulator-only. Never erase a phone here.' }
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$version = (Select-String -Path "$projectRoot/gradle.properties" -Pattern '^itermuxVersionName=(.+)$').Matches[0].Groups[1].Value.Trim()
$release = "$projectRoot/dist/Roamtty-$version.apk"
$upgrade = "$projectRoot/.local/upgrade-test.apk"
if (!(Test-Path $release) -or !(Test-Path $upgrade)) { throw 'Build the release and an upgrade-test APK with a higher versionCode (-PitermuxVersionCode) first.' }
$report = "$projectRoot/.local/release-smoke-$Serial.txt"
function AdbCheck([string[]]$Arguments) {
    $output = & $adb -s $Serial @Arguments 2>&1
    $output | Add-Content -LiteralPath $report -Encoding UTF8
    if ($LASTEXITCODE -ne 0 -or ($output -match 'Failure \[|Error:')) { throw "ADB step failed: $Arguments" }
    $output
}
function Launch([int]$Code) {
    AdbCheck @('shell','am','start','-W','-n','com.roamtty.app/io.itermux.app.MainActivity') | Out-Null
    Start-Sleep -Seconds 2
    $pidResult = AdbCheck @('shell','pidof','com.roamtty.app')
    if (!$pidResult) { throw 'Application process did not survive launch' }
    $package = AdbCheck @('shell','dumpsys','package','com.roamtty.app')
    if (!($package -match "versionCode=$Code ")) { throw "Expected versionCode $Code" }
}
"Release clean install and upgrade test: $Serial" | Set-Content -LiteralPath $report -Encoding UTF8
# Only our disposable test applications are removed.
& $adb -s $Serial uninstall com.roamtty.app.test | Out-Null
& $adb -s $Serial uninstall com.roamtty.app | Out-Null
AdbCheck @('install',$release) | Out-Null
Launch 1
AdbCheck @('install','-r',$upgrade) | Out-Null
Launch 2
AdbCheck @('shell','am','force-stop','com.roamtty.app') | Out-Null
Launch 2
# Leave the actual delivered build on the emulator, not the upgrade fixture.
AdbCheck @('uninstall','com.roamtty.app') | Out-Null
AdbCheck @('install',$release) | Out-Null
Launch 1
$summary = 'PASS: clean install v1, same-certificate upgrade v1 -> v2, force-stop/relaunch, delivered v1 restored.'
$summary | Add-Content -LiteralPath $report -Encoding UTF8
$summary
