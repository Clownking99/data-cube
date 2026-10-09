param([Parameter(Mandatory)][string]$Identity)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if ($Identity -ne 'D:/Projects/朝花夕拾/docs/superpowers/verification/evidence/g11-p3-581b459dad08-full-9931c5051ca841719751e79dd32bff01/926ed1f62d8e48c99ef77fd7593d5039/processes/java-version/root-identity.json') { throw 'Unexpected controlled identity path' }
$default = Get-Content -LiteralPath $Identity -Raw | ConvertFrom-Json
$stable = Get-Content -LiteralPath $Identity -Raw | ConvertFrom-Json -DateKind String
$iso = '2026-10-09T06:56:33.8687531Z'
[ordered]@{
    powershellVersion=$PSVersionTable.PSVersion.ToString()
    identity=$Identity
    defaultType=$default.startTimeUtc.GetType().FullName
    defaultString=[string]$default.startTimeUtc
    expectedIso=$iso
    defaultComparison=($iso -eq $default.startTimeUtc)
    stringType=$stable.startTimeUtc.GetType().FullName
    stringComparison=($iso -eq $stable.startTimeUtc)
    purpose='Read-only reproduction of identity comparison semantics; not a repaired runner acceptance.'
} | ConvertTo-Json
