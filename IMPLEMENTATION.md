# Running the services

Both Spring Boot services run as local processes. Docker Compose runs only their dedicated PostgreSQL 16 databases.

1. Start the databases from the project root: `docker compose up -d`.
2. Build both services: `mvn package`.
3. In separate terminals, start Song Service with `java -jar song-service/target/song-service-1.0.0.jar` and Resource Service with `java -jar resource-service/target/resource-service-1.0.0.jar`.

Song Service listens on port 8081 and Resource Service on port 8080. Environment variables `SONG_SERVICE_PORT`, `RESOURCE_SERVICE_PORT`, `SONG_DB_URL`, `SONG_DB_USER`, `SONG_DB_PASSWORD`, `RESOURCE_DB_URL`, `RESOURCE_DB_USER`, `RESOURCE_DB_PASSWORD`, and `SONG_SERVICE_URL` override the defaults.

For the supplied Postman collection, set `song_service_url=http://localhost:8081` and `resource_service_url=http://localhost:8080`. Extract `sample-mp3-file/mp3.zip` and select `mp3/valid-sample-with-required-tags.mp3` in both file upload requests. Run the requests in collection order against fresh databases. The standalone test song has an unrelated generated ID; it does not require a resource.

The collection and response specification are in `api-tests/`. The supplied ZIP remains in the project root but is ignored by Git because the relevant fixtures have been extracted into their documented paths.

## Verification performed

- `mvn package` completed successfully.
- The supplied Postman collection was run through Newman with only its two blank local file references filled in a temporary copy. All 33 requests and 382 assertions passed. The original collection was unchanged.
- On this Windows host, both installed Java 26 and a downloaded Java 21 runtime failed while creating a JDK loopback socket. The live services were therefore run locally in WSL Ubuntu with Java 25; PostgreSQL remained in Docker.

The course submission also requests Postman screenshots in a single PDF or DOCX and a public repository link in the Avalia folder. Those submission artifacts are separate from this implementation.
