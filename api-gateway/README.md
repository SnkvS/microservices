# API Gateway

The gateway provides a stable entry point at `http://localhost:8082`:

- `/songs` and `/songs/**` route to `lb://song-service`.
- `/resources` and `/resources/**` route to `lb://resource-service`.

Spring Cloud LoadBalancer resolves these names through Eureka and distributes
Song requests across the registered instances. Requests retain their paths,
query strings and bodies, including binary MP3 uploads and downloads.

## Docker Compose

Run from the repository root:

Defaults work without a `.env` file. Optional overrides are documented in
`.env.example`; preserve any existing `.env` settings.

```powershell
docker compose up -d --build --scale song-service=2
```

`GATEWAY_PORT` in `.env` controls the gateway's published host port; its default
is `8082`. Check `http://localhost:8082/actuator/health` and the Eureka dashboard
at `http://localhost:8761`. Allow a few seconds for service discovery to refresh
after startup. Both Song instances and Resource Service should be registered.

Normal Compose publishes only the Gateway's business API port; backend application
ports remain internal. For single-instance debugging with fixed backend host ports,
use `docker compose -f compose.yaml -f compose.development.yaml up -d --build`.
Do not select that override when scaling Song Service.

## Local execution

Use Java 17 or a compatible later JDK. Start infrastructure in Docker, then run
Eureka Server, Song Service, Resource Service, Resource Processor and API Gateway from their main
classes in IntelliJ. Stop the Docker application containers first to free the
local ports and avoid registering Docker instances in the local registry:

```powershell
docker compose stop api-gateway resource-service song-service resource-processor eureka-server
docker compose up -d --wait resource-db song-db rabbitmq localstack
mvn -f api-gateway/pom.xml clean package
java -jar api-gateway/target/api-gateway-1.0.0.jar
```

The gateway's local defaults use port `8082` and Eureka at
`http://localhost:8761/eureka/`. No Spring profile is required.

## Postman

Set both variables in the active Postman environment to the gateway address:

```text
song_service_url = http://localhost:8082
resource_service_url = http://localhost:8082
```

Use the supplied collection's existing `/songs` and `/resources` paths. Select
the valid MP3 file for the binary upload requests, then run the whole collection.
`GET /songs/{id}` returns JSON metadata; `GET /resources/{id}` returns the MP3
with `Content-Type: audio/mpeg`.

## Routing tests

```powershell
mvn -f api-gateway/pom.xml test
```

Tests send real HTTP requests through the gateway to two discovered Song
backends and one Resource backend. They check balancing, binary preservation,
delete query parameters and downstream error responses.

Gateway-owned errors use the same `errorCode` / `errorMessage` JSON envelope as
the APIs. Undefined routes return 404, missing or unreachable services return
503, and upstream response timeouts return 504. Downstream API responses are
forwarded unchanged. The default connect timeout is 2 seconds and response
timeout is 10 seconds; override `GATEWAY_CONNECT_TIMEOUT` (milliseconds) and
`GATEWAY_RESPONSE_TIMEOUT` (for example, `20s`) if needed.

Run `./scripts/verify-module-5.ps1 -Mode Docker` from the repository root for
real Eureka registration and load-balancing verification against two Song
containers. Use `-Mode Local` to check local Java application processes against
isolated Docker infrastructure. See [verification results](../docs/module-5-verification.md).
