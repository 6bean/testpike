# -*- coding: utf-8 -*-
"""Factory-default parity gate: shipped secret defaults must satisfy the
product's own boot-time floors.

Class defect this closes: .env.example and the compose fallback default for
SSO_JWT_SECRET were shorter than the 32-character minimum JwtTokenService
enforces, so an untouched fresh install crash-looped the admin service (live
evidence from a remote first install). Any install-time secret with a
boot-time validation must have its two shipped carriers (the template and the
compose fallback) proven against the same rule table the installer pre-check
consumes, or a fresh install ships broken.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import s1_bootstrap  # rule table single source (R40)

REPO = Path(__file__).resolve().parents[2]
ENV_EXAMPLE = (REPO / ".env.example").read_text(encoding="utf-8")
COMPOSE = (REPO / "docker-compose.yml").read_text(encoding="utf-8")


def _example_value(key):
    m = re.search(r"^%s=(.*)$" % re.escape(key), ENV_EXAMPLE, re.M)
    assert m, ".env.example must expose key %s (shipped carrier)" % key
    return m.group(1).strip()


def _compose_fallback(key):
    m = re.search(r"\$\{%s:-([^}]*)\}" % re.escape(key), COMPOSE)
    assert m, "docker-compose.yml must carry a fallback for %s" % key
    return m.group(1).strip()


def _rule(key):
    for name, ok, _why in s1_bootstrap.SECRET_SHAPE_RULES:
        if name == key:
            return ok
    raise AssertionError("rule table lost key %s" % key)


def test_shipped_sso_jwt_secret_carriers_meet_the_boot_floor():
    ok = _rule("SSO_JWT_SECRET")
    example = _example_value("SSO_JWT_SECRET")
    fallback = _compose_fallback("SSO_JWT_SECRET")
    assert ok(example), "the .env.example placeholder would crash the admin boot"
    assert ok(fallback), "the compose fallback would crash the admin boot"
    assert "change-me" in example, "the placeholder must read as a placeholder"


def test_shipped_config_aes_key_carriers_meet_the_cipher_shape():
    ok = _rule("CONFIG_AES_KEY")
    example = _example_value("CONFIG_AES_KEY")
    fallback = _compose_fallback("CONFIG_AES_KEY")
    assert not example or ok(example)
    assert not fallback or ok(fallback)


def test_gate_refuses_a_short_jwt_value_and_accepts_the_floor(tmp_path, monkeypatch):
    root = tmp_path / "repo"
    root.mkdir()
    monkeypatch.delenv("CONFIG_AES_KEY", raising=False)
    monkeypatch.delenv("SSO_JWT_SECRET", raising=False)
    (root / ".env").write_text("SSO_JWT_SECRET=dev-only-change-me\n", encoding="utf-8")
    note = s1_bootstrap._secret_keys_note(str(root))
    assert note and "SSO_JWT_SECRET" in note and "openssl rand -hex 16" in note
    assert "dev-only-change-me" not in note, "values are never echoed"
    (root / ".env").write_text("SSO_JWT_SECRET=%s\n" % ("k" * 32), encoding="utf-8")
    assert s1_bootstrap._secret_keys_note(str(root)) is None


def test_failure_guide_teaches_the_jwt_floor():
    import failure_guide
    crash = ("Caused by: java.lang.IllegalArgumentException: SSO_JWT_SECRET "
             "未配置或过短（至少 32 字符，从 .env 读取）")
    assert failure_guide.classify_failure(crash) == "jwt_secret_short"
    note = failure_guide.note_for_failure(crash)
    for needle in ("SSO_JWT_SECRET", "openssl rand -hex 16", ".env", "re-run"):
        assert needle in note, needle
