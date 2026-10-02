$ErrorActionPreference='Stop'
$worker=Join-Path $PSScriptRoot '../metadata-native-keyboard'
$launch=Get-Content -LiteralPath (Join-Path $worker 'launch.json') -Raw | ConvertFrom-Json
$manifest=@(Get-Content -LiteralPath (Join-Path $worker 'raw-byte-manifest.json') -Raw | ConvertFrom-Json)
foreach($entry in $manifest){
    if($entry.name -ne [IO.Path]::GetFileName($entry.name)){throw 'Worker manifest escaped flat evidence directory'}
    $file=Get-Item -LiteralPath (Join-Path $worker $entry.name)
    if($file.Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $entry.sha256){throw ('Worker raw manifest mismatch: '+$entry.name)}
}
$actual=@(Get-ChildItem -LiteralPath $worker -File | Where-Object {$_.Name -notin @('raw-byte-manifest.json','.gitattributes')})
if($actual.Count -ne $manifest.Count){throw 'Worker raw file count mismatch'}
$states=@(Get-Content -LiteralPath (Join-Path $worker 'native-actions.jsonl') | ForEach-Object {$_|ConvertFrom-Json})
$images=@();$initialId=$states[0].window.id
foreach($state in $states){
    if($state.window.id -ne $initialId -or $state.window.title -ne $launch.title -or !$state.window.app.EndsWith('build\jpackage\DataCube\runtime\bin\javaw.exe',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected target window in native state'}
    for($i=0;$i -lt $state.screenshots.Count;$i++){
        $shot=$state.screenshots[$i]
        $name=('{0:D3}-{1}-{2}.jpg' -f [int]$state.seq,$state.label,$i)
        $file=Get-Item -LiteralPath (Join-Path $worker $name)
        $raw=[IO.File]::ReadAllBytes($file.FullName)
        if($raw.Length -lt 4 -or $raw[0] -ne 255 -or $raw[1] -ne 216){throw 'Not original JPEG format'}
        $offset=2;$width=0;$height=0
        while($offset -lt $raw.Length-9){
            if($raw[$offset] -ne 255){throw 'Invalid JPEG marker'}
            while($raw[$offset] -eq 255){$offset++}
            $marker=$raw[$offset++];if($marker -eq 217 -or $marker -eq 218){break}
            if($marker -in @(216,1) -or ($marker -ge 208 -and $marker -le 215)){continue}
            $length=([int]$raw[$offset] -shl 8)+$raw[$offset+1]
            if($marker -in @(192,193,194,195,197,198,199,201,202,203,205,206,207)){
                $height=([int]$raw[$offset+3] -shl 8)+$raw[$offset+4];$width=([int]$raw[$offset+5] -shl 8)+$raw[$offset+6];break
            }
            $offset+=$length
        }
        if($width -ne $shot.width -or $height -ne $shot.height){throw ('JPEG dimensions differ from returned state: '+$name)}
        $images+=[ordered]@{path=$name;bytes=$raw.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash;returnedWidth=$shot.width;returnedHeight=$shot.height;jpegWidth=$width;jpegHeight=$height}
    }
}
$log=Get-Content -LiteralPath (Join-Path $worker 'desktop-runtime.log') -Raw
$matches=[regex]::Matches($log,'COUNTERS mockOpens=(\d+) mockCloses=(\d+) searches=(\d+) pages=(\d+) ddls=(\d+) writeAttempts=(\d+) executionAttempts=(\d+)')
$last=$matches[$matches.Count-1];$counts=@($last.Groups|Select-Object -Skip 1|ForEach-Object {[int]$_.Value})
$closed=Get-Content -LiteralPath (Join-Path $worker 'process-closed.json') -Raw | ConvertFrom-Json
$expected=Get-Content -LiteralPath (Join-Path $worker 'results.json') -Raw | ConvertFrom-Json
if($closed.pid -ne $launch.pid -or !$closed.exited -or !$log.Contains('SHUTDOWN_COMPLETED') -or $counts[0] -ne 1 -or $counts[1] -ne 1 -or @($counts|Select-Object -Skip 2|Where-Object {$_ -ne 0}).Count -gt 0){throw 'Synthetic resources or shutdown differ from claimed evidence'}
$events=[ordered]@{key=[regex]::Matches($log,'(?m)^DIALOG_KEY=').Count;typed=[regex]::Matches($log,'(?m)^DIALOG_TYPED=').Count;query=[regex]::Matches($log,'(?m)^QUERY_CHANGE=').Count;mode=[regex]::Matches($log,'(?m)^MODE_CHANGE=').Count;resize=[regex]::Matches($log,'(?m)^STATE event=(width|height)').Count}
if(@($events.Values|Where-Object {$_ -ne 0}).Count -gt 0 -or $expected.nativeInputPassed -or $expected.nativeKeyboardPassed -or $expected.actualSmallerWindowPassed -or $expected.invalidationWithPublishedResultPassed){throw 'Native failure/unverified matrix contradicts raw evidence'}
$resizes=@($states | Where-Object {$_.label -in @('before-native-resize','native-resize-attempt-one')} | ForEach-Object {[ordered]@{label=$_.label;dialogWidth=$_.screenshots[1].width;dialogHeight=$_.screenshots[1].height}})
$passed=$images.Count -eq 39 -and $states.Count -eq 21 -and $images.Count -eq $expected.savedImages
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');passed=$passed;workerManifestFiles=$manifest.Count;states=$states.Count;images=$images.Count;targetWindowId=$initialId;fixturePid=$launch.pid;fixtureExited=$closed.exited;shutdownCompleted=$true;counters=$counts;events=$events;resizeObservations=$resizes;nativeAcceptancePassed=$false;newLiveRun=$false;imageDetails=$images} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'worker-independent-audit.json') -Encoding utf8
if(!$passed){throw 'Worker state/image count differs'}
Write-Output "Worker raw manifest $($manifest.Count), states $($states.Count), JPEGs $($images.Count), counters and unverified matrix independently checked."
