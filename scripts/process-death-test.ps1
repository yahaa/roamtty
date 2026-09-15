param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Process-death test is restricted to disposable emulators.' }
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$ready = '/sdcard/Android/data/io.itermux.app/files/lifecycle-ready.txt'
$runner = 'io.itermux.app.test/androidx.test.runner.AndroidJUnitRunner'
& $adb -s $Serial reverse tcp:22228 tcp:22228
& $adb -s $Serial shell am force-stop io.itermux.app
& $adb -s $Serial shell rm -f $ready
$log = "$projectRoot/.local/process-prepare-$Serial.txt"
$process = Start-Process -FilePath $adb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','class','io.itermux.app.ProcessDeathTest#prepare','-e','lifecycleStage','prepare',$runner) -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
$readySeen = $false
for ($attempt = 0; $attempt -lt 35; $attempt++) {
    $probe = & $adb -s $Serial shell "if test -f $ready; then cat $ready; fi"
    if ($LASTEXITCODE -eq 0 -and $probe -match '^\d+$') { $readySeen = $true; break }
    if ($process.HasExited) { break }
    Start-Sleep -Seconds 1
}
if (!$readySeen) { throw 'Live ordinary SSH + tmux setup did not become ready; inspect the preparation log.' }
& $adb -s $Serial shell am force-stop io.itermux.app
$process.WaitForExit(10000) | Out-Null
$report = "$projectRoot/.local/process-death-$Serial.txt"
'Killed the live application process after ordinary SSH and tmux connected; the preparation runner is expected to terminate.' | Set-Content -LiteralPath $report -Encoding UTF8
$result = & $adb -s $Serial shell am instrument -w -e class 'io.itermux.app.ProcessDeathTest#recover' -e lifecycleStage recover $runner
$resultCode = $LASTEXITCODE
$result | Add-Content -LiteralPath $report -Encoding UTF8
$result
if ($resultCode -ne 0 -or !(($result -join "`n") -match 'OK \(1 test\)')) { throw 'Process-death recovery test failed' }
$summary = 'PASS: no stale sessions after process death, persisted Room/Keystore data, explicit tmux reattachment.'
$summary | Add-Content -LiteralPath $report -Encoding UTF8
$summary
