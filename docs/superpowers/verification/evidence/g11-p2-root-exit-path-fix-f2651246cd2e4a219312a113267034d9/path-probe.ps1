param([string]$Check)
$record=Get-Content -LiteralPath $Check -Raw|ConvertFrom-Json
$request=Get-Content -LiteralPath (Join-Path $record.receipt.directory 'request.json') -Raw|ConvertFrom-Json
$path=$request.argv[-2]
[ordered]@{path=$path;pathLength=$path.Length;dotnetExists=[IO.File]::Exists($path);dotnetContent=[IO.File]::ReadAllText($path);powershell=$PSVersionTable.PSVersion.ToString();dotnet=[Environment]::Version.ToString()}|ConvertTo-Json