# Runtime image for the Salty KMP server.
#
# This image runs a PREBUILT fat jar (it does not build it). Build the jar first on a machine that has
# JDK 21 + the Android SDK (the Gradle build configures the whole multiplatform project):
#
#     ./gradlew :server:buildFatJar      # -> server/build/libs/salty-server.jar
#
# Then build/run via docker compose (context is the server/ dir).
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/salty-server.jar /app/salty-server.jar

# Defaults; override real values via docker compose / environment.
ENV PORT=8080 \
    SALTY_IMAGE_DIR=/data/images

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/salty-server.jar"]
