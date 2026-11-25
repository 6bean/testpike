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
