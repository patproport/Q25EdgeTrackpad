[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = '',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$expectedPackage = 'io.github.patproport.q25edgetrackpad'
$expectedVersionCode = '1'
$expectedVersionName = '1.0.0'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'

if ($JavaHome) {
    $java = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java not found: $java" }
    $env:JAVA_HOME = $JavaHome
} elseif (-not (Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue)) {
    throw 'Java 17+ not found. Set JAVA_HOME or add java.exe to PATH.'
}

if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildToolsRoot = Join-Path $AndroidSdk 'build-tools'
if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
    throw 'Android SDK build-tools not found. Set ANDROID_SDK_ROOT or pass -AndroidSdk.'
}
$env:ANDROID_HOME = $AndroidSdk
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $aapt2) -or -not (Test-Path -LiteralPath $apksigner)) {
    throw 'aapt2.exe or apksigner.bat not found.'
}

$signingNames = @(
    'Q25_RELEASE_STORE_FILE', 'Q25_RELEASE_KEY_ALIAS',
    'Q25_RELEASE_STORE_PASSWORD', 'Q25_RELEASE_KEY_PASSWORD'
)
try {
    if (-not $env:Q25_RELEASE_STORE_FILE) {
        $env:Q25_RELEASE_STORE_FILE = Read-Host 'Keystore path outside this repository'
    }
    if (-not $env:Q25_RELEASE_KEY_ALIAS) {
        $env:Q25_RELEASE_KEY_ALIAS = Read-Host 'Release key alias'
    }
    if (-not $env:Q25_RELEASE_STORE_PASSWORD) {
        $secure = Read-Host 'Keystore password' -AsSecureString
        $env:Q25_RELEASE_STORE_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
    }
    if (-not $env:Q25_RELEASE_KEY_PASSWORD) {
        $secure = Read-Host 'Key password' -AsSecureString
        $env:Q25_RELEASE_KEY_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
    }
    if ($signingNames | Where-Object { -not [Environment]::GetEnvironmentVariable($_) }) {
        throw 'All four release signing values are required.'
    }
    if (-not (Test-Path -LiteralPath $env:Q25_RELEASE_STORE_FILE -PathType Leaf)) {
        throw 'Keystore file not found.'
    }
    $storePath = [IO.Path]::GetFullPath($env:Q25_RELEASE_STORE_FILE)
    $repositoryPath = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($storePath.StartsWith($repositoryPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The release keystore must be outside this repository.'
    }

    Push-Location $PSScriptRoot
    try {
        $arguments = @('--no-daemon')
        if ($Clean) { $arguments += 'clean' }
        $arguments += ':app:assembleRelease'
        & (Join-Path $PSScriptRoot 'gradlew.bat') @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Release APK not found.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $oldIcons = @($archive.Entries | Where-Object {
            $_.FullName -match '(^|/)ic_launcher(_foreground|_round)?\.webp$|(^|/)ic_launcher-playstore\.png$'
        })
        if ($oldIcons.Count -gt 0) { throw 'Former launcher bitmap found in the release APK.' }
    } finally {
        $archive.Dispose()
    }
    $signature = & $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $badging = & $aapt2 dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed.' }
    $packageLine = @($badging | Where-Object { $_ -match '^package: ' }) | Select-Object -First 1
    $identity = [regex]::Match($packageLine, "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (-not $identity.Success -or $identity.Groups[1].Value -ne $expectedPackage -or
        $identity.Groups[2].Value -ne $expectedVersionCode -or
        $identity.Groups[3].Value -ne $expectedVersionName) {
        throw "Release identity mismatch: $packageLine"
    }
    $certificateLine = @($signature | Where-Object {
        $_ -match '^Signer #1 certificate SHA-256 digest: '
    }) | Select-Object -First 1
    if (-not $certificateLine) { throw 'Signing certificate fingerprint not found.' }

    Write-Host "Release APK: $apk"
    Write-Host $packageLine
    Write-Host $certificateLine
    Write-Host "APK SHA-256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)"
    Write-Host 'No ADB installation or GitHub upload was performed.'
} finally {
    foreach ($name in $signingNames) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
