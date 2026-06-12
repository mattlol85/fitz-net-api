# fitz-net-api — Agentic Development Guide

Spring Boot 3.4 REST API — the backend for Fitz-Net. Provides user management, authentication, and encryption services.

---

## Tech Stack

| Item | Value |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4 |
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
- **Public endpoints:** `/user/create`, `/user/login`, `/actuator/health`, `/actuator/info` (`/encrypt` and `/decrypt` require JWT)
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
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun      # Start locally (dev profile has a JWT fallback)
```

Startup fails fast if `JWT_SECRET` is unset or shorter than 32 bytes (except under the `dev` profile, which has a local-only fallback). Set `JWT_SECRET` in the environment to run the default profile.

---

## API Reference

| Method | Path | Auth | Request Body | Response |
|---|---|---|---|---|
| POST | `/user/create` | Public | `UserDTO { username, email, password }` | `User` |
| POST | `/user/login` | Public | `LoginRequestDto { username, password }` | `LoginResponseDto { success, message, username, email, token }` |
| POST | `/user/read` | JWT | `String username` | `User` |
| GET | `/user/readAll` | JWT | — | `List<User>` |
| PUT | `/user/update` | JWT | `UpdateProfileRequestDto { username, email, password }` | `UpdateProfileResponseDto { success, message, username, email }` |
| PATCH | `/user/update` | JWT | `UpdateUserRequestDto { updatedUsername, updatedEmail, updatedPassword }` (target is always the authenticated user; any body `username` is ignored) | `UpdateProfileResponseDto { success, message, username, email, boardColor }` |
| DELETE | `/user/delete` | JWT | — (deletes the authenticated user; no body) | `DeleteUserResponseDto { success, message, username }` |

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
