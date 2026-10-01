param([Parameter(Mandatory=$true)][string]$Name,[string]$ReportRoot='build/test-results/test')
$ErrorActionPreference='Stop'
$destination=Join-Path $PSScriptRoot $Name
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$summary=@(); $total=0; $failures=0; $errors=0; $skipped=0
foreach($file in Get-ChildItem -LiteralPath $ReportRoot -Filter 'TEST-*.xml' -File) {
    [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
    $suite=$xml.testsuite
    $summary+= [ordered]@{suite=$suite.name; tests=[int]$suite.tests; failures=[int]$suite.failures; errors=[int]$suite.errors; skipped=[int]$suite.skipped}
    $total+=[int]$suite.tests; $failures+=[int]$suite.failures; $errors+=[int]$suite.errors; $skipped+=[int]$suite.skipped
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}
[ordered]@{suites=$summary.Count; tests=$total; passed=($total-$failures-$errors-$skipped); failures=$failures; errors=$errors; skipped=$skipped; details=$summary} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'summary.json') -Encoding utf8
Write-Output "${Name}: suites=$($summary.Count) total=$total passed=$($total-$failures-$errors-$skipped) failed=$failures errors=$errors skipped=$skipped"
