$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$version = (Select-String -Path "$root/gradle.properties" -Pattern '^itermuxVersionName=(.+)$').Matches[0].Groups[1].Value.Trim()
$zipPath = Join-Path $root "dist/Roamtty-$version-source.zip"
$folders = @('app','ssh-core','terminal-emulator','terminal-view','scripts','docs','licenses','gradle','artwork')
$rootNames = @('.gitignore','.gitattributes','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','LICENSE','NOTICE','README.md','README.zh-CN.md','THIRD_PARTY_NOTICES.md','toolchains.lock.json','test-tool.lock.json')
$files = @($rootNames | ForEach-Object { Get-Item -LiteralPath (Join-Path $root $_) })
foreach ($folder in $folders) {
    $files += Get-ChildItem -LiteralPath (Join-Path $root $folder) -Recurse -File | Where-Object { $_.FullName -notmatch '\\(build|\.gradle)\\' -and $_.Name -ne 'test_password' }
}
# Create exactly the reviewed source allowlist; no credentials, SDK, build caches, APKs or signing key.
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::Create)
$archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($files | Sort-Object FullName)) {
        $relative = $file.FullName.Substring($root.Length + 1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, "Roamtty/$relative", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $stream.Dispose() }
$artifacts = @(Get-ChildItem -LiteralPath (Join-Path $root 'dist') -Filter '*.apk' -File) + @(Get-Item -LiteralPath $zipPath)
$checksums = foreach ($artifact in ($artifacts | Sort-Object Name)) { "{0}  {1}" -f (Get-FileHash $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $artifact.Name }
[IO.File]::WriteAllLines((Join-Path $root 'dist/SHA256SUMS.txt'), $checksums, [Text.Encoding]::ASCII)
Write-Output "Source archive created: $($files.Count) files."
