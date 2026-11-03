FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml ./pom.xml
RUN --mount=type=cache,id=recording-maven,target=/root/.m2/repository,sharing=locked mvn -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,id=recording-maven,target=/root/.m2/repository,sharing=locked mvn -B clean package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
CMD ["java", "-jar", "app.jar"]
