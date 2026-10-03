param([string]$Serial = 'emulator-5554', [string]$TestClass = 'io.itermux.app.DeviceCryptoSshTest,io.itermux.app.TerminalUiTest')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
& $adb -s $Serial reverse tcp:22228 tcp:22228
& $adb -s $Serial install -r "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'App installation failed' }
& $adb -s $Serial install -r "$projectRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
$options = @('-s', $Serial, 'shell', 'am', 'instrument', '-w')
if ($TestClass) { $options += @('-e', 'class', $TestClass) }
$options += 'io.itermux.app.test/io.itermux.app.ItermuxTestRunner'
& $adb @options | Tee-Object -FilePath "$projectRoot/.local/device-test-$Serial.txt"
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation failed' }
if (!(Select-String -LiteralPath "$projectRoot/.local/device-test-$Serial.txt" -Pattern 'OK \([0-9]+ tests?\)' -Quiet)) { throw 'Instrumentation reported test failures' }
