# -*- coding: utf-8 -*-
"""Receipts for the dedicated command queue carry the enrollment identity.

03 section 2.9 (bidirectional authentication, receipts half): progress/result
for enroll-token targets must present the plaintext token (folded to its
digest by the api bridge, never seen in plaintext by core) plus the bound
machine fingerprint; core reconciles before any write. These contracts pin
the client-side body shape so the identity keys can never silently vanish:

- service_agent.ack_command sends token + fingerprint on the result leg;
- enroll_client poll --ack sends both on the progress leg and the result leg;
- receipt bodies keep the protocol keys (commandId/claimerId/success/step).
"""
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import enroll_client as ec
import service_agent as sa

POLL_DATA = {"code": "0", "data": [{"id": 9, "claimedBy": 3,
                                    "commandType": "scale-in"}]}


def _capture(monkeypatch):
    bodies = []

    def fake_post_json(base_url, path, body, timeout=15):
        bodies.append((path, dict(body)))
        return dict(POLL_DATA)

    monkeypatch.setattr(ec, "_post_json", fake_post_json)
    return bodies


def test_service_agent_result_receipt_carries_identity(monkeypatch):
    bodies = _capture(monkeypatch)
    sa.ack_command("http://api:8082", 9, 3, True, "self-check done",
                   "tok-plain", "fp-machine")
    path, body = bodies[-1]
    assert path.endswith("/internal/cluster/commands/result")
    assert body["token"] == "tok-plain", "receipt must hand the plaintext token to the api bridge"
    assert body["fingerprint"] == "fp-machine"
    assert body["commandId"] == 9 and body["claimerId"] == 3
    assert body["success"] is True


def test_poll_ack_receipts_carry_identity(monkeypatch):
    bodies = _capture(monkeypatch)
    monkeypatch.setattr(ec, "machine_fingerprint", lambda: "fp-machine")
    args = types.SimpleNamespace(base_url="http://api:8082", token="tok-plain",
                                 limit=5, ack=True)
    assert ec.cmd_poll(args) == 0
    receipt_legs = [(p, b) for p, b in bodies
                    if p.endswith("/internal/cluster/commands/progress")
                    or p.endswith("/internal/cluster/commands/result")]
    assert len(receipt_legs) == 2, "ack mode must deliver progress + result"
    for path, body in receipt_legs:
        assert body.get("token") == "tok-plain", path
        assert body.get("fingerprint") == "fp-machine", path
        assert body.get("claimerId") == 3, path
