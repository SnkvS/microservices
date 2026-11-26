param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
$project='microservices-monitoring-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$composeArgs=@('compose','-p',$project,'-f',(Join-Path $repository 'compose.testing.yaml'),'-f',(Join-Path $repository 'compose.monitoring-testing.yaml'))
$work=Join-Path $repository "output/module-7/$project"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$checks=[Collections.Generic.List[string]]::new()
$ids=[Collections.Generic.List[long]]::new()
$traceIds=[Collections.Generic.List[string]]::new()
$traceResources=[Collections.Generic.Dictionary[string,long]]::new()
function Compose([string[]]$Arguments) {
    $result=& docker @composeArgs @Arguments
    if($LASTEXITCODE -ne 0) { throw "Compose failed: $($Arguments -join ' ')" }
    return $result
}
function Port([string]$Service,[int]$Port) {
    $mapping=Compose @('port',$Service,"$Port")
    return [int](($mapping | Select-Object -First 1).Trim() -split ':')[-1]
}
function Await([string]$Description,[scriptblock]$Check,[int]$Seconds=180) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds);$lastFailure='not ready'
    do {
        try { & $Check; $checks.Add($Description); Write-Host "PASS: $Description"; return }
        catch { $lastFailure=$_.Exception.Message }
        Start-Sleep -Milliseconds 1000
    } while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: ${Description}: $lastFailure"
}
function Search([string]$Trace) {
    $query=@{size=200;query=@{term=@{'traceId.keyword'=$Trace}}} | ConvertTo-Json -Depth 10
    return Invoke-RestMethod "$elastic/microservices-logs/_search" -Method Post -ContentType application/json -Body $query -TimeoutSec 15
}
function PromQuery([string]$Expression) {
    $response=Invoke-RestMethod "$prometheus/api/v1/query?query=$([Uri]::EscapeDataString($Expression))" -TimeoutSec 15
    if($response.status -ne 'success') { throw 'Prometheus query failed' }
    return $response.data.result
}
function Upload([string]$Trace) {
    $headers=if($Trace) {@{'X-Trace-ID'=$Trace}} else {@{}}
    $response=Invoke-WebRequest "$gateway/resources" -Method Post -Headers $headers -ContentType audio/mpeg -InFile $recording -TimeoutSec 60
    $id=[long](($response.Content | ConvertFrom-Json).id)
    $ids.Add($id)
    $returned=[string]($response.Headers['X-Trace-ID'] | Select-Object -First 1)
    if($Trace -and $returned -ne $Trace) { throw 'Upload changed a valid trace ID' }
    if(!$Trace -and $returned -notmatch '^[0-9a-f]{32}$') { throw 'Upload did not return a generated trace ID' }
    $traceIds.Add($returned)
    $traceResources[$returned]=$id
    Await "recording $id completes promotion with trace $returned" {
        $row=Compose @('exec','-T','resource-db','psql','-U','resources','-d','resources','-At','-c',"SELECT state || ':' || (cleanup_source_key IS NULL)::text FROM resources WHERE id=$id")
        if(($row | Out-String).Trim() -ne 'PERMANENT:true') { throw 'Promotion incomplete' }
        $song=Invoke-RestMethod "$gateway/songs/$id" -TimeoutSec 10
        if([long]$song.id -ne $id) { throw 'Missing song metadata' }
    }
    $download=Join-Path $work "download-$id.mp3"
    Invoke-WebRequest "$gateway/resources/$id" -OutFile $download -TimeoutSec 30
    if((Get-FileHash $download -Algorithm SHA256).Hash -ne $recordingHash) { throw 'Download bytes changed' }
    return $returned
}
function AssertTrace([string]$Trace) {
    Await "one trace finds Gateway, Resource, Processor, Song and Storage logs: $Trace" {
        $result=Search $Trace
        $found=@($result.hits.hits._source.service | Sort-Object -Unique)
        foreach($name in @('api-gateway','resource-service','resource-processor','song-service','storage-service')) {
            if($name -notin $found) { throw "Missing $name logs" }
        }
        $resourceId=$traceResources[$Trace]
        if(!@($result.hits.hits._source | Where-Object { $_.service -eq 'resource-service' -and $_.message -eq "Completed resource event $resourceId" }).Count) {
            throw 'Completion-event hop has not logged under the original upload trace'
        }
        $result | ConvertTo-Json -Depth 40 | Set-Content (Join-Path $work "trace-$Trace.json")
    }
}
try {
    Push-Location $repository
    $recording=Join-Path $repository 'resource-service/src/test/resources/mp3/valid.mp3'
    $recordingHash=(Get-FileHash $recording -Algorithm SHA256).Hash
    $up=@('up','-d','--scale','song-service=2')
    if(!$SkipBuild) { $up+='--build' }
    Compose $up | Out-Host
    $gateway="http://127.0.0.1:$(Port 'api-gateway' 8082)"
    $elastic="http://127.0.0.1:$(Port 'elasticsearch' 9200)"
    $prometheus="http://127.0.0.1:$(Port 'prometheus' 9090)"
    $grafana="http://127.0.0.1:$(Port 'grafana' 3000)"
    $grafanaHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('verification:module7-verification'))}
    Await 'Gateway routes to the seeded Storage catalog' { $catalog=Invoke-RestMethod "$gateway/storages" -TimeoutSec 10; if($catalog.Count -lt 2) { throw 'Catalog unavailable' } }
    Await 'all six services and both Song replicas expose real Prometheus targets' {
        $targets=(Invoke-RestMethod "$prometheus/api/v1/targets" -TimeoutSec 10).data.activeTargets
        $healthy=@($targets | Where-Object health -eq 'up')
        if(@($healthy.labels.job | Sort-Object -Unique).Count -ne 6 -or @($healthy | Where-Object {$_.labels.job -eq 'song-service'}).Count -ne 2) { throw 'Missing healthy targets/replicas' }
    }
    Await 'JVM memory and thread series are scraped from all six services' {
        if(@(PromQuery 'sum by(job) (jvm_memory_used_bytes)').Count -ne 6 -or @(PromQuery 'jvm_threads_live_threads').Count -lt 7) { throw 'Missing JVM series' }
    }
    Await 'Gateway exposes request counts and latency histogram buckets' {
        $metrics=(Invoke-WebRequest "$gateway/actuator/prometheus" -TimeoutSec 10).Content
        if($metrics -notmatch 'spring_cloud_gateway_requests_seconds_count' -or $metrics -notmatch 'spring_cloud_gateway_requests_seconds_bucket') { throw 'Gateway metrics missing' }
        if(@(PromQuery 'spring_cloud_gateway_requests_seconds_count').Count -eq 0) { throw 'Gateway not scraped' }
    }
    Await 'each Song replica writes to its own structured log file' {
        $files=Compose @('exec','-T','api-gateway','sh','-c','ls /var/log/services/song-service/*.json | wc -l')
        if([int](($files | Out-String).Trim()) -ne 2) { throw 'Replica log files collide or are missing' }
    }
    Await 'Grafana dashboards and both data sources are provisioned' {
        foreach($uid in @('microservices-overview','upload-trace')) {
            if(!(Invoke-RestMethod "$grafana/api/dashboards/uid/$uid" -Headers $grafanaHeaders -TimeoutSec 10).dashboard.panels) { throw "Missing dashboard $uid" }
        }
        foreach($uid in @('prometheus','elasticsearch')) {
            if((Invoke-RestMethod "$grafana/api/datasources/uid/$uid" -Headers $grafanaHeaders -TimeoutSec 10).uid -ne $uid) { throw 'Missing datasource' }
        }
    }
    $known=Upload ('upload-'+[Guid]::NewGuid().ToString('N'))
    AssertTrace $known
    $generated=Upload ''
    AssertTrace $generated
    Await 'all six applications have indexed structured logs' {
        $query=@{size=0;aggs=@{services=@{terms=@{field='service.keyword';size=10}}}} | ConvertTo-Json -Depth 10
        $result=Invoke-RestMethod "$elastic/microservices-logs/_search" -Method Post -ContentType application/json -Body $query -TimeoutSec 10
        if(@($result.aggregations.services.buckets).Count -ne 6) { throw 'Missing application logs' }
    }
    Await 'Grafana queries real logs by trace ID through its Elasticsearch data source' {
        $query=@{from=([DateTimeOffset]::UtcNow.AddMinutes(-30).ToUnixTimeMilliseconds()).ToString();to=([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).ToString();queries=@(@{refId='A';datasource=@{uid='elasticsearch';type='elasticsearch'};query="traceId.keyword:`"$known`"";metrics=@(@{id='1';type='logs'});bucketAggs=@();intervalMs=1000;maxDataPoints=1000})} | ConvertTo-Json -Depth 12
        $result=Invoke-RestMethod "$grafana/api/ds/query" -Headers $grafanaHeaders -Method Post -ContentType application/json -Body $query -TimeoutSec 20
        if($result.results.A.error -or ($result | ConvertTo-Json -Depth 40) -notmatch $known) { throw 'Grafana logs query returned no trace' }
    }
    Await 'Grafana queries actual JVM metrics through its Prometheus data source' {
        $query=@{from=([DateTimeOffset]::UtcNow.AddMinutes(-15).ToUnixTimeMilliseconds()).ToString();to=([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()).ToString();queries=@(@{refId='A';datasource=@{uid='prometheus';type='prometheus'};expr='sum(jvm_threads_live_threads)';instant=$true;range=$false;intervalMs=5000;maxDataPoints=1000})} | ConvertTo-Json -Depth 12
        $result=Invoke-RestMethod "$grafana/api/ds/query" -Headers $grafanaHeaders -Method Post -ContentType application/json -Body $query -TimeoutSec 20
        if($result.results.A.error -or !$result.results.A.frames -or $result.results.A.frames[0].data.values[1][0] -le 0) { throw 'Grafana metrics query has no positive value' }
    }
    $http=[Net.Http.HttpClient]::new()
    $http.Timeout=[TimeSpan]::FromSeconds(30)
    $parallel=@()
    try {
        foreach($i in 1..8) {
            $trace='parallel-'+[Guid]::NewGuid().ToString('N')
            $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get,"$gateway/storages")
            $request.Headers.Add('X-Trace-ID',$trace)
            $parallel+=@{Trace=$trace;Request=$request;Task=$http.SendAsync($request)}
        }
        foreach($entry in $parallel) {
            $response=$entry.Task.GetAwaiter().GetResult()
            try {
                if(!$response.IsSuccessStatusCode -or @($response.Headers.GetValues('X-Trace-ID'))[0] -ne $entry.Trace) { throw 'Concurrent response changed its trace ID' }
            } finally { $response.Dispose(); $entry.Request.Dispose() }
        }
    } finally { $http.Dispose() }
    Await 'concurrent HTTP requests retain distinct IDs in Gateway and Storage logs' {
        foreach($entry in $parallel) {
            $found=@((Search $entry.Trace).hits.hits._source.service | Sort-Object -Unique)
            if('api-gateway' -notin $found -or 'storage-service' -notin $found) { throw 'Concurrent trace not propagated' }
        }
    }
    $knownCount=(Search $known).hits.total.value
    Compose @('stop','fluent-bit','elasticsearch') | Out-Host
    $offline=Upload ('offline-'+[Guid]::NewGuid().ToString('N'))
    $checks.Add('recording lifecycle and exact bytes succeed with collector and log storage stopped')
    # Force actual Logback size rollover while the collector cannot read new records.
    foreach($i in 1..250) {
        Invoke-RestMethod "$gateway/storages" -Headers @{'X-Trace-ID'="rollover-$i"} -TimeoutSec 10 | Out-Null
    }
    $archives=Compose @('exec','-T','api-gateway','sh','-c','ls /var/log/services/api-gateway/*.json.*')
    $archives | Set-Content (Join-Path $work 'rolled-files.txt')
    if(!($archives | Out-String).Trim()) { throw 'Gateway log did not rotate during collector outage' }
    $archivedTrace=Compose @('exec','-T','api-gateway','sh','-c',"grep -l -- '$offline' /var/log/services/api-gateway/*.rotated")
    if(!($archivedTrace | Out-String).Trim()) { throw 'Offline upload trace was not retained in a rotated file' }
    $archivedTrace | Set-Content (Join-Path $work 'offline-trace-archive.txt')
    $checks.Add('real Gateway traffic rotates application files while collector is stopped')
    Compose @('rm','-f','elasticsearch') | Out-Host
    Compose @('up','-d','elasticsearch') | Out-Host
    Compose @('up','-d','--force-recreate','fluent-bit') | Out-Host
    # Replacing a disposable container assigns a new random host port.
    $elastic="http://127.0.0.1:$(Port 'elasticsearch' 9200)"
    AssertTrace $known
    $checks.Add('indexed trace persists after Elasticsearch container replacement on the same volume')
    AssertTrace $offline
    $checks.Add('collector recovers stored offsets and catches up logs written during outage')
    # The same inode must retain its offset when renamed and rediscovered.
    Await 'rotation recovery indexes one upload boundary and does not replay consumed records' {
        if((Search $known).hits.total.value -ne $knownCount) { throw 'Previously consumed trace records were replayed' }
        $boundaries=@((Search $offline).hits.hits._source | Where-Object { $_.service -eq 'api-gateway' -and $_.message -like 'Gateway HTTP POST /resources status=*' })
        if($boundaries.Count -ne 1) { throw "Expected one offline upload boundary, found $($boundaries.Count)" }
    }

    foreach($id in $ids) { Invoke-RestMethod "$gateway/resources?id=$id" -Method Delete -TimeoutSec 30 | Out-Null }
    @{project=$project;completedUtc=[DateTime]::UtcNow.ToString('o');checks=@($checks);traceIds=@($traceIds);parallelTraceIds=@($parallel.Trace);recordingSha256=$recordingHash} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $work 'results.json')
    Write-Host "Module 7 verification passed. Evidence: $work"
}
finally {
    try { Compose @('logs','--no-color') | Set-Content (Join-Path $work 'services.log') }
    finally { & docker @composeArgs down --volumes --remove-orphans | Out-Host; Pop-Location }
}
