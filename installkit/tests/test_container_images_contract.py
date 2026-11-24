"""Contracts for the containerized delivery surface (T293a images + compose profiles).

Why these tests exist: the open-source delivery promise is "docker compose up"
brings the whole product stack, and the main dev stack (middleware services in
docker-compose.yml) must keep working untouched. Each assertion below pins one
delivery-critical property that a later refactor could silently break:
  - backend images build from JDK 21 with layered extraction (fast, cacheable),
  - no credentials or .env ever bake into an image,
  - the frontend image serves the SPA and reverse-proxies /api and /open,
  - compose declares the app tier under an opt-in profile so existing
    `docker compose up -d` behavior (middleware only) does not change,
  - every host-facing port/credential is an env key with a shipped default.
"""

import re
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
DOCKERFILE_BACKEND = REPO / "deploy" / "docker" / "Dockerfile.backend"
DOCKERFILE_FRONTEND = REPO / "deploy" / "docker" / "Dockerfile.frontend"
NGINX_TEMPLATE = REPO / "deploy" / "docker" / "nginx.conf.template"
DOCKER_IGNORE = REPO / ".dockerignore"
COMPOSE = REPO / "docker-compose.yml"

APP_SERVICES = ["tp-core", "tp-admin", "tp-api", "tp-ai-service", "tp-web"]
MIDDLEWARE_UNTOUCHED = [
    "postgres", "redis", "rabbitmq", "seaweedfs", "seaweedfs-dbinit",
    "seaweedfs-ui-auth",
]


def _compose() -> dict:
    return yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))


def test_backend_dockerfile_exists_and_is_layered():
    assert DOCKERFILE_BACKEND.is_file(), "backend image recipe is missing"
    text = DOCKERFILE_BACKEND.read_text(encoding="utf-8")
    assert re.search(r"^ARG SERVICE", text, re.M), (
        "one recipe parameterized over the four backend modules (single source, "
        "per the no-duplicated-logic rule)")
    for module in ["core", "admin", "api", "ai-service"]:
        assert module in text, f"module {module} must be a selectable build arg"
    assert "eclipse-temurin:21" in text, "builder must run on JDK 21"
    assert text.count("FROM ") >= 2, "multi-stage build is required"
    # Amended 2026-11-21 (MP5 boot finding, shipped in the fat-jar switch): the
    # extract --layers shape produced a thin jar with a broken Class-Path chain
    # in the container, so the runtime contract is now the self-contained boot
    # jar staged under a stable name and launched with java -jar.
    assert "/staged/app.jar" in text and "exec java $JAVA_OPTS -jar /app/app.jar" in text, (
        "self-contained boot jar launch (no extraction: thin-jar Class-Path drift)")
    assert re.search(r"^USER ", text, re.M), "runtime must drop to a non-root user"


def test_backend_dockerfile_bakes_no_secrets():
    text = DOCKERFILE_BACKEND.read_text(encoding="utf-8")
    assert not re.search(r"^COPY .*\.env", text, re.M), (
        "the install-time .env carries credentials and must never be copied")
    assert "ENV DB_PASSWORD" not in text and "ENV DB_URL" not in text, (
        "credentials reach the container only through compose environment keys")


def test_dockerignore_excludes_credentials_and_build_leftovers():
    assert DOCKER_IGNORE.is_file(), ".dockerignore is missing"
    lines = {
        ln.strip() for ln in DOCKER_IGNORE.read_text(encoding="utf-8").splitlines()
        if ln.strip() and not ln.strip().startswith("#")
    }
    for required in [".env", "**/.env", "node_modules", "**/target", ".git"]:
        assert required in lines, f".dockerignore must exclude {required}"


def test_frontend_dockerfile_builds_and_serves_via_nginx():
    assert DOCKERFILE_FRONTEND.is_file(), "frontend image recipe is missing"
    text = DOCKERFILE_FRONTEND.read_text(encoding="utf-8")
    assert "npm ci" in text, "clean install from the lockfile, not npm install"
    assert "vite build" in text or "npm run build" in text
    assert "nginx" in text, "runtime stage must be nginx (SPA + reverse proxy)"
    assert r"COPY .*\.env" not in text.replace("\\", "") or ".env" not in text


def test_nginx_template_proxies_api_and_open_homes():
    assert NGINX_TEMPLATE.is_file(), "nginx template is missing"
    text = NGINX_TEMPLATE.read_text(encoding="utf-8")
    assert re.search(r"location\s+/\w*api", text), "browser calls /api -> admin"
    assert re.search(r"location\s+/\w*open", text), "share doors ride /open -> api"
    assert "${TP_ADMIN_UPSTREAM}" in text and "${TP_API_UPSTREAM}" in text, (
        "upstream hosts come from env (nginx official image envsubst templates)")
    assert "try_files" in text, "SPA deep links must fall back to index.html"


def test_compose_app_tier_declared_under_opt_in_profile():
    data = _compose()
    services = data["services"]
    for name in APP_SERVICES:
        assert name in services, f"app service {name} missing from compose"
        svc = services[name]
        assert svc.get("profiles") == ["app"], (
            "the app tier must sit behind the `app` profile so the existing "
            "`docker compose up -d` middleware-only behavior is unchanged")
    for name in MIDDLEWARE_UNTOUCHED:
        assert name in services, f"middleware {name} was removed — main chain depends on it"
        assert "profiles" not in services[name], (
            "middleware stays unprofiled; default up must keep starting it")


def test_compose_app_tier_build_wiring_and_image_tags():
    services = _compose()["services"]
    for name in ["tp-core", "tp-admin", "tp-api", "tp-ai-service"]:
        build = services[name].get("build") or {}
        assert build.get("dockerfile", "").endswith("Dockerfile.backend")
        assert build.get("args", {}).get("SERVICE") in {"core", "admin", "api", "ai-service"}
        assert "image" in services[name], "every app service needs an image tag"
    web_build = services["tp-web"].get("build") or {}
    assert web_build.get("dockerfile", "").endswith("Dockerfile.frontend")


def test_compose_app_tier_points_at_in_network_middleware():
    services = _compose()["services"]
    for name in ["tp-core", "tp-admin", "tp-api", "tp-ai-service"]:
        env = services[name].get("environment") or {}
        if isinstance(env, list):
            env = dict(kv.split("=", 1) for kv in env)
        joined = "\n".join(f"{k}={v}" for k, v in env.items())
        assert "localhost" not in joined, (
            "inside the compose network, localhost is the container itself")
        assert "jdbc:postgresql://testpike-postgres" in joined \
            and "${TP_CONTAINER_PREFIX:-testpike}-redis" in joined, (
            "in-network container names are the only reachable hosts; redis "
            "resolves through the instance prefix, PG through the APP_DB_URL "
            "channel (nested defaults are unsupported in compose)")
        deps = (services[name].get("depends_on") or {}).keys()
        assert "postgres" in deps, "startup order must wait for healthy middleware"
    web_env = services["tp-web"].get("environment") or {}
    assert "${TP_ADMIN_UPSTREAM:-testpike-admin}" in yaml.safe_dump(web_env)
    assert "${TP_API_UPSTREAM:-testpike-api}" in yaml.safe_dump(web_env)


def test_compose_app_tier_ports_and_credentials_are_env_keys():
    services = _compose()["services"]
    dumped = yaml.safe_dump({n: services[n] for n in APP_SERVICES})
    assert re.search(r"\$\{TP_WEB_HOST_PORT:-", dumped), "web host port is an env key"
    assert re.search(r"\$\{APP_ADMIN_HOST_PORT:-", dumped)
    assert re.search(r"\$\{APP_API_HOST_PORT:-", dumped)
    assert re.search(r"\$\{APP_CORE_HOST_PORT:-", dumped)
    assert re.search(r"\$\{APP_AISERVICE_HOST_PORT:-", dumped)
    # app tier must not introduce a second literal password path
    for name in APP_SERVICES:
        env = services[name].get("environment") or {}
        if isinstance(env, list):
            env = dict(kv.split("=", 1) for kv in env)
        for key, val in env.items():
            if "PASSWORD" in key.upper():
                assert str(val).startswith("${") or str(val) == "", (
                    f"{name}.{key}: credentials must be env-indirected")
    # The storage gateway provisions its S3 credentials from the compose seed
    # source; the backend tier must consume the same keys or every backend
    # container refuses to start on the core storage door ("S3 credentials
    # missing"). The web tier is nginx static serving and opens no S3 client.
    # Shipped defaults live in the compose file only (single source, R48).
    for name in [n for n in APP_SERVICES if n != "tp-web"]:
        env = services[name].get("environment") or {}
        if isinstance(env, list):
            env = dict(kv.split("=", 1) for kv in env)
        for key in ("S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_BUCKET"):
            val = str(env.get(key) or "")
            assert re.match(r"^\$\{[A-Z0-9_]+:-[^}]+\}$", val), (
                f"{name}.{key}: S3 wiring must be an env key with a shipped default")


def test_compose_app_tier_auth_secrets_are_passed_through():
    # L4 r2 live evidence: the app tier booted core (S3 fix proven) but admin
    # crash-looped because compose never forwarded the SSO secret. The auth
    # family below is read at startup by the backend tier; every one of them
    # must reach the containers as an env-indirected key. Shipped defaults
    # mirror .env.example (single source, R48); empty defaults are legal where
    # the install template ships the key empty (REDIS_PASSWORD). The LLM_*
    # family is optional by design (feature off = honest guidance, R32) and
    # intentionally not required here.
    services = _compose()["services"]
    required = ("SSO_JWT_SECRET", "STORAGE_URL_SIGN_SECRET",
                "REDIS_PASSWORD", "AUTH_MODE", "SSO_MODE")
    for name in [n for n in APP_SERVICES if n != "tp-web"]:
        env = services[name].get("environment") or {}
        if isinstance(env, list):
            env = dict(kv.split("=", 1) for kv in env)
        for key in required:
            val = str(env.get(key) or "")
            assert re.match(r"^\$\{[A-Z0-9_]+:-[^}]*\}$", val), (
                f"{name}.{key}: auth wiring must be an env-indirected key")


def test_compose_app_tier_admin_seed_credential_is_passed_through():
    # Clean-machine r46 evidence: a fresh database boots with an empty tp_user
    # and UserSeedInitializer creates the seed admin only when the env var
    # ADMIN_INIT_PASSWORD reaches the container. compose never forwarded the
    # key, so the first-boot admin account was absent and the demo proof hit
    # HTTP 401 on /api/v1/auth/login. The key ships empty (no credential in
    # the compose file, R1); the real value comes from the install .env.
    services = _compose()["services"]
    for name in [n for n in APP_SERVICES if n != "tp-web"]:
        env = services[name].get("environment") or {}
        if isinstance(env, list):
            env = dict(kv.split("=", 1) for kv in env)
        val = str(env.get("ADMIN_INIT_PASSWORD") or "")
        assert re.match(r"^\$\{ADMIN_INIT_PASSWORD:-[^}]*\}$", val), (
            f"{name}.ADMIN_INIT_PASSWORD: admin seed credential must be "
            "an env-indirected key with an empty shipped default")


def test_compose_app_tier_llm_seed_family_is_passed_through():
    # The startup seed for the default chat-model row
    # (LlmConfigGenerationSeedInitializer) reads these keys from the container
    # environment. Without this pass-through a docker-path install boots with
    # no LLM endpoint, the seed honestly skips, and the doc-extraction gate D5
    # stays red on a clean install. Defaults mirror the Java-side @Value
    # shipped defaults (single source, R48); the API key never bakes a secret.
    services = _compose()["services"]
    expected = {
        "LLM_PROVIDER": "${LLM_PROVIDER:-openai}",
        "LLM_BASE_URL": "${LLM_BASE_URL:-}",
        "LLM_API_KEY": "${LLM_API_KEY:-}",
        "LLM_MODEL": "${LLM_MODEL:-}",
        "LLM_TOKENIZER_FAMILY": "${LLM_TOKENIZER_FAMILY:-}",
    }
    for name in [n for n in APP_SERVICES if n != "tp-web"]:
        env = services[name].get("environment") or {}
        if isinstance(env, list):
            env = dict(kv.split("=", 1) for kv in env)
        for key, want in expected.items():
            assert env.get(key) == want, (
                f"{name}.{key}: LLM seed wiring must mirror the shipped default")


def test_env_template_exposes_tokenizer_family_key():
    # R48②: every key the backend reads at install time must appear in the
    # install template, or a new user cannot discover it. The tokenizer family
    # is optional (empty = derive from the model name), so it ships empty.
    text = (REPO / ".env.example").read_text(encoding="utf-8")
    assert re.search(r"^LLM_TOKENIZER_FAMILY=$", text, re.M), (
        ".env.example must expose LLM_TOKENIZER_FAMILY (empty = auto-derive)")
