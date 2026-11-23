
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

from actions_common import EXPLORE_FORBIDDEN_VERBS, IR_VERBS
from explore_worker import (
    ENVELOPE_KIND,
    MOBILE_ROAM_ACTIONS,
    MOBILE_TARGET,
    TREE_CARRIER_STEP,
    _parse_envelope,
    _tree_step,
    run_explore_task,
)

_JAVA_CONTRACT = (
    Path(__file__).resolve().parents[2]
    / "common/src/main/java/com/sixbean/testpike/common/util/ExploreEnvelopeContract.java"
)


def _java_string_list(source: str, name: str) -> tuple[str, ...]:
    m = re.search(name + r"\s*=\s*List\.of\(([^)]*)\)", source, re.DOTALL)
    assert m, f"{name} 清单在 ExploreEnvelopeContract.java 未找到（对账锚丢失=先修锚）"
    return tuple(re.findall(r'"([^"]+)"', m.group(1)))


def _java_const(source: str, name: str) -> str:
    m = re.search(r'String\s+' + name + r'\s*=\s*"([^"]*)"', source)
    assert m, f"{name} 常量在 ExploreEnvelopeContract.java 未找到（对账锚丢失=先修锚）"
    return m.group(1)


class TestRoamContractMirror(unittest.TestCase):
    def setUp(self) -> None:
        self.java = _JAVA_CONTRACT.read_text(encoding="utf-8")

    def test_漫游白名单双端逐字同集合(self) -> None:
        self.assertEqual(_java_string_list(self.java, "MOBILE_ROAM_ACTIONS"),
                         tuple(MOBILE_ROAM_ACTIONS))

    def test_信封常量双端逐字一致(self) -> None:
        self.assertEqual(_java_const(self.java, "EXPLORE_ACT_KIND"), ENVELOPE_KIND)
        self.assertEqual(_java_const(self.java, "TARGET_MOBILE"), MOBILE_TARGET)
        self.assertEqual(_java_const(self.java, "TREE_CARRIER_STEP"), TREE_CARRIER_STEP)

    def test_漫游映射步全部落IR基线且避开黑名单(self) -> None:
        for action in MOBILE_ROAM_ACTIONS:
            step = _tree_step({"action": action, "arguments": {}})
            verb = step["action"]
            self.assertIn(verb, [f"app.{v}" for v in IR_VERBS] + [TREE_CARRIER_STEP],
                          f"漫游步必须映射到 IR 基线动词: {verb}")
            core = verb.removeprefix("app.")
            self.assertNotIn(core, EXPLORE_FORBIDDEN_VERBS,
                             f"黑名单动词不得出现在漫游映射面: {core}")

    def test_白名单外信封_形状守fail_closed零拉起(self) -> None:
        envelope = {"kind": ENVELOPE_KIND, "target": MOBILE_TARGET,
                    "action": "uninstall", "engine": {"id": "mw"},
                    "leaseRef": {"serial": "emulator-5554"}}
        with self.assertRaises(ValueError):
            _parse_envelope(json.dumps(envelope, ensure_ascii=False))
        outcome = run_explore_task(json.dumps(envelope, ensure_ascii=False), "/tmp")
        self.assertEqual("fail", outcome.result)
        self.assertIn("漫游白名单", outcome.error_detail)


if __name__ == "__main__":
    unittest.main()
