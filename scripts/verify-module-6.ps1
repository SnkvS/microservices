#requires -Version 7.0
param(
    [ValidateSet('Docker', 'Local')]
    [string]$Mode = 'Docker',
    [string[]]$JavaOptions = @()
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$project = 'microservices-fault-tolerance-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$composeArgs = @('compose', '-p', $project, '-f', (Join-Path $repository 'compose.testing.yaml'))
$override = if ($Mode -eq 'Docker') { 'compose.fault-tolerance-testing.yaml' } else { 'compose.local-testing.yaml' }
$composeArgs += @('-f', (Join-Path $repository $override))
if ($Mode -eq 'Local') { $composeArgs += @('-f',(Join-Path $repository 'compose.fault-tolerance-testing.yaml')) }
$work = Join-Path $repository "output/module-6/$project"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$checks = [System.Collections.Generic.List[string]]::new()

function Invoke-Compose([string[]]$Arguments) {
    $output = & docker @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $($Arguments -join ' ')" }
    return $output
}

function Wait-Until([string]$Description, [scriptblock]$Check, [int]$Seconds = 120) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    $lastFailure = 'not ready'
    do {
        try { & $Check; Write-Host "PASS: $Description"; $checks.Add($Description); return }
        catch { $lastFailure = $_.Exception.Message }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for ${Description}: $lastFailure"
}

function Get-Port([string]$Service, [int]$ContainerPort) {
    $mapping = Invoke-Compose @('port', $Service, "$ContainerPort")
    if (!$mapping) { throw "No port mapping for $Service" }
    return [int](($mapping | Select-Object -First 1).Trim() -split ':')[-1]
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Invoke-Maven([string]$Module, [string[]]$Goals) {
    & docker run --rm -v "${repository}:/workspace" `
        -v "${env:USERPROFILE}/.m2/repository:/root/.m2/repository" -w /workspace `
        maven:3.9-eclipse-temurin-17-alpine mvn -B -f "$Module/pom.xml" @Goals | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Maven failed for $Module" }
}

function Start-Java([string]$Module, [hashtable]$Environment) {
    $directory = Join-Path $work $Module
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $jar = Join-Path $directory 'app.jar'
    # Freeze one build per owned run; restarts must not depend on a concurrently rebuilt target.
    if (!(Test-Path -LiteralPath $jar)) {
        Copy-Item -LiteralPath (Join-Path $repository "$Module/target/$Module-1.0.0.jar") -Destination $jar
    }
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = (Get-Command java -ErrorAction Stop).Source
    $info.WorkingDirectory = $directory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.ArgumentList.Add('-Duser.timezone=UTC')
    foreach ($option in $JavaOptions) { $info.ArgumentList.Add($option) }
    $info.ArgumentList.Add('-jar')
    $info.ArgumentList.Add($jar)
    foreach ($entry in $Environment.GetEnumerator()) { $info.Environment[$entry.Key] = [string]$entry.Value }
    # Let Java write its logs directly to a file, avoiding blocked redirected pipes.
    $info.Environment['LOGGING_FILE_NAME'] = Join-Path $directory 'application.log'
    $process = [System.Diagnostics.Process]::Start($info)
    $processes.Add($process)
    return $process
}


function Get-Recording([long]$Id) {
    $sql = "SELECT row_to_json(r) FROM resources r WHERE id=$Id"
    $row = Invoke-Compose @('exec','-T','resource-db','psql','-U','resources','-d','resources','-At','-c',$sql)
    if (!$row) { return $null }
    return ($row | Out-String | ConvertFrom-Json)
}

function Assert-Object([string]$Bucket, [string]$Key) {
    $head = Invoke-Compose @('exec','-T','localstack','awslocal','s3api','head-object','--bucket',$Bucket,'--key',$Key) |
        Out-String | ConvertFrom-Json
    if ($head.ContentType -ne 'audio/mpeg') { throw 'S3 content type changed' }
    Invoke-Compose @('exec','-T','localstack','awslocal','s3api','get-object','--bucket',$Bucket,'--key',$Key,'/tmp/module6-proof.mp3') | Out-Null
    $hash = Invoke-Compose @('exec','-T','localstack','sha256sum','/tmp/module6-proof.mp3')
    if (($hash -split '\s+')[0] -ne $recordingHash) { throw "S3 bytes differ at $Bucket/$Key" }
}

function Assert-MissingObject([string]$Bucket, [string]$Key) {
    $result = & docker @composeArgs exec -T localstack awslocal s3api head-object --bucket $Bucket --key $Key 2>&1
    if ($LASTEXITCODE -eq 0) { throw "Old object still exists at $Bucket/$Key" }
    if (($result | Out-String) -notmatch '404|Not Found|NoSuchKey') { throw "S3 check failed: $result" }
}

function Stop-OwnedApplication([string]$Module) {
    if ($Mode -eq 'Docker') { Invoke-Compose @('stop',$Module) | Out-Host }
    else {
        $process = $localServices[$Module].Process
        if (!$process.HasExited) { $process.Kill($true); if (!$process.WaitForExit(10000)) { throw "$Module did not stop" } }
    }
}
function Start-OwnedApplication([string]$Module) {
    if ($Mode -eq 'Docker') { Invoke-Compose @('start',$Module) | Out-Host }
    else { $localServices[$Module].Process = Start-Java $Module $localServices[$Module].Environment }
}
function Get-ResourceLogs {
    if ($Mode -eq 'Docker') { return (Invoke-Compose @('logs','--no-color','resource-service') | Out-String) }
    return Get-Content -LiteralPath (Join-Path $work 'resource-service/application.log') -Raw
}
function Wait-Resource {
    Wait-Until 'Resource route is available' {
        $response = Invoke-WebRequest "$gatewayUrl/resources/999999999" -SkipHttpErrorCheck -TimeoutSec 10
        if ([int]$response.StatusCode -ne 404 -or ($response.Content | ConvertFrom-Json).errorMessage -notlike 'Resource with ID=*') {
            throw 'Resource route is not ready'
        }
    } 180
}
function Upload-Recording {
    $created = Invoke-RestMethod "$gatewayUrl/resources" -Method Post -ContentType 'audio/mpeg' -InFile $recording -TimeoutSec 30
    $id = [long]$created.id
    if ($id -le 0) { throw 'Upload did not return a positive ID' }
    return $id
}
function Complete-Recording([long]$Id, $Source) {
    Wait-Until "recording $Id promoted and source cleanup committed" {
        $row = Get-Recording $Id
        if (!$row -or $row.state -ne 'PERMANENT' -or $row.cleanup_source_bucket) { throw 'Promotion is not complete' }
    } 120
    $row = Get-Recording $Id
    Assert-Object $row.storage_bucket $row.object_key
    Assert-MissingObject $Source.storage_bucket $Source.object_key
    Wait-Until "recording $Id has matching song metadata" {
        $song = Invoke-RestMethod "$gatewayUrl/songs/$Id" -TimeoutSec 10
        if ($song.id -ne $Id -or $song.name -ne 'Test Title' -or $song.artist -ne 'Test Artist' -or
            $song.album -ne 'Test Album' -or $song.duration -ne '00:07' -or $song.year -ne '2025') { throw 'Metadata differs' }
    }
    $download = Join-Path $work "download-$Id.mp3"
    Invoke-WebRequest "$gatewayUrl/resources/$Id" -OutFile $download -TimeoutSec 15
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $recordingHash) { throw 'Downloaded bytes differ' }
}
function Delete-Recording([long]$Id) {
    $deleted = Invoke-RestMethod "$gatewayUrl/resources?id=$Id" -Method Delete -TimeoutSec 30
    if (@($deleted.ids).Count -ne 1 -or $deleted.ids[0] -ne $Id) { throw 'Resource deletion failed' }
    if (Get-Recording $Id) { throw 'Deleted Resource row still exists' }
    $song = Invoke-WebRequest "$gatewayUrl/songs/$Id" -SkipHttpErrorCheck -TimeoutSec 10
    if ([int]$song.StatusCode -ne 404) { throw 'Deleted metadata still exists' }
}
function Get-PromotionQueue {
    Invoke-RestMethod "$managementUrl/api/queues/%2F/resource.promotion" -Headers $brokerHeaders -TimeoutSec 10
}

Push-Location $repository
try {
    $recording = Join-Path $repository 'resource-service/src/test/resources/mp3/valid.mp3'
    $recordingHash = (Get-FileHash -LiteralPath $recording -Algorithm SHA256).Hash
    $localServices = @{}
    if ($Mode -eq 'Docker') {
        Invoke-Compose @('up','-d','--build','--wait','--wait-timeout','240') | Out-Host
        $gatewayPort = Get-Port 'api-gateway' 8082
    } else {
        Invoke-Compose @('up','-d','--wait','--wait-timeout','180','resource-db','song-db','storage-db','rabbitmq','localstack') | Out-Host
        foreach ($module in @('eureka-server','storage-service','song-service','resource-service','resource-processor','api-gateway')) {
            Invoke-Maven $module @('-Dmaven.test.skip=true','package')
        }
        $registryPort = Get-FreePort
        $gatewayPort = Get-FreePort
        $common = @{
            EUREKA_SERVER_URL = "http://127.0.0.1:$registryPort/eureka/"; EUREKA_PREFER_IP_ADDRESS = 'true'
            EUREKA_INSTANCE_IP_ADDRESS = '127.0.0.1'
            RESOURCE_DB_URL = "jdbc:postgresql://localhost:$(Get-Port 'resource-db' 5432)/resources"
            RESOURCE_DB_USER = 'resources'; RESOURCE_DB_PASSWORD = 'resources'
            SONG_DB_URL = "jdbc:postgresql://localhost:$(Get-Port 'song-db' 5432)/songs"
            SONG_DB_USER = 'songs'; SONG_DB_PASSWORD = 'songs'
            STORAGE_DB_URL = "jdbc:postgresql://localhost:$(Get-Port 'storage-db' 5432)/storages"
            STORAGE_DB_USER = 'storages'; STORAGE_DB_PASSWORD = 'storages'
            RABBITMQ_HOST = 'localhost'; RABBITMQ_PORT = Get-Port 'rabbitmq' 5672
            RABBITMQ_USER = 'resources'; RABBITMQ_PASSWORD = 'resources'
            RESOURCE_STORAGE_ENDPOINT = "http://localhost:$(Get-Port 'localstack' 4566)"
            RESOURCE_STORAGE_REGION = 'us-east-1'; AWS_ACCESS_KEY_ID = 'test'; AWS_SECRET_ACCESS_KEY = 'test'
            STAGING_STORAGE_BUCKET = 'resources-staging'; STAGING_STORAGE_PATH = '/files'
            PERMANENT_STORAGE_BUCKET = 'resources-permanent'; PERMANENT_STORAGE_PATH = '/files'
            RESOURCE_SERVICE_URL = 'http://resource-service'; SONG_SERVICE_URL = 'http://song-service'
            STORAGE_SERVICE_URL = 'http://storage-service'; STORAGE_BREAKER_OPEN_MILLIS = '2000'
            STORAGE_CONNECT_TIMEOUT = '500'; STORAGE_READ_TIMEOUT = '1000'
        }
        $registryEnv = $common.Clone()
        $registryEnv['EUREKA_SERVER_PORT'] = $registryPort; $registryEnv['EUREKA_SERVER_HOSTNAME'] = 'localhost'
        Start-Java 'eureka-server' $registryEnv | Out-Null
        Wait-Until 'local Eureka is healthy' {
            if ((Invoke-RestMethod "http://127.0.0.1:$registryPort/actuator/health" -TimeoutSec 5).status -ne 'UP') { throw 'Eureka not UP' }
        }
        foreach ($entry in @(@('storage-service','STORAGE_SERVICE_PORT'),@('song-service','SONG_SERVICE_PORT'),
                @('resource-service','RESOURCE_SERVICE_PORT'),@('resource-processor','PROCESSOR_PORT'),@('api-gateway','GATEWAY_PORT'))) {
            $environment = $common.Clone()
            $environment[$entry[1]] = if ($entry[0] -eq 'api-gateway') { $gatewayPort } else { Get-FreePort }
            $localServices[$entry[0]] = @{ Environment=$environment; Process=(Start-Java $entry[0] $environment) }
        }
    }
    $gatewayUrl = "http://127.0.0.1:$gatewayPort"
    $managementUrl = "http://127.0.0.1:$(Get-Port 'rabbitmq' 15672)"
    $brokerHeaders = @{ Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('resources:resources')) }
    Wait-Resource
    Wait-Until 'Storage seeds are routed through Gateway' {
        $definitions = Invoke-RestMethod "$gatewayUrl/storages" -TimeoutSec 10
        if (@($definitions | Where-Object storageType -eq 'STAGING').Count -ne 1 -or
            @($definitions | Where-Object storageType -eq 'PERMANENT').Count -ne 1) { throw 'Missing default definitions' }
    } 180
    $definitions = Invoke-RestMethod "$gatewayUrl/storages" -TimeoutSec 10
    $seedId = ($definitions | Where-Object storageType -eq 'STAGING').id
    $customBucket = 'module6-extra-'+[Guid]::NewGuid().ToString('N')
    Invoke-Compose @('exec','-T','localstack','awslocal','s3api','create-bucket','--bucket',$customBucket) | Out-Null
    $extra = Invoke-RestMethod "$gatewayUrl/storages" -Method Post -ContentType 'application/json' -Body (
        @{storageType='STAGING';bucket=$customBucket;path='/custom/'} | ConvertTo-Json)
    if ($extra.id -le $seedId) { throw 'Additional definition did not receive a later ID' }
    $accepted = (@('9223372036854775807')*10) -join ','
    $rejected = $accepted.Substring(1)+',9'
    if ($accepted.Length -ne 199 -or $rejected.Length -ne 200) { throw 'CSV fixtures are invalid' }
    $empty = Invoke-RestMethod "$gatewayUrl/storages?id=$accepted" -Method Delete -TimeoutSec 10
    if ($empty.Count -ne 0) { throw 'Missing IDs were not ignored' }
    $bad = Invoke-WebRequest "$gatewayUrl/storages?id=$rejected" -Method Delete -SkipHttpErrorCheck -TimeoutSec 10
    if ([int]$bad.StatusCode -ne 400) { throw '200 character CSV was accepted' }
    $checks.Add('Storage CRUD, additional entries and strict CSV boundary')

    Stop-OwnedApplication 'resource-processor'
    $id = Upload-Recording
    $source = Get-Recording $id
    if ($source.state -ne 'STAGING' -or $source.storage_bucket -ne 'resources-staging' -or $source.object_key -notlike 'files/*.mp3') {
        throw 'Upload did not use the lowest-ID STAGING definition'
    }
    Assert-Object $source.storage_bucket $source.object_key
    $checks.Add('Lowest-ID selection and STAGING bytes persisted before processing')
    Start-OwnedApplication 'resource-processor'
    Complete-Recording $id $source

    Wait-Until 'initial promotion acknowledgement is observable' {
        if ((Get-PromotionQueue).message_stats.ack -lt 1) { throw 'Initial acknowledgement not observable yet' }
    } 30
    $before = (Get-PromotionQueue).message_stats.ack
    $publication = @{properties=@{delivery_mode=2;content_type='application/json'};routing_key='resource.processed';
        payload=(@{resourceId=$id}|ConvertTo-Json -Compress);payload_encoding='string'} | ConvertTo-Json
    $routed = Invoke-RestMethod "$managementUrl/api/exchanges/%2F/resources/publish" -Method Post -Headers $brokerHeaders -ContentType 'application/json' -Body $publication
    if (!$routed.routed) { throw 'Duplicate notification was not routed' }
    Wait-Until 'duplicate completion is acknowledged' {
        if ((Get-PromotionQueue).message_stats.ack -le $before) { throw 'Duplicate is not acknowledged yet' }
    } 30
    Complete-Recording $id $source
    Delete-Recording $id
    $checks.Add('Confirmed completion, exact bytes, source removal, duplicate delivery and deletion')

    Stop-OwnedApplication 'storage-service'
    for ($i=0; $i -lt 4; $i++) {
        Stop-OwnedApplication 'resource-processor'
        $id = Upload-Recording; $source = Get-Recording $id
        if ($source.storage_bucket -ne 'resources-staging') { throw 'Outage did not use configured STAGING fallback' }
        Start-OwnedApplication 'resource-processor'
        Complete-Recording $id $source; Delete-Recording $id
    }
    Wait-Until 'actual Storage outage opens the circuit breaker' {
        if ((Get-ResourceLogs) -notmatch 'Storage circuit breaker CLOSED -> OPEN') { throw 'No OPEN transition' }
    }
    $checks.Add('Complete recording lifecycle continues with Storage stopped')
    Stop-OwnedApplication 'resource-processor'
    Stop-OwnedApplication 'resource-service'
    Start-OwnedApplication 'resource-service'
    Wait-Resource
    $id = Upload-Recording; $source = Get-Recording $id
    if ($source.state -ne 'STAGING' -or $source.storage_bucket -ne 'resources-staging') { throw 'Cold-start fallback failed' }
    Start-OwnedApplication 'resource-processor'
    Complete-Recording $id $source; Delete-Recording $id
    $checks.Add('Resource cold-starts and processes recordings with Storage absent')

    Start-OwnedApplication 'storage-service'
    Wait-Until 'Storage recovers through Gateway' {
        if ((Invoke-RestMethod "$gatewayUrl/storages" -TimeoutSec 10).Count -lt 3) { throw 'Catalog not ready' }
    }
    $deleted = Invoke-RestMethod "$gatewayUrl/storages?id=$seedId,$seedId,9223372036854775807" -Method Delete
    if ($deleted.Count -ne 1 -or $deleted[0] -ne $seedId) { throw 'Storage deletion response differs' }
    Stop-OwnedApplication 'resource-processor'
    $probes = [System.Collections.Generic.List[long]]::new()
    Wait-Until 'live catalog selection resumes after HALF_OPEN probes' {
        $probe = Upload-Recording; $probes.Add($probe); $row = Get-Recording $probe
        if ($row.storage_bucket -ne $customBucket -or $row.object_key -notlike 'custom/*.mp3') { throw 'Fallback still active' }
        if ((Get-ResourceLogs) -notmatch 'Storage circuit breaker HALF_OPEN -> CLOSED') { throw 'Recovery not yet CLOSED' }
    } 60
    foreach ($probe in $probes) { Delete-Recording $probe }
    Start-OwnedApplication 'resource-processor'
    $checks.Add('HALF_OPEN recovery closes the breaker and uses changed live catalog data')
    Get-ResourceLogs | Set-Content -LiteralPath (Join-Path $work 'resource-service.log')
    @{mode=$Mode;project=$project;completedUtc=[DateTime]::UtcNow.ToString('o');checks=@($checks)} |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $work 'results.json')
    Write-Host "Module 6 $Mode verification passed. Evidence: $work"
}
catch {
    Invoke-Compose @('logs','--no-color','--tail','60') | Out-Host
    throw
}
finally {
    try {
        foreach ($process in $processes) {
            try { if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit(10000) | Out-Null } }
            catch { Write-Warning "Owned Java process cleanup failed: $($_.Exception.Message)" }
            finally { $process.Dispose() }
        }
    } finally {
        try { Invoke-Compose @('down','--volumes','--remove-orphans') | Out-Host }
        finally { Pop-Location }
    }
}
