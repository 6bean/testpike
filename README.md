# testPike — Autonomous AI Test Case Generation & Review System

> English | **[简体中文](README.zh-CN.md)**

testPike turns requirement documents into structured test cases and executable browser scripts,
then closes the loop: AI generation → human review → real execution → operations & governance.
Built on a Java 21 microservice backend, a Vue 3 console, and a Playwright executor cluster.

## Features

### AI test case generation
- Generate single or batch test cases (up to 50 per run, with cost estimation) from natural-language requirements or user stories
- Coverage gap-filling generation, style adaptation to your existing case library, and near-duplicate hints before submitting
- Structured output: steps, preconditions, priority and confidence suggestions, tags, per-endpoint attributes (web / mobile / POS)

### Script generation & editing
- Auto-convert approved cases into executable action-JSON scripts with locator-strategy generation
- Dry-run validation, L1 failure self-healing, codegen normalization
- Dual-mode editor: form mode and source mode with live diff preview, drag-and-drop step tree, loop/if control flow
- Assertions: visual regression plus non-UI primitives (console, network, HTTP, SQL), expect matchers with range/regex

### Real-browser execution
- Python 3.11 + Playwright executor cluster: register → heartbeat → claim → execute → report back
- Process-level isolation per task (reliable timeout kill, immune to lost browser sessions)
- Screenshot and artifact storage on SeaweedFS (S3-compatible); autonomous page exploration (agentic mode) on the roadmap-to-GA track

### Review workbench & case governance
- Review flow with flowchart generation and bidirectional description↔flowchart sync
- Case version history with field-level diff and rollback, cloning, tagging, assignment, recycle bin with restore
- Traceability views (requirement → case → script → execution → defect)

### Test operations
- Execution plans with lock modes, regression suites, batch scheduling with checkpoint/resume and watchdog
- Defect tracker, data factory, deployment status, progress dashboards, report center with saved statistics
- Flaky detection & quarantine, review reminders, daily digest, configurable data retention

### Knowledge, SKILL & MCP
- Project knowledge base on pgvector (semantic retrieval fused into generation prompts), rebuild & ledger reconciliation
- SKILL definitions with approval flow and marketplace; tool registry with generic ToolSPI and AI consumption chain
- MCP: connect external MCP servers as tools, and expose testPike itself as an MCP server
- Feedback & learning: quality feedback capture, training-sample collection, excellent-case marking

### Platform & governance
- Multi-project with tenant-level data isolation, project-scoped knowledge/style/environment config, RBAC
- LLM provider adapter (OpenAI-compatible), centralized key management (AES), token cost accounting with budget guard
- Prompt-injection protection, training-data masking, audit trail with integrity checks
- Security operations: login lockout, password policy, dual-token session rotation, environment health checks
- Notification center with channels and webhooks; i18n; dark (default) and light themes; global search
- CI/CD integration endpoints (runs API + status badges), `cli/` trigger tool, and an industry plugin extension framework (POS/ERP samples)

147 feature points are tracked in the requirement matrix; 122 are delivered, the rest are scheduled.

## Architecture

| Module | Role | Port |
| --- | --- | --- |
| `admin` | user-facing API gateway (auth, workbench, CRUD facades) | 8081 |
| `api` | machine-facing integrations (CI/CD, executor protocol, MCP server) | 8082 |
| `core` | service layer — domain logic, data, scheduling (consumed via Feign by admin/api) | 8083 |
| `ai-service` | AI orchestration — LLM/embedding clients, generation & review pipelines | 18084 |
| `common` | shared primitives (Response, exceptions, validation) | — |
| `executor` | Python Playwright cluster (register/heartbeat/claim/result protocol) | — |
| `frontend` | Vue 3 + TypeScript + Element Plus console | 5173 (dev) |

Infrastructure: PostgreSQL 16 + pgvector (15432), Redis 7 (6380), RabbitMQ 3 (5672), SeaweedFS S3 gateway (8333; metadata in a dedicated PostgreSQL database) —
all provided by `docker compose`. Request flow: frontend → admin → Feign → core / ai-service; async work
runs over RabbitMQ; artifacts go to object storage via S3 presigned URLs (SigV4); point S3_ENDPOINT at any cloud S3 API for hosted deployments.

## Installation

New users: follow the step-by-step [INSTALL.md](INSTALL.md) — prerequisites, wizard scenarios,
`.env` configuration, database initialization, first login, and a post-install acceptance
checklist ([简体中文版](INSTALL.zh-CN.md)). The summary below covers the same ground in brief.

### Guided installer (Linux / macOS / WSL)

```bash
bash installkit/install.sh --scenario=first
```

The first-run wizard asks a few questions, then installs the stack step by step. Every step is
idempotent (already-installed components are detected and skipped), an interrupted run resumes at
its checkpoint, and full logs land in `~/.testpike-installkit/logs/`. `--scenario=repair` runs a
health check that fills missing pieces in place.

Three resource tiers (disk/RAM figures are summed programmatically from the component matrix —
not hand-written; pass `--tier=lightweight|standard|full` to skip the question):

| Tier | Components | Disk | RAM |
| --- | --- | --- | --- |
| `lightweight` (default) | PostgreSQL / Redis / RabbitMQ / SeaweedFS + backend services | 2700 MB | 3968 MB |
| `standard` | + web console served by nginx (compose profile `app`) | 2900 MB | 4032 MB |
| `full` | + Prometheus / Grafana observability + Playwright executor | 4587 MB | 4864 MB |

Prerequisites: Docker with the Compose plugin, plus `git`, `curl` and `python3` (with `pip`) on the
host — the wizard checks them first and names exactly what is missing and how to install it.
Windows hosts use `installkit/install.ps1` (same scenario grid). `--unattended` runs the whole flow
with default answers on the same code path, so CI pipelines never hang on a prompt.

Other entry points: `--scenario=scale-out` enrols another machine into the executor pool with a
one-time token; `--scenario=scale-in` drains first and removes only what installkit itself
installed; `--scenario=engines` adds or swaps load-test engines from descriptors. Every
install-time value (ports, database name, object bucket, message vhost, thresholds) is read from
the project-root `.env` — see `.env.example` for the key set with shipped defaults; edit `.env`,
never the scripts.

Mobile (Android) journeys additionally require a real device or an emulator attached to an enrolled
executor; API/web journeys run without any device.

> Open-source installer policy: the wizard follows common open-source platform conventions
> (one-command bootstrap, idempotent steps, programmatically summed resource tiers). Design ideas
> were reviewed from projects such as TestHub, but no GPL-licensed source code is copied — every
> file under `installkit/` is an original implementation of this repository.

## Quick Start

### 1. Infrastructure (Docker)

```bash
docker compose up -d      # PostgreSQL 16 (host 15432) + Redis (6380) + RabbitMQ (5672) + SeaweedFS (8333)
```

### 2. Backend (JDK 21 + Maven)

```bash
# compile everything
mvn -q compile
# unit tests (per module)
mvn -pl core test
# run services (from each module directory)
mvn spring-boot:run       # admin:8081 / api:8082 / core:8083 / ai-service:18084
```

> Environment variables: start with `cp .env.example .env` (sample values match the docker compose
> development credentials, so it works out of the box); the app reads them from the project-root `.env`
> (never inline secrets; load via `set -a && source .env && set +a`). `.env` is gitignored and never committed.
> Database initialization: fully automatic for a fresh install — the first `docker compose up -d` on an empty
> data volume runs `db/schema-init.sql` (all V1..迁移件 migrations + seed data merged, 244K, 69 tables).
> To rebuild from an existing volume: `docker compose down -v && docker compose up -d`. Upgrading an
> existing database still applies incremental scripts from `db/migration/` manually (Flyway is not enabled).
> `db/schema-init.sql` is a generated artifact — regenerate it with `bash tools/ops/gen_schema_init.sh`
> after adding migrations; do not edit by hand.
> LLM: with `LLM_MOCK_ENABLED=true` (default) a built-in mock provider keeps everything runnable with no
> external API; switch it off and point `LLM_BASE_URL` / `LLM_API_KEY` at any OpenAI-compatible gateway.

### 3. Frontend (Vue 3)

```bash
cd frontend
npm install && npm run dev     # http://localhost:5173
npm run build                  # production build (use NODE_OPTIONS=--max-old-space-size=4096 to avoid OOM)
npx vue-tsc --noEmit           # type check
```

### 4. Executor (Python 3.11, real browser execution)

```bash
cd executor
pip install -r requirements.txt
python3 runner.py              # register → heartbeat → claim → subprocess execution → report back
```

> `python3 serve_fixtures.py &` starts local demo target pages (port 8899) so you can run scripts end-to-end
> without a real application under test.

## License

Apache License 2.0 (see LICENSE)
