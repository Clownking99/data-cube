param([string]$Name, [string[]]$GradleArgs, [string]$Repository, [string]$ProfileName = 'profile', [switch]$ImageBuild)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $Repository
$scratch = $PSScriptRoot
if (!$ImageBuild) {
 $env:DATACUBE_G7_PROFILE = Join-Path $scratch $ProfileName
 New-Item -ItemType Directory -Path $env:DATACUBE_G7_PROFILE -Force | Out-Null
} else {
 Remove-Item -LiteralPath 'Env:DATACUBE_G7_PROFILE' -ErrorAction SilentlyContinue
}
$arguments = @($GradleArgs) + @('--offline','--no-daemon','--console=plain')
if (!$ImageBuild) { $arguments += @('--init-script', (Join-Path $scratch 'isolated-tests.gradle')) }
$log = Join-Path $scratch ($Name + '.log')
& .\gradlew.bat @arguments *> $log
$runExit = $LASTEXITCODE
$record = [ordered]@{ name=$Name; head=(git rev-parse HEAD); arguments=$arguments; completedAt=(Get-Date).ToString('o'); exitCode=$runExit; log=($Name+'.log'); logSha256=(Get-FileHash -LiteralPath $log).Hash }
$taskPattern = if ($GradleArgs -contains ':buildSrc:test') { '(?m)^> Task :buildSrc:test(?: FAILED)?\r?$' } else { '(?m)^> Task :test(?: FAILED)?\r?$' }
$record.actualTestTaskRan = !$ImageBuild -and ((Get-Content -LiteralPath $log -Raw) -match $taskPattern)
if ($record.actualTestTaskRan) {
 $source = if ($GradleArgs -contains ':buildSrc:test') { 'buildSrc\build\test-results\test' } else { 'build\test-results\test' }
 $destination = Join-Path $scratch ($Name + '-xml')
 New-Item -ItemType Directory -Path $destination -Force | Out-Null
 Copy-Item -Path (Join-Path $source 'TEST-*.xml') -Destination $destination
 $files = @(Get-ChildItem -LiteralPath $destination -Filter 'TEST-*.xml' | Sort-Object Name)
 $counts = @{ tests=0; failures=0; errors=0; skipped=0 }; $skips = @(); $failed = @()
 foreach ($file in $files) {
  [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
  foreach ($key in @('tests','failures','errors','skipped')) { $counts[$key] += [int]$xml.testsuite.GetAttribute($key) }
  foreach ($case in $xml.testsuite.testcase) {
   if ($case.skipped) { $skips += [ordered]@{test=($case.classname+'.'+$case.name);reason=$case.skipped.message} }
   if ($case.failure -or $case.error) { $failed += ($case.classname+'.'+$case.name) }
  }
 }
 $manifest = ($files | ForEach-Object { $_.Name + ' ' + (Get-FileHash -LiteralPath $_.FullName).Hash }) -join "`n"
 $record.suites=$files.Count; $record.tests=$counts.tests; $record.passed=($counts.tests-$counts.failures-$counts.errors-$counts.skipped)
 $record.failures=$counts.failures; $record.errors=$counts.errors; $record.skipped=$counts.skipped; $record.skipDetails=$skips; $record.failedTests=$failed
 $record.xmlDirectory=($Name+'-xml'); $record.xmlManifestSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($manifest)))
}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $scratch ($Name+'.json')) -Encoding utf8
Get-Content -LiteralPath $log -Tail 24
$record | ConvertTo-Json -Depth 6
exit $runExit
