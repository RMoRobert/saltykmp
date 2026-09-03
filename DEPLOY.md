# Deploying the Salty KMP server (Docker + NGINX)

The server is a Ktor app backed by Postgres. This setup runs **app + Postgres in Docker** on a home
server, with your own reverse proxy (e.g., NGINX) handling HTTPS in front of it.

## 1. Build the fat jar (on a dev machine)

The Gradle build configures the whole multiplatform project (incl. the Android app), so the jar is
built outside Docker on a machine with JDK 21 + the Android SDK. Asssuming JAVA_HOME=/path/to/jdk-21:

```bash
./gradlew :server:buildFatJar
# -> server/build/libs/salty-server.jar
```

Copy the repo (with that JAR present) to the server, or build on the server if it has the JDK/SDK.

## 2. Configure secrets

Settings live directly in the compose file (no `.env`). Copy the
example template and edit your copy:

```bash
cp docker-compose.example.yml docker-compose.yml
```

Edit the `CHANGE_ME_*` values in `docker-compose.yml`:

- `db-password` — strong DB password (defined once; shared by Postgres and the app via a YAML anchor).
- `SALTY_DEFAULT_PASSWORD` — the seeded admin login password.
- `SALTY_TOKEN_SECRET` — long + random:  `openssl rand -hex 32`. Keys the HMAC over every device
  sync token.

> `SALTY_DEFAULT_USER`/`PASSWORD` seed a login **only on first run** (empty users table). The default
> `SALTY_TOKEN_SECRET` placeholder is forgeable — you must change it.

> **`SALTY_TRUST_PROXY`** is not a secret and is already set to `"true"` in the template, because
> that template's whole design is loopback-bound behind NGINX. It is listed here because it is the
> one setting whose *correct* value depends on how you expose the server rather than on your taste:
> `true` behind a reverse proxy, `false` (or absent) when the port is reachable directly. See
> §4 for what it does.

> **Upgrading from pre-3.3.0 server with `SALTY_JWT_SECRET`?** It is still read if `SALTY_TOKEN_SECRET` is unset with
> a warning in logs. Rename the variable, as it will probably be removed in a future version, but **keep its value**
> because it is still used to key device tokens tokens (and this will avoid invalidating all current logins, although
> you may deliberately rotate it if not a concern).


> **Clients apps and server must both be upgraded to 3.3 or later if using sync:** Signing in now requires a device id,
> and a token-based approach per device is used rather than username/password authentication (your client asking for
> this or an HTTP 400 are clues that your client must be older).

## 3. Run

```bash
docker compose up -d --build
```

- App listens on **`127.0.0.1:8080`** (localhost only — NGINX proxies to it).
- Postgres data → `salty-db18` volume; recipe images → `salty-images` volume (both persist across redeploys).
- Health check: `curl http://127.0.0.1:8080/health` → `OK`. (`/` is the web app — redirects to `/login`, then to `/app`.)

### Managing users

Each user has a completely separate set of recipes and related data (suggested setup: one user shares
same user account across all devices; different users have different accounts).
The seeded `SALTY_DEFAULT_USER` is an admin. Sign in to the web app and open the **account menu**
(person icon, top right) → **Manage users…** to add accounts, reset passwords, grant/revoke admin
(not recommended for regular user accounts), or delete a user (including all of their data). Only
admins see that menu item, and every route behind it re-checks against the user row.
New users start empty; point a client app at the server, log in as that user on the client, and sync
to populate data.

Any logged in user can change their password from the same menu. This page alos lists which apps are
authorized to sync with their account. A password change signs out every one of those apps, or any
individual app can also be removed or renamed in this list.

## 4. NGINX reverse proxy (HTTPS)

Add a server block (alongside your existing TLS/cert setup, e.g. certbot):

```nginx
server {
    listen 443 ssl;
    server_name salty.example.com;

    # ssl_certificate / ssl_certificate_key ...  (your existing Let's Encrypt config)

    client_max_body_size 25m;   # recipe image uploads are multipart -- raise above NGINX's 1m default

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**This block only works with `SALTY_TRUST_PROXY: "true"`**, which `docker-compose.example.yml`
already sets — check it survived if you have edited your copy, and set it in any other way you run
the server behind a proxy. Setting `X-Forwarded-For` here does nothing on its own: the app ignores
forwarded headers until it is told the proxy is trustworthy, because a *directly* reachable server
that believed them would let any client claim any source IP. That is also why
`docker-compose.offline.example.yml` leaves it commented out — that file publishes port 8080 on
every interface, so it is the one shape where trusting the header would be the hole. **Enable it
only alongside the loopback binding.**

Untold, every request looks like it came from `127.0.0.1`, which costs two things:

- the per-IP login throttle keys on client IP **and** username, so that someone hammering one
  account cannot lock out the real owner signing in from somewhere else. With one IP standing in
  for the whole internet, that protection is gone. (The account-wide lockout below still applies.)
- `SALTY_SECURE_COOKIES` defaults to whatever `SALTY_TRUST_PROXY` is, so the session cookie is not
  marked `Secure` either. The one variable fixes both.

**No `gzip` directive here on purpose.** The app compresses its own responses (Ktor's `Compression`
plugin), which is what also covers the direct-8080 shape further down, where there is no proxy to
configure. NGINX passes the client's `Accept-Encoding` upstream by default, so this works as-is —
and NGINX will not re-compress a response that arrives already gzipped. If you add `gzip on;` for
other sites in the same config, nothing here breaks.

Then point the client app at `https://salty.example.com` and log in with
the configured user account. First sync uploads (and downloads) everything.

## Locked-out accounts

Two layers guard the login endpoints, and they exist because neither one alone is enough.

| | Keyed by | Trips at | Locks for |
| --- | --- | --- | --- |
| `LoginThrottle` | client IP **+** username | 10 failures | 15 minutes |
| `AccountLockout` | username only | 50 failures | 1 hour |

The per-IP throttle is the one that catches ordinary hammering, and it includes the IP so that an
attacker guessing at your username cannot lock *you* out — you are signing in from somewhere else,
so you are a different key. That is exactly why it cannot stand alone: an attacker spreading guesses
for one account across a botnet never accrues more than a failure or two per IP and never trips it.

`AccountLockout` is the backstop for that case, and being username-only is the point rather than an
oversight. The cost is the obvious one: 50 failures against your username locks *you* out too, for
up to an hour, no matter where you are signing in from. The threshold is set well above what typos
reach, and both counters are cleared by a successful login.

**Recovering a locked account: restart the server.** Both counters are in memory, so
`docker compose restart server` clears every lockout. That is deliberate — the alternative is an
unlock endpoint, which is a remote thing an attacker can also reach, and this way recovery requires
host access. Note that an admin *password reset* does not clear a lock on its own: until the window
expires, the new password cannot be used to log in successfully and so cannot clear the counter.
Restart, or wait it out.

A locked account's attempts are rejected **before** the bcrypt verify, so a flood against a locked
username costs the server almost nothing — the lockout bounds CPU as well as guesses.

## Updating

Assuming `JAVA_HOME=/path/to/jdk-21`:

```bash
./gradlew :server:buildFatJar           # rebuild jar
docker compose up -d --build server     # rebuild + restart app only
```

## Offline deploy

For a target with no internet and no source checkout (or if you just prefer to buildin your machine
instead of the server). Everything is built on a machine that *does* Internet access, Docker, and the source,
then shipped as image tarballs, and run from `docker-compose.offline.example.yml` (which will need to use
`image:` rather than `build:`).

On the build machine, assuming `JAVA_HOME=/path/to/jdk-21`:

```bash
./gradlew :server:buildFatJar
docker build -t saltyserver:latest ./server
docker save saltyserver:latest -o saltyserver-image.tar
# If needed (no Internet access at all on server):
docker pull postgres:18 && docker save postgres:18 -o postgres18-image.tar
```

### Building on Apple Silicon for an x86-64 target

The commands above produce an image for the **build machine's** architecture. On an Apple Silicon Mac
that is `arm64`, which will not run on an x86-64 Linux host (or run slowly if emulated), you
should build for the target architecture. Pass the target platform explicitly instead. Again
assuming `JAVA_HOME=/path/to/jdk-21`:

```bash
./gradlew :server:buildFatJar
docker buildx build --platform linux/amd64 -t saltyserver:latest --load ./server
docker save saltyserver:latest -o saltyserver-image.tar
# If needed (no Internet access at all on server):
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
- **The fat jar itself is architecture-independent** -- it's JVM bytecode, so `buildFatJar` needs no
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
- **CORS** is not enabled, and the web app doesn't need it: it is served from this same origin.
- **Backups:** back up the `salty-db18` and `salty-images` Docker volumes.

---

# Building the Android app for Play (internal testing)

Play needs a **signed Android App Bundle** (`.aab`). Signing is configured from `keystore.properties`
at the repo root — gitignored, since it holds the keystore password.

## 1. One-time: point the build at your upload key

```bash
cp keystore.properties.example keystore.properties
```

Fill it in (absolute path to the `.jks`, alias, both passwords). Nothing else needs changing.

**Which key?** If `com.enuvro.saltykmp` is already on Play, it must be the key Play expects — your
*upload* key if the app is enrolled in Play App Signing, otherwise the original release key. A
mismatched key is rejected at upload and can only be changed through Google's key-reset process. For a
brand-new listing, any key works; generate one with:

```bash
keytool -genkeypair -v -keystore upload-key.jks -alias salty-upload -keyalg RSA -keysize 2048 -validity 10000
```

Back that file up somewhere durable — losing it means losing the ability to update the app.

## 2. Build the bundle

```bash
./gradlew :composeApp:bundleRelease
```

Output: `composeApp/build/outputs/bundle/release/composeApp-release.aab`

Without `keystore.properties` the build still succeeds but the bundle is **unsigned** and Play will
reject it. To confirm a bundle is signed:

```bash
unzip -l composeApp/build/outputs/bundle/release/composeApp-release.aab | grep -c 'META-INF/.*\.RSA'
```

## 3. Upload

If you are the developer releasing the CMP app to a store:

Play Console → your app → **Testing → Internal testing → Create new release** → upload the `.aab`,
add testers, roll out. Internal testing reaches testers in minutes and skips the full review queue.

## Version numbers

Both come from `appVersion` in the root `gradle.properties` (e.g., `3.2.100`):

- `versionName` = `appVersion` verbatim
- `versionCode` = `major * 10_000_000 + minor * 100_000 + patch` (so `3.2.100` → `30200100`)

**Play rejects a versionCode it has already seen**, so bump `appVersion` before every upload. Because
the code is derived, minor must stay < 100 and patch < 100_000.

## Possible "gotchas" for a release build (but not in debug)

- **Plain HTTP is blocked.** `usesCleartextTraffic` is false in release by design, so a Salty Server
  reached over `http://` will fail for testers with a network error. Testers need an `https://` server.
  This is the single most likely "it works on my machine" report.
- **R8/minification is off** (`isMinifyEnabled = false`). Deliberate: SQLDelight, Ktor, and
  kotlinx-serialization all need keep rules that don't exist yet, and nothing has been shrink-tested.
  That keeps the download larger than it needs to be; enabling it is its own piece of work.
- The linked-folder sync and the iOS bookmark round-trip are still **not device-tested** against a real
  cloud provider (see TODO.md) — worth saying so in the tester release notes.
