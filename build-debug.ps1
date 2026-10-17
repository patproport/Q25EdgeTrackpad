[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = '',
    [string]$BackupApk = '',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$ExpectedPackage = 'io.github.patproport.q25edgetrackpad'
$ExpectedVersionCode = '1'
$CandidateApk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaCommand = Get-Command 'java.exe' -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $javaCommand) {
        throw 'Java 17+ not found. Set JAVA_HOME or add java.exe to PATH.'
    }
} else {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
        throw "Java not found under JAVA_HOME: $JavaHome"
    }
    $env:JAVA_HOME = $JavaHome
}

if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    if ($env:ANDROID_SDK_ROOT) {
        $AndroidSdk = $env:ANDROID_SDK_ROOT
    } elseif ($env:ANDROID_HOME) {
        $AndroidSdk = $env:ANDROID_HOME
    } elseif ($env:LOCALAPPDATA) {
        $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk) -or
    -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'build-tools') -PathType Container)) {
    throw 'Android SDK build-tools not found. Set ANDROID_SDK_ROOT or pass -AndroidSdk.'
}
$env:ANDROID_HOME = $AndroidSdk
$BuildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $BuildTools) { throw 'Android SDK build-tools not found.' }
$Aapt2 = Join-Path $BuildTools.FullName 'aapt2.exe'
$ApkSigner = Join-Path $BuildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $Aapt2) -or
    -not (Test-Path -LiteralPath $ApkSigner)) {
    throw 'aapt2.exe or apksigner.bat not found in the selected build-tools directory.'
}

function Get-ApkIdentity {
    param([Parameter(Mandatory = $true)][string]$Path)
    $badging = & $Aapt2 dump badging $Path
    if ($LASTEXITCODE -ne 0) { throw "aapt2 failed: $Path" }
    $packageLine = @($badging | Where-Object { $_ -match '^package: ' }) | Select-Object -First 1
    $identityMatch = [regex]::Match($packageLine,
        "^package: name='([^']+)' versionCode='([^']+)'")
    if (-not $identityMatch.Success) {
        throw "Cannot parse package/versionCode: $Path"
    }
    $priorErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $certOutput = & $ApkSigner verify --print-certs $Path 2>&1
        $signerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $priorErrorActionPreference
    }
    if ($signerExitCode -ne 0) { throw "APK signature verification failed: $Path" }
    $certLine = @($certOutput | Where-Object {
        $_ -match '^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$'
    }) | Select-Object -First 1
    if (-not $certLine) { throw "Cannot read signing certificate SHA-256: $Path" }
    [pscustomobject]@{
        Package = $identityMatch.Groups[1].Value
        VersionCode = $identityMatch.Groups[2].Value
        CertSha256 = ([regex]::Match($certLine,
            'SHA-256 digest: ([0-9a-fA-F]+)$')).Groups[1].Value.ToUpperInvariant()
        ApkSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    }
}

if (-not [string]::IsNullOrWhiteSpace($BackupApk)) {
    if (-not (Test-Path -LiteralPath $BackupApk -PathType Leaf)) {
        throw "Reference APK not found: $BackupApk"
    }
    $Backup = Get-ApkIdentity -Path $BackupApk
    if ($Backup.Package -ne $ExpectedPackage -or $Backup.VersionCode -ne $ExpectedVersionCode) {
        throw "Reference APK identity differs: $($Backup.Package) versionCode=$($Backup.VersionCode)"
    }
}

Push-Location $PSScriptRoot
try {
    $gradleArguments = @('--no-daemon')
    if ($Clean) { $gradleArguments += 'clean' }
    $gradleArguments += ':app:assembleDebug'
    & (Join-Path $PSScriptRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. No installation was attempted.' }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $CandidateApk -PathType Leaf)) {
    throw "Candidate APK not found: $CandidateApk"
}
$Candidate = Get-ApkIdentity -Path $CandidateApk
if ($Candidate.Package -ne $ExpectedPackage -or $Candidate.VersionCode -ne $ExpectedVersionCode) {
    throw "Candidate identity differs: $($Candidate.Package) versionCode=$($Candidate.VersionCode)"
}
if ($Backup -and $Candidate.CertSha256 -ne $Backup.CertSha256) {
    throw 'Candidate signing certificate differs from the reference APK. Do not install it as an update.'
}

Write-Host "Candidate: $CandidateApk"
Write-Host "Candidate APK SHA-256: $($Candidate.ApkSha256)"
Write-Host "Package: $($Candidate.Package)"
Write-Host "VersionCode: $($Candidate.VersionCode)"
Write-Host "Signer certificate SHA-256: $($Candidate.CertSha256)"
if ($Backup) {
    Write-Host "Reference APK identity and signing certificate matched: $BackupApk"
} else {
    Write-Host 'No reference APK supplied; signing was verified, but update compatibility was not checked.'
}
Write-Host 'No ADB command or installation was performed.'
