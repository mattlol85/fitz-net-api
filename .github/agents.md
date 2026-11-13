# fitz-net-api — Agentic Development Guide

Spring Boot 4.1 REST API — the backend for Fitz-Net. Provides user management and authentication services.

---

## Tech Stack

| Item | Value |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1 |
| Build tool | Gradle (Kotlin DSL) |
| Database | MongoDB (Spring Data MongoDB) |
| Auth | JWT Bearer tokens (JJWT) |
| Password hashing | BCrypt |
| Testing | JUnit 5, Mockito, Flapdoodle embedded MongoDB |

---

## Architecture

| Layer | Location | Pattern |
|---|---|---|
| Controllers | `controller/` | REST endpoints, request validation, maps DTOs ↔ service calls |
| Services | `service/` | Business logic, password hashing, delegates to repositories |
| Repositories | `repository/` | Spring Data MongoDB + custom `MongoTemplate` queries |
| DTOs | `dto/requests/`, `dto/responses/` | Immutable request DTOs (`@Valid`), response DTOs with Lombok `@Data` |
| Models | `model/` | MongoDB `@Document` entities with Lombok `@Builder` |
| Config | `config/` | Spring Security (stateless JWT), CORS, BCrypt, global exception handler |
| Util | `util/` | JWT generation/validation (`JwtUtil`), key generation |

---

## Key Conventions

- Endpoints are prefixed `/user/` (e.g. `/user/create`, `/user/login`)
- Auth: JWT Bearer token — `JwtAuthenticationFilter` puts username into `SecurityContextHolder`
- Authenticated endpoints: call `SecurityContextHolder.getContext().getAuthentication().getName()` to get current user
- **Public endpoints:** `/user/create`, `/user/login`, `/actuator/health`, `/actuator/info`
- All other endpoints require a valid JWT — add to `SecurityConfig.permitAll()` when making a new public endpoint
- Passwords hashed with BCrypt via `PasswordEncoder`
- Unit tests: Mockito (`@Mock`, `@InjectMocks`); integration tests: Flapdoodle embedded MongoDB
- Test properties: `src/test/resources/application.properties`
- New CORS origins: add to both `SecurityConfig.corsConfigurationSource()` AND `management.endpoints.web.cors.allowed-origins` in `application.properties`
- Backend responses must always return a DTO (never `void`) so the frontend can call `response.json()` safely
- No wildcard imports — use single explicit imports only
- Tests must start with `should`

---

## Feature Implementation Order

1. **DTO** — Create request DTO in `dto/requests/`, response DTO in `dto/responses/`
2. **Service** — Add/modify methods in the service layer
3. **Repository** — Add custom queries if needed
4. **Controller** — Wire endpoint, validate with `@Valid`, extract JWT user from `SecurityContextHolder`
5. **Security** — Update `SecurityConfig` if the new endpoint has different auth rules
6. **Tests** — Unit test controller (mock service) and service (mock repository)

---

## Build & Test

```bash
./gradlew test                                    # Run all tests
./gradlew test --tests "*.UserControllerTest"     # Run specific test class
./gradlew bootRun                                 # Start locally
```

---

## API Reference

| Method | Path | Auth | Request Body | Response |
|---|---|---|---|---|
| POST | `/user/create` | Public | `UserDTO { username, email, password }` | `User` |
| POST | `/user/login` | Public | `LoginRequestDto { username, password }` | `LoginResponseDto { success, message, username, email, token }` |
| POST | `/user/read` | JWT | `String username` | `User` |
| GET | `/user/readAll` | JWT | — | `List<User>` |
| PUT | `/user/update` | JWT | `UpdateProfileRequestDto { username, email, password }` | `UpdateProfileResponseDto { success, message, username, email }` |
| PATCH | `/user/update` | JWT | `UpdateUserRequestDto { username, updatedUsername, email, updatedEmail, updatedPassword }` | `void` |
| DELETE | `/user/delete` | JWT | `DeleteUserRequestDto { username }` | `void` |

### Matt-GPT v2 node/model layer (`/ai/**`, contract: `docs/ai-chat-contract.md` in the Fitz-Net hub)

All need `AI_USER` or `ADMIN` (`AiCatalogController`, gRPC `AiChatService`). New classes live in `service/ai/`.

| Method | Path | Response |
|---|---|---|
| GET | `/ai/models[?refresh=true]` | node + model catalogue (Ollama `/api/tags`, `/api/ps`, `/api/show`, cached; never exposes a node address) |
| POST | `/ai/warmup` | `{ state: loaded\|loading\|unavailable, nodeId, nodeName, model, message }` |
| GET / PUT | `/ai/preferences` | `{ lastTarget, lastPresetId, updatedAt }` (PUT: absent field = keep, `null` = clear) |

- Effective node status (`NodeStatusResolver`) is the only status to expose or route on: OFFLINE when the heartbeat is older than `ai.node.stale-after`, BUSY when the API has a generation in flight (`NodeActivityTracker`).
- `ModelRouter.route(model, nodeId|null, excluded)` resolves a target (Auto order in `AutoOrder`); `OllamaTurnStreamer` runs one generation and returns a `TurnResult` (it never writes `meta`/`done`/`error`).
- The turn engine talks to Ollama through the JDK `HttpClient` (HTTP/1.1; cancel the pending future before headers, close the body stream after), not `HttpURLConnection`: `HttpURLConnection.disconnect()` cannot wake a thread blocked reading a silent body, so cancel would be late. The JDK client has no read timeout, so the ticker enforces `ollama.read-timeout` as upstream silence.
### Matt-GPT v2 conversations (`/ai/conversations/**`)

| Method | Path | Notes |
|---|---|---|
| GET / POST | `/ai/conversations` | list (`q`, `limit`, `offset`; pinned first) / create |
| GET / PATCH / DELETE | `/ai/conversations/{id}` | detail with messages and context / PATCH (absent = untouched, `target` and `settings` replaced whole) / delete |
| POST | `/ai/conversations/{id}/title` | best-effort model title, unchanged on any failure |
| GET | `/ai/conversations/{id}/context?model=&nodeId=` | context usage preflight (503 when the target cannot be resolved) |
| POST | `/ai/conversations/{id}/summarize` | blocking summary of the trimmed turns (409 when nothing is trimmed) |
| POST | `/ai/conversations/{id}/messages`, `.../messages/{mid}/regenerate`, `.../messages/{mid}/edit` | `text/event-stream` turns (meta, status, thinking, token, done or error) |

Someone else's conversation is a 404. `TurnService.prepare` validates and applies (400/404/409, one generation per conversation via `GenerationRegistry`), `TurnService.run` routes, builds the prompt (`ContextBuilder`), streams and always persists the assistant message. Part 3 plugs in through `ConversationSettingsResolver` (presets) and `MessageLifecycleHook` (attachment cascade).

### Matt-GPT v2 presets and attachments

| Method | Path | Notes |
|---|---|---|
| GET / POST | `/ai/presets` | visible presets / create (`private` default; `shared` needs ADMIN) |
| PUT / DELETE | `/ai/presets/{id}` | replace / delete (private: owner only, 404 for others incl. ADMIN; shared: ADMIN only, 403) |
| POST | `/ai/attachments` | `{ name, mimeType, dataBase64 }`, png/jpeg/webp, magic number checked, at most `ai.chat.max-attachment-bytes` |
| GET | `/ai/attachments/{id}` | raw bytes, owner only, `Cache-Control: private, max-age=31536000, immutable` |

`ConversationSettingsResolver.resolve` reads the preset at generation time (a deleted or no longer visible preset is ignored). Attachments are attached to a user message by `TurnService.prepare` (clears `expiresAt`); `AttachmentCleanupHook` deletes them with their messages. A 3 MB image needs `spring.grpc.server.inbound.message.max-size` above gRPC's 4 MiB default only for headroom; Tomcat has no JSON body limit (Jackson caps a string at 20 M characters).

- Conversations are written by several actors (turns, PATCH, title, summarise): outside the turn pipeline use `ConversationWrites` (targeted `$set`/`$unset`) and never `conversations.save` a document loaded earlier; `summarize` and turns hold the conversation's `GenerationRegistry` slot, and the next `seq` comes from the stored messages, not from `nextSeq`.
- Background work must go through `MdcPropagation` so the `correlationId` follows it.

**Usernames are case-insensitive and stored lowercase.** Input is trimmed and lowercased before lookup, so `ZMat` and `zmat` are the same account. Login, reads, updates and deletes accept any casing; responses return the stored lowercase name.

**HTTP 409 means the username is already taken** (for example, on `POST /user/create` or a rename that matches another user's name ignoring case).

---

## Commit Convention

```
feat(subject): description
fix(subject): description
chore(subject): description
```

Use `feat` for new user-facing behavior, `fix` for bug fixes, `chore` for maintenance/tooling.

---

## Common Pitfalls

- **HTTP method mismatch:** `fetch()` method must match backend annotation (`@GetMapping`, `@PostMapping`, etc.)
- **DTO field name mismatch:** Java camelCase field names must match the JSON keys the frontend sends/expects
- **Void responses:** Always return a response DTO — never `void` — so `response.json()` works on the frontend
- **CORS:** New origins need updating in both `SecurityConfig.corsConfigurationSource()` AND `application.properties`
- **Security:** New public endpoints must be added to the `permitAll()` list in `SecurityConfig`

---

## gRPC (always alongside REST)

Every REST endpoint has an equivalent RPC in `src/main/proto/fitznet/api/v1/*.proto`, implemented by a
`@GrpcService` in `org.fitznet.fitznetapi.grpc` that delegates to the controller bean (so `@PreAuthorize`,
validation and error messages are shared). When you add or change a REST endpoint, in the same PR:

1. Add/adjust the RPC and messages in the matching `.proto` (never reuse field numbers).
2. Add the method to the `*GrpcService` (map proto → DTO → controller → proto in `ProtoMapper`). Run
   `GrpcRequestValidator.valid(dto)` on bodies that are `@Valid` in REST.
3. If the REST route is `permitAll()`, add the RPC to `GrpcAuthInterceptor.PUBLIC_METHODS`.
4. Add a case to `GrpcIntegrationTest` (success, auth failure, validation error).
5. Update `docs/grpc.md` in the Fitz-Net hub repo.

New `ResponseStatusException` types map automatically via `GrpcExceptionMapper`; a new exception class
needs a branch there, mirroring `GlobalExceptionHandler`.
