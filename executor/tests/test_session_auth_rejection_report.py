"""Login-state auth-rejection observation relay (AV face 3, .

The executor reports, never judges: while a ledger login state is injected
into the browser context, same-origin 401/403 responses become a trailing
evidence key that core SessionAuthRejectionWriteback adjudicates (origin
whitelist + expected-status exemption + the single markVerified write path).
These spectra nail the narrow-down itself — third-party rejections must not
be relayed (they would wrongly invalidate a healthy state), and volume is
capped like every other evidence channel (trailing-key precedent).
"""
from driver.session_context import collect_session_auth_rejections


def _entry(url: str, status: int) -> dict:
    return {"url": url, "method": "GET", "status": status}


def test_relay_keeps_same_origin_rejections_only():
    entries = [
        _entry("http://127.0.0.1:8099/dashboard", 401),
        _entry("https://cdn.third-party.test/widget.js", 401),
        _entry("http://127.0.0.1:8099/profile", 200),
        _entry("http://127.0.0.1:8099/admin", 403),
    ]
    rows = collect_session_auth_rejections(entries, "http://127.0.0.1:8099/env")
    assert [r["status"] for r in rows] == [401, 403]
    assert rows[0]["url"] == "http://127.0.0.1:8099/dashboard"


def test_relay_dedupes_and_caps_evidence_rows():
    entries = [_entry(f"http://127.0.0.1:8099/api/{i}", 401) for i in range(10)]
    entries += entries[:3]
    rows = collect_session_auth_rejections(entries, "http://127.0.0.1:8099")
    assert len(rows) == 5


def test_relay_needs_a_usable_environment_origin():
    entries = [_entry("http://127.0.0.1:8099/dashboard", 401)]
    assert collect_session_auth_rejections(entries, "") == []
    assert collect_session_auth_rejections(entries, None) == []
    assert collect_session_auth_rejections([], "http://127.0.0.1:8099") == []


def test_relay_matches_origin_not_prefix():
    # A different host that merely shares the "127.0" prefix is not the
    # protected surface of this run.
    entries = [_entry("http://127.0.0.199:8099/x", 403)]
    assert collect_session_auth_rejections(entries, "http://127.0.0.1:8099") == []
