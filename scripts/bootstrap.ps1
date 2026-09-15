$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$toolDir = Join-Path $root '.tools'
New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
function Download([string]$url, [string]$out) {
    if (!(Test-Path -LiteralPath $out)) {
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $out
    }
}
if (!(Test-Path "$toolDir/java-home.txt")) {
    $lock = Join-Path $root 'toolchains.lock.json'
    if (!(Test-Path $lock)) {
        $release = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows')[0]
        @{ javaVersion = $release.version.semver; url = $release.binary.package.link; sha256 = $release.binary.package.checksum } | ConvertTo-Json | Set-Content $lock -Encoding UTF8
    }
    $jdk = Get-Content $lock -Raw | ConvertFrom-Json
    Download $jdk.url "$toolDir/jdk.zip"
    if ((Get-FileHash "$toolDir/jdk.zip" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jdk.sha256) { throw 'JDK checksum mismatch' }
    Expand-Archive -LiteralPath "$toolDir/jdk.zip" -DestinationPath "$toolDir/jdk" -Force
    (Get-ChildItem "$toolDir/jdk" -Directory | Select-Object -First 1).FullName | Set-Content "$toolDir/java-home.txt"
}
$commit = '4584488513c099f2e98bcfcd00f006d213248729'
Download "https://codeload.github.com/termux/termux-app/zip/$commit" "$toolDir/termux.zip"
if (!(Test-Path "$toolDir/termux-app-$commit")) { Expand-Archive -LiteralPath "$toolDir/termux.zip" -DestinationPath $toolDir }
Download 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' "$toolDir/cmdtools.zip"
if (!(Test-Path "$toolDir/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat")) {
    Expand-Archive -LiteralPath "$toolDir/cmdtools.zip" -DestinationPath "$toolDir/cmdtools"
    New-Item -ItemType Directory -Force -Path "$toolDir/android-sdk/cmdline-tools" | Out-Null
    # Both targets are fixed children of this project's .tools directory.
    Move-Item -LiteralPath "$toolDir/cmdtools/cmdline-tools" -Destination "$toolDir/android-sdk/cmdline-tools/latest"
}
Download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' "$toolDir/gradle.zip"
Download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256' "$toolDir/gradle.sha256"
if ((Get-FileHash "$toolDir/gradle.zip" -Algorithm SHA256).Hash.ToLowerInvariant() -ne (Get-Content "$toolDir/gradle.sha256" -Raw).Trim()) { throw 'Gradle checksum mismatch' }
if (!(Test-Path "$toolDir/gradle-8.11.1")) { Expand-Archive -LiteralPath "$toolDir/gradle.zip" -DestinationPath $toolDir }
Write-Output 'Tool downloads complete.'
if (!(Test-Path "$toolDir/current-cmdtools/cmdline-tools/bin/sdkmanager.bat")) {
    Download 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' "$toolDir/current-cmdtools.zip"
    if ((Get-FileHash "$toolDir/current-cmdtools.zip" -Algorithm SHA256).Hash.ToLowerInvariant() -ne '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a') { throw 'Current command-line tools checksum mismatch' }
    Expand-Archive -LiteralPath "$toolDir/current-cmdtools.zip" -DestinationPath "$toolDir/current-cmdtools"
}
if (!(Test-Path "$toolDir/android-sdk/platform-tools/adb.exe")) {
    Download 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip?itermux=1' "$toolDir/platform-tools.zip"
    if ((Get-FileHash "$toolDir/platform-tools.zip" -Algorithm SHA1).Hash.ToLowerInvariant() -ne 'e03e78b1d80b396f1c3358e31251cb31740e1110') { throw 'Platform tools checksum mismatch' }
    Expand-Archive -LiteralPath "$toolDir/platform-tools.zip" -DestinationPath "$toolDir/android-sdk" -Force
}
New-Item -ItemType Directory -Force -Path "$root/licenses" | Out-Null
Download 'https://www.apache.org/licenses/LICENSE-2.0.txt' "$root/licenses/Apache-2.0.txt"
Copy-Item -LiteralPath "$root/licenses/Apache-2.0.txt" -Destination "$root/LICENSE" -Force
Download 'https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.txt' "$root/licenses/JSch-BSD.txt"
Download 'https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.JZlib.txt' "$root/licenses/JZlib-BSD.txt"
Download 'https://raw.githubusercontent.com/mwiede/jsch/jsch-2.28.0/LICENSE.jBCrypt.txt' "$root/licenses/jBCrypt-ISC.txt"
Download 'https://raw.githubusercontent.com/bcgit/bc-java/r1rv80/LICENSE.html' "$root/licenses/BouncyCastle.html"
if (!(Test-Path "$toolDir/codex-linux")) {
    $aiLock = Join-Path $root 'test-tool.lock.json'
    if (!(Test-Path $aiLock)) {
        $release = Invoke-RestMethod 'https://api.github.com/repos/openai/codex/releases/latest'
        $asset = $release.assets | Where-Object { $_.name -eq 'codex-x86_64-unknown-linux-musl.tar.gz' } | Select-Object -First 1
        if (!$asset) { throw 'No Linux Codex release asset' }
        @{ version = $release.tag_name; url = $asset.browser_download_url; sha256 = $asset.digest.Replace('sha256:','') } | ConvertTo-Json | Set-Content $aiLock -Encoding UTF8
    }
    $ai = Get-Content $aiLock -Raw | ConvertFrom-Json
    Download $ai.url "$toolDir/codex-linux.tar.gz"
    if ((Get-FileHash "$toolDir/codex-linux.tar.gz" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ai.sha256) { throw 'Codex test tool checksum mismatch' }
    New-Item -ItemType Directory -Force -Path "$toolDir/codex-linux" | Out-Null
    tar.exe -xzf "$toolDir/codex-linux.tar.gz" -C "$toolDir/codex-linux"
}
