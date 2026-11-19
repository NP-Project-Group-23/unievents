# unievents
Real-Time University Event Ticket Booking System built with Next.js, Spring Boot, and Java Network Programming.

The Spring Boot backend uses the team's shared Neon PostgreSQL database. For a new
checkout, copy `.env.example` to `.env.local` in the repository root and obtain the
database password from the team privately. `.env.local` is ignored by Git and is
loaded automatically when starting the backend from the root or `backend` directory.
Environment variables `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` can also be used.

Start the backend on Windows:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Run the complete local desktop app without Docker or Neon:

```powershell
cd backend
.\mvnw.cmd -Plocal-desktop package -DskipTests
java -jar target\unievents-backend-0.0.1-SNAPSHOT.jar
```

This starts an embedded file database, runs all migrations, starts the HTTP/TCP/UDP
backend and opens JavaFX. Local data is stored under `backend/data/` and is ignored
by Git. The local-only administrator is `admin@unievents.local` / `Admin123!` unless
`LOCAL_ADMIN_EMAIL` and `LOCAL_ADMIN_PASSWORD` are set before the first launch.

Treat this database as shared team data; use an isolated database for destructive
tests or migration experiments.

Team documentation:

- [Stage 3 stabilization and demo acceptance evidence](docs/STABILIZATION_STAGE_3.md)
- [Stage 2 desktop UX completion](docs/DESKTOP_UX_STAGE_2.md)
- [Stage 1 secure shared Neon integration](docs/NEON_STAGE_1.md)
- [JavaFX desktop dashboard and live seat workflow](docs/DESKTOP_STEP_7.md)
- [Authenticated UDP seat-change notifications](docs/UDP_STEP_6.md)
- [Booking TCP operations and commit handoff](docs/BOOKING_STEP_5.md)
- [Event administration, seat generation and migration setup](docs/EVENTS_STEP_4.md)

- [Verified session authorization and metrics integration](docs/AUTH_METRICS_STEP_3.md)

- [Current TCP protocol, authentication rules and operation contracts](docs/PROTOCOL.md)
- [JavaFX integration architecture, ownership and configuration plan](docs/ARCHITECTURE.md)

The JavaFX client now integrates authentication, events, booking, history, admin
event management and TCP-authoritative UDP refresh. Launch
`com.unievents.unievents_backend.ui.UniEventsDesktopApplication` from the IDE after
starting the backend; configure `TCP_HOST` and `TCP_PORT` when needed. The web
features documented below remain the current HTTP starter. The backend starts the TCP listener
on `127.0.0.1:9090` through Spring; see [Step 2 wiring and verification](docs/TCP_STEP_2.md).

### Simple account login

Start the backend with the command above. In a second terminal, run `cd frontend`,
`npm install`, and `npm run dev`, then open http://localhost:3000.
Choose **Create account**, enter your name, email, and a password of at least eight
characters. Registration signs you in; sign out to try logging back in. Refreshing
the page restores your current session.

Provision the schema using the Step 4 migration guide before starting the backend.
Startup now validates the schema instead of creating tables. It stores BCrypt password hashes,
and uses an HttpOnly session cookie with CSRF protection. Sessions expire after
30 idle minutes and are lost when the backend restarts. Accounts remain in Neon.
This starter does not include email verification, password recovery, or login rate
limiting. Use HTTPS and `SESSION_COOKIE_SECURE=true` when deploying.

The frontend proxies `/api/*` to `http://localhost:8080`; set `BACKEND_URL` in the
frontend environment if the backend runs elsewhere. Database credentials belong
only in the root `.env.local`, never in frontend public environment variables.

Endpoints: `GET /api/auth/csrf`, `POST /api/auth/register`, `POST /api/auth/login`,
`GET /api/auth/me`, and `POST /api/auth/logout`. POST requests require the token
and header name returned by the CSRF endpoint, using the same session cookie.

Run `backend/mvnw.cmd -f backend/pom.xml test` for backend checks. The default
test configuration uses isolated H2 and ephemeral TCP ports; the application
context test does not verify the shared PostgreSQL connection. JavaFX tests
may require a graphical environment. See the Step 2 document for the verified
server regression command.

- [Workload, architecture, milestones, and acceptance criteria](docs/TEAM_WORKLOAD.md)
- [Daily Git workflow, pull requests, and conflict recovery](docs/GIT_GUIDE.md)
- [Pull request template](.github/pull_request_template.md)
