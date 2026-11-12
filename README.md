# Recording microservices

Java 17 / Spring Boot services for storing MP3 recordings and asynchronously
extracting song metadata. Eureka provides discovery and the Gateway exposes
the Resource and Song APIs. RabbitMQ transports upload notifications; PostgreSQL
stores records and LocalStack emulates S3.

## Run everything in Docker

Prerequisites: Docker Desktop with Linux containers and Docker Compose v2.

```powershell
docker compose up -d --build
docker compose ps
```

No account, token, manual database setup or Spring profile is needed. The first
build downloads Maven dependencies and images. For a command that also waits
until every container is healthy, use `docker compose up -d --build --wait`.

| Application | Default address | Health endpoint |
| --- | --- | --- |
| API Gateway | http://localhost:8082 | `/actuator/health` |
| Eureka | http://localhost:8761 | `/actuator/health` |

The Processor's HTTP server provides health reporting; MP3 processing still runs
through RabbitMQ. Only the health Actuator endpoint is exposed. Container health
checks verify application health, database/broker availability and the initialized
S3 bucket. Dependent applications wait for healthy dependencies before starting.
Eureka registration propagates asynchronously; the API tests wait for both Gateway
routes before beginning the recording lifecycle.

Use the Gateway for all business API requests: `/resources` and `/songs`. Backend
application ports stay inside the Docker network. To run two Song instances:

```powershell
docker compose up -d --build --scale song-service=2
```

For single-instance debugging with backend host ports `8080`, `8081` and `8083`,
select the development override explicitly. Omit it when scaling:

```powershell
docker compose -f compose.yaml -f compose.development.yaml up -d --build
```

Eureka's dashboard and infrastructure host ports remain available for development.

PostgreSQL 17 Alpine loads the schemas from `init-scripts/*-db` through
`/docker-entrypoint-initdb.d`. LocalStack enables only S3 and creates the bucket
automatically. Its fixed Community version (`4.14.0`) supports token-free local
development; it is deliberately pinned rather than following authenticated newer
releases. See [LocalStack's image change](https://blog.localstack.cloud/the-road-ahead-for-localstack/).

Optional configuration overrides belong in an untracked `.env` file. Copy
`.env.example` to `.env` if you want to customize the defaults; Compose reads it
automatically. Keep real credentials out of version control. Port variables in
Compose select **host** ports; application ports inside Docker stay fixed. Database
URLs use the configured database name unless explicitly overridden. Internal URLs
use Compose service names and container ports, regardless of published host ports.

```powershell
docker compose stop
docker compose down
```

As in the earlier modules, database data is disposable (`tmpfs`). RabbitMQ uses a
named volume. `docker compose down --volumes` also removes that broker data.

## Run applications locally against Docker resources

Prerequisites: Java 17+ and Maven, plus the Docker resources above. Stop the full
stack first if it is already running so application ports are free:

```powershell
docker compose stop resource-processor api-gateway resource-service song-service eureka-server
docker compose up -d --wait resource-db song-db rabbitmq localstack
```

Run each command in a separate terminal from the repository root, starting Eureka
first. No application configuration edits or profile switches are needed:

```powershell
mvn -f eureka-server/pom.xml spring-boot:run
mvn -f song-service/pom.xml spring-boot:run
mvn -f resource-service/pom.xml spring-boot:run
mvn -f resource-processor/pom.xml spring-boot:run
mvn -f api-gateway/pom.xml spring-boot:run
```

Application defaults connect to `localhost`: Resource DB `5432`, Song DB `5433`,
RabbitMQ `5672`, S3 `4566` and Eureka `8761`. Service-to-service requests still use
Eureka. Spring applications do not load Compose's `.env`; defaults already match
the default Docker resource ports and credentials. If you deliberately customize
resource ports or credentials, pass the corresponding environment variables to
your local Java processes too.

If this Windows host reports Java NIO `Unable to establish loopback connection`,
use the process-local JVM workaround described in
[Module 5 verification](docs/module-5-verification.md). The automated local runner
accepts it through `-JavaOptions`.

## Verify the application

```powershell
.\scripts\test.ps1 -Suite All
```

This runs fast, integration, HTTP/messaging contract, Cucumber component and E2E
tests using Docker Java 17. To run one category, replace `All` with `Unit`,
`Integration`, `Contracts`, `Component` or `E2E`. The E2E runner creates and cleans
up a separate disposable stack; it does not reset your development resources.

To run the six existing Cucumber E2E scenarios against the stack already running
at the default Gateway port (Docker or local Java):

```powershell
mvn -f e2e-tests/pom.xml -Pe2e -De2e.base-url=http://localhost:8082 verify
```

See [testing strategy](docs/testing-strategy.md) for coverage and report locations.

## Verify Module 5 service discovery

```powershell
./scripts/verify-module-5.ps1 -Mode Docker
./scripts/verify-module-5.ps1 -Mode Local
```

These PowerShell 7 commands own separate disposable Docker projects. Docker mode
checks two real Song instances, request distribution, Eureka registration, Gateway
errors and recovery. Local mode starts the Java applications on available host
ports against isolated Docker infrastructure. Both run the existing Cucumber E2E
suite through the Gateway and clean up their own resources. Local mode also
requires Java on PATH. Reports and local application logs are saved under
`output/module-5/<project>/`.

See [Module 5 verification](docs/module-5-verification.md) for actual results.
