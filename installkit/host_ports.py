"""Single source for the compose host-port band keys and their resolution order.

Every host-mapped port in docker-compose.yml ships as ``${KEY:-shipped
default}``, and compose resolves such keys with the process environment
taking precedence over the repo ``.env`` file. Installer-side checks must
resolve the same value through the same precedence: a runner that exports
the band in the environment without touching .env (the clean-machine matrix
cells do exactly this) otherwise leaves the checks probing a port no stack
ever bound (r42 cell: ui-auth bound 25406, the storage gate probed 9000).

Consumers pinned by installkit/tests/test_clean_matrix_contract.py: the
matrix runner (band injection) and the S1 storage gate (port probing) both
read HOST_PORT_KEYS from here - no second list anywhere.
"""
import os
import re

# Keys that appear as ${KEY:-...} host mappings in docker-compose.yml.
# Adding a host-mapped service means adding its key here too; the contract
# test asserts every key here has a compose mapping (and vice versa for the
# band consumers).
HOST_PORT_KEYS = (
    "PG_HOST_PORT", "REDIS_HOST_PORT", "MQ_AMQP_HOST_PORT", "MQ_MGMT_HOST_PORT",
    "SWF_S3_HOST_PORT", "SWF_UI_FILER_PROXY_HOST_PORT", "SWF_UI_MASTER_PROXY_HOST_PORT",
    "APP_CORE_HOST_PORT", "APP_ADMIN_HOST_PORT", "APP_API_HOST_PORT",
    "APP_AISERVICE_HOST_PORT", "TP_WEB_HOST_PORT", "TP_FIXTURE_HOST_PORT",
)


def overlay(env, environ=None):
    """Return a copy of the .env dict with band keys resolved from ``environ``.

    Mirrors compose's ``${KEY:-fallback}`` semantics: a non-empty process
    environment value wins over the .env entry; an absent or empty value
    keeps the .env entry (or leaves the key absent so callers fall back to
    their shipped default). Keys outside HOST_PORT_KEYS are never copied."""
    environ = os.environ if environ is None else environ
    out = dict(env)
    for key in HOST_PORT_KEYS:
        value = (environ.get(key) or "").strip()
        if value:
            out[key] = value
    return out


_COMPOSE_HOST_MAPPING = re.compile(r'"?\$\{([A-Z][A-Z0-9_]*):-([0-9]+)\}:')


def compose_shipped_defaults(root):
    """Parse ${KEY:-port} host mappings from docker-compose.yml.

    The compose file is the source of truth for shipped host-port defaults;
    re-declaring the numbers here would be a second list free to drift."""
    out = {}
    with open(os.path.join(root, "docker-compose.yml"), encoding="utf-8") as fh:
        for line in fh:
            match = _COMPOSE_HOST_MAPPING.search(line)
            if match and match.group(1) in HOST_PORT_KEYS:
                out.setdefault(match.group(1), match.group(2))
    return out


def read_dotenv(root):
    """Read the repo .env as KEY=VALUE pairs (values are never printed)."""
    path = os.path.join(root, ".env")
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                out[key.strip()] = value.strip().strip('"').strip("'")
    return out


def effective_host_ports(root, environ=None):
    """Ordered (key, port) pairs with compose's own resolution order.

    process env > repo .env > compose shipped default. Installer-side
    checks (preflight probes, readiness gates) must consume this instead
    of a literal list, otherwise a band-exporting runner (clean-machine
    matrix cells) gets reports about ports no stack ever bound."""
    env = overlay(read_dotenv(root), environ)
    defaults = compose_shipped_defaults(root)
    pairs = []
    for key in HOST_PORT_KEYS:
        value = (env.get(key) or "").strip() or defaults.get(key)
        if value:
            pairs.append((key, value))
    return pairs
