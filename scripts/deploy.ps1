<#
.SYNOPSIS
    Deploys the PearlPlus jar to every bot (1/2/3) of every game server listed in
    ~/.ssh/config, then verifies the transfer by md5.

.DESCRIPTION
    For each host the script checks that <RemoteRoot>/<bot>/plugins exists, skips the
    bot when it does not (raspberry pi / bastion hosts just get skipped), copies the
    jar over scp and compares the remote md5sum with the local Get-FileHash result.

    Bots are never restarted by this script - restart them yourself afterwards.

.EXAMPLE
    .\scripts\deploy.ps1
    .\scripts\deploy.ps1 -Build              # gradle build first, then deploy
    .\scripts\deploy.ps1 -Hosts wei,weiyi    # only two servers
    .\scripts\deploy.ps1 -Bots 2             # only bot 2 on every server
    .\scripts\deploy.ps1 -DryRun             # show what would be deployed
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [string[]]$Hosts = @('bad', 'fentiao', 'xhz', 'wei', 'kirito', 'weiyi', 'fen'),
    [string[]]$Bots = @('1', '2', '3'),
    [string]$RemoteRoot = '/usr/local/games/2b2t_con',
    [string]$JarName = 'PearlPlus-2.2.1.jar',
    [int]$ConnectTimeoutSec = 10,
    [switch]$Build,
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
# powershell -File passes "a,b,c" as a single string, so split/trim defensively
$Hosts = @($Hosts | ForEach-Object { "$_".Split(',') } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$Bots = @($Bots | ForEach-Object { "$_".Split(',') } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Jar) {
    $Jar = Join-Path $repoRoot ('build\libs\' + $JarName)
}

if ($Build) {
    Write-Host "Building $JarName ..." -ForegroundColor Cyan
    & (Join-Path $repoRoot 'gradlew.bat') build --offline --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw 'gradle build failed'
    }
}

if (-not (Test-Path -LiteralPath $Jar)) {
    throw "jar not found: $Jar (run .\gradlew.bat build or pass -Jar)"
}

$localMd5 = (Get-FileHash -LiteralPath $Jar -Algorithm MD5).Hash.ToLowerInvariant()
$localSize = (Get-Item -LiteralPath $Jar).Length

$sshOpts = @('-o', 'BatchMode=yes', '-o', "ConnectTimeout=$ConnectTimeoutSec")

Write-Host ("Local jar  : {0}" -f $Jar)
Write-Host ("MD5        : {0}  ({1} bytes)" -f $localMd5, $localSize)
Write-Host ("Servers    : {0}" -f ($Hosts -join ', '))
Write-Host ("Bots       : {0}" -f ($Bots -join ', '))
Write-Host ''

$results = New-Object System.Collections.Generic.List[object]

foreach ($server in $Hosts) {
    foreach ($bot in $Bots) {
        $remoteDir = '{0}/{1}/plugins' -f $RemoteRoot, $bot
        $remotePath = '{0}/{1}' -f $remoteDir, $JarName
        $row = [pscustomobject]@{
            Server = $server
            Bot    = $bot
            Status = ''
            Detail = ''
        }

        if ($DryRun) {
            $row.Status = 'DRY'
            $row.Detail = "scp -> ${server}:$remotePath"
            $results.Add($row)
            continue
        }

        $dirCheck = & ssh @sshOpts $server "test -d $remoteDir && echo OK" 2>$null
        if ($LASTEXITCODE -ne 0 -or ("$dirCheck" -replace '\s', '') -ne 'OK') {
            $row.Status = 'SKIP'
            $row.Detail = "missing $remoteDir"
            $results.Add($row)
            Write-Host ("[{0}/{1}] SKIP  no such directory" -f $server, $bot) -ForegroundColor DarkGray
            continue
        }

        $null = & scp @sshOpts -q $Jar "${server}:$remotePath" 2>$null
        if ($LASTEXITCODE -ne 0) {
            $row.Status = 'FAIL'
            $row.Detail = 'scp failed (check key / network)'
            $results.Add($row)
            Write-Host ("[{0}/{1}] FAIL  scp failed" -f $server, $bot) -ForegroundColor Red
            continue
        }

        $remoteMd5 = ("$(& ssh @sshOpts $server "md5sum $remotePath" 2>$null)" -split '\s+')[0]
        $remoteMd5 = $remoteMd5.Trim().ToLowerInvariant()
        if ($remoteMd5 -eq $localMd5) {
            $row.Status = 'OK'
            $row.Detail = $remoteMd5
            Write-Host ("[{0}/{1}] OK    {2}" -f $server, $bot, $remoteMd5) -ForegroundColor Green
        } else {
            $row.Status = 'FAIL'
            $row.Detail = "md5 mismatch (remote: $remoteMd5)"
            Write-Host ("[{0}/{1}] FAIL  md5 mismatch" -f $server, $bot) -ForegroundColor Red
        }
        $results.Add($row)
    }
}

Write-Host ''
$results | Format-Table -AutoSize

$ok = @($results | Where-Object Status -eq 'OK').Count
$fail = @($results | Where-Object Status -eq 'FAIL').Count
$skip = @($results | Where-Object Status -eq 'SKIP').Count
$dry = @($results | Where-Object Status -eq 'DRY').Count

Write-Host ("deployed={0}  failed={1}  skipped={2}  dry-run={3}" -f $ok, $fail, $skip, $dry) -ForegroundColor Cyan
if (-not $DryRun -and $ok -gt 0) {
    Write-Host 'Restart each bot for the new jar to take effect.' -ForegroundColor Yellow
}
if ($fail -gt 0) {
    exit 1
}
