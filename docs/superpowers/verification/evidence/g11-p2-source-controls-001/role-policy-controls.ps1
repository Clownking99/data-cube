param([string]$Tools,[string]$Out)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $Tools 'VerificationCore.psm1') -Force
$scope=@{stage='linked';paths=@{Pwsh='C:\owned\pwsh.exe';Python='C:\owned\python.exe';Jdk='C:\owned\jdk';RuntimeParent='C:\admitted'}}
$rows=[Collections.Generic.List[object]]::new()
foreach($pair in @(@('jdk-jimage','C:\owned\jdk\bin\javac.exe'),@('image-java','C:\foreign\java.exe'),@('unknown','C:\owned\jdk\bin\java.exe'),@('jdk-java','C:\never\.testagent\java.exe'))) {
 try{$null=Assert-OwnedExecutableRole $scope $pair[1] $pair[0];throw 'MISSING_ROLE_REFUSAL'}catch{if($_.Exception.Message -eq 'MISSING_ROLE_REFUSAL'){throw};$rows.Add(@{role=$pair[0];path=$pair[1];actualRefusal=$_.Exception.Message})}
}
$scope.imageBinding=@{schema='owned-image-binding/v1';sourceRuntime=('C:\foreign\datacube-g11-'+('a'*32));image=('C:\foreign\datacube-g11-'+('a'*32)+'\build\main\jpackage\DataCube')}
try{$null=Assert-OwnedExecutableRole $scope 'C:\foreign\java.exe' 'image-java';throw 'MISSING_PARENT_REFUSAL'}catch{if($_.Exception.Message -eq 'MISSING_PARENT_REFUSAL'){throw};$rows.Add(@{actualRefusal=$_.Exception.Message;case='foreign-runtime-parent'})}
$policy=Get-Content -LiteralPath (Join-Path $Tools 'stage-policy.json') -Raw|ConvertFrom-Json -AsHashtable
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $Tools 'run-stage.ps1'),[ref]$tokens,[ref]$errors)
if($errors){throw 'BAD_STAGE_SOURCE'}
$definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Apply-TestPolicy'},$true)
 . ([scriptblock]::Create($definition.Extent.Text))
foreach($rule in $policy.liveSkips){foreach($Mode in @('targeted','full','buildsrc')) {
 $failures=[Collections.Generic.List[string]]::new()
 $results=@{schema='test-results/v1';failures=0;errors=0;passed=1;cases=@(@{status='skipped';class=$rule.class;name=$rule.name;reason=$rule.reason})}
 Apply-TestPolicy $results $Mode
 $expected=$Mode -in $rule.stages
 if(($failures.Count -eq 0) -ne $expected){throw 'STAGE_SKIP_POLICY_MISMATCH'}
 $rows.Add(@{stage=$Mode;class=$rule.class;name=$rule.name;allowed=$expected;actualFailures=@($failures)})
}}
$rows|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Out 'role-policy-results.json') -Encoding utf8NoBOM
