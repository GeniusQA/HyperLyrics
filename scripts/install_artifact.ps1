#Requires -Version 7.2
<#
.SYNOPSIS
    下载 GitHub Actions 构建产物并安装 APK 到手机。

.DESCRIPTION
    根据指定的 workflow run id，使用 gh CLI 下载 artifacts，解压后找到 APK，
    优先通过无线调试安装，失败则回退 USB 连接的设备。

.PARAMETER RunId
    GitHub Actions run id（在 workflow 运行页 URL 中可见）。
    如果不传，默认取当前仓库 android-release.yml 最新 successful 的运行。

.PARAMETER Repo
    GitHub 仓库，默认 QuanTum2088/HyperLyrics。

.PARAMETER WifiIp
    手机无线调试 IP，默认 192.168.5.3。

.PARAMETER WifiPort
    手机无线调试端口，默认 5555。

.PARAMETER RestartSystemUI
    安装完成后强制停止 SystemUI 以使其重新加载模块。

.PARAMETER KeepArtifact
    安装完成后保留下载的 artifact 目录，便于排查。

.EXAMPLE
    .\scripts\install_artifact.ps1 -RunId 34590717811
    .\scripts\install_artifact.ps1 -RunId 34590717811 -RestartSystemUI
#>
param(
    [Parameter()]
    [string]$RunId = "",

    [Parameter()]
    [string]$Repo = "QuanTum2088/HyperLyrics",

    [Parameter()]
    [string]$WifiIp = "192.168.5.3",

    [Parameter()]
    [int]$WifiPort = 5555,

    [Parameter()]
    [switch]$RestartSystemUI,

    [Parameter()]
    [switch]$KeepArtifact
)

$ErrorActionPreference = 'Stop'

function Test-CommandAvailable {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-LatestSuccessfulRunId {
    $json = gh run list -R $Repo -w "android-release.yml" -b main -s success -L 1 --json databaseId,displayTitle,status | ConvertFrom-Json
    if (-not $json) { throw "未找到 main 分支上 android-release.yml 的成功运行记录。" }
    return $json[0].databaseId
}

function Connect-AdbDevice {
    # 先尝试无线调试
    $wirelessAddr = "${WifiIp}:${WifiPort}"
    Write-Host "尝试连接无线调试 $wirelessAddr ..." -ForegroundColor Cyan
    $null = adb connect $wirelessAddr 2>&1

    $devices = adb devices 2>&1 | Select-String "^\S+\s+device$" | ForEach-Object { ($_ -split "\s+")[0] }
    if ($devices) {
        Write-Host "已连接设备：$($devices -join ', ')" -ForegroundColor Green
        return $true
    }

    Write-Host "无线调试未连接，回退到 USB 设备..." -ForegroundColor Yellow
    $devices = adb devices 2>&1 | Select-String "^\S+\s+device$" | ForEach-Object { ($_ -split "\s+")[0] }
    if ($devices) {
        Write-Host "已连接 USB 设备：$($devices -join ', ')" -ForegroundColor Green
        return $true
    }

    return $false
}

# 1. 前置检查
if (-not (Test-CommandAvailable 'gh')) { throw "需要 GitHub CLI (gh)，请先安装并登录。" }
if (-not (Test-CommandAvailable 'adb')) { throw "需要 adb，请确保 Android SDK platform-tools 在 PATH 中。" }

# 2. 解析 run id
if ([string]::IsNullOrWhiteSpace($RunId)) {
    Write-Host "未指定 RunId，自动获取最新 successful 运行..." -ForegroundColor Cyan
    $RunId = Get-LatestSuccessfulRunId
    Write-Host "使用最新成功运行：$RunId" -ForegroundColor Green
}

# 3. 下载 artifact
$artifactDir = Join-Path $env:TEMP "hyperlyrics-artifact-$RunId"
if (Test-Path $artifactDir) { Remove-Item -Recurse -Force $artifactDir }
New-Item -ItemType Directory -Path $artifactDir | Out-Null

try {
    Write-Host "下载 run $RunId 的 artifacts 到 $artifactDir ..." -ForegroundColor Cyan
    $downloadOutput = gh run download $RunId -R $Repo --dir $artifactDir 2>&1
    if ($LASTEXITCODE -ne 0) { throw "下载 artifact 失败：$downloadOutput" }

    # 4. 查找 APK
    $apkFile = Get-ChildItem -Path $artifactDir -Recurse -Filter '*.apk' -File | Select-Object -First 1
    if (-not $apkFile) { throw "artifact 中未找到 .apk 文件，请检查 run $RunId 是否上传了 APK。" }

    Write-Host "找到 APK：$($apkFile.FullName)（$([math]::Round($apkFile.Length/1MB,2)) MB）" -ForegroundColor Green

    # 5. 连接并安装
    if (-not (Connect-AdbDevice)) { throw "未找到任何可用的 adb 设备（无线/USB 均失败）。" }

    Write-Host "安装 APK：$($apkFile.Name) ..." -ForegroundColor Cyan
    $installOutput = adb install -r $apkFile.FullName 2>&1
    if ($LASTEXITCODE -ne 0) { throw "安装失败：$installOutput" }
    Write-Host $installOutput -ForegroundColor Green

    # 6. 可选重启 SystemUI
    if ($RestartSystemUI) {
        Write-Host "重启 SystemUI ..." -ForegroundColor Cyan
        adb shell am force-stop com.android.systemui 2>&1 | Out-Null
        Write-Host "SystemUI 已发送停止指令，系统会自动重新加载。" -ForegroundColor Green
    }

    Write-Host "安装完成。" -ForegroundColor Green
}
finally {
    if (-not $KeepArtifact -and (Test-Path $artifactDir)) {
        Remove-Item -Recurse -Force $artifactDir -ErrorAction SilentlyContinue
        Write-Host "已清理临时目录 $artifactDir" -ForegroundColor DarkGray
    }
}
