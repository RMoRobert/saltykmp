# Runtime image for the Salty KMP server.
#
# This image runs a PREBUILT fat jar (it does not build it). Build the jar first on a machine that has
# JDK 21 + the Android SDK (the Gradle build configures the whole multiplatform project):
#
#     ./gradlew :server:buildFatJar      # -> server/build/libs/salty-server.jar
#
# Then build/run via docker compose (context is the server/ dir).
# JDK 25 (LTS) runtime. The jar is still JVM 21 bytecode (server/build.gradle.kts), so rolling back is just
# this line: the same jar runs on eclipse-temurin:21-jre unchanged.
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY build/libs/salty-server.jar /app/salty-server.jar

# Defaults; override real values via docker compose / environment.
ENV PORT=8080 \
    SALTY_IMAGE_DIR=/data/images

EXPOSE 8080
# `--enable-native-access=ALL-UNNAMED` is load-bearing on this base. jansi (pulled in by
# ktor-server-call-logging) calls System.load for its native library, and Netty loads its native transport
# the same way. JEP 472 classes both as restricted methods: without the flag, JDK 25 prints a four-line
# "A restricted method in java.lang.System has been called ... JansiLoader" WARNING on every start, and a
# later release will refuse the call outright. With it, startup is silent (both verified 2026-09-24 on
# temurin 25.0.4 against postgres:18). JDK 21 accepts the option and ignores it, so it survives a rollback.
#
# It is a declaration of intent, not warning suppression. ALL-UNNAMED is the only granularity available:
# a fat jar puts every class in the unnamed module, so there is no finer target to name.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/salty-server.jar"]
