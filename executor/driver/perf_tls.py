"""B-10 load-engine TLS tier injection — the executor half.

Input = the claim perf context's ``tlsPolicy`` dict, already resolved and
decrypted by core (single source = common TlsContextResolver through
EnvTlsSupport). This module translates the tier into engine launcher
arguments and material files. It re-decides NOTHING (double-gate discipline:
only normalize_policy's defensive mirror from driver/tls_client runs, which
fails dirty values safe to strict — never a silent relaxation).

Channel facts (live-proven against a self-signed fixture, ):
- k6: script-level options.tls.* and K6_TLS_INSECURE_SKIP_VERIFY are both
 ineffective on v0.55 — the ONLY working insecure channel is the CLI flag
 ``--insecure-skip-tls-verify``. The k6 CLI has NO CA flag, so custom_ca
 cannot be injected: the engine keeps its built-in trust store (strict
 behaviour) and a human-worded event names the limitation — never a silent
 downgrade to insecure.
- Gatling (Java launcher): insecure rides the engine switch
 ``-Dgatling.http.ssl.disable-certification=true``; custom_ca builds a
 PKCS12 trust store from the CA material with keytool (user-installed JDK
 prerequisite, same as the engine launcher itself) and hands it over with
 ``-Djavax.net.ssl.trustStore*``. custom_ca without material =
 GatlingTlsError fail-closed (mirror of http_tls_kwargs stance — a dispatch
 anomaly is named, never passed as the default tier).

Material files (trust stores hold no private key, but stay 0600 by the same
discipline as driver/tls_client) live in the caller-provided run directory
and die with it — never in logs (red line 4).
"""
from __future__ import annotations

import logging
import os
import secrets
import stat
import subprocess
import tempfile

log = logging.getLogger("executor.perf_tls")

MODE_STRICT = "strict"
MODE_CUSTOM_CA = "custom_ca"
MODE_INSECURE = "insecure"

# k6 insecure channel — the single live-proven flag (see module docstring).
K6_INSECURE_FLAG = "--insecure-skip-tls-verify"
# Gatling engine switch that skips certificate validation (gatling-http
# configuration reference; consumed by the engine's SSL helper builder).
GATLING_INSECURE_PROP = "-Dgatling.http.ssl.disable-certification=true"


class GatlingTlsError(Exception):
    """TLS material gap on the Gatling launch path (fail-closed, human-worded
    by the caller; the engine process must never start without it)."""


def k6_tls_flags(policy: dict) -> list[str]:
    """Normalized tlsPolicy -> extra k6 CLI flags.

    insecure = the skip-verify flag; strict/custom_ca = no flags (custom_ca
    has no k6 CA channel — the engine verifies against its built-in trust
    store, i.e. strict behaviour, and the caller surfaces that honesty note;
    relaxing to insecure here would be a silent tier rewrite).
    """
    if policy.get("mode") == MODE_INSECURE:
        return [K6_INSECURE_FLAG]
    return []


def gatling_tls_properties(policy: dict, work_dir: str) -> list[str]:
    """Normalized tlsPolicy -> extra JVM -D properties for the Gatling launcher.

    strict = [] (engine default verification); insecure = the engine's
    disable-certification switch; custom_ca = a PKCS12 trust store built from
    the CA material + the three trustStore properties. Raises GatlingTlsError
    when custom_ca arrives without CA material (dispatch anomaly = named, not
    passed as default) or when keytool cannot produce the store.
    """
    mode = policy.get("mode")
    if mode == MODE_INSECURE:
        return [GATLING_INSECURE_PROP]
    if mode != MODE_CUSTOM_CA:
        return []
    ca_pem = policy.get("caPem")
    if not ca_pem:
        raise GatlingTlsError(
            "TLS 决议为 custom_ca 但未随 claim 下发 CA 材料"
            "（环境档案证书内容缺失或解密异常）——拒绝按默认档放行启动 Gatling，请检查档案配置")
    # Per-run random store password (secrets-backed, never a shipped default;
    # the store only ever holds the environment's own CA certificate).
    password = secrets.token_hex(16)
    store_path = _build_trust_store(ca_pem, work_dir, password)
    return [
        f"-Djavax.net.ssl.trustStore={store_path}",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        f"-Djavax.net.ssl.trustStorePassword={password}",
    ]


def _build_trust_store(ca_pem: str, work_dir: str, password: str) -> str:
    """CA PEM -> PKCS12 trust store via keytool (JDK prerequisite of the
    Gatling launcher anyway — no new dependency). Returns the 0600 store path.

    The PEM is first normalized into its own temp file (keytool accepts an
    X.509 certificate file; a missing/dirty material is named, never
    swallowed). Failure = GatlingTlsError with the keytool output tail so the
    run outcome says what broke.
    """
    os.makedirs(work_dir, exist_ok=True)
    ca_file = os.path.join(work_dir, "tp-ca.pem")
    with open(ca_file, "w", encoding="utf-8") as f:
        f.write(ca_pem)
    os.chmod(ca_file, stat.S_IRUSR | stat.S_IWUSR)
    store_path = os.path.join(work_dir, "tp-trust.p12")
    if os.path.exists(store_path):
        os.remove(store_path)
    try:
        proc = subprocess.run(
            ["keytool", "-importcert", "-noprompt", "-alias", "tp-env-ca",
             "-file", ca_file, "-keystore", store_path,
             "-storetype", "PKCS12", "-storepass", password],
            capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as gone:
        raise GatlingTlsError(f"Gatling custom_ca 信任库构建失败（keytool 不可用）：{gone}") from gone
    if proc.returncode != 0 or not os.path.isfile(store_path):
        tail = (proc.stderr or proc.stdout or "")[-500:]
        raise GatlingTlsError(
            f"Gatling custom_ca 信任库构建失败：keytool 退出码 {proc.returncode}；输出尾={tail}")
    os.chmod(store_path, stat.S_IRUSR | stat.S_IWUSR)
    return store_path
