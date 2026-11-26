# -*- coding: utf-8 -*-
"""Compose credential single-source + bounded metadata-init wait.

Guards the failure shape reported from a fresh install host: the postgres
service hard-coded its credentials while every consumer (dbinit, app tier,
storage checks) read the install .env — a custom .env pair left the init
container in an unbounded silent retry loop and `up -d` hung forever.
Both directions are asserted so the gate cannot spin silently (R43).
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import failure_guide

COMPOSE = (Path(__file__).resolve().parents[2] / "docker-compose.yml").read_text(encoding="utf-8")


def _block(header_re):
    m = re.search(header_re + r"\n((?:[ ]{4,}.*\n)*)", COMPOSE, re.M)
    assert m, "compose service block not found for %s" % header_re
    return m.group(1)


def test_postgres_credentials_read_install_env_keys():
    pg = _block(r"^  postgres:")
    assert "POSTGRES_USER: ${DB_USERNAME:-testpike}" in pg, pg
    assert "POSTGRES_PASSWORD: ${DB_PASSWORD:-testpike_dev}" in pg, pg
    assert "pg_isready -U ${DB_USERNAME:-testpike}" in pg, pg


def test_rabbitmq_credentials_read_install_env_keys():
    # Same dual-source class as postgres: the broker seeds its account only
    # when the volume is created, while the app tier and vhost reconciler
    # consume the .env pair — hard-coded creds left installs on a mismatch.
    mq = _block(r"^  rabbitmq:")
    assert "RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME:-testpike}" in mq, mq
    assert "RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-testpike_dev}" in mq, mq


def test_backend_services_map_the_host_gateway():
    # Containers reach a host-side LLM gateway only through this mapping; the
    # fresh-install chat trap (base URL pointed at a container-local
    # localhost) is closed by declaring the gateway and shipping a URL that
    # resolves with it.
    for service in ("tp-core", "tp-admin", "tp-api", "tp-ai-service"):
        block = _block(r"^  %s:" % service)
        assert "host.docker.internal:host-gateway" in block, \
            "%s lacks the host-gateway mapping" % service


def test_dbinit_wait_is_bounded_and_diagnoses():
    init = _block(r"^  seaweedfs-dbinit:")
    assert "until psql" not in init, "unbounded ready-wait loop is back"
    assert "deadline" in init and "exit 1" in init, init
    # The give-up path names the keys an operator must compare (R63 elements).
    assert "DB_USERNAME/DB_PASSWORD" in init, init


def test_failure_guide_classifies_the_hang_signature():
    auth = 'psql: error: FATAL: password authentication failed for user "ops"'
    assert failure_guide.classify_failure(auth) == "dbinit_unreachable"
    marker = "swf-dbinit: cannot connect to postgres as ops within 300s"
    assert failure_guide.classify_failure(marker) == "dbinit_unreachable"
    note = failure_guide.note_for_failure(marker)
    for needle in (".env", "DB_USERNAME", "DB_PASSWORD", "docker compose down -v",
                   "docker compose logs postgres", "re-run"):
        assert needle in note, "recovery note misses %s: %s" % (needle, note)


def test_failure_guide_keeps_other_families_untouched():
    # Exclusion branches need a hit case too: an auth string must not steal a
    # duplicate-database tail, and unrelated noise must stay unclassified.
    dup = ("psql: error: ERROR:  database \"seaweedfs\" already exists\n"
           "password authentication failed noise from a wrapped vendor line")
    assert failure_guide.classify_failure(dup) == "pg_existing"
    assert failure_guide.classify_failure("step done, nothing unusual") is None


def test_failed_dependency_logs_are_surfaced_and_classified(monkeypatch, capsys):
    # compose up -d prints only `service "X" didn't complete successfully`
    # without the container's own stderr: the installer must fetch and print
    # those logs, otherwise the on-screen cause claim is a dead pointer.
    import s1_bootstrap
    monkeypatch.delenv("INSTALLKIT_COMPOSE_BIN", raising=False)
    tail = 'Container x-seaweedfs-dbinit  service "seaweedfs-dbinit" didn\'t complete successfully: exit 1\n'
    fake = ("swf-dbinit: cannot connect to postgres as ops within 300s — last error:\n"
            'psql: error: FATAL: password authentication failed for user "ops"\n')

    class _Done:
        stdout = fake

    def fake_run(cmd, **kw):
        assert cmd[-4:-1] == ["logs", "--tail", "40"], cmd
        assert cmd[-1] == "seaweedfs-dbinit"
        return _Done()

    monkeypatch.setattr(s1_bootstrap.subprocess, "run", fake_run)
    joined = s1_bootstrap._surface_dependency_logs(["docker", "compose", "-f", "x"], tail)
    assert "password authentication failed" in joined
    assert "the failing dependency" in capsys.readouterr().out
    note = failure_guide.note_for_failure(tail + joined)
    assert note and "DB_USERNAME" in note and "docker compose down -v" in note


def test_substitute_mode_never_probes_real_docker(monkeypatch, capsys):
    # The skip branch needs a hit case too (no silent idle): with a substitute
    # compose binary there is no container store to read, so nothing runs.
    import s1_bootstrap
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "/bin/true")

    def explode(*a, **k):
        raise AssertionError("substitute mode must not invoke real docker")

    monkeypatch.setattr(s1_bootstrap.subprocess, "run", explode)
    tail = 'service "seaweedfs-dbinit" didn\'t complete successfully: exit 1'
    assert s1_bootstrap._surface_dependency_logs(["docker", "compose"], tail) == ""
    tail_unhealthy = "dependency failed to start: container testpike-core is unhealthy"
    assert s1_bootstrap._surface_dependency_logs(["docker", "compose"], tail_unhealthy) == ""
    # And a healthy tail without either marker never probes.
    monkeypatch.delenv("INSTALLKIT_COMPOSE_BIN")
    assert s1_bootstrap._surface_dependency_logs(["docker", "compose"], "everything fine") == ""


def test_unhealthy_service_logs_are_surfaced(monkeypatch, capsys):
    # compose blames `container X is unhealthy` and prints nothing of the
    # Spring startup failure; the stage must echo the app log tail.
    import s1_bootstrap
    monkeypatch.delenv("INSTALLKIT_COMPOSE_BIN", raising=False)
    tail = ("Container testpike-core  Error\ndependency failed to start: "
            "container testpike-core is unhealthy\n")
    seen = []

    class _Done:
        stdout = ("APPLICATION FAILED TO START\n"
                  "Config AES key missing: set CONFIG_AES_KEY in .env\n")

    def fake_run(cmd, **kw):
        seen.append(cmd)
        return _Done()

    monkeypatch.setattr(s1_bootstrap.subprocess, "run", fake_run)
    joined = s1_bootstrap._surface_dependency_logs(["docker", "compose", "-f", "x"], tail)
    assert seen == [["docker", "logs", "--tail", "60", "testpike-core"]], seen
    assert "CONFIG_AES_KEY" in joined
    out = capsys.readouterr().out
    assert "the unhealthy service" in out


def test_config_aes_key_prefill_gate(tmp_path, monkeypatch):
    # A passphrase-shaped CONFIG_AES_KEY used to burn every image build and
    # only die later as a core crash loop; the compose step must refuse it
    # first, name the key and the generation command, and never echo values.
    import argparse
    import s1_bootstrap
    bogus = "0VerySecretPassphraseThatIsNotHexAtAll"
    root = tmp_path / "repo"
    root.mkdir()
    (root / "docker-compose.yml").write_text("services: {}\n", encoding="utf-8")
    (root / ".env").write_text("CONFIG_AES_KEY=%s\n" % bogus, encoding="utf-8")
    monkeypatch.delenv("CONFIG_AES_KEY", raising=False)
    args = argparse.Namespace(answers_file=str(tmp_path / "missing.json"),
                              repo_root=str(root))
    monkeypatch.setattr(s1_bootstrap, "compose_stages",
                        lambda *a, **k: [{"phase": "middleware", "cmd": ["true"]}])
    touched = []
    monkeypatch.setattr(s1_bootstrap, "_run_stage",
                        lambda cmd: touched.append(cmd) or (0, ""))
    monkeypatch.setattr(s1_bootstrap, "relay_stage_images",
                        lambda *a, **k: touched.append("relay") or True)
    monkeypatch.setattr(s1_bootstrap.mq_vhost, "ensure_vhost",
                        lambda **kw: (True, []))
    monkeypatch.setattr(s1_bootstrap, "four_services_guide", lambda tier=None: ["g"])
    assert s1_bootstrap.cmd_compose(args) == 1
    assert touched == [], "the gate must fire before any image build or compose stage"

    # Accepted shapes pass the gate: 64-hex, and empty (= feature off).
    (root / ".env").write_text("CONFIG_AES_KEY=%s\n" % ("0f" * 32), encoding="utf-8")
    assert s1_bootstrap.cmd_compose(args) == 0
    (root / ".env").write_text("CONFIG_AES_KEY=\n", encoding="utf-8")
    assert s1_bootstrap.cmd_compose(args) == 0
    # Wrong length but valid hex chars: also refused (no silent disable).
    touched.clear()
    (root / ".env").write_text("CONFIG_AES_KEY=%s\n" % ("ab" * 20), encoding="utf-8")
    assert s1_bootstrap.cmd_compose(args) == 1


def test_failure_guide_classifies_config_key_crash():
    legacy = ("Caused by: java.lang.NumberFormatException: For input string: \"0V\" "
              "under radix 16\n\tat com.sixbean.testpike.core.config.domain.component."
              "ConfigCipher.hexToBytes(ConfigCipher.java:89)")
    assert failure_guide.classify_failure(legacy) == "config_key_invalid"
    fast = "CONFIG_AES_KEY must be a hex string of exactly 32, 48 or 64 characters"
    assert failure_guide.classify_failure(fast) == "config_key_invalid"
    note = failure_guide.note_for_failure(fast)
    for needle in ("CONFIG_AES_KEY", "openssl rand -hex 32", ".env", "re-run"):
        assert needle in note, needle
