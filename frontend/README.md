# UniEvents frontend

The Next.js frontend is a complete browser client for the UniEvents TCP backend. A server-side route handler connects to the Java TCP server; raw database credentials and TCP session tokens are never exposed to browser JavaScript.

## Features

- Student registration, login, restored sessions, and logout.
- Paginated event discovery and event details.
- Responsive visual seat selection with five-second authoritative TCP refreshes.
- Atomic seat booking feedback and private booking history.
- Role-gated event creation, editing, deletion, and automatic seat generation.
- Role-gated ExecutorService/server metrics.
- Mobile, tablet, and desktop layouts with loading, empty, error, and confirmation states.

Browsers cannot receive the Java UDP datagrams directly. While an event is open, the browser requests an authoritative `GET_SEATS` snapshot every five seconds. The JavaFX client remains the direct UDP demonstration client.

## Run locally

Start the backend first:

```powershell
cd backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

Then start the frontend in another terminal:

```powershell
cd frontend
Copy-Item .env.example .env.local
npm install
npm run dev
```

Open `http://localhost:3000`.

The local administrator defaults to `admin@unievents.local` / `Admin123!`. Override `LOCAL_ADMIN_EMAIL` and `LOCAL_ADMIN_PASSWORD` before the backend's first local startup when different demo credentials are required.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `TCP_HOST` | `127.0.0.1` | TCP backend host used only by the Next.js server |
| `TCP_PORT` | `9090` | TCP backend port |
| `TCP_REQUEST_TIMEOUT_MS` | `10000` | Gateway request timeout |
| `BACKEND_URL` | `http://127.0.0.1:8080` | Legacy HTTP auth/metrics proxy target |

Do not prefix TCP settings or database credentials with `NEXT_PUBLIC_`.

## Verify

```powershell
npm run lint
npm test
npm run build
```

The automated suite covers TCP framing/timeouts, gateway authentication and cookie security, operation allow-listing, backend error mapping, authentication UI, and booking conflict feedback.
