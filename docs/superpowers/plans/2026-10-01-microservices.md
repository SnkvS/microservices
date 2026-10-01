# MP3 Microservices Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Build the two local Spring Boot services described in README.md.

**Architecture:** Independent Maven modules and PostgreSQL instances. Resource uploads are parsed with Apache Tika and sent to Song Service over HTTP. Each service owns its data, validation, and error responses.

**Tech Stack:** Java 17 source, Spring Boot 3.5, Maven, PostgreSQL 16, Apache Tika 3.2.3.

**Spec:** `README.md`, `api-tests/api-response-specification.md`, and `api-tests/introduction_to_microservices.postman_collection.json`. The fixtures were supplied in `java-courses-main.zip` and extracted into their documented paths.

## Tasks

1. Scaffold Maven modules, database Compose, and focused validation tests.
2. Implement Song Service CRUD, validation, and global error handling; run its tests.
3. Implement Resource Service upload, MP3 extraction, retrieval, deletion, and Song Service calls; run its tests.
4. Build both modules, exercise live API flows with Docker databases, and document run instructions and fixture limitations.
