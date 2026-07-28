# Deploying the Salty KMP server (Docker + NGINX)

The server is a Ktor app backed by Postgres. This setup runs **app + Postgres in Docker** on a home
server, with **your own NGINX** handling HTTPS in front of it (same shape as the old Salty Server).

## 1. Build the fat jar (on a dev machine)

The Gradle build configures the whole multiplatform project (incl. the Android app), so the jar is
built **outside** Docker on a machine with **JDK 21 + the Android SDK**:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :server:buildFatJar
# -> server/build/libs/salty-server.jar
```

Copy the repo (with that jar present) to the server, or build on the server if it has the JDK/SDK.

## 2. Configure secrets

Settings live directly in the compose file (no `.env`). Copy the
example template and edit your copy:

```bash
cp docker-compose.example.yml docker-compose.yml
```

Edit the `CHANGE_ME_*` values in `docker-compose.yml`:

- `db-password` — strong DB password (defined once; shared by Postgres and the app via a YAML anchor).
- `SALTY_DEFAULT_PASSWORD` — the seeded admin login password.
- `SALTY_JWT_SECRET` — long + random:  `openssl rand -hex 32`.

> `SALTY_DEFAULT_USER`/`PASSWORD` seed a login **only on first run** (empty users table). The default
> `SALTY_JWT_SECRET` placeholder is forgeable — you must change it.

## 3. Run

```bash
docker compose up -d --build
```

- App listens on **`127.0.0.1:8080`** (localhost only — NGINX proxies to it).
- Postgres data → `salty-db18` volume; recipe images → `salty-images` volume (both persist across redeploys).
- Health check: `curl http://127.0.0.1:8080/health` → `OK`. (`/` is the web UI — redirects to `/login`.)

### Managing users

Each user has a completely separate set of recipes and library (every app/sync logs in as one user).
The seeded `SALTY_DEFAULT_USER` is an **administrator**. Sign in to the web UI and open **Users** in the
top nav to add accounts, reset passwords, grant/revoke admin, or delete a user (deleting also removes all
of that user's recipes and images). Only admins see the Users page. New users start empty; point an app at
the server and log in as that user to populate their library.

## 4. NGINX reverse proxy (HTTPS)

Add a server block (alongside your existing TLS/cert setup, e.g. certbot):

```nginx
server {
    listen 443 ssl;
    server_name salty.example.com;

    # ssl_certificate / ssl_certificate_key ...  (your existing Let's Encrypt config)

    client_max_body_size 25m;   # recipe image uploads are multipart — raise above NGINX's 1m default

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Then point the **Swift app** and **KMP app** Server URL at `https://salty.example.com` and log in with
the seeded account. First sync uploads/downloads everything.

## Updating

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :server:buildFatJar   # rebuild jar
docker compose up -d --build server                       # rebuild + restart app only
```

## Offline deploy

For a target with no internet and no source checkout. Everything is built on a machine that *does*
have both, shipped as image tarballs, and run from `docker-compose.offline.example.yml` (which uses
`image:` rather than `build:`).

On the build machine:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :server:buildFatJar
docker build -t saltyserver:latest ./server
docker save saltyserver:latest -o saltyserver-image.tar
docker pull postgres:18 && docker save postgres:18 -o postgres18-image.tar
```

### Building on Apple Silicon for an x86-64 target

The commands above produce an image for the **build machine's** architecture. On an Apple Silicon Mac
that is `arm64`, which will not run on an x86-64 Linux host (or run slowly if emulated).
Pass the target platform explicitly instead:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :server:buildFatJar
docker buildx build --platform linux/amd64 -t saltyserver:latest --load ./server
docker save saltyserver:latest -o saltyserver-image.tar
docker pull --platform linux/amd64 postgres:18
docker save postgres:18 -o postgres18-image.tar
```

Three things worth knowing:

- **`--load` is required.** `buildx` writes to its own build cache by default, and `docker save` only
  sees the local image store. Without it the save fails or exports a stale image. (`--load` only works
  for a single `--platform`, which is what we want here.)
- **`docker pull` needs the flag too.** It's easy to fix the app image and forget Postgres: on Apple
  Silicon a bare `docker pull postgres:18` fetches the arm64 variant, so you'd ship one image that runs
  and one that doesn't.
- **The fat jar itself is architecture-independent** — it's JVM bytecode, so `buildFatJar` needs no
  platform flag. Only the image build does, because it bakes in a platform-specific JRE
  (`eclipse-temurin:21-jre`).

Verify before shipping — this should print `amd64`, not `arm64`:

```bash
docker image inspect saltyserver:latest --format '{{.Architecture}}'
```

Copy `saltyserver-image.tar`, `postgres18-image.tar`, and the compose template to the target, then:

```bash
cp docker-compose.offline.example.yml docker-compose.offline.yml   # edit the CHANGE_ME_* values
docker load -i saltyserver-image.tar
docker load -i postgres18-image.tar
docker compose -f docker-compose.offline.yml up -d
```

Both tarballs are needed if the target cannot pull `postgres:18` either. Repeat the `saltyserver`
half for app updates, and bump Postgres version if changes in future.

Unlike the NGINX-fronted setup above, this file publishes port 8080 on all interfaces for direct
plain-HTTP LAN access. Do not expose that to the internet; see the notes in the file itself.

## Upgrading Postgres (17 -> 18)

The compose files use **`postgres:18`**; server 2.x used 17. A major-version bump for Postgres
is never in-place: Postgres refuses to start on a data directory written by an older major version.\
Postgres 18 also changed the *image* layout: the volume is now `/var/lib/postgresql` (was
`/var/lib/postgresql/data`) and `PGDATA` is `/var/lib/postgresql/18/docker`.

**Recommended: start clean (delete all volumes, re-create usernames/PWs if use any besides specified
in Compose or environment) and re-sync from a client.** Drop the old volume, let 18 come up
empty, and push your library back from a known-good client (i.e., full re-sync to server). I know this
sounds a bit of a hassle, but I can't imagine anyone besides me is using the server module at this point,
so it seems like a reasonable enough tradeoff...but if you don't want to, see below for upgrade options.

```bash
docker compose down
docker volume rm $(basename "$PWD")_salty-db   # irreversible: this is the Postgres 17 data
docker compose up -d
```

> **Want a rollback path?** The compose files name the volume `salty-db` for both versions, so if you
> skip the `docker volume rm` above, 18 mounts the same volume and initialises a fresh cluster *beside*
> the 17 data — leaving the two co-mingled and no clean way back. To keep 17 intact, edit your compose
> file to give 18 its own volume (e.g. `salty-db18:/var/lib/postgresql`) before first start. Rolling
> back is then `image: postgres:17` with `salty-db:/var/lib/postgresql/data`. (Docker has no
> `volume rename`, so this has to be decided up front — you can't retrofit it later.)

**Migrating the data instead of re-syncing.** Dump from 17 *before* changing anything:

```bash
# 1. Stop the app so nothing writes during the dump (leave the 17 db running).
docker compose stop server

# 2. Dump the still-running Postgres 17 database to the host.
docker compose exec -T db pg_dumpall -U salty > salty-pg17.sql

# 3. Now edit the compose file (postgres:18, plus a separate volume name if you want a rollback),
#    then bring everything down.
docker compose down

# 4. Start ONLY the new db so it initialises the 18 cluster, and wait for it to be healthy.
docker compose up -d db
docker compose exec db pg_isready -U salty -d salty

# 5. Restore. The dump recreates the salty role/database, so ignore "already exists" notices.
docker compose exec -T db psql -U salty -d postgres < salty-pg17.sql

# 6. Start the app and check the data is there (log in, list recipes).
docker compose up -d server
curl http://127.0.0.1:8080/health
```

Keep `salty-pg17.sql` until you have verified the upgrade. If you kept 17 on a separate volume, remove
it once happy: `docker volume rm $(basename "$PWD")_salty-db` (irreversible).

> Offline deploys: the target also needs the `postgres:18` image, which it can't pull. Add it to the
> transfer — `docker save postgres:18 -o postgres18-image.tar`, copy, `docker load -i postgres18-image.tar`.

## Notes / future

- **Schema migrations:** startup runs `SchemaUtils.create` (creates missing tables; does not alter).
  Fine for first deploy; a future schema change needs a migration step (Flyway/Exposed migrations).
- **CORS** is not enabled (native clients don't need it); add it if a browser-based web UI is introduced.
- **Backups:** back up the `salty-db18` and `salty-images` Docker volumes.
