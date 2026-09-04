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
# `--enable-native-access=ALL-UNNAMED` is here for the NEXT base image, not this one. Netty loads its
# native transport via System.loadLibrary, and jansi (pulled in by ktor-server-call-logging) does the
# same when call logging colours are on. JEP 472 classes both as restricted methods: JDK 24+ warns on
# every start and a later release will refuse the call outright unless the application has declared
# that it uses native access. On the temurin:21-jre base above none of that applies yet -- startup is
# silent today, verified -- so this changes nothing now and is what keeps the bump to a JDK 24+ base
# from being a surprise. JDK 21 accepts the option and ignores it.
#
# It is a declaration of intent, not warning suppression. ALL-UNNAMED is the only granularity available:
# a fat jar puts every class in the unnamed module, so there is no finer target to name.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/salty-server.jar"]
