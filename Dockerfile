# Build
FROM gradle:8.10-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle --no-daemon installDist

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/install/bundling-poc /app

# The LLD pins the job to a 512 MB heap. Keeping it here means a multi-GiB
# bundle has to stream, and cannot quietly start buffering.
ENV JAVA_OPTS="-Xmx512m"

ENTRYPOINT ["/app/bin/bundling-poc"]
CMD ["help"]
