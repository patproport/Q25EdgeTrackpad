[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$SkipLaunch
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$BuildScript = Join-Path $ProjectRoot 'build-debug.ps1'
$CandidateApk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$Activity = 'io.github.patproport.q25edgetrackpad/tech.shroyer.q25trackpadcustomizer.MainActivity'

function Resolve-AdbPath {
    $adbCommand = Get-Command 'adb.exe' -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($adbCommand) {
        return $adbCommand.Source
    }

    $sdkCandidates = @()
    if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) {
        $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    }

    foreach ($sdk in ($sdkCandidates | Select-Object -Unique)) {
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    throw 'ADBが見つかりません。PATHまたはANDROID_HOME／ANDROID_SDK_ROOTを確認してください。'
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$AdbPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $AdbPath @Arguments 2>&1 | ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } catch {
        throw "ADBの実行に失敗しました: $($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Get-ConnectedDeviceSerial {
    param([Parameter(Mandatory = $true)][string]$AdbPath)

    $result = Invoke-Adb -AdbPath $AdbPath -Arguments @('devices')
    if ($result.ExitCode -ne 0) {
        throw "adb devices が終了コード $($result.ExitCode) で失敗しました。`n$($result.Output -join "`n")"
    }

    $rows = @()
    foreach ($line in $result.Output) {
        if ($line -match '^\s*List of devices attached\s*$' -or
            $line -match '^\s*\*') {
            continue
        }
        if ($line -match '^\s*(\S+)\s+(\S+)(?:\s+.*)?$') {
            $rows += [pscustomobject]@{
                Serial = $Matches[1]
                State = $Matches[2]
            }
        }
    }

    $unauthorized = @($rows | Where-Object { $_.State -eq 'unauthorized' })
    if ($unauthorized.Count -gt 0) {
        $serials = ($unauthorized | ForEach-Object { $_.Serial }) -join ', '
        throw "ADB端末がunauthorizedです: $serials。端末側でUSBデバッグ許可を確認してください。"
    }
    if ($rows.Count -eq 0) {
        throw 'ADB接続済み端末が0台です。端末接続とUSBデバッグを確認してください。'
    }
    if ($rows.Count -gt 1) {
        $summary = ($rows | ForEach-Object { "$($_.Serial) [$($_.State)]" }) -join ', '
        throw "ADB端末が複数あります。1台だけ接続してください: $summary"
    }
    if ($rows[0].State -ne 'device') {
        throw "ADB端末 $($rows[0].Serial) は利用できない状態です: $($rows[0].State)"
    }

    return $rows[0].Serial
}

function Write-CommandOutput {
    param([string[]]$Lines)
    foreach ($line in $Lines) {
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            Write-Host "  $line"
        }
    }
}

try {
    Write-Host '[1/5] 前提ファイルを確認しています...' -ForegroundColor Cyan
    if (-not (Test-Path -LiteralPath $BuildScript -PathType Leaf)) {
        throw "既存ビルドスクリプトがありません: $BuildScript"
    }
    $windowsPowerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) {
        throw "Windows PowerShell 5.1が見つかりません: $windowsPowerShell"
    }

    Write-Host '[2/5] ADBと接続端末を確認しています...' -ForegroundColor Cyan
    $adb = Resolve-AdbPath
    $adbVersion = Invoke-Adb -AdbPath $adb -Arguments @('version')
    if ($adbVersion.ExitCode -ne 0) {
        throw "ADBを実行できません（終了コード $($adbVersion.ExitCode)）。`n$($adbVersion.Output -join "`n")"
    }
    Write-CommandOutput -Lines $adbVersion.Output
    $serial = Get-ConnectedDeviceSerial -AdbPath $adb
    Write-Host "  接続端末: $serial" -ForegroundColor Green

    Write-Host '[3/5] 前回の対象APKを削除してからビルドします...' -ForegroundColor Cyan
    if (Test-Path -LiteralPath $CandidateApk -PathType Leaf) {
        Remove-Item -LiteralPath $CandidateApk -Force
        Write-Host "  削除: $CandidateApk"
    } else {
        Write-Host '  前回の対象APKはありません。'
    }

    Push-Location $ProjectRoot
    try {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $buildArguments = @('-NoLogo', '-NoProfile', '-File', $BuildScript)
            if ($Clean) { $buildArguments += '-Clean' }
            $buildOutput = @(& $windowsPowerShell @buildArguments 2>&1 |
                ForEach-Object { [string]$_ })
            $buildExitCode = $LASTEXITCODE
        } catch {
            $buildOutput = @($_.Exception.Message)
            $buildExitCode = 1
        } finally {
            $ErrorActionPreference = $previousPreference
        }
    } finally {
        Pop-Location
    }
    Write-CommandOutput -Lines $buildOutput
    if ($buildExitCode -ne 0) {
        throw "ビルドスクリプトが終了コード $buildExitCode で失敗しました。APKはインストールしません。"
    }
    if (-not (Test-Path -LiteralPath $CandidateApk -PathType Leaf)) {
        throw "ビルド成功後に対象APKが見つかりません。インストールしません: $CandidateApk"
    }
    $apk = Get-Item -LiteralPath $CandidateApk
    if ($apk.Length -le 0) {
        throw "対象APKが空です。インストールしません: $CandidateApk"
    }
    Write-Host "  ビルド終了コード: $buildExitCode"
    Write-Host "  APK: $CandidateApk ($($apk.Length) bytes)" -ForegroundColor Green

    Write-Host '[4/5] 接続状態を再確認し、APKをインストールします...' -ForegroundColor Cyan
    $serialAfterBuild = Get-ConnectedDeviceSerial -AdbPath $adb
    if ($serialAfterBuild -ne $serial) {
        throw "ビルド中にADB端末が変わりました（前=$serial、現在=$serialAfterBuild）。インストールしません。"
    }
    $install = Invoke-Adb -AdbPath $adb -Arguments @('-s', $serial, 'install', '-r', $CandidateApk)
    Write-CommandOutput -Lines $install.Output
    if ($install.ExitCode -ne 0 -or
        -not ($install.Output | Where-Object { $_ -match '^Success\s*$' })) {
        throw "adb install -r が成功しませんでした（終了コード $($install.ExitCode)）。設定やアプリデータは消去していません。"
    }

    if ($SkipLaunch) {
        Write-Host '[5/5] 呼び出し元の試験Activity起動に備えて画面起動をスキップします...' -ForegroundColor Cyan
    } else {
        Write-Host '[5/5] 通常のLAUNCHER画面を起動します...' -ForegroundColor Cyan
        $launch = Invoke-Adb -AdbPath $adb -Arguments @('-s', $serial, 'shell', 'am', 'start', '-n', $Activity)
        Write-CommandOutput -Lines $launch.Output
        if ($launch.ExitCode -ne 0 -or
            ($launch.Output | Where-Object { $_ -match '^\s*(Error|Exception):' })) {
            throw "Activityの起動に失敗しました（終了コード $($launch.ExitCode)）。"
        }
    }

    Write-Host ''
    if ($SkipLaunch) {
        Write-Host '完了: ビルドとインストールに成功しました。画面起動は呼び出し元に委ねました。' -ForegroundColor Green
    } else {
        Write-Host '完了: ビルド、インストール、通常画面の起動に成功しました。' -ForegroundColor Green
    }
    exit 0
} catch {
    Write-Host ''
    Write-Host "失敗: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
