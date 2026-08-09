[CmdletBinding()]
param(
    [int]$WarmupSeconds = 12,
    [double]$ExpectedMaxWorkingSetMB = 175,
    [ValidateRange(1, 20)]
    [int]$Samples = 1,
    [ValidateSet('auto', 'on', 'off')]
    [string]$CdsMode = 'auto'
)

$ErrorActionPreference = 'Stop'
$CdsMode = $CdsMode.ToLowerInvariant()
$javaw = (Resolve-Path "$PSScriptRoot/../build/image/bin/javaw.exe").Path
$cdsArchive = Join-Path (Split-Path $javaw -Parent) 'server/classes.jsa'
if ($CdsMode -eq 'on' -and -not (Test-Path -LiteralPath $cdsArchive -PathType Leaf)) {
    throw "CDS archive not found: $cdsArchive. Run build/image/bin/java.exe -Xshare:dump first."
}
$vmArgs = @(
    "-Xshare:$CdsMode",
    '-Xms16m', '-Xmx256m', '-XX:+UseG1GC',
    '-XX:MaxHeapFreeRatio=20', '-XX:MinHeapFreeRatio=5',
    '-XX:G1PeriodicGCInterval=30000',
    '--enable-native-access=com.datacube',
    '-m', 'com.datacube/com.datacube.DataCubeFx'
)

$results = @()
$maxWorkingSetBytes = 0L
for ($sampleNumber = 1; $sampleNumber -le $Samples; $sampleNumber++) {
    $process = Start-Process -FilePath $javaw -ArgumentList $vmArgs -PassThru -WindowStyle Hidden
    try {
        Start-Sleep -Seconds $WarmupSeconds
        $sample = Get-Process -Id $process.Id
        $workingSetBytes = $sample.WorkingSet64
        if ($workingSetBytes -gt $maxWorkingSetBytes) {
            $maxWorkingSetBytes = $workingSetBytes
        }
        $results += [pscustomobject]@{
            Sample = $sampleNumber
            Pid = $sample.Id
            ElapsedSeconds = $WarmupSeconds
            CdsMode = $CdsMode
            VmArgs = $vmArgs -join ' '
            WorkingSetMB = [math]::Round($workingSetBytes / 1MB, 1)
            PrivateMB = [math]::Round($sample.PrivateMemorySize64 / 1MB, 1)
            Threads = $sample.Threads.Count
        }
    } finally {
        $live = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
        if ($live) {
            $null = $live.CloseMainWindow()
            if (-not $live.WaitForExit(3000)) {
                Stop-Process -Id $process.Id -Force
            }
        }
    }
}

$results | Format-List
$expectedMaxWorkingSetBytes = $ExpectedMaxWorkingSetMB * 1MB
if ($maxWorkingSetBytes -gt $expectedMaxWorkingSetBytes) {
    $measuredMaxWorkingSetMB = [math]::Round($maxWorkingSetBytes / 1MB, 3)
    throw "Working set $($measuredMaxWorkingSetMB)MB exceeds $ExpectedMaxWorkingSetMB MB"
}
