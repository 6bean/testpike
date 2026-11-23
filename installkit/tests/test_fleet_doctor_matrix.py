# -*- coding: utf-8 -*-
"""Fleet doctor six-check matrix contract (T294b cluster-doctor aggregate).

The matrix judges per-node facts against the six observable signals of the
executor ledger (identity / heartbeat / version / queue / capacity / drain).
Judgement is pure logic over an input fact set: missing facts degrade to
"unknown" (honest three-state, never a fake red or a fake green), every red
or unknown carries an operator-actionable reason line (named value + literal
fix entry), and an all-green fleet renders zero reason lines (the guidance
branch must be able to prove it does not fire on healthy nodes).
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import fleet_doctor as fd  # noqa: E402


def node(**overrides):
    """A fully healthy node fact; each test overrides the one signal it probes."""
    facts = {
        "name": "ex-node-1",
        "enrolled": True,
        "heartbeat_age_seconds": 5,
        "version": "1.4.0",
        "gold_version": "1.4.0",
        "queue_reachable": True,
        "capacity": 4,
        "running_cnt": 1,
    }
    facts.update(overrides)
    return facts


def checks_by_key(row):
    return {c["key"]: c for c in row["checks"]}


def test_green_node_has_six_ordered_checks_and_no_red():
    row = fd.inspect_node(node())
    assert row["node"] == "ex-node-1"
    assert [c["key"] for c in row["checks"]] == list(fd.CHECK_KEYS)
    assert row["overall"] == "green"
    assert all(c["status"] == "pass" for c in row["checks"])


def test_each_red_family_hits_exactly_its_own_check():
    cases = {
        "identity": node(enrolled=False),
        "heartbeat": node(heartbeat_age_seconds=31),
        "version": node(version="1.3.9"),
        "queue": node(queue_reachable=False),
        "capacity": node(running_cnt=4),
        "drain": node(capacity=4, running_cnt=9),
    }
    for expected_key, facts in cases.items():
        row = fd.inspect_node(facts)
        by_key = checks_by_key(row)
        assert row["overall"] == "red", f"{expected_key} must turn the node red"
        assert by_key[expected_key]["status"] == "fail"
        assert by_key[expected_key]["reason"].strip(), "a red line must explain itself"


def test_missing_facts_are_unknown_never_red_and_never_green():
    blank = node(enrolled=None, heartbeat_age_seconds=None, version=None,
                 gold_version=None, queue_reachable=None,
                 capacity=None, running_cnt=None)
    row = fd.inspect_node(blank)
    assert all(c["status"] == "unknown" for c in row["checks"])
    assert row["overall"] == "unknown"
    assert all(c["reason"].strip() for c in row["checks"])
    assert fd.GLYPH["unknown"] in row["line"]


def test_offline_threshold_is_an_option_not_a_hardcoded_wall():
    # shipped default mirrors testpike.executor.offline-seconds = 30
    assert checks_by_key(fd.inspect_node(node(heartbeat_age_seconds=29)))["heartbeat"]["status"] == "pass"
    # caller-injected configured value must be honoured (single source stays server-side)
    relaxed = fd.inspect_node(node(heartbeat_age_seconds=60), {"offline_seconds": 90})
    assert checks_by_key(relaxed)["heartbeat"]["status"] == "pass"


def test_negative_heartbeat_age_names_the_clock_skew():
    check = checks_by_key(fd.inspect_node(node(heartbeat_age_seconds=-40)))["heartbeat"]
    assert check["status"] == "fail"
    assert "-40" in check["reason"]


def test_build_matrix_keeps_order_and_counts_three_states():
    matrix = fd.build_matrix([
        node(name="ex-a"),
        node(name="ex-b", enrolled=False),
        node(name="ex-c", enrolled=None, heartbeat_age_seconds=None,
             version=None, queue_reachable=None, capacity=None, running_cnt=None),
    ])
    assert [r["node"] for r in matrix["rows"]] == ["ex-a", "ex-b", "ex-c"]
    assert matrix["summary"] == {"green": 1, "red": 1, "unknown": 1}


def test_render_emits_zero_reason_lines_for_a_healthy_fleet():
    matrix = fd.build_matrix([node(name="ex-a"), node(name="ex-ok")])
    lines = fd.render_lines(matrix)
    assert len(lines) == 2, "one row line per node, nothing else when all green"
    assert not any("✗" in ln or "?" in ln[ln.index("] "):] for ln in lines)


def test_red_row_renders_reason_with_literal_fix_entry():
    matrix = fd.build_matrix([node(name="ex-b", heartbeat_age_seconds=600)])
    text = "\n".join(fd.render_lines(matrix))
    assert "ex-b" in text
    # guidance must name a real, greppable entry point, not an open-ended "check config"
    assert "tp-service-agent" in text


def test_version_red_points_at_the_upgrade_center():
    matrix = fd.build_matrix([node(name="ex-v", version="1.2.0", gold_version="1.4.0")])
    text = "\n".join(fd.render_lines(matrix))
    assert "升级中心" in text


def test_reasons_render_on_both_language_tracks():
    facts = node(name="ex-l", enrolled=False, queue_reachable=False)
    zh = checks_by_key(fd.inspect_node(facts))
    os.environ["INSTALLKIT_LANG"] = "en"
    try:
        en = checks_by_key(fd.inspect_node(facts))
    finally:
        os.environ.pop("INSTALLKIT_LANG", None)
    for key in ("identity", "queue"):
        assert zh[key]["reason"] and en[key]["reason"]
        assert zh[key]["reason"] != en[key]["reason"]
