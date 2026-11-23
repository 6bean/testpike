# testPike Installation Guide (new users)

> English | **[简体中文](INSTALL.zh-CN.md)**

This guide takes you from an empty machine to a verified, logged-in testPike instance:
prerequisites, the guided installer, configuration, database initialization, first login,
and a post-install acceptance checklist. It is written for evaluators and operators;
contributors who run services from source can use the shorter
[Quick Start in README.md](README.md#quick-start) instead.

## 1. Prerequisites

| Requirement | Notes |
| --- | --- |
| Docker + Compose plugin | Provides PostgreSQL 16 (pgvector), Redis 7, RabbitMQ 3, SeaweedFS S3, and (in higher tiers) the app/console/observability services |
| `git`, `curl`, `python3` (with `pip`) on the host | The installer checks these first and names exactly what is missing, with the right package-manager command for your distro |
| Free disk / RAM | See the tier table in §2.3 |

Linux, macOS and WSL2 use `installkit/install.sh`; Windows hosts use
`installkit/install.ps1` (same scenario grid, same underlying steps).

## 2. Run the guided installer

### 2.1 One command

```bash
git clone <this repository> testpike && cd testpike
cp .env.example .env          # review/adjust before installing (see §3)
bash installkit/install.sh --scenario=first
```

The wizard asks a few questions (role, tier, engines, mirror, autostart — every question has
a default, so you can press Enter through), then installs step by step. Guarantees:

- **Idempotent** — already-installed components are detected and skipped; re-running is safe.
- **Resumable** — an interrupted run continues from its checkpoint
  (`~/.testpike-installkit/state/`); pass `--restart` to clear checkpoints and redo a scenario.
- **Logged** — every run writes full logs to `~/.testpike-installkit/logs/`.
- **Non-destructive to your other services** — a port held by a foreign service is reported
  in plain words; the installer never stops services it did not install.
- **CI-friendly** — `--unattended` runs the identical code path with default answers.

### 2.2 Scenarios

| Command | What it does |
| --- | --- |
| `--scenario=first` | First install: questions → compose stack → services → demo-data seed → trial-run pointer |
| `--scenario=repair` | Health check (doctor probes) + fills missing pieces in place, then re-checks |
| `--scenario=scale-out --token=<one-time token> --base-url=http://<host>:18082` | Enrols another machine into the executor pool (token generated on the platform's cluster page) |
| `--scenario=scale-in` | Drains first, then removes only what installkit itself installed |
| `--scenario=engines --engine=<code> [--dry-run]` | Adds or swaps load-test engines from descriptors (e.g. k6, Gatling) |

### 2.3 Resource tiers

The wizard asks for a tier; skip the question with `--tier=lightweight|standard|full`.
Disk/RAM figures are summed programmatically from the component matrix.

| Tier | Components | Disk | RAM |
| --- | --- | --- | --- |
| `lightweight` (default) | PostgreSQL / Redis / RabbitMQ / SeaweedFS + backend services | 2700 MB | 3968 MB |
| `standard` | + web console served by nginx | 2900 MB | 4032 MB |
| `full` | + Prometheus / Grafana observability + Playwright executor | 4587 MB | 4864 MB |

`lightweight` runs the API surface only — open the console via `frontend` dev mode
(`cd frontend && npm install && npm run dev`, port 5173) or choose `standard` to get the
console served for you.

## 3. Configuration: everything lives in `.env`

Every install-time value (ports, database name, object bucket, message vhost, thresholds) is
read from the project-root `.env` — key set and shipped defaults are documented in
`.env.example`. Edit `.env`, never the scripts. Never commit it (already gitignored).

Keys worth reviewing before first start:

| Key | Shipped sample value | Meaning |
| --- | --- | --- |
| `ADMIN_INIT_PASSWORD` | `Admin#123` | Seeds the `admin` account on first core start; if absent, no account is created (fail-closed). **Change it right after the first login.** |
| `LLM_MOCK_ENABLED` | `false` | `true` = built-in mock provider: the full pipeline runs offline with zero API keys. `false` = real mode, then set `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` to any OpenAI-compatible gateway. |
| `TP_WEB_HOST_PORT` | `18080` | Browser console door (standard/full tiers). |
| `APP_ADMIN_HOST_PORT` | `18081` | User-facing API gateway (also used by the demo-data seeder). |
| `APP_API_HOST_PORT` | `18082` | Machine-facing API (CI/CD, executor protocol, MCP). |
| `APP_CORE_HOST_PORT` | `18083` | Internal service layer (loopback; not part of the user path). |
| `APP_AISERVICE_HOST_PORT` | `18084` | AI orchestration service (loopback). |
| `LLM_TIMEOUT_SECONDS` | `800` | Per-request LLM timeout; keep ≥ 800 for long generations. |

### Port map (shipped defaults)

| Service | Host port | | Service | Host port |
| --- | --- | --- | --- | --- |
| Web console | **18080** | | PostgreSQL | 15432 |
| admin API | 18081 | | Redis | 6380 |
| api (machine) | 18082 | | RabbitMQ AMQP / mgmt | 5672 / 15672 |
| core (internal) | 18083 | | SeaweedFS S3 | 8333 |
| ai-service (internal) | 18084 | | SeaweedFS consoles (login-protected) | 9000 / 9001 |
| Prometheus | 19090 | | Grafana | 13000 |

All are `${KEY:-factory default}` — set the key in `.env` to move a port.
When running services from source (dev mode) the in-container ports apply instead:
admin 8081, api 8082, core 8083, ai-service 18084, Vite dev console 5173,
demo fixture target site 8899 (`python3 serve_fixtures.py` in `executor/`).

## 4. Database initialization

No manual steps for a fresh install:

- The first `docker compose up -d` on an empty data volume automatically runs
  `db/schema-init.sql` — all migrations plus factory seed data merged
  (69 tables: RBAC roles, system config defaults, prompt pack, dictionaries, demo accounts).
- Rebuild from scratch: `docker compose down -v && docker compose up -d`.
- Upgrading an existing database: apply the incremental scripts in `db/migration/` manually
  (Flyway is not enabled). `db/schema-init.sql` is a generated artifact — regenerate with
  `bash tools/ops/gen_schema_init.sh` after adding migrations; do not edit by hand.

## 5. First login

1. Open the console: `http://localhost:18080` (standard/full tier; `http://localhost:5173`
   in dev mode).
2. Sign in as **`admin`** with the value of `ADMIN_INIT_PASSWORD` from your `.env`
   (sample value `Admin#123`). Change the password immediately — the password-expiry policy
   will push you to do so on a later login if you don't.
3. Optional, for a zero-effort tour — five demo accounts come seeded, one per role, all
   sharing the factory demo password **`Demon@tp2026`**:

   | Account | Role | What you can see |
   | --- | --- | --- |
   | `demo_developer` | developer | author cases/scripts, run them |
   | `demo_tester` | tester | execution keys: run batches |
   | `demo_test_lead` | test lead | review & assign |
   | `demo_project_manager` | project manager | plans & approvals, no execution |
   | `demo_admin` | system admin | full console incl. audit |

   These are development/demo credentials shipped in the open-source seed, not secrets —
   delete or change them before exposing an instance to any real network.

The `--scenario=first` wizard also auto-seeds demo content at the end (sample project with
one case, the five-minute guided journey, order/inventory micro-apps plus a demo performance
scenario). If the platform wasn't ready at that moment it says so in plain words and prints
one re-runnable command:

```bash
python3 installkit/s1_bootstrap.py seed-demo --base-url http://localhost:18081
```

Seeding is idempotent — re-running reuses same-named items instead of duplicating.

## 6. Post-install acceptance checklist

Work top to bottom; each row is one command (or one click) with the pass condition.

| # | Check | Command / action | Pass condition |
| --- | --- | --- | --- |
| 1 | Containers healthy | `docker compose ps` | every service in the chosen tier shows `healthy` (first app start can take ~1–2 min for health checks) |
| 2 | Four services up | `curl -s http://127.0.0.1:18081/actuator/health` (repeat for `18082`, `18083`, `18084`) | `{"status":"UP"}` each |
| 3 | Doctor probe suite | `python3 installkit/doctor.py --state-dir ~/.testpike-installkit/state` | no blocking `missing_*` lines; hints name the fix command |
| 4 | Console door | open `http://localhost:18080` | login page renders (no browser console errors) |
| 5 | First login | sign in as `admin` with `ADMIN_INIT_PASSWORD` | session opens on the dashboard |
| 6 | Seeded data visible | project list | sample project, five-minute-journey items and 订单中心 / 库存中心 projects are listed |
| 7 | AI path runnable | any generation entry | with `LLM_MOCK_ENABLED=true` a mock answer completes the loop offline; with `false`, responses come from your configured gateway |
| 8 | Executor pool (full tier) | `python3 executor/runner.py` on an installed executor machine, then console → executors page | runner registers and shows `online` with heartbeat |
| 9 | End-to-end smoke | open a seeded API-case journey and run it against the built-in fixture target | batch reaches a terminal state with report/screenshots |
| 10 | Logs reachable | `ls ~/.testpike-installkit/logs` | dated install logs exist |

Anything red: run `bash installkit/install.sh --scenario=repair` — it re-probes, fills what is
missing in place, prints a structured report, and re-checks after fixing.

## 7. Upgrading, scaling, removing

- **Upgrade services:** `git pull`, rebuild images (`docker compose build`),
  `docker compose up -d`; apply new `db/migration/*.sql` on existing databases as above.
- **Add executor capacity:** on the platform's cluster page generate a one-time token, then
  on the new machine run `bash installkit/install.sh --scenario=scale-out --token=<token>
  --base-url=http://<platform-host>:18082`.
- **Remove a machine:** `--scenario=scale-in` drains its queue first and removes only
  installkit-managed pieces.
- **Mobile (Android) journeys:** attach a real device or emulator to an enrolled executor
  (`adb devices` visible). API/web journeys need no device.

## 8. Troubleshooting quick table

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Installer names a held port and stops | A service you already run owns that port | Stop/choose the other service, or remap via the matching `.env` key, re-run the same command (resumes from checkpoint) |
| `core` restarts with AMQP 530 loop | Custom RabbitMQ vhost missing on a fresh broker | Re-run `--scenario=first` (or repair); vhost reconciliation is a checkpointed step |
| Login says locked | Repeated wrong passwords | Lockout is time-boxed — wait, or reset via `docker exec <postgres-container> psql -U testpike -d testpike -c "update tp_user set fail_count=0, locked_until=null where username='admin'"` |
| AI features time out | Real gateway unreachable / short timeout | Check `LLM_BASE_URL`/`LLM_API_KEY`, keep `LLM_TIMEOUT_SECONDS` ≥ 800, or flip `LLM_MOCK_ENABLED=true` to isolate the network from the pipeline |
| Docker pull slow in your region | Registry latency | Re-run with `--mirror-base-url=<your mirror root>` (first scenario) or set the platform key on the cluster page |
| Unsure what broke | — | `bash installkit/install.sh --scenario=repair` then read `~/.testpike-installkit/logs/` |

---

Next steps after acceptance: sign in, open the dashboard's first-login guide overlay, and
walk the five-minute journey (generate → review → run → report) on the seeded sample project.
