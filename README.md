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

- [Workload, architecture, milestones, and acceptance criteria](docs/TEAM_WORKLOAD.md)
- [Daily Git workflow, pull requests, and conflict recovery](docs/GIT_GUIDE.md)
- [Pull request template](.github/pull_request_template.md)
