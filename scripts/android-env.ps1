$projectRoot = Split-Path -Parent $PSScriptRoot
if (Test-Path "$projectRoot/.tools/java-home.txt") { $env:JAVA_HOME = (Get-Content "$projectRoot/.tools/java-home.txt" -Raw).Trim() }
elseif (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\JetBrains\GoLand 2024.1\jbr' }
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.local/android'
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.local/avd'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.local/gradle'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME,$env:GRADLE_USER_HOME | Out-Null
