"""WE 3b relay: hit/miss rows travel out on the result envelope trailing key.

The executor never scores (single ordering/confidence source is core); it only
relays what happened. Contract pinned here:
① submit_result(element_anchor_hits=rows) trails the ``elementAnchorHits`` key
 verbatim; ② empty/None omits the key entirely (trailing-key compatibility
 precedent : old cores deserialize untouched); ③ the runner relay call
 actually passes the collected rows (a forgotten passthrough used to hide in
 prose — pin it at source level).
"""
from __future__ import annotations

from pathlib import Path

from protocol import ExecutorProtocolClient

REPO_ROOT = Path(__file__).resolve().parents[2]

ROWS = [{"elementId": 7, "name": "登录按钮", "viewport": "1440x900",
         "anchorType": "css", "hit": True, "tried": True}]


def _spy_client() -> tuple[ExecutorProtocolClient, dict]:
    client = ExecutorProtocolClient("http://127.0.0.1:1/internal")
    captured: dict = {}

    def _fake_post(path, payload):
        captured["path"] = path
        captured["payload"] = payload
        return {}

    client._post = _fake_post
    return client, captured


def test_hit_rows_trail_element_anchor_hits_key():
    client, captured = _spy_client()
    client.submit_result(11, "tok", "pass", element_anchor_hits=ROWS)
    assert captured["path"] == "/internal/tasks/result"
    assert captured["payload"]["elementAnchorHits"] == ROWS


def test_empty_or_none_omits_the_key():
    client, captured = _spy_client()
    client.submit_result(11, "tok", "pass")
    assert "elementAnchorHits" not in captured["payload"]
    client.submit_result(11, "tok", "pass", element_anchor_hits=[])
    assert "elementAnchorHits" not in captured["payload"]


def test_runner_passes_collected_rows():
    source = (REPO_ROOT / "executor" / "runner.py").read_text(encoding="utf-8")
    assert "element_anchor_hits=outcome.element_anchor_hits or None" in source
