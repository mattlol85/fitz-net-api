# Fitz-Net API

Backend REST API for the Fitz-Net platform — user authentication, a real-time shared LiveBoard canvas, and an Overwatch 2 player tracker backed by the [OverFast API](https://overfast-api.tekrop.fr).

**Stack:** Java 21 · Spring Boot 3.4 · MongoDB · Spring Security (JWT) · Spring WebSocket (STOMP) · Gradle

---

## Table of Contents

- [Quick Start](#quick-start)
- [Environment Variables](#environment-variables)
- [Running Locally](#running-locally)
- [Docker](#docker)
- [API Reference](#api-reference)
  - [Authentication](#authentication)
  - [User](#user-endpoints)
  - [Overwatch](#overwatch-endpoints)
  - [LiveBoard (WebSocket)](#liveboard-websocket)
  - [Encryption](#encryption-endpoints)
  - [Actuator](#actuator-endpoints)
- [Data Model](#data-model)
- [Overwatch Refresh Cron](#overwatch-refresh-cron)
- [CI / CD](#ci--cd)

---

## Quick Start

```bash
# Prerequisites: Java 21, MongoDB

git clone https://github.com/mattlol85/fitz-net-api.git
cd fitz-net-api

export JWT_SECRET=your-secret-here
export MONGO_HOST=localhost

./gradlew bootRun
# API available at http://localhost:8080
```

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `JWT_SECRET` | Yes (prod) | `myDefaultSecretKey...` | HS256 signing secret for JWT tokens |
| `ENCRYPTION_KEY` | No | *(empty)* | AES key for the `/encrypt`/`/decrypt` endpoints |
| `MONGO_HOST` | No | `localhost` | MongoDB host |
| `MONGO_PORT` | No | `27017` | MongoDB port |
| `MONGO_DATABASE` | No | `test` | MongoDB database name (`fitznet` in prod) |
| `SPRING_PROFILES_ACTIVE` | No | *(default)* | Set to `prod` in production |

> **Note:** Never commit a real `JWT_SECRET` or `ENCRYPTION_KEY`. The defaults are for local development only.

---

## Running Locally

```bash
# Run tests (uses embedded MongoDB — no local Mongo needed for tests)
./gradlew test

# Build a runnable JAR
./gradlew bootJar

# Run the JAR directly
java -jar build/libs/fitz-net-api-*.jar
```

The server starts on port `8080` by default.

---

## Docker

Images are published to Docker Hub automatically on each release.

```bash
# Pull and run the latest image
docker run -d \
  -p 8080:8080 \
  -e JWT_SECRET=your-secret \
  -e MONGO_HOST=your-mongo-host \
  -e MONGO_DATABASE=fitznet \
  -e SPRING_PROFILES_ACTIVE=prod \
  mattlol85/fitz-net-api:latest
```

---

## API Reference

### Authentication

Protected endpoints require a `Bearer` token in the `Authorization` header, obtained from `POST /user/login`. Tokens expire after 24 hours.

```
Authorization: Bearer <token>
```

---

### User Endpoints

#### `POST /user/create` — public
Create a new account.

**Request body:**
```json
{ "username": "matt", "email": "matt@example.com", "password": "secret123" }
```

**Response `200`:**
```json
{ "id": "...", "username": "matt", "email": "matt@example.com", "boardColor": "hsl(210,72%,50%)" }
```

Fails `409` if username or email is already taken. Password must be at least 8 characters.

---

#### `POST /user/login` — public
Authenticate and receive a JWT.

**Request body:**
```json
{ "username": "matt", "password": "secret123" }
```

**Response `200`:**
```json
{
  "success": true,
  "message": "Login successful",
  "username": "matt",
  "email": "matt@example.com",
  "boardColor": "hsl(210,72%,50%)",
  "token": "<jwt>"
}
```

---

#### `POST /user/read` — 🔒 authenticated
Get a user by username.

**Request body:** `"matt"` (plain string)

**Response `200`:**
```json
{ "id": "...", "username": "matt", "email": "matt@example.com", "boardColor": "hsl(210,72%,50%)" }
```

---

#### `GET /user/readAll` — 🔒 authenticated
Get all registered users.

**Response `200`:** array of user objects (same shape as `/user/read`).

---

#### `PUT /user/update` — 🔒 authenticated
Update the authenticated user's profile (username, email, and/or password). Identity is taken from the JWT — you cannot update another user's profile.

**Request body:**
```json
{ "username": "matt", "email": "new@example.com", "password": "newpassword" }
```
`password` is optional. `username` must match the current authenticated username unless you're also changing it.

**Response `200`:**
```json
{ "success": true, "message": "Profile updated successfully", "username": "matt", "email": "new@example.com", "boardColor": "hsl(210,72%,50%)" }
```

---

#### `PATCH /user/update` — 🔒 authenticated
Partial field update (username, email, password). Accepts any subset of fields to change.

**Request body:**
```json
{ "username": "matt", "updatedUsername": "matt2", "updatedEmail": "new@example.com", "updatedPassword": "newpass" }
```

**Response `200`:** no body.

---

#### `DELETE /user/delete` — 🔒 authenticated
Delete a user account.

**Request body:**
```json
{ "username": "matt" }
```

**Response `200`:** no body. Fails `404` if user not found.

---

### Overwatch Endpoints

All Overwatch endpoints require authentication. Player data is sourced from the [OverFast API](https://overfast-api.tekrop.fr) and cached for 15 minutes.

---

#### `GET /overwatch/search?name=<query>` — 🔒 authenticated
Search for Overwatch players by name or BattleTag.

**Response `200`:** array of player search results.

---

#### `POST /overwatch/profile` — 🔒 authenticated
#### `PUT /overwatch/profile` — 🔒 authenticated
Link (or re-link) an Overwatch player to your account. Both methods are equivalent.

**Request body:**
```json
{ "playerId": "Zmat-1733", "gamemode": "competitive", "platform": "pc" }
```

`playerId` accepts either the BattleTag format (`Zmat#1733`) or the URL-safe format (`Zmat-1733`).

**Response `200`:** your full Overwatch profile (see profile shape below).

---

#### `GET /overwatch/me` — 🔒 authenticated
Get the authenticated user's linked Overwatch profile.

**Response `200`:**
```json
{
  "username": "matt",
  "playerId": "Zmat-1733",
  "battleTag": "Zmat-1733",
  "displayName": "Zmat-1733",
  "avatarUrl": "https://...",
  "lastUpdatedAt": "2026-05-30T18:30:00Z",
  "gamesWon": 278,
  "gamesPlayed": 559,
  "winrate": 49.73,
  "kda": 2.27,
  "eliminations": 6494,
  "deaths": 3438,
  "damage": 3213539,
  "healing": 1677928,
  "dpsRating": 1800,
  "tankRating": null,
  "healsRating": 2000,
  "dpsPeakRating": 1800,
  "tankPeakRating": null,
  "healsPeakRating": 2000
}
```

Returns `{ "username": "matt" }` (no Overwatch fields) if no profile is linked.

---

#### `GET /overwatch/leaderboard` — 🔒 authenticated
Get all users with linked Overwatch profiles, sorted by average competitive rating (falls back to win rate when no rated roles).

**Response `200`:** array of profile objects (same shape as `/overwatch/me`).

---

#### `GET /overwatch/me/history` — 🔒 authenticated
Get rating history and cross-season progression for the authenticated user.

**Response `200`:** season history including per-role rating timelines for the current season and the latest snapshot per historical season.

---

#### `GET /overwatch/{playerId}/history` — 🔒 authenticated
Get public rating history for any player by BattleTag (e.g. `Zmat-1733`). Does not require the player to be registered on Fitz-Net, but per-season history is only available for registered, linked users.

---

### LiveBoard WebSocket

A real-time shared canvas. Connect via STOMP over SockJS.

| Endpoint | Description |
|---|---|
| `WS /ws-board` | SockJS / STOMP handshake |
| `SUBSCRIBE /topic/board` | Receive canvas events broadcast to all clients |
| `SEND /app/board` | Publish a canvas event |

No authentication token is required to connect.

---

### Encryption Endpoints

Symmetric AES encryption utilities (requires `ENCRYPTION_KEY` to be set).

#### `POST /encrypt` — public
**Request body:** `{ "plaintext": "hello" }`
**Response:** `{ "ciphertext": "..." }`

#### `POST /decrypt` — public
**Request body:** `{ "ciphertext": "..." }`
**Response:** `{ "plaintext": "hello" }`

---

### Actuator Endpoints

| Endpoint | Auth | Description |
|---|---|---|
| `GET /actuator/health` | public | Service health |
| `GET /actuator/info` | public | Build info, Git commit, Java/OS metadata |

---

## Data Model

### User
Stored in the `users` MongoDB collection.

| Field | Type | Notes |
|---|---|---|
| `id` | String | MongoDB ObjectId |
| `username` | String | Lowercase, unique |
| `email` | String | Lowercase, unique |
| `password` | String | BCrypt hashed, never serialized |
| `boardColor` | String | HSL color auto-assigned on registration |
| `overwatch` | Object | Embedded `OverwatchProfile` — `null` if not linked |

### OverwatchProfile (embedded)
Stored inline on each `User` document. Fields mirror the `/overwatch/me` response.

### OverwatchRatingSnapshot
Stored in the `overwatch_rating_snapshots` collection. One document per refresh per user per season, used to build the rating history charts. Fields: `userId`, `season`, `recordedAt`, `dpsRating`, `tankRating`, `healsRating`.

---

## Overwatch Refresh Cron

A background job runs every 30 minutes to refresh ratings for all users with a linked Overwatch profile:

- Fetches current competitive ratings from OverFast
- Writes a new `OverwatchRatingSnapshot` for the current season
- Updates all-time peak ratings if any role hit a new high
- Skips users refreshed within the last 20 minutes (cooldown)
- Waits 3 seconds between each player request to stay within OverFast's Blizzard rate limits
- Logs and skips individual failures (e.g. OverFast 503) without aborting the cycle

Ratings are stored as approximate numeric SR (Bronze = 0–499, Silver = 500–999, … Champion = 3500+).

To change the current season label update `overwatch.current-season` in `application.properties`.

---

## CI / CD

| Workflow | Trigger | What it does |
|---|---|---|
| **Gradle Build** | Push / PR | Compiles, runs tests (embedded Mongo) |
| **Publish Release** | Manual (`workflow_dispatch`) | Bumps version, creates Git tag, publishes GitHub Release with JAR, builds and pushes Docker image to Docker Hub |
| **Copilot Code Review** | PR | Automated code review |

To cut a release, trigger **Publish Release** from the Actions tab and choose `major`, `minor`, or `patch`.
