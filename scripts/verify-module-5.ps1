#requires -Version 7.0
param(
    [ValidateSet('Docker', 'Local')]
    [string]$Mode = 'Docker',
    [string[]]$JavaOptions = @()
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$project = 'microservices-discovery-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$composeArgs = @('compose', '-p', $project, '-f', (Join-Path $repository 'compose.testing.yaml'))
$override = if ($Mode -eq 'Docker') { 'compose.discovery-testing.yaml' } else { 'compose.local-testing.yaml' }
$composeArgs += @('-f', (Join-Path $repository $override))
$work = Join-Path $repository "output/module-5/$project"
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
    Copy-Item -LiteralPath (Join-Path $repository "$Module/target/$Module-1.0.0.jar") -Destination $jar
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

function Get-Instances([string]$Application) {
    $registry = Invoke-RestMethod "$registryUrl/apps/$Application" -Headers @{ Accept = 'application/json' } `
        -SkipHttpErrorCheck -TimeoutSec 10
    return @($registry.application.instance | Where-Object { $_ -and $_.status -eq 'UP' })
}

function Assert-Api([string]$Path, [int]$Status, [string]$Message) {
    $response = Invoke-WebRequest "$gatewayUrl$Path" -SkipHttpErrorCheck -TimeoutSec 15
    if ([int]$response.StatusCode -ne $Status) { throw "$Path returned $($response.StatusCode), expected $Status" }
    $body = $response.Content | ConvertFrom-Json
    if ($body.errorCode -cne "$Status" -or $body.errorMessage -cne $Message) {
        throw "$Path returned unexpected error: $($response.Content)"
    }
    if (@($body.PSObject.Properties).Count -ne 2) { throw "$Path leaked additional error fields" }
    if ($response.Headers['Content-Type'] -notlike 'application/json*') { throw "$Path did not return JSON" }
}

function Assert-Downstream {
    Assert-Api '/songs/999999999' 404 'Song metadata for ID=999999999 not found'
}

function Assert-EmptyDiscovery([string]$GatewayLog) {
    if (@(Get-Instances 'SONG-SERVICE').Count -ne 0) { throw 'Song registration remains' }
    Assert-Api '/songs/999999999' 503 'Service unavailable'
    # A stale address also produces 503. Require the load balancer's own empty-list
    # evidence from this phase, after the registry entries were removed.
    if ($GatewayLog -notmatch 'No servers available for service: song-service') {
        throw 'Gateway discovery client still has not observed an empty Song instance list'
    }
}

Push-Location $repository
try {
    if ($Mode -eq 'Docker') {
        Invoke-Compose @('up', '-d', '--build', '--wait', '--wait-timeout', '240', '--scale', 'song-service=2') | Out-Host
        $gatewayPort = Get-Port 'api-gateway' 8082
        $registryPort = Get-Port 'eureka-server' 8761
        $expectedSongs = 2
    } else {
        Invoke-Compose @('up', '-d', '--wait', '--wait-timeout', '180', 'resource-db', 'song-db', 'rabbitmq', 'localstack') | Out-Host
        foreach ($module in @('eureka-server', 'song-service', 'resource-service', 'resource-processor', 'api-gateway')) {
            Invoke-Maven $module @('-Dmaven.test.skip=true', 'package')
        }
        $registryPort = Get-FreePort
        $gatewayPort = Get-FreePort
        $common = @{
            EUREKA_SERVER_URL = "http://127.0.0.1:$registryPort/eureka/"
            EUREKA_PREFER_IP_ADDRESS = 'true'
            EUREKA_INSTANCE_IP_ADDRESS = '127.0.0.1'
            RESOURCE_DB_URL = "jdbc:postgresql://localhost:$(Get-Port 'resource-db' 5432)/resources"
            RESOURCE_DB_USER = 'resources'; RESOURCE_DB_PASSWORD = 'resources'
            SONG_DB_URL = "jdbc:postgresql://localhost:$(Get-Port 'song-db' 5432)/songs"
            SONG_DB_USER = 'songs'; SONG_DB_PASSWORD = 'songs'
            RABBITMQ_HOST = 'localhost'; RABBITMQ_PORT = Get-Port 'rabbitmq' 5672
            RABBITMQ_USER = 'resources'; RABBITMQ_PASSWORD = 'resources'
            RESOURCE_STORAGE_ENDPOINT = "http://localhost:$(Get-Port 'localstack' 4566)"
            RESOURCE_STORAGE_REGION = 'us-east-1'; RESOURCE_STORAGE_BUCKET = 'resources'
            AWS_ACCESS_KEY_ID = 'test'; AWS_SECRET_ACCESS_KEY = 'test'
            RESOURCE_SERVICE_URL = 'http://resource-service'; SONG_SERVICE_URL = 'http://song-service'
        }
        $registryEnv = $common.Clone()
        $registryEnv['EUREKA_SERVER_PORT'] = $registryPort
        $registryEnv['EUREKA_SERVER_HOSTNAME'] = 'localhost'
        Start-Java 'eureka-server' $registryEnv | Out-Null
        Wait-Until 'local Eureka is healthy' {
            $health = Invoke-RestMethod "http://127.0.0.1:$registryPort/actuator/health" -TimeoutSec 5
            if ($health.status -ne 'UP') { throw 'Eureka is not UP' }
        }
        foreach ($entry in @(
            @('song-service', 'SONG_SERVICE_PORT'), @('resource-service', 'RESOURCE_SERVICE_PORT'),
            @('resource-processor', 'PROCESSOR_PORT'), @('api-gateway', 'GATEWAY_PORT')
        )) {
            $environment = $common.Clone()
            $environment[$entry[1]] = if ($entry[0] -eq 'api-gateway') { $gatewayPort } else { Get-FreePort }
            $started = Start-Java $entry[0] $environment
            if ($entry[0] -eq 'song-service') { $localSong = $started }
        }
        $expectedSongs = 1
    }
    $gatewayUrl = "http://127.0.0.1:$gatewayPort"
    $registryUrl = "http://127.0.0.1:$registryPort/eureka"
    Wait-Until "Eureka lists Gateway, Resource and $expectedSongs distinct Song instance(s)" {
        $songs = @(Get-Instances 'SONG-SERVICE')
        if ($songs.Count -ne $expectedSongs -or @($songs.instanceId | Select-Object -Unique).Count -ne $expectedSongs) {
            throw "Found $($songs.Count) Song instances"
        }
        if (@(Get-Instances 'API-GATEWAY').Count -ne 1 -or @(Get-Instances 'RESOURCE-SERVICE').Count -ne 1) {
            throw 'Gateway or Resource registration missing'
        }
    } 180
    # Use only successful downstream responses as readiness, not Gateway-owned 404s.
    Wait-Until 'both business routes are ready through the Gateway' {
        Assert-Downstream
        Assert-Api '/resources/999999999' 404 'Resource with ID=999999999 not found'
    }
    Assert-Api '/undefined-module-5-route' 404 'Route not found'
    $checks.Add('unknown Gateway route returns the friendly 404 envelope')
    $health = Invoke-RestMethod "$gatewayUrl/actuator/health" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw 'Gateway health is not UP' }

    if ($Mode -eq 'Docker') {
        $songs = @(Invoke-Compose @('ps', '-q', 'song-service'))
        if ($songs.Count -ne 2) { throw 'Expected two real Song containers' }
        $marker = [Guid]::NewGuid().ToString('N')
        for ($i = 0; $i -lt 8; $i++) {
            Assert-Api "/songs/999999999?module5Probe=$marker" 404 'Song metadata for ID=999999999 not found'
        }
        Wait-Until 'Gateway requests reached both real Song containers' {
            foreach ($container in $songs) {
                $access = & docker exec $container sh -c 'cat /tmp/access-logs/*'
                if ($LASTEXITCODE -ne 0 -or !($access -match $marker)) { throw "No matching access log in $container" }
            }
        } 30
    }

    Invoke-Maven 'e2e-tests' @('-Pe2e', "-De2e.base-url=http://host.docker.internal:$gatewayPort", 'verify')
    $checks.Add('existing Cucumber recording-lifecycle E2E suite passed through the Gateway')

    if ($Mode -eq 'Docker') {
        & docker stop --timeout 20 $songs[0] | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop owned Song replica' }
        Wait-Until 'one Song replica deregisters and the remaining replica serves requests' {
            if (@(Get-Instances 'SONG-SERVICE').Count -ne 1) { throw 'Registry has not converged' }
            for ($i = 0; $i -lt 4; $i++) { Assert-Downstream }
        }
        & docker kill $songs[1] | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Could not interrupt owned Song replica' }
    } else {
        $localSong.Kill($true)
        $localSong.WaitForExit(10000) | Out-Null
    }
    Wait-Until 'stale registered Song address returns a friendly 503' {
        if (@(Get-Instances 'SONG-SERVICE').Count -eq 0) { throw 'Stale registration already disappeared' }
        Assert-Api '/songs/999999999' 503 'Service unavailable'
    }
    $emptyPhaseStart = [DateTime]::UtcNow.ToString('o')
    $gatewayLogPath = Join-Path $work 'api-gateway/application.log'
    $emptyPhaseLogLength = if ($Mode -eq 'Local') { (Get-Content -LiteralPath $gatewayLogPath -Raw).Length } else { 0 }
    foreach ($instance in @(Get-Instances 'SONG-SERVICE')) {
        $id = [Uri]::EscapeDataString($instance.instanceId)
        Invoke-WebRequest "$registryUrl/apps/SONG-SERVICE/$id" -Method Delete -TimeoutSec 10 | Out-Null
    }
    if ($Mode -eq 'Docker') { $gatewayContainer = Invoke-Compose @('ps', '-q', 'api-gateway') }
    Wait-Until 'no registered Song instance returns a friendly 503' {
        $gatewayLog = if ($Mode -eq 'Docker') {
            $logOutput = & docker logs --since $emptyPhaseStart $gatewayContainer 2>&1
            if ($LASTEXITCODE -ne 0) { throw 'Could not read Gateway discovery logs' }
            $logOutput | Out-String
        } else {
            (Get-Content -LiteralPath $gatewayLogPath -Raw).Substring($emptyPhaseLogLength)
        }
        Assert-EmptyDiscovery $gatewayLog
    }
    if ($Mode -eq 'Docker') {
        & docker start @songs | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart owned Song replicas' }
    } else {
        $songEnv = $common.Clone()
        $songEnv['SONG_SERVICE_PORT'] = Get-FreePort
        Start-Java 'song-service' $songEnv | Out-Null
    }
    Wait-Until 'Song registration and Gateway routing recover' {
        if (@(Get-Instances 'SONG-SERVICE').Count -ne $expectedSongs) { throw 'Song registrations have not recovered' }
        for ($i = 0; $i -lt 4; $i++) { Assert-Downstream }
    } 180
    @{ mode = $Mode; project = $project; completedUtc = [DateTime]::UtcNow.ToString('o'); checks = @($checks) } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $work 'results.json')
    Write-Host "Module 5 $Mode verification passed. Evidence: $work"
}
catch {
    Invoke-Compose @('logs', '--no-color', '--tail', '60') | Out-Host
    throw
}
finally {
    try {
        foreach ($process in $processes) {
            try {
                if (!$process.HasExited) {
                    $process.Kill($true)
                    if (!$process.WaitForExit(10000)) { throw 'Owned Java process did not exit' }
                }
            }
            catch { Write-Warning "Owned Java process cleanup failed: $($_.Exception.Message)" }
            finally {
                try { $process.Dispose() }
                catch { Write-Warning "Java process handle cleanup failed: $($_.Exception.Message)" }
            }
        }
    }
    finally {
        try { Invoke-Compose @('down', '--volumes', '--remove-orphans') | Out-Host }
        finally { Pop-Location }
    }
}
