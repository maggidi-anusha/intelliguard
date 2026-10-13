# Deployment (production-style, Docker Compose)

`infra/docker-compose.prod.yml` is a **standalone** compose file (not an override of the
development `docker-compose.yml`). It runs:

- the frontend as a static build served by **nginx**, which also proxies `/api` to backend-core,
  so the browser uses a single origin;
- healthchecks on postgres, backend-core, ml-service and the frontend, with start order through
  `depends_on: service_healthy`;
- `restart: unless-stopped` everywhere, and Postgres data on a named volume;
- only the frontend (`FRONTEND_PORT`, default 80) and the Jaeger UI (`JAEGER_UI_PORT`, default
  16686) published to the host. backend-core, ml-service and Postgres are reachable only on the
  internal network;
- backend-core with the `prod` Spring profile. It refuses to start without a JWT secret of at
  least 32 characters that isn't a placeholder. The compose file itself refuses to start when a
  required secret is missing from `.env`; there are no insecure defaults.

A cloud VM takes the same steps: install Docker, open only the frontend port (put TLS in front of
it, e.g. a reverse proxy or load balancer), and follow the steps below.

## Prerequisites

- Docker Engine with Docker Compose v2.17 or newer (`additional_contexts` is used to build
  ml-service), about 4 GB of free memory, and `git` and `openssl`.

## Steps

```bash
git clone https://github.com/maggidi-anusha/intelliguard.git
cd intelliguard/infra
cp .env.example .env
```

Edit `infra/.env` and set at least:

| Variable | Value |
|---|---|
| `INTELLIGUARD_DB_NAME`, `INTELLIGUARD_DB_USERNAME`, `INTELLIGUARD_DB_PASSWORD` | the database name, user and a strong password |
| `INTELLIGUARD_SIMULATOR_ADMIN_PASSWORD` | password of the first ADMIN account (at least 8 characters) |
| `INTELLIGUARD_JWT_SECRET` | output of `openssl rand -base64 48` |
| `FRONTEND_PORT` | optional, default 80 |

Then build and start:

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker ps --filter label=com.docker.compose.project=intelliguard-prod   # wait for (healthy)
```

Open `http://<host>:<FRONTEND_PORT>/`.

## Accounts

- **First admin:** at startup backend-core creates the ADMIN account `simulator-admin` with the
  password `INTELLIGUARD_SIMULATOR_ADMIN_PASSWORD`, if that account doesn't exist yet. An existing
  account is never modified. Changing the variable later doesn't change the password.
- **USER / VIEWER / more ADMIN accounts:** an ADMIN creates them through the API (public
  registration only creates read-only VIEWER accounts):

```bash
BASE=http://localhost:${FRONTEND_PORT:-80}
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username": "simulator-admin", "password": "<admin password>"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST $BASE/api/admin/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username": "oncall-1", "email": "oncall-1@example.com", "password": "<at least 8 chars>", "role": "USER"}'
```

Roles: `VIEWER` (read only, can ask the copilot), `USER` (also writes services, dependencies,
incident notes and acknowledgements), `ADMIN` (also telemetry ingestion, ground truth and
accounts). Login tokens last 15 minutes.

## Simulator (synthetic telemetry)

The simulator is optional and starts only with the `simulator` profile:

```bash
docker compose -f docker-compose.prod.yml --profile simulator up -d
```

The mode comes from `INTELLIGUARD_SCENARIO_MODE` in `.env`:

- `demo` (default): a fixed 18-minute schedule.
- `continuous`: random anomalies.
- `normal`: no anomalies.
- `cascade`: root-cause cascades with distractors.

Set `INTELLIGUARD_SIMULATOR_SEED` for a reproducible run. Stop it with
`docker compose -f docker-compose.prod.yml stop simulator`.

## Logs

```bash
docker compose -f docker-compose.prod.yml logs -f backend-core     # or ml-service, frontend, simulator, postgres
```

## Backup and restore (Postgres)

Load the variables into the shell first: `set -a; . ./.env; set +a`.

```bash
# Backup (plain SQL)
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "$INTELLIGUARD_DB_USERNAME" -d "$INTELLIGUARD_DB_NAME" > intelliguard-$(date +%Y%m%d-%H%M).sql

# Restore into an empty schema: stop the writers first, then load the dump
docker compose -f docker-compose.prod.yml stop backend-core simulator
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$INTELLIGUARD_DB_USERNAME" -d "$INTELLIGUARD_DB_NAME" -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$INTELLIGUARD_DB_USERNAME" -d "$INTELLIGUARD_DB_NAME" < intelliguard-YYYYMMDD-HHMM.sql
docker compose -f docker-compose.prod.yml start backend-core
```

The data lives in the named volume `intelliguard-prod_pgdata`. `docker compose ... down` keeps it,
but `down -v` deletes it.

## Upgrade and rollback

```bash
# Upgrade: back up first, then pull and rebuild; the schema is updated at startup
git pull
docker compose -f docker-compose.prod.yml up -d --build

# Rollback: rebuild the previous version; if the new version already changed the data, restore the backup taken before the upgrade
git checkout <previous tag or commit>
docker compose -f docker-compose.prod.yml up -d --build
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `required variable ... is missing a value` | A required variable is empty in `infra/.env`. Set it. |
| backend-core keeps restarting, log says `JWT_SECRET must ...` | With the prod profile the JWT secret must be set, at least 32 characters, and not a placeholder. Generate one with `openssl rand -base64 48`. |
| frontend not healthy, or 502 on `/api/...` | backend-core isn't healthy yet or crashed: check `logs backend-core`. The frontend starts only after backend-core is healthy. |
| `port is already allocated` | Another process (or the development stack) uses `FRONTEND_PORT` or 16686. Change the port in `.env`, or stop the other stack with `docker compose stop`. |
| everyone is logged out after a restart | The JWT secret changed. Keep `INTELLIGUARD_JWT_SECRET` stable. |
| build fails on `additional_contexts` | Docker Compose is older than v2.17. Update Docker. |
| no data on the dashboard | Nothing is sending telemetry. Start the simulator (above) or your own agents (ADMIN-only ingestion API). |
