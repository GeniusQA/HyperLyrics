<#
.SYNOPSIS
  HyperLyrics：GitHub 构建成功后自动下载产物、解压并无线安装到手机。

.DESCRIPTION
  链路：gh 查最新成功构建 -> 下载产物 zip（gh 自带鉴权）-> PowerShell 原生解压
  （无需 Bandizip，脚本不会弹"选择应用"窗口）-> 挑出指定版本 APK ->
  adb 无线安装（首次需手动配对一次，之后可 mDNS 自动发现）-> 记录 run id 防重复安装。

  产物保留策略：GitHub 产物默认保留 90 天；canary/stable 通用（按 run 下载，不依赖 Release 页）。

.EXAMPLE
  .\auto-install-latest.ps1                          # 一次性：安装最近一次成功构建（装过则跳过）
  .\auto-install-latest.ps1 -Watch                   # 轮询模式：每 5 分钟检查一次新构建
  .\auto-install-latest.ps1 -Watch -IntervalSec 120  # 2 分钟一查
  .\auto-install-latest.ps1 -PreferApk Canary -RestartSystemUI   # 装 canary 并重启 SystemUI
  .\auto-install-latest.ps1 -DryRun                  # 只检查发现逻辑，不下载不安装
#>
param(
    [string]$Repo = "GeniusQA/HyperLyrics",
    [string]$Workflow = "android-release.yml",
    [ValidateSet("Release", "Canary")]
    [string]$PreferApk = "Release",
    [switch]$Watch,
    [int]$IntervalSec = 300,
    [switch]$RestartSystemUI,
    [switch]$DryRun,
    [string]$DeviceAddr = "",
    [string]$StateFile = "$env:TEMP\hyperlyrics-installed-run.txt"
)

# 已配对设备的无线连接地址（mDNS 不可用时用它）。可改默认值或命令行 -DeviceAddr 传入。
if (-not $DeviceAddr) { $DeviceAddr = "192.168.1.6:37333" }

$ErrorActionPreference = "Stop"

function Get-LatestSuccessRun {
    $runs = gh run list -R $Repo --workflow $Workflow --status success -L 1 --json databaseId,displayTitle | ConvertFrom-Json
    if (-not $runs) { throw "没有找到成功的构建（$Repo / $Workflow）" }
    return $runs[0]
}

function Connect-WirelessAdb {
    if (adb devices | Select-String -Pattern "\tdevice$") { return $true }

    if ($DeviceAddr) {
        Write-Host "尝试用已配置地址连接 $DeviceAddr ..."
        adb connect $DeviceAddr | Out-Host
    }

    if (adb devices | Select-String -Pattern "\tdevice$") { return $true }

    Write-Host "尝试 mDNS 自动发现无线调试设备..."
    $services = adb mdns services 2>$null
    $svc = $services | Select-String -Pattern "_adb-tls-connect\._tcp\.\s+(\S+)$"
    if ($svc) {
        $addr = $svc.Matches[0].Groups[1].Value.Trim()
        Write-Host "发现设备 $addr，尝试 adb connect ..."
        adb connect $addr | Out-Host
    }

    if (adb devices | Select-String -Pattern "\tdevice$") { return $true }

    Write-Warning "未找到可用设备。首次使用需手动配对一次（配对码只会显示在手机上，无法全自动）："
    Write-Warning "  1. 手机：设置 -> 开发者选项 -> 无线调试 -> 打开 -> 点『使用配对码配对设备』"
    Write-Warning "  2. 电脑执行: adb pair <手机上显示的IP:配对端口>   然后输入 6 位配对码"
    Write-Warning "  3. 配对成功后再运行本脚本，之后会通过 mDNS 自动连接（手机与电脑需同一 Wi-Fi）"
    return $false
}

function Install-LatestRun {
    $run = Get-LatestSuccessRun
    $lastRun = if (Test-Path $StateFile) { (Get-Content $StateFile -Raw).Trim() } else { "" }
    if ("$($run.databaseId)" -eq $lastRun) {
        Write-Host "构建 $($run.databaseId)（$($run.displayTitle)）已安装过，跳过"
        return
    }

    Write-Host "发现未安装的构建: $($run.databaseId)  $($run.displayTitle)"
    if ($DryRun) { Write-Host "[DryRun] 将下载该 run 产物并安装 $PreferApk 版（本次跳过）"; return }

    $tmp = Join-Path $env:TEMP ("hl-artifact-" + $run.databaseId)
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Path $tmp | Out-Null

    # 只下载对应渠道的 artifact（Release/Canary 已拆成独立产物，避免整包拉 70+MB）
    $arts = gh api -R $Repo "actions/runs/$($run.databaseId)/artifacts" --jq '.artifacts[] | [.id, .name] | @tsv' |
        ConvertFrom-Csv -Delimiter "`t" -Header id, name
    $needle = if ($PreferApk -eq "Release") { "-release-v" } else { "-canary-v" }
    $art = $arts | Where-Object { $_.name -match [regex]::Escape($needle) } | Select-Object -First 1
    if (-not $art) { throw "该 run 没有找到 $PreferApk 产物（可用: $($arts.name -join ', ')）" }

    Write-Host "下载产物 $($art.name) 中（约十几 MB，请稍候）..."
    gh run download $run.databaseId -R $Repo -n $art.name -D $tmp

    # gh 下载的是 zip；用 PowerShell 原生解压（替代手动 Bandizip）
    Get-ChildItem $tmp -Recurse -Filter *.zip | ForEach-Object {
        Expand-Archive -Path $_.FullName -DestinationPath ($_.FullName + "-x") -Force
    }

    $apk = Get-ChildItem $tmp -Recurse -Filter *.apk |
        Where-Object { $_.Name -match $PreferApk } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $apk) { throw "产物里没找到 $PreferApk 版 APK，目录：$tmp" }

    if (-not (Connect-WirelessAdb)) { throw "未连接手机，安装中止（产物保留在 $tmp，可手动装）" }

    Write-Host "安装 $($apk.Name) ..."
    adb install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败（退出码 $LASTEXITCODE）" }

    if ($RestartSystemUI) {
        Write-Host "重启 SystemUI 使模块生效..."
        adb shell su -c "killall com.android.systemui"
    }

    Set-Content -Path $StateFile -Value $run.databaseId
    Write-Host "完成 ✓ 已记录 run id，不会重复安装"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Install-LatestRun

if ($Watch) {
    Write-Host "进入轮询模式：每 $IntervalSec 秒检查一次新构建（Ctrl+C 退出）"
    while ($true) {
        Start-Sleep -Seconds $IntervalSec
        try { Install-LatestRun } catch { Write-Warning $_.Exception.Message }
    }
}
