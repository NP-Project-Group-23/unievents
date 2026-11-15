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

Treat this database as shared team data; use an isolated database for destructive
tests or migration experiments.

Team documentation:

### Simple account login

Start the backend with the command above. In a second terminal, run `cd frontend`,
`npm install`, and `npm run dev`, then open http://localhost:3000.
Choose **Create account**, enter your name, email, and a password of at least eight
characters. Registration signs you in; sign out to try logging back in. Refreshing
the page restores your current session.

The backend creates `app_users` if it does not exist, stores BCrypt password hashes,
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

Run `backend/mvnw.cmd -f backend/pom.xml test` for backend checks. Authentication
flow tests use H2; the existing application context test verifies your configured
PostgreSQL connection.

- [Workload, architecture, milestones, and acceptance criteria](docs/TEAM_WORKLOAD.md)
- [Daily Git workflow, pull requests, and conflict recovery](docs/GIT_GUIDE.md)
- [Pull request template](.github/pull_request_template.md)
