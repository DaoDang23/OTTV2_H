# API module (Phase 3a)

The API stores users and bots in PostgreSQL 16. Flyway applies the schema in `src/main/resources/db/migration` at startup.

## Local run

From the repository root:

```sh
docker compose up -d postgres
./mvnw -pl api -am spring-boot:run
```

In Windows PowerShell, use `docker compose up -d postgres` followed by `.\mvnw.cmd -pl api -am spring-boot:run`.

For submissions and ladder play, start local infrastructure with `docker compose up -d postgres rabbitmq redis minio` first. RabbitMQ management is at port 15672, Redis at port 6379, and the MinIO console at port 9001. The default credentials in Compose are for local development only; set the corresponding environment variables before exposing any service.

Configuration can be overridden with `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `SESSION_COOKIE_SECURE`.

## Authentication flow

The API uses server-side sessions and BCrypt password hashes. The browser must first call `GET /api/auth/csrf`, then send the returned token in the returned header for state-changing requests. The session cookie is HTTP-only and SameSite=Lax; enable `SESSION_COOKIE_SECURE=true` behind HTTPS.

Endpoints:

- `POST /api/auth/register` — `{ "studentCode", "email", "password", "displayName" }` (password 12–72 characters)
- `POST /api/auth/login` — `{ "email", "password" }`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `GET /api/bots`
- `POST /api/bots` — `{ "name" }`
- `POST /api/bots/{botId}/submissions` — `{ "language": "JAVA|CPP|PYTHON", "source": "..." }`
- `GET /api/bots/{botId}/submissions`
- `GET /api/bots/{botId}/submissions/{submissionId}`
- `GET /api/matches/playable-submissions` — compiled versions available for matches (metadata only)
- `POST /api/matches` — `{ "submissionAId": 12, "submissionBId": 18 }`; caller must own at least one bot
- `GET /api/matches`
- `GET /api/matches/{publicId}`
- `GET /api/matches/{publicId}/replay` — participant owners only; gzip JSON response
- `GET /api/matches/{publicId}/logs?side=A|B` — only the owner of that side's bot can read its log
- `POST /api/ladder/online` — `{ "botId": 10, "submissionId": 23 }`; refresh this periodically to keep the bot online
- `DELETE /api/ladder/online/{botId}` — take an owned bot offline
- `GET /api/ladder/rankings?seasonId=1&limit=100` — Elo ranking from Redis (limit capped at 500)
- `GET /api/ladder/history` — ladder matches for bots owned by the caller
- `POST /api/tournaments` — admin creates a tournament with `{ "seasonId": 1, "name": "Finals" }`
- `GET /api/tournaments` — list tournaments available for viewing
- `POST /api/tournaments/{id}/seed` — admin freezes the season, snapshots the Top 32, builds and starts the bracket
- `POST /api/tournaments/{id}/matches/{publicId}/retry` — admin requeues a failed infrastructure match
- `GET /api/tournaments/{id}` — bracket and per-game status
- `WS /ws` STOMP topic `/topic/tournaments/{id}` — `BRACKET_UPDATED` event after bracket changes; fetch the GET endpoint for the current bracket

Bot queries and creation always use the authenticated user's id as `owner_id`.
Submissions are limited to 256 KiB, one per 30 seconds, and 50 per day by default. Override these with `SUBMISSION_MAX_BYTES`, `SUBMISSION_COOLDOWN_SECONDS`, and `SUBMISSION_DAILY_LIMIT`.

## Compile worker

The compile worker is a separate Linux process and never connects to PostgreSQL. Build it with `./mvnw -pl worker -am package`, then run:

```sh
ISOLATE_BOX_ID=100 java -cp worker/target/worker-0.0.1-SNAPSHOT.jar ottv2.worker.compile.CompileWorkerCommand
```

Run it from WSL2 or Linux with `isolate`, `g++`, Python 3, and JDK 21 installed. `ISOLATE_BOX_ID` must be unique per worker process, and the worker must have permission to initialize isolate boxes under `/var/lib/isolate`. Java bot source must define the protocol entry point `public class Bot` with a `main` method. Kotlin and JavaScript are stored in the schema but are not compiled by this phase's worker.

## Match queue and worker

The API creates a `FRIENDLY` match for two compiled submissions, snapshots the submission/artifact ids, and writes a `match.ladder.q` outbox event in the same database transaction. `MatchWorkerCommand` consumes that queue, runs the referee and both bots in isolate, uploads replay and compressed bot logs to MinIO, then publishes typed match events to `result.q`. The API result listener routes compile and match events and stores match status, participant faults, and replay metadata idempotently. Match workers do not connect to PostgreSQL.

For local development, keep PostgreSQL, RabbitMQ, and MinIO running with `docker compose up -d postgres rabbitmq minio`. Run the worker under Linux/WSL2 with isolate configured and two unique boxes initialized:

```sh
sudo isolate --box-id=200 --cg --init
sudo isolate --box-id=201 --cg --init
ISOLATE_BOX_ID=200 java -cp worker/target/worker-0.0.1-SNAPSHOT.jar ottv2.worker.match.MatchWorkerCommand
```

Choose a different `ISOLATE_BOX_ID` for each concurrent worker process; each worker uses that id and the next one for its two bots. Configure `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `MINIO_ENDPOINT`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, and `MINIO_BUCKET` as needed. Infrastructure failures retry three times before `match.dlq`; bot timeout/crash is a normal match result and is not retried.

Run the replay frontend from `web/` with `npm install` followed by `npm run dev`. Vite proxies `/api` to `http://localhost:8080` by default. It uses the existing session/CSRF login, lets users queue a compiled submission against another playable bot, and shows saved replay frames and bot logs.

## Ladder (phase 4)

Create an active season and configure its `ladder_windows` rows in PostgreSQL. If the season has no configured windows, the scheduler uses Monday–Friday, 19:00–22:00 Asia/Ho_Chi_Minh. The API scheduler checks every five seconds, pairs online bots by closest Elo (initial range 100, widening by 50 per waiting minute), avoids the three most recent opponents, and caps a bot at one two-game series per minute by default. Set `LADDER_POLL_MS`, `LADDER_PRESENCE_TTL_SECONDS`, and `LADDER_MAX_MATCHES_PER_MINUTE` to adjust these settings.

Each pairing creates two matches with the same seed and selected submissions, with the sides swapped for the second game. Elo is updated once both games finish; a split series is recorded as a draw. Online presence expires after two minutes without a heartbeat. The `match.ladder.q` consumer remains the existing isolated match worker.

## Tournament (phase 5)

An admin creates a tournament for an active season and seeds it once at least 32 bots have ratings and compiled submissions. Seeding freezes that season's ladder, pins each entry's submission and Elo, builds the documented 31-node bracket, and queues the opening round. Nodes start with two side-swapped games; an unresolved result queues a third game. A winner advances automatically, and the championship completion marks the tournament finished. The match worker consumes `match.tournament.q`; the STOMP topic emits a notification after each committed bracket update.

## Metrics (phase 6)

The API exposes Prometheus metrics at `http://localhost:8081/actuator/prometheus`; the worker exposes them at `http://localhost:9404/metrics`. `MANAGEMENT_BIND_ADDRESS` controls the API metrics bind address. The local monitoring stack is in `compose.yaml`; Prometheus scrapes API and worker ports through `host.docker.internal` and RabbitMQ's per-object metrics endpoint.
