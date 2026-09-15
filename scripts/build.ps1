param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks = @('assembleRelease','test','lint'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
Push-Location $projectRoot
try {
    if (Test-Path "$projectRoot/.tools/gradle-8.11.1/bin/gradle.bat") { & "$projectRoot/.tools/gradle-8.11.1/bin/gradle.bat" @Tasks --console=plain }
    else { & "$projectRoot/gradlew.bat" @Tasks --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
