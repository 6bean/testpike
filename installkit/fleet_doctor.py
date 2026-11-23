# -*- coding: utf-8 -*-
"""Fleet doctor: per-node x six-check red-green matrix over executor facts.

Aggregate counterpart of the single-host ``doctor.py``: instead of probing one
machine, it judges a set of node fact dicts (assembled upstream from the
tp_executor ledger, the gold-version baseline and connectivity probes) and
renders one matrix row per node. Six checks, each judged independently:

- identity  — the node completed the one-time enroll flow (token valid);
- heartbeat — last heartbeat age within the offline threshold (the threshold
  itself stays server-side config ``testpike.executor.offline-seconds``; the
  shipped default 30 mirrors the server constant and is overridable per call);
- version   — reported version equals the gold version baseline;
- queue     — command queue reachable under mutual authentication;
- capacity  — concurrency headroom (capacity - running_cnt) above zero;
- drain     — running count sits in the 0..capacity window, i.e. draining the
  node to zero is reachable by waiting.

Three-state honesty: a fact the caller cannot provide degrades to "unknown"
with a reason line, never to a fake red or a fake green. Every non-pass line
follows the error-guidance contract: name the offending value, name the fix
entry as a literal operators can act on.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import messages  # noqa: E402

CHECK_KEYS = ("identity", "heartbeat", "version", "queue", "capacity", "drain")
GLYPH = {"pass": "\u2713", "fail": "\u2717", "unknown": "?"}
PASS, FAIL, UNKNOWN = "pass", "fail", "unknown"

# Mirrors ExecutorServiceImpl.DEFAULT_OFFLINE_SECONDS on the server side; the
# authoritative value stays the system config key, callers pass what they read.
DEFAULT_OFFLINE_SECONDS = 30

STATUS_GREEN, STATUS_RED, STATUS_UNKNOWN = "green", "red", "unknown"


def _check(key, status, message_key=None, **fmt):
    reason = messages.t(message_key, **fmt) if message_key else None
    return {"key": key, "status": status, "reason": reason}


def _judge_identity(name, enrolled):
    if enrolled is True:
        return _check("identity", PASS)
    if enrolled is False:
        return _check("identity", FAIL, "fleet.identity_fail", name=name)
    return _check("identity", UNKNOWN, "fleet.identity_unknown", name=name)


def _judge_heartbeat(name, age, offline_seconds):
    if age is None:
        return _check("heartbeat", UNKNOWN, "fleet.heartbeat_unknown", name=name)
    if age < 0:
        return _check("heartbeat", FAIL, "fleet.heartbeat_skew", name=name, age=age)
    if age <= offline_seconds:
        return _check("heartbeat", PASS)
    return _check("heartbeat", FAIL, "fleet.heartbeat_fail",
                  name=name, age=age, offline=offline_seconds)


def _judge_version(name, version, gold_version):
    if not version or not gold_version:
        return _check("version", UNKNOWN, "fleet.version_unknown", name=name)
    if version == gold_version:
        return _check("version", PASS)
    return _check("version", FAIL, "fleet.version_fail",
                  name=name, version=version, gold=gold_version)


def _judge_queue(name, queue_reachable):
    if queue_reachable is True:
        return _check("queue", PASS)
    if queue_reachable is False:
        return _check("queue", FAIL, "fleet.queue_fail", name=name)
    return _check("queue", UNKNOWN, "fleet.queue_unknown", name=name)


def _judge_capacity(name, capacity, running):
    if capacity is None or running is None:
        return _check("capacity", UNKNOWN, "fleet.capacity_unknown", name=name)
    if running > capacity or running < 0:
        return _check("capacity", FAIL, "fleet.ledger_inconsistent",
                      name=name, running=running, capacity=capacity)
    if running == capacity:
        return _check("capacity", FAIL, "fleet.capacity_full",
                      name=name, running=running, capacity=capacity)
    return _check("capacity", PASS)


def _judge_drain(name, capacity, running):
    if capacity is None or running is None:
        return _check("drain", UNKNOWN, "fleet.capacity_unknown", name=name)
    if 0 <= running <= capacity:
        return _check("drain", PASS)
    return _check("drain", FAIL, "fleet.ledger_inconsistent",
                  name=name, running=running, capacity=capacity)


def _overall(checks):
    statuses = {c["status"] for c in checks}
    if FAIL in statuses:
        return STATUS_RED
    if UNKNOWN in statuses:
        return STATUS_UNKNOWN
    return STATUS_GREEN


def inspect_node(facts, options=None):
    """Judge one node fact dict into a matrix row (order-preserving six checks)."""
    opts = options or {}
    offline = opts.get("offline_seconds", DEFAULT_OFFLINE_SECONDS)
    name = facts.get("name") or "?"
    checks = [
        _judge_identity(name, facts.get("enrolled")),
        _judge_heartbeat(name, facts.get("heartbeat_age_seconds"), offline),
        _judge_version(name, facts.get("version"), facts.get("gold_version")),
        _judge_queue(name, facts.get("queue_reachable")),
        _judge_capacity(name, facts.get("capacity"), facts.get("running_cnt")),
        _judge_drain(name, facts.get("capacity"), facts.get("running_cnt")),
    ]
    overall = _overall(checks)
    line = "%s [%s] %s" % (name, overall, "".join(GLYPH[c["status"]] for c in checks))
    return {"node": name, "overall": overall, "checks": checks, "line": line}


def build_matrix(nodes, options=None):
    """Aggregate node facts into {rows, summary}; rows keep caller order."""
    rows = [inspect_node(n, options) for n in nodes]
    summary = {STATUS_GREEN: 0, STATUS_RED: 0, STATUS_UNKNOWN: 0}
    for row in rows:
        summary[row["overall"]] += 1
    return {"rows": rows, "summary": summary}


def render_lines(matrix):
    """One row line per node plus an indented reason line per non-pass check.

    A healthy fleet renders exactly the row lines (zero guidance noise), which
    is the bidirectional self-test pin and the exit-code contract below.
    """
    lines = []
    for row in matrix["rows"]:
        lines.append(row["line"])
        for check in row["checks"]:
            if check["status"] != PASS:
                lines.append("  - " + check["reason"])
    return lines


def main(argv=None):
    """CLI form consumed by the bare healthcheck and the delivery windows.

    Input document: {"nodes":[<fact dicts>], "options":{"offline_seconds":N}};
    ``--facts -`` reads the same document from stdin. Exit codes are the
    three-state contract: 0 every node green, 1 any red, 2 unknowns only.
    """
    import argparse
    import json

    parser = argparse.ArgumentParser(
        prog="fleet_doctor.py",
        description="render the per-node six-check red/green matrix from a node-facts document")
    parser.add_argument("--facts", required=True,
                        help="path to the facts JSON, or '-' for stdin")
    parser.add_argument("--offline-seconds", type=int, default=None,
                        help="heartbeat threshold override; the authoritative value "
                             "stays the server config key testpike.executor.offline-seconds")
    args = parser.parse_args(argv)
    if args.facts == "-":
        doc = json.loads(sys.stdin.read() or "{}")
    else:
        doc = json.loads(Path(args.facts).read_text(encoding="utf-8"))
    options = dict(doc.get("options") or {})
    if args.offline_seconds is not None:
        options["offline_seconds"] = args.offline_seconds
    matrix = build_matrix(doc.get("nodes") or [], options)
    for line in render_lines(matrix):
        print(line)
    summary = matrix["summary"]
    print("summary: green=%d red=%d unknown=%d"
          % (summary[STATUS_GREEN], summary[STATUS_RED], summary[STATUS_UNKNOWN]))
    if summary[STATUS_RED]:
        return 1
    if summary[STATUS_UNKNOWN]:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
