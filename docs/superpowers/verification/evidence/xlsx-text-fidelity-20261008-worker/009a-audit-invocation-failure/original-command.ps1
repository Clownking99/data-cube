$base='docs/superpowers/verification/evidence/xlsx-text-fidelity-20261008-worker'
$evidenceArgs=@('-ReviewedCommit','ce2a096a044db6a731774b13a8ae61ea44687953','-Name','009-worker-image-audit')
& (Join-Path $base 'Audit-Main-Image.ps1') @evidenceArgs
$code=$LASTEXITCODE
if($null -eq $code){$code=0}
@{script='Audit-Main-Image.ps1';argv=$evidenceArgs;exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $base '009-worker-image-audit/command-exit.json') -Encoding utf8
exit $code
