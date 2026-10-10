$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$rows=@()
foreach($size in @(0,1,2)){
    [int[]]$expected=$(switch($size){0 {@()};1 {@(41)};2 {@(41,42)}})
    $query={ $members=@($expected);return ,$members }
    $actual=(& $query)
    if($actual.Count -ne $expected.Count){throw "COUNT_MISMATCH:$size"}
    $originalForeach=@(foreach($pidValue in $expected){if($pidValue -isnot [int]){throw 'ORIGINAL_NOT_INT'};$pidValue})
    $diagnosticForeach=@(foreach($pidValue in $(if($false){@()}else{(& $query)})){if($pidValue -isnot [int]){throw "DIAGNOSTIC_NOT_INT:$size"};$pidValue})
    if($originalForeach.Count -ne $diagnosticForeach.Count -or [string]::Join(',', $originalForeach) -cne [string]::Join(',', $diagnosticForeach)){throw "FOREACH_MISMATCH:$size"}
    $rows+=@{members=$size;originalCount=$expected.Count;diagnosticCount=$actual.Count;foreachScalarInt=$true;values=$diagnosticForeach}
}
$diagState=@{overflow=$false};$markOverflow={$diagState.overflow=$true}
if($diagState.overflow){throw 'BAD_INITIAL_OVERFLOW'}
& $markOverflow
if(-not $diagState.overflow){throw 'OVERFLOW_STATE_NOT_SHARED'}
foreach($name in @('diagnose.ps1','VerificationCore.diagnostic.psm1')){
    $tokens=$null;$parseErrors=$null;$null=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw ('PARSE_ERRORS:'+($parseErrors|Out-String))}
}
@{passed=$true;probeExecuted=$false;processStartCalls=0;cases=$rows;overflowStateVerified=$true;parseErrors=0}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'pure-assertions-result.json') -Encoding utf8NoBOM
