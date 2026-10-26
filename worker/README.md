# Bot runner (stage 2)

Build from the repository root with `./mvnw -pl worker -am package` (Windows: `mvnw.cmd -pl worker -am package`).

CLI: `java -jar worker/target/worker-0.0.1-SNAPSHOT.jar --botA <path> --botB <path> --seed N [--sandbox isolate|local] [--box-id N] [--box-root '/var/lib/isolate/{id}/box'] [--replay file.json]`.

Each bot path may be a single `.py`/`.jar` file or a directory containing `bot.py`, `__main__.py`, or `bot.jar`. The sandbox stages the selected artifact (and Python package files) into its box and executes that staged path. Isolate stderr is inherited by the worker console so runtime diagnostics are visible.

On Windows invoke the CLI through WSL2 or Docker for `isolate`. Each concurrent bot needs its own initialized isolate box; the default ids are `N` and `N+1`. Initialize boxes in cgroup mode (`isolate --box-id=N --cg --init`) before starting the worker. The runner stages one bot file into each box, enforces a 10-second reply timeout, and applies match-wide isolate caps of 200 CPU seconds, 600 wall seconds, 256 MiB of cgroup RAM and 16 processes. Java bots use a 64 MiB heap and a 1 GiB virtual-address allowance for JVM startup. Isolate installation/configuration must enforce network and filesystem policy (seccomp, read-only root, writable temporary area); those host policies cannot be safely provisioned by this Java adapter alone. The adapter caps each stdout line at 64 KiB; Isolate stderr is inherited by the worker console.

`--sandbox local` exists for development only. It runs bot code directly on the host, with no isolation or limits. Do not run untrusted bots in this mode.

Bot protocol uses one JSON object per line on stdin and one response per line on stdout. Responses are `{"pieceId":3,"to":[5,2]}`. Keep diagnostics on stderr. Templates are in `bots/java`, `bots/cpp`, and `bots/python`; malicious examples are in `bots/malicious` and must only be used against a correctly provisioned Linux isolate installation.

## Match worker (phase 3c)

Build the worker artifact from the repository root with `./mvnw -pl worker -am package` (Windows: `mvnw.cmd -pl worker -am package`). Start `ottv2.worker.match.MatchWorkerCommand` on Linux/WSL2 after PostgreSQL, RabbitMQ, MinIO, and the API are running. It consumes `match.ladder.q` and `match.tournament.q`, loads immutable artifacts from MinIO through a bounded LRU cache, runs matches, and publishes typed `MATCH_STARTED`, `MATCH_FINISHED`, or `MATCH_FAILED` events to `result.q`. The worker has no database dependency or credentials.

Set `ISOLATE_BOX_ID` to a unique base id per worker process and initialize that id plus the next id before startup. In a Kubernetes StatefulSet, set `POD_NAME` from the pod name and `ISOLATE_BOX_BASE_ID`; the worker derives a stable pair of isolate ids from the ordinal. Set `ISOLATE_BOX_ROOT` only when isolate uses a non-default box path. Each successful match uploads `matches/{publicId}/replay.json.gz` and per-bot gzip logs before publishing the result. The API stores the object keys and enforces participant/owner checks when serving them. `WORKER_METRICS_PORT` (default `9404`) exposes match timing, queue lag, process CPU, and in-flight metrics at `/metrics`.

Directly requested friendly matches and scheduled ladder/tournament matches all use the same isolated execution path.
