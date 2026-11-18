$ErrorActionPreference = 'Stop'
$transaction = $env:JOBPILOT_UPDATE_DIR
$target = $env:JOBPILOT_UPDATE_TARGET
$version = $env:JOBPILOT_UPDATE_VERSION
$parentPid = [int]$env:JOBPILOT_UPDATE_PID
$port = $env:JOBPILOT_UPDATE_PORT
$parent = Split-Path -Parent $target
$staging = Join-Path $parent ('.JobPilot-update-' + [guid]::NewGuid().ToString('N'))
$backup = Join-Path $parent ('.JobPilot-backup-' + [guid]::NewGuid().ToString('N'))
$moved = $false
$success = $false
$newProcess = $null
function Set-Status($state, $message) {
    $json = @{ state = $state; version = $version; message = $message } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText((Join-Path $transaction 'status.tmp'), $json, (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath (Join-Path $transaction 'status.tmp') -Destination (Join-Path $transaction 'status.json') -Force
}
function Save-Result {
    Copy-Item -LiteralPath (Join-Path $transaction 'status.json') -Destination (Join-Path $transaction '..\result.tmp')
    Move-Item -LiteralPath (Join-Path $transaction '..\result.tmp') -Destination (Join-Path $transaction '..\result.json') -Force
}
function Move-WithRetry([string]$from, [string]$to) {
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            Move-Item -LiteralPath $from -Destination $to
            return
        } catch {
            if ($attempt -eq 39) { throw }
            Start-Sleep -Milliseconds 500
        }
    }
}
function Quote-Argument([string]$text) {
    $escaped = [regex]::Replace($text, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
$restartArguments = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText((Join-Path $transaction 'args.json')))
$quotedArguments = foreach ($argument in $restartArguments) { Quote-Argument ([string]$argument) }
$argumentLine = [string]::Join(' ', [string[]]$quotedArguments)
function Start-App {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $target 'JobPilot.exe'
    $info.WorkingDirectory = $target
    $info.Arguments = $argumentLine
    $info.UseShellExecute = $false
    return [Diagnostics.Process]::Start($info)
}
try {
    New-Item -ItemType Directory -Path $staging | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $transaction 'package.zip'))
    try {
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ($name -notlike 'JobPilot/*' -or $name -match '(^/|^[A-Za-z]:|(^|/)\.\.(/|$))') {
                throw '安装包路径无效'
            }
        }
    } finally { $zip.Dispose() }
    Expand-Archive -LiteralPath (Join-Path $transaction 'package.zip') -DestinationPath $staging
    $source = Join-Path $staging 'JobPilot'
    if (-not (Test-Path -LiteralPath (Join-Path $source 'JobPilot.exe'))) { throw '缺少启动程序' }
    $config = [IO.File]::ReadAllText((Join-Path $source 'app\JobPilot.cfg'))
    if (-not (($config -split "\r?\n") -contains "java-options=-Djpackage.app-version=$version")) { throw '安装包版本不匹配' }
    Set-Status 'READY' '安装包已准备完成'
    for ($i=0; $i -lt 120; $i++) {
        if (-not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 1
    }
    if (Get-Process -Id $parentPid -ErrorAction SilentlyContinue) { throw '原程序尚未退出' }
    Move-WithRetry $target $backup
    $moved = $true
    Move-WithRetry $source $target
    $newProcess = Start-App
    for ($i=0; $i -lt 60; $i++) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
            if ($health.data.version -eq $version) {
                Set-Status 'DONE' '更新完成，软件已重新启动'
                Save-Result
                $success = $true
                break
            }
        } catch { }
        if ($newProcess.HasExited) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $success) { throw '新程序启动失败' }
} catch {
    Write-Output $_
    if ($null -ne $newProcess -and -not $newProcess.HasExited) {
        Stop-Process -Id $newProcess.Id -Force
        $newProcess.WaitForExit(15000) | Out-Null
    }
    if ($moved) {
        if (Test-Path -LiteralPath $target) { Move-WithRetry $target (Join-Path $staging 'failed') }
        Move-WithRetry $backup $target
        $newProcess = Start-App
    } elseif (-not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) {
        $newProcess = Start-App
    }
    Set-Status 'FAILED' '本次更新未完成，已保留或恢复原程序，可稍后重试'
    Save-Result
    exit 1
} finally {
    if ($success) { Remove-Item -LiteralPath $staging -Force }
}
