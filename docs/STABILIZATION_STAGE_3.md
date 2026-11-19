# Stage 3: stabilization and demo readiness

## Automated acceptance flow

`TcpBookingConcurrencyAcceptanceTests` now covers two complementary scenarios over real TCP sockets:

1. Ten independent student clients attempt the same seat concurrently. Exactly one succeeds, nine receive `SEAT_ALREADY_BOOKED`, and one booking is persisted.
2. An administrator creates an event through `CREATE_EVENT`; the event and generated seats are queried through TCP; two students contend for the same seat; and each student's private `MY_BOOKINGS` history is verified against the winning session.

The tests use an isolated H2 database with the production Flyway migrations. They never modify Neon or require Docker.

## Verification evidence

Verified on 2026-11-19:

- Focused TCP acceptance suite: 2 tests passed.
- Complete backend suite: 144 tests passed, 0 failures, 0 errors, 0 skipped.
- Local desktop release packaging: successful.

Commands:

```powershell
cd backend
.\mvnw.cmd clean "-Dtest=TcpBookingConcurrencyAcceptanceTests" test
.\mvnw.cmd test
.\mvnw.cmd -Plocal-desktop package -DskipTests
```

Release artifact:

```text
backend/target/unievents-backend-0.0.1-SNAPSHOT.jar
```

Run it without Docker or Neon:

```powershell
java -jar target\unievents-backend-0.0.1-SNAPSHOT.jar
```

## Remaining manual acceptance

- Launch two desktop clients on the target demonstration machines.
- Confirm UDP seat refresh across the actual network and firewall configuration.
- Check keyboard navigation, scaling, and readability on the presentation display.
- Rotate the previously exposed Neon credentials before any shared-database demonstration.
- Use an isolated Neon branch—not production—if PostgreSQL-specific concurrency evidence is required.

