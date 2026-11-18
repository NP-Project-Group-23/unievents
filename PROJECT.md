# Project: UniTicket — Member 01 Architecture Roadmap & Workload Specifications

## 1. Executive Summary & System Architecture

### 1.1 Objective
Design, plan, implement, and verify Member 01's complete backend, frontend, and verification workload for the UniTicket network programming project. UniTicket is a real-time concurrent event ticketing system demonstrating core network programming concepts: TCP socket communication, multithreading, concurrency synchronization, and thread pool management, operating against a shared Neon cloud PostgreSQL database.

Member 01 provides the foundational networking transport, protocol codec, user persistence, authentication security, and session management consumed by all other team members.

### 1.2 Architectural Layers
```
+-------------------------------------------------------------------------------+
|                    Client Tier (Desktop & Gateway Clients)                   |
|  - F01 JavaFX Desktop Client (LoginView, RegisterView, ConnectionIndicator)    |
|  - Member 05 Desktop Client & Management UI                                   |
+-------------------------------------------------------------------------------+
                                      │  Raw TCP Sockets
                                      │  Newline-Delimited UTF-8 JSON (NDJSON)
                                      ▼
+-------------------------------------------------------------------------------+
|                    Member 01: Network Transport Layer (B02)                    |
|  - TcpServer (ServerSocket acceptor loop on configurable port)                |
|  - TcpClient (Socket wrapper with correlation IDs, timeouts, auto-reconnect)  |
|  - Connection/Inactivity timeouts (30s idle server, 10s client timeout)        |
+-------------------------------------------------------------------------------+
                                      │
                                      ▼
+-------------------------------------------------------------------------------+
|                 Member 01: Protocol & Dispatcher Layer (B01, B02)              |
|  - FrameCodec: NDJSON line framing (\n), 64KB bounded buffer                  |
|  - Envelopes: RequestEnvelope, ResponseEnvelope                               |
|  - ErrorCode Catalogue: Standardized machine-readable codes                   |
|  - RequestDispatcher: Operation registry & routing                            |
+-------------------------------------------------------------------------------+
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        ▼                             ▼                             ▼
+───────────────────────+   +───────────────────────+   +───────────────────────+
| Member 01: Auth (B04) |   | Member 02: Events     |   | Member 03: Booking    |
| - REGISTER, LOGIN     |   | - LIST_EVENTS         |   | - GET_SEATS           |
| - LOGOUT              |   | - GET_EVENT           |   | - BOOK_SEAT           |
| - SESSION_CHECK       |   | - CREATE/UPDATE/DELETE|   | - MY_BOOKINGS         |
| - SessionManager      |   +───────────────────────+   +───────────────────────+
+───────────────────────+                                           │
        │                                                           │
        │ (SecurityContext: userId, role)                           │ (userId)
        ▼                                                           ▼
+───────────────────────────────────────────────────────────────────────────────+
|             Domain & Persistence Layer (B03 / Shared Neon DB)                 |
|  - User Entity (id, name, email, password_hash, role: STUDENT | ADMIN)       |
|  - UserRepository (CRUD operations against app_users table)                   |
|  - StudentAuthService (BCrypt work factor 12, 72-byte guard, timing defense)  |
|  - Neon Cloud PostgreSQL (ep-muddy-bird-a1ajf820-pooler, sslmode=require)      |
+───────────────────────────────────────────────────────────────────────────────+
```

---

## 2. Preliminary Technology Research & Stack Selection

| Domain | Evaluated Options | Selected Tool | Engineering Rationale |
|---|---|---|---|
| **JSON Serialization** | Jackson vs Gson vs org.json | **Jackson (`ObjectMapper`)** | Already present on classpath via `spring-boot-starter-webmvc`. Native Java 21 `record` support without reflection flags, polymorphic `JsonNode` for generic payloads, zero added bloat. |
| **TCP Framing** | NDJSON vs Length-Prefixed Binary | **Newline-Delimited JSON (NDJSON)** | Mandated by project contract B01. Human-readable, netcat-testable. Implemented via `BufferedReader`/`BufferedWriter` (UTF-8). Rejects frames $> 64\text{ KB}$ (`FRAME_TOO_LARGE`). |
| **Socket Tuning** | Default vs Tuned Options | **`TCP_NODELAY = true`, `SO_TIMEOUT`** | Disables Nagle's algorithm to eliminate 40ms delayed-ACK latency. Server read timeout set to 30s; client request timeout set to 10s. |
| **Password Hashing** | `jBCrypt` vs `spring-security-crypto` vs Argon2 | **Spring Security `BCryptPasswordEncoder`** | Already present in `backend/pom.xml`. Standard OpenBSD BCrypt, salted using `SecureRandom`. Production cost factor 12 ($\sim 250\text{ ms}$); test cost factor 4 ($\sim 1\text{ ms}$). Enforces 72-byte UTF-8 pre-validation. |
| **Database Persistence** | HikariCP vs Single Connection | **`org.postgresql:postgresql` + HikariCP** | Neon serverless database requires connection pooling with SSL (`sslmode=require`). Configured with `connectionTimeout=30000`, `keepaliveTime=30000`, and `maxLifetime=600000` to handle Neon sleep/cold-start. |
| **Testing & Concurrency** | JUnit 5 + Mockito + AssertJ | **JUnit 5 Jupiter + Ephemeral Sockets** | Ephemeral `new ServerSocket(0)` allocation prevents port collision in concurrent CI builds. Paired `CountDownLatch` harnesses test multithreaded socket operations deterministically. |

---

## 3. Upstream & Downstream Integration Contracts

### 3.1 Contract with Member 05 (Thread Pool & Infrastructure Lead)
- **Handoff Mechanism**: Member 01 provides `ServerSocketAcceptor` which accepts raw client sockets. Member 05 injects a bounded `ExecutorService` (e.g. `ThreadPoolExecutor` with max 50 threads and bounded `ArrayBlockingQueue(100)`).
- **Overload Handling**: If the thread pool queue is full, Member 05 or the acceptor catches `RejectedExecutionException`, frames a `SERVER_OVERLOAD` response to the client socket, and closes the socket gracefully without crashing the server.
- **Database Lifecycle**: Member 05 configures the shared HikariCP `DataSource` pointing to Neon DB. Member 01's `UserRepository` injects this `DataSource` or `JdbcTemplate`.

### 3.2 Contract with Member 02 (ClientHandler & Request Consumer)
- **Framing Codec**: Member 02's `ClientHandler` uses Member 01's `FrameCodec` to read and write newline-delimited JSON envelopes across the client socket.
- **Dispatcher Execution**: `ClientHandler` delegates received `RequestEnvelope` objects to Member 01's `RequestDispatcher.dispatch(request, context)`.
- **Role Enforcement for Events**: Member 02 registers event CRUD operations (`CREATE_EVENT`, `UPDATE_EVENT`, `DELETE_EVENT`) with Member 01's dispatcher. When handling these operations, Member 02 inspects Member 01's `SecurityContext` extracted from the session token: if `context.role() != Role.ADMIN`, Member 01 or Member 02 immediately returns `ErrorCode.FORBIDDEN`.

### 3.3 Contract with Member 03 (Booking & Concurrency Lead)
- **Direct Service Dependency**: Member 03's `BookingService` methods require an authenticated `userId`:
  ```java
  public BookingResult bookSeat(Long userId, BookingRequest request);
  public Page<BookingResult> getMyBookings(Long userId, int page, int size);
  ```
- **Context Extraction & Spoof Prevention**: The client request payload may not forge `userId`. Member 01's `RequestDispatcher` validates the session `token`, resolves the authenticated `userId` from `SessionManager`, and passes this verified `Long userId` into Member 03's service calls.
- **Error Code Propagation**: Member 01 maps Member 03's `BookingException` (`SEAT_NOT_FOUND`, `SEAT_UNAVAILABLE`, `BOOKING_NOT_FOUND`) directly to standardized response envelopes.

### 3.4 Contract with Member 04 (UDP Notification Isolation Boundary)
- **Functional Isolation**: Member 04 operates a separate UDP multicast/broadcast channel for real-time seat availability updates.
- **Fault Decoupling**: All TCP login, session, and booking requests are 100% decoupled from UDP. UDP socket initialization errors, packet drops, or network congestion MUST NOT block, delay, or fail TCP transactions.
- **Authoritative Fallback**: On client reconnect or packet drop, the client uses Member 01's TCP channel to query authoritative seat states.

---

## 4. Feature Inventory

| # | Feature | Description | Milestone | Source |
|---|---|---|---|---|
| 1 | Newline-Delimited Framing | Encodes and decodes JSON envelopes using `\n` delimiter; bounds frame to 64KB | M1 | Survey (B01) |
| 2 | Request JSON Envelope | `RequestEnvelope(requestId, type, token, payload)` record definition | M1 | Survey (B01) |
| 3 | Response JSON Envelope | `ResponseEnvelope(requestId, status, data, errorCode, errorMessage)` record definition | M1 | Survey (B01) |
| 4 | Error Code Catalogue | Machine-readable enum catalogue of protocol, auth, booking, and system errors | M1 | Survey (B01) |
| 5 | ServerSocket Acceptor | Non-blocking/threaded `TcpServer` listening on configurable port with clean lifecycle | M1 | Survey (B02) |
| 6 | Socket Client Wrapper | `TcpClient` with async `CompletableFuture` response correlation and timeouts | M1 | Survey (B02) |
| 7 | Request Dispatcher | Operation registry routing request types to registered `RequestHandler`s | M1 | Survey (B02) |
| 8 | Socket Timeouts | Server 30s idle read timeout and client 10s request timeout enforcement | M1 | Survey (B02) |
| 9 | Client Reconnect Logic | Exponential backoff auto-reconnect state machine (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`) | M1 | Survey (B02) |
| 10 | User Domain Model | `User(id, name, email, passwordHash, role, createdAt)` domain entity | M2 | Survey (B03) |
| 11 | Role Enumeration | `Role.STUDENT`, `Role.ADMIN` with database constraint mapping | M2 | Survey (B03) |
| 12 | UserRepository | JDBC repository for user CRUD, email lookup, and database persistence | M2 | Survey (B03) |
| 13 | Database Schema Update | Add `role` column to `app_users` table with check constraint (`ck_users_role`) | M2 | Survey (B03) |
| 14 | BCrypt Password Hashing | `PasswordHasher` service using BCrypt (factor 12 prod, factor 4 test) | M2 | Survey (B03) |
| 15 | 72-Byte Password Guard | Pre-hash validation rejecting passwords > 72 UTF-8 bytes | M2 | Survey (B03) |
| 16 | Constant-Time Login Defense | Dummy BCrypt hash verification on non-existent email to prevent user enumeration | M2 | Survey (B03) |
| 17 | StudentAuthService | Core authentication service handling registration, validation, and credentials check | M2 | Survey (B03) |
| 18 | Thread-Safe SessionManager | `ConcurrentHashMap` session cache with 30-minute idle eviction | M2 | Survey (B04) |
| 19 | REGISTER Protocol Handler | Dispatcher handler executing student registration and returning user profile | M2 | Survey (B04) |
| 20 | LOGIN Protocol Handler | Dispatcher handler authenticating credentials and issuing session token | M2 | Survey (B04) |
| 21 | LOGOUT Protocol Handler | Dispatcher handler invalidating active session token | M2 | Survey (B04) |
| 22 | SESSION_CHECK Handler | Dispatcher handler validating token liveness and refreshing expiration | M2 | Survey (B04) |
| 23 | Role Validation Middleware | Role verification interceptor enforcing STUDENT vs ADMIN privileges | M2 | Survey (B04) |
| 24 | Downstream Context Bridge | Resolves session token to verified `userId` and passes to `BookingService` | M2 | Survey (Contracts) |
| 25 | JavaFX Connection Indicator | Visual indicator (Connected/Disconnected/Reconnecting) in UI header | M3 | Survey (F01) |
| 26 | JavaFX Login View | Form UI with email/password fields, async TCP login dispatch, and error banner | M3 | Survey (F01) |
| 27 | JavaFX Registration View | Form UI with name/email/password/role selection, validation, and async registration | M3 | Survey (F01) |
| 28 | Comprehensive Framing Tests | Unit tests for malformed JSON, frames > 64KB, coalesced and split frames | M3 | Survey (T01) |
| 29 | Comprehensive Auth Tests | Integration tests for duplicate email, bad password, invalid login, role checks | M3 | Survey (T01) |
| 30 | Network Resilience Tests | Socket tests for abrupt disconnection, reconnect recovery, server restart | M3 | Survey (T01) |
| 31 | Concurrency & Session Tests | 20+ concurrent socket client tests, session isolation, and downstream booking validation | M3 | Survey (T01) |

---

## 5. Milestones & Sequential Implementation Roadmap

All work executes on dedicated feature branches branched from `origin/dev`. Existing branches created by other members remain untouched.

| # | Milestone Name | Work Packages | Git Feature Branch | Dependencies | Status |
|---|---|---|---|---|---|
| **M1** | **Network Transport & Protocol Framing** | B01, B02 | `feature/member-01-phase-1-tcp-protocol` | None | DONE |
| **M2** | **Domain Persistence, Auth & Session Handlers** | B03, B04 | `feature/member-01-phase-2-auth-handlers` | M1 | IN_PROGRESS |
| **M3** | **JavaFX Client UI & Comprehensive Test Suite** | F01, T01 | `feature/member-01-phase-3-client-ui-verification` | M1, M2 | PLANNED |

---

## 6. Interface Contracts & Protocol Schemas

### 6.1 Protocol Schemas
#### Request Envelope
```json
{
  "requestId": "UUID",
  "type": "LOGIN",
  "token": "optional-session-token",
  "payload": { ... }
}
```

#### Response Envelope
```json
{
  "requestId": "UUID",
  "status": "SUCCESS | ERROR",
  "data": { ... },
  "errorCode": "ERROR_CODE_STRING",
  "errorMessage": "Human readable error description"
}
```

### 6.2 Error Code Catalogue Standard
- **Framing & Protocol**: `FRAME_TOO_LARGE`, `MALFORMED_JSON`, `INVALID_REQUEST`, `UNKNOWN_ACTION`, `SERVER_OVERLOAD`, `INTERNAL_ERROR`.
- **Authentication & Security**: `VALIDATION_FAILED`, `DUPLICATE_EMAIL`, `INVALID_CREDENTIALS`, `UNAUTHENTICATED`, `SESSION_EXPIRED`, `FORBIDDEN`.
- **Downstream Domain**: `EVENT_NOT_FOUND`, `SEAT_NOT_FOUND`, `SEAT_ALREADY_BOOKED`, `BOOKING_NOT_FOUND`.

### 6.3 Java Interface Contracts
```java
// Network framing codec
public class FrameCodec {
    public static final int MAX_FRAME_BYTES = 65536;
    public static void writeFrame(BufferedWriter writer, String json) throws IOException;
    public static String readFrame(BufferedReader reader) throws IOException;
}

// Request dispatcher
public interface RequestHandler {
    ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context);
}

public interface RequestDispatcher {
    void registerHandler(String operationType, RequestHandler handler);
    ResponseEnvelope dispatch(RequestEnvelope request, ConnectionContext context);
}

// Connection Context passed to handlers
public record ConnectionContext(String connectionId, Long userId, String email, Role role, Socket socket) {}
```

---

## 7. Code Layout

```
backend/src/main/java/com/unievents/unievents_backend/
├── network/
│   ├── protocol/
│   │   ├── RequestEnvelope.java
│   │   ├── ResponseEnvelope.java
│   │   ├── ErrorCode.java
│   │   └── FrameCodec.java
│   ├── server/
│   │   ├── TcpServer.java
│   │   ├── ConnectionContext.java
│   │   ├── RequestHandler.java
│   │   └── RequestDispatcher.java
│   └── client/
│       ├── TcpClient.java
│       ├── ConnectionState.java
│       └── ConnectionListener.java
├── domain/
│   ├── model/
│   │   ├── User.java
│   │   └── Role.java
│   ├── repository/
│   │   └── UserRepository.java
│   └── service/
│       ├── PasswordHasher.java
│       ├── StudentAuthService.java
│       ├── SessionContext.java
│       └── SessionManager.java
└── handlers/
    ├── RegisterHandler.java
    ├── LoginHandler.java
    ├── LogoutHandler.java
    └── SessionCheckHandler.java

backend/src/main/resources/
└── schema.sql (includes role column & index)

backend/src/test/java/com/unievents/unievents_backend/
├── network/
│   ├── FrameCodecTest.java
│   ├── TcpServerClientTest.java
│   └── RequestDispatcherTest.java
├── auth/
│   ├── UserRepositoryTest.java
│   ├── StudentAuthServiceTest.java
│   └── SessionManagerTest.java
└── integration/
    ├── Member01AuthProtocolIntegrationTest.java
    └── Member01DownstreamIntegrationTest.java
```
