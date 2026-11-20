# Team setup: shared Neon database

The shared Neon schema is managed by Flyway and is currently at version 4. Normal
application startup validates the schema but does not run migrations. Only one
designated database operator applies a future migration once; other members only
need the pooled runtime connection.

## Requirements

- Java 21 or newer
- Node.js 20 or newer
- Git
- No Docker or local PostgreSQL installation

## Private root environment

Each member creates `unievents/.env.local` locally. Never commit or post this file.

```properties
DB_URL=jdbc:postgresql://YOUR-POOLER-HOST/neondb?sslmode=require&channelBinding=require
DB_USERNAME=neondb_owner
DB_PASSWORD=YOUR_ROTATED_SHARED_PASSWORD
TCP_ENABLED=true
TCP_BIND_ADDRESS=127.0.0.1
TCP_PORT=9090
```

`DB_URL` must contain `-pooler`. Do not add quotes, trailing backslashes, or escaped
colons. `DATABASE_URL`, Neon Auth, Functions, and Realtime variables are not used
by the current Spring/Next.js application.

Validate without printing credentials:

```powershell
.\scripts\verify-neon-config.ps1
```

## Frontend environment

From `frontend`:

```powershell
Copy-Item .env.example .env.local
npm install
```

The generated file uses the local backend:

```properties
TCP_HOST=127.0.0.1
TCP_PORT=9090
TCP_REQUEST_TIMEOUT_MS=10000
BACKEND_URL=http://127.0.0.1:8080
```

Database credentials belong only in the root `.env.local`, never in the frontend.

## Run

Terminal 1, from the repository root:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Do not select the `local` profile; it uses a private H2 database instead of Neon.

Terminal 2, from the repository root:

```powershell
cd frontend
npm run dev
```

Open `http://localhost:3000`. Each member runs a local frontend and backend, while
events, users, seats, and bookings are shared through Neon. Login sessions remain
local to the backend where the user logged in.

## Verify shared behavior

1. An administrator creates an event on one machine.
2. Another member refreshes and opens that event.
3. Two students attempt the same seat: exactly one succeeds.
4. The winner sees the booking in history and the administrator sees it in the
   event manifest.
5. Students booking different seats can all succeed.

## Future schema changes

Never edit an applied V0-V4 file. Add the next ordered migration, test it on a Neon
development branch, then have one designated operator apply it using the direct
non-pooler endpoint. Runtime traffic continues to use the pooled endpoint.

## Common failures

- `missing column` or `missing table`: the backend and database migration versions
  differ; do not enable Hibernate `update` or `create`.
- `password authentication failed`: update the private root `.env.local` and restart.
- port `9090` in use: choose another `TCP_PORT` in both root and frontend files.
- a token fails on another backend: sessions are in memory and are not shared;
  log in through that member's local frontend/backend pair.
