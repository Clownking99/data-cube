$ErrorActionPreference='Stop'
$base=$PSScriptRoot
$wrapper=Join-Path $base '009-worker-image-audit-wrapper'
if(Test-Path -LiteralPath $wrapper){throw 'Preserve existing audit wrapper'}
New-Item -ItemType Directory -Path $wrapper | Out-Null
@{script='Audit-Main-Image.ps1';reviewedCommit='ce2a096a044db6a731774b13a8ae61ea44687953';name='009-worker-image-audit';utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $wrapper 'command.json') -Encoding utf8
$code=1
try {
 & (Join-Path $base 'Audit-Main-Image.ps1') -ReviewedCommit 'ce2a096a044db6a731774b13a8ae61ea44687953' -Name '009-worker-image-audit' 2>&1 | Tee-Object -FilePath (Join-Path $wrapper 'audit-stdout.log')
 $code=$LASTEXITCODE
 if($null -eq $code){throw 'Missing audit command exit'}
} catch {
 $_ | Out-String | Tee-Object -FilePath (Join-Path $wrapper 'audit-error.log')
 $code=1
} finally {
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $wrapper 'exit.json') -Encoding utf8
}
exit $code
