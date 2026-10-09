$ErrorActionPreference='Stop'
$out=Join-Path $PSScriptRoot 'process-settlement.json'
if(Test-Path -LiteralPath $out){throw 'Fresh receipt required'}
$scope=@(foreach($name in @('001-complete-targeted','002-clean-full','003-buildsrc-forced','004-image-forced')){
 $exit=Get-Content -LiteralPath (Join-Path $PSScriptRoot "$name/exit.json") -Raw | ConvertFrom-Json
 if($exit.exitCode -ne 0){throw 'Unsettled or failed run'}
 $cmd=Get-Content -LiteralPath (Join-Path $PSScriptRoot "$name/command.json") -Raw | ConvertFrom-Json
 $cmd.owned;$cmd.shortTemp
})
$audit=Get-Content -LiteralPath (Join-Path $PSScriptRoot '005-image-linked-audit/audit.json') -Raw | ConvertFrom-Json
$scope+=@($audit.owned,$audit.shortTemp)
$alive=@(foreach($ownedPath in $scope){
 if($ownedPath.Replace('\','/') -notmatch '^C:/Users/hetia/AppData/Local/Temp/(datacube-g10-main-[a-z0-9-]+|[A-Z0-9]+~[0-9]+/temp)$'){throw 'Unexpected scope'}
 $literal=$ownedPath.Replace('\','\\').Replace("'","\'")
 $query="SELECT ProcessId,ParentProcessId FROM Win32_Process WHERE (Name='java.exe' OR Name='javaw.exe') AND CommandLine LIKE '%$literal%'"
 foreach($p in Get-CimInstance -Query $query){@{pid=$p.ProcessId;parentPid=$p.ParentProcessId;ownedRange=$ownedPath}}
})
$known=@(foreach($step in @('module-index','probe-compile','driver-discovery','redis-linked')){
 $receipt=Get-Content -LiteralPath (Join-Path $PSScriptRoot "005-image-linked-audit/$step/exit.json") -Raw | ConvertFrom-Json
 if(!$receipt.processExited -or $receipt.timedOut -or $receipt.exitCode -ne 0){throw 'Probe not settled'}
 @{step=$step;pid=$receipt.pid;exited=$receipt.processExited;utc=$receipt.utc}
})
@{utc=[datetime]::UtcNow.ToString('o');scopes=$scope;ownedJavaProcesses=$alive;ownedJavaCount=$alive.Count;knownProbeExits=$known;coverage='Recorded wrapper exits, probe PID exits, CIM service-side owned UUID/temp predicates only; no claim about all machine processes'} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $out -Encoding utf8
if($alive.Count){throw 'Owned Java process remains'}
Write-Output 'P3_OWNED_PROCESSES_SETTLED'
