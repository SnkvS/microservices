param(
    [ValidateSet('All', 'Unit', 'Integration', 'Contracts', 'Component', 'E2E')]
    [string]$Suite = 'All'
)

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$project = 'microservices-testing-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$compose = Join-Path $repository 'compose.testing.yaml'
$mavenCache = Join-Path $env:USERPROFILE '.m2\repository'
New-Item -ItemType Directory -Force -Path $mavenCache | Out-Null

function Invoke-Maven([string]$Module, [string[]]$Goals) {
    $runnerArgs = @('run', '--rm', '-v', "${repository}:/workspace",
        '-v', "${mavenCache}:/root/.m2/repository", '-v', '/var/run/docker.sock:/var/run/docker.sock',
        '-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal', '-w', '/workspace')
    $runnerArgs += @('maven:3.9-eclipse-temurin-17-alpine', 'mvn', '-B', '-f', "$Module/pom.xml", 'clean')
    & docker @runnerArgs @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven failed for $Module ($Suite), exit $LASTEXITCODE" }
}

function Install-Contracts {
    Invoke-Maven 'song-service' @('-Pcontracts', 'install')
    Invoke-Maven 'resource-service' @('-Pcontracts', 'install')
}

Push-Location $repository
try {
    switch ($Suite) {
        'All' {
            Invoke-Maven 'song-service' @('-Pintegration,contracts', 'install')
            Invoke-Maven 'resource-service' @('-Pintegration,contracts', 'install')
            Invoke-Maven 'resource-processor' @('-Pintegration,contracts,component', 'verify')
            Invoke-Maven 'api-gateway' @('test')
            Invoke-Maven 'eureka-server' @('test')
        }
        'Unit' {
            foreach ($module in @('song-service', 'resource-service', 'resource-processor', 'api-gateway')) {
                Invoke-Maven $module @('test')
            }
        }
        'Integration' {
            foreach ($module in @('song-service', 'resource-service', 'resource-processor')) {
                Invoke-Maven $module @('-Pintegration', 'verify')
            }
        }
        'Contracts' {
            Install-Contracts
            Invoke-Maven 'resource-processor' @('-Pcontracts', 'verify')
        }
        'Component' {
            Install-Contracts
            Invoke-Maven 'resource-processor' @('-Pcomponent', 'verify')
        }
    }

    if ($Suite -in @('All', 'E2E')) {
        try {
            & docker compose -p $project -f $compose up --build -d
            if ($LASTEXITCODE -ne 0) { throw 'Could not start the isolated E2E stack' }
            $mapping = & docker compose -p $project -f $compose port api-gateway 8082
            if ($LASTEXITCODE -ne 0 -or !$mapping) { throw 'Could not find the E2E gateway port' }
            $port = ($mapping.Trim() -split ':')[-1]
            Invoke-Maven 'e2e-tests' @('-Pe2e', "-De2e.base-url=http://host.docker.internal:$port", 'verify')
        }
        catch {
            & docker compose -p $project -f $compose logs --no-color --tail 80
            throw
        }
        finally {
            # This generated project owns only disposable test containers/networks.
            & docker compose -p $project -f $compose down --volumes --remove-orphans
            if ($LASTEXITCODE -ne 0) { Write-Warning "Cleanup failed for test project $project" }
        }
    }
}
finally {
    Pop-Location
}
