param(
    [int]$WarmupSeconds = 12,
    [double]$ExpectedMaxWorkingSetMB = 175
)

$ErrorActionPreference = 'Stop'
$javaw = (Resolve-Path "$PSScriptRoot/../build/image/bin/javaw.exe").Path
$vmArgs = @(
    '-Xms16m', '-Xmx256m', '-XX:+UseG1GC',
    '-XX:MaxHeapFreeRatio=20', '-XX:MinHeapFreeRatio=5',
    '-XX:G1PeriodicGCInterval=30000',
    '--enable-native-access=com.datacube',
    '-m', 'com.datacube/com.datacube.DataCubeFx'
)

$process = Start-Process -FilePath $javaw -ArgumentList $vmArgs -PassThru -WindowStyle Hidden
try {
    Start-Sleep -Seconds $WarmupSeconds
    $sample = Get-Process -Id $process.Id
    $result = [pscustomobject]@{
        Pid = $sample.Id
        ElapsedSeconds = $WarmupSeconds
        WorkingSetMB = [math]::Round($sample.WorkingSet64 / 1MB, 1)
        PrivateMB = [math]::Round($sample.PrivateMemorySize64 / 1MB, 1)
        Threads = $sample.Threads.Count
    }
    $result | Format-List
    if ($result.WorkingSetMB -gt $ExpectedMaxWorkingSetMB) {
        throw "Working set $($result.WorkingSetMB)MB exceeds $ExpectedMaxWorkingSetMB MB"
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
