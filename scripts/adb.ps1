param([Parameter(ValueFromRemainingArguments=$true)][string[]]$AdbArgs)
. "$PSScriptRoot/android-env.ps1"
& "$env:ANDROID_HOME/platform-tools/adb.exe" @AdbArgs
exit $LASTEXITCODE
