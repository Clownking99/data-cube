param([string]$PlanPath)
# UTF-8, single-shell helper. Never recursively deletes an installation or backup.
function Assert-DataCubePlainPath([string]$Path) {
    if (-not [IO.Path]::IsPathRooted($Path)) { throw 'RELATIVE_PATH' }
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -cne $Path) { throw 'NON_CANONICAL_PATH' }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_PATH' }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $full
}
function Assert-DataCubeImage([string]$Path) {
    $null = Assert-DataCubePlainPath $Path
    foreach ($relative in @('DataCube.exe','app\DataCube.cfg','runtime\lib\modules','runtime\bin\java.exe')) {
        $file = Join-Path $Path $relative
        $null = Assert-DataCubePlainPath $file
        $item = Get-Item -LiteralPath $file -ErrorAction Stop
        if ($item.PSIsContainer -or $item.Length -eq 0) { throw 'INVALID_IMAGE' }
    }
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction Stop) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_IMAGE' }
    }
}
function Set-DataCubeUpdateState($Plan, [string]$State) {
    $path = Join-Path $Plan.workspace 'state.json'
    $null = Assert-DataCubePlainPath $path
    $temp = Join-Path $Plan.workspace ('state-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    @{state=$State;version=$Plan.version;token=$Plan.token} | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $temp -Encoding UTF8 -ErrorAction Stop
    Move-Item -LiteralPath $temp -Destination $path -Force -ErrorAction Stop
}
function Get-DataCubeHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
}
function Wait-DataCubeOriginal($Plan) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 300) {
        $running = Get-Process -Id $Plan.pid -ErrorAction SilentlyContinue
        if ($null -eq $running) { return $true }
        $start = ([DateTimeOffset]$running.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        if ([Math]::Abs($start - [long]$Plan.pidStart) -gt 1000) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}
function Test-DataCubeAcknowledgement($Plan, $Process) {
    $path = Join-Path $Plan.workspace 'startup-ack.json'
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    $null = Assert-DataCubePlainPath $path
    if ((Get-Item -LiteralPath $path).Length -gt 16384) { return $false }
    try {
        $ack = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
        return $ack.token -ceq $Plan.token -and $ack.version -ceq $Plan.version -and
            $ack.appDir -ceq $Plan.appDir -and [long]$ack.pid -eq [long]$Process.Id
    } catch { return $false }
}
function Wait-DataCubeStartup($Plan, $Process) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        if ((Test-DataCubeAcknowledgement $Plan $Process) -or $Process.HasExited) { return }
        Start-Sleep -Milliseconds 250
    }
}
function Invoke-DataCubeUpdate {
    param(
        [string]$PlanFile,
        [scriptblock]$WaitOriginal = { param($plan) Wait-DataCubeOriginal $plan },
        [scriptblock]$Launch = {
            param($exe, $arguments)
            if ($arguments.Count -eq 0) { Start-Process -FilePath $exe -PassThru }
            else { Start-Process -FilePath $exe -ArgumentList $arguments -PassThru }
        },
        [scriptblock]$WaitStartup = { param($plan,$process) Wait-DataCubeStartup $plan $process },
        [scriptblock]$Move = { param($from,$to) [IO.Directory]::Move($from,$to) }
    )
    $ErrorActionPreference = 'Stop'
    $file = Assert-DataCubePlainPath $PlanFile
    if ([IO.Path]::GetFileName($file) -cne 'plan.json' -or (Get-Item -LiteralPath $file).Length -gt 16384) { throw 'INVALID_PLAN' }
    $plan = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($plan.format -ne 1 -or $plan.mode -notin @('PORTABLE','INSTALLED') -or
        $plan.token -cnotmatch '^[a-f0-9]{32}$' -or $plan.version -cnotmatch '^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$' -or
        $plan.sha256 -cnotmatch '^[a-f0-9]{64}$' -or [long]$plan.pid -le 0 -or [long]$plan.pidStart -le 0) { throw 'INVALID_PLAN' }
    $workspace = Assert-DataCubePlainPath $plan.workspace
    $target = Assert-DataCubePlainPath $plan.appDir
    $asset = Assert-DataCubePlainPath $plan.asset
    if ([IO.Path]::GetDirectoryName($file) -cne $workspace -or
        [IO.Path]::GetDirectoryName($asset) -cne $workspace -or
        [IO.Path]::GetFileName($workspace) -notmatch '^\.?datacube-update-[A-Za-z0-9]+$' -or
        $target -eq $workspace -or [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($target)).Length -eq 0) { throw 'INVALID_OWNERSHIP' }
    $owner = Join-Path $workspace 'owner'
    $null = Assert-DataCubePlainPath $owner
    if ([IO.File]::ReadAllText($owner) -cne $plan.token) { throw 'INVALID_OWNERSHIP' }
    $extension = if ($plan.mode -eq 'PORTABLE') { 'portable.zip' } else { 'setup.exe' }
    if ([IO.Path]::GetFileName($asset) -cne ('DataCube-v'+$plan.version+'-win64-'+$extension)) { throw 'INVALID_ASSET' }
    Assert-DataCubeImage $target
    $newImage = Join-Path $workspace 'new\DataCube'
    $previous = Join-Path $workspace 'previous'
    $failed = Join-Path $workspace 'failed-new'
    foreach ($path in @($newImage,$previous,$failed)) { $null = Assert-DataCubePlainPath $path }
    if ((Test-Path -LiteralPath $previous) -or (Test-Path -LiteralPath $failed) -or
        (Test-Path -LiteralPath (Join-Path $workspace 'startup-ack.json'))) { throw 'ATTEMPT_ALREADY_USED' }
    if ($plan.mode -eq 'PORTABLE') {
        if ([IO.Path]::GetDirectoryName($target) -cne [IO.Path]::GetDirectoryName($workspace)) { throw 'DIFFERENT_PARENT' }
        Assert-DataCubeImage $newImage
    }
    Set-DataCubeUpdateState $plan 'WAITING_FOR_EXIT'
    if (-not (& $WaitOriginal $plan)) { Set-DataCubeUpdateState $plan 'CANCELLED_BEFORE_SWAP'; return }
    $movedOld = $false; $movedNew = $false; $process = $null
    try {
        Assert-DataCubeImage $target
        if ((Get-DataCubeHash $asset) -cne $plan.sha256) { throw 'ASSET_CHANGED' }
        if ($plan.mode -eq 'INSTALLED') {
            Set-DataCubeUpdateState $plan 'INSTALLER_STARTING'
            $process = & $Launch $asset @()
            Set-DataCubeUpdateState $plan 'INSTALLER_STARTED_UNCONFIRMED'
            return
        }
        Assert-DataCubeImage $newImage
        Set-DataCubeUpdateState $plan 'BACKING_UP'
        & $Move $target $previous
        $movedOld = $true
        Set-DataCubeUpdateState $plan 'BACKED_UP'
        & $Move $newImage $target
        $movedNew = $true
        Set-DataCubeUpdateState $plan 'REPLACED'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($file)).TrimEnd('=').Replace('+','-').Replace('/','_')
        $process = & $Launch (Join-Path $target 'DataCube.exe') @('--datacube-update='+$encoded)
        Set-DataCubeUpdateState $plan 'STARTING'
        & $WaitStartup $plan $process
        if (Test-DataCubeAcknowledgement $plan $process) {
            Set-DataCubeUpdateState $plan 'START_CONFIRMED'
        } elseif ($process.HasExited) {
            throw 'NEW_PROCESS_EXITED_BEFORE_ACK'
        } else {
            Set-DataCubeUpdateState $plan 'START_UNCONFIRMED_BACKUP_RETAINED'
        }
    } catch {
        if ($null -ne $process -and -not $process.HasExited) {
            Set-DataCubeUpdateState $plan 'RECOVERY_REQUIRED_PROCESS_RUNNING'
            return
        }
        if ($movedOld) {
            try {
                if ($movedNew) { $null = Assert-DataCubePlainPath $target; & $Move $target $failed }
                $null = Assert-DataCubePlainPath $previous
                $null = Assert-DataCubePlainPath $target
                & $Move $previous $target
                Set-DataCubeUpdateState $plan 'ROLLED_BACK'
                $null = & $Launch (Join-Path $target 'DataCube.exe') @()
            } catch { Set-DataCubeUpdateState $plan 'RECOVERY_REQUIRED_BACKUP_RETAINED' }
        } else { Set-DataCubeUpdateState $plan 'FAILED_BEFORE_SWAP' }
    }
}
if ($MyInvocation.InvocationName -ne '.') {
    try { Invoke-DataCubeUpdate -PlanFile $PlanPath } catch { exit 1 }
}
