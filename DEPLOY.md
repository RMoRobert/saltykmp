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

Settings live directly in the compose file (no `.env`). The real file is gitignored, so copy the
committed template and edit your copy:

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
- Postgres data → `salty-db` volume; recipe images → `salty-images` volume (both persist across redeploys).
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

## Notes / future

- **Schema migrations:** startup runs `SchemaUtils.create` (creates missing tables; does not ALTER).
  Fine for first deploy; a future schema change needs a migration step (Flyway / Exposed migrations).
- **CORS** is not enabled (native clients don't need it); add it if a browser-based web UI is introduced.
- **Backups:** back up the `salty-db` and `salty-images` Docker volumes.
