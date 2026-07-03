This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM), Server. The Server app is Salty Sever,
usable as a Sync server for the Swift (Salty repo) app or the KMP/CMP Android, iOS, or desktop/JVM apps in this
repo. The desktop and mobile apps are experimental; using the Salty macOS or iOS apps (from the Salty repo or App Store)
is recommended instead, but I plan to test and improve the Kotlin apps as time goes on. The server module is currently
in production use by me, although I can again make no guarantees as these are all things I work on in my free time.

# Salty Server

To run Salty Server, run the server:buildFatJar gradle task and deploy in Docker, "raw" JVM, or your preferred setup.
Consult DEPLOY.md for additional guidance.

This is a self-hosted option only.

## Salty Server Notes

*These notes are from the original Salty Server, versions 2.x and prior, which were written in Spring Boot. Version 3
has been rewritten using primarily Ktor, but the same notes apply.*

Salty Server is a companion to the Salty desktop or mobile apps that allows syncing content among multiple devices (without needing to worry about storing the data file in a shared location and concerns regarding simultaneous usage) as well as a basic web interface for viewing synced data.

Salty Server is *not* a standalone app; it must be used with the desktop or mobile apps to sync data (although technically you could use the same API endpoints for CRUD operations to get data in or out yourself...).

**Salty Server is currently beta quality. Do not use in a production environment without adequate testing.** Salty Server is provided as-is under the terms of the LICENSE. As an open-source project created largely for my own personal use, no support is guaranteed, but feel free to use GitHub features to discuss, etc.


## Original Notes from KMP Project:


* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

* [/shared](./shared/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./shared/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

### Build and Run Server

To build and run the development version of the server, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :server:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :server:run
  ```

#### Database for local / manual testing

By default the server uses a **file-based H2 database** (no setup required) — data is stored in
`./salty-db/` (gitignored) and persists across restarts. This is `SALTY_DB=h2`, the default. The H2
profile also uses its own image folder, `./salty-images-h2/`, so the throwaway sandbox stays paired
with its database; the Postgres profile keeps `./salty-images/`. (`SALTY_IMAGE_DIR` overrides either.)

To run against a **real Postgres** instead (e.g. to test Postgres-specific behaviour), set one env var
on the run configuration:

```shell
SALTY_DB=postgres ./gradlew :server:run
```

`SALTY_DB=postgres` expects Postgres on `localhost:5432`, with database/user/password all `salty`.
A quick local Postgres:

```shell
docker run --name salty-pg -e POSTGRES_DB=salty -e POSTGRES_USER=salty \
  -e POSTGRES_PASSWORD=salty -p 5432:5432 -d postgres:17
```

For full control, the individual `SALTY_DB_URL` / `SALTY_DB_DRIVER` / `SALTY_DB_USER` /
`SALTY_DB_PASSWORD` vars override the profile defaults (this is how the Docker deploy is wired).
Production uses Postgres.

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…