
from __future__ import annotations

import re
import unittest
from pathlib import Path

from actions_common import (
    APP_NAMESPACE,
    CAPABILITY_FLAGS,
    IR_VERBS,
    app_action_violation,
    app_verb,
    is_app_action,
)

FROZEN_VERBS = [
    "launch", "close", "tap", "longPress", "doubleTap", "input", "clearInput",
    "swipe", "scroll", "back", "screenshot", "assertVisible", "assertNotVisible",
    "assertText", "waitVisible", "extractText",
    "deepLink", "openUrl", "rotation", "home", "background", "foreground",
    "clearData", "grantPermission", "wakeUnlock", "networkProfile",
    "install", "uninstall", "verifyVersion", "perf", "launchPerf",
]

_JAVA_CONTRACT = (
    Path(__file__).resolve().parents[2]
    / "common/src/main/java/com/sixbean/testpike/common/util/UiActionContract.java"
)


class TestIrVerbBaseline(unittest.TestCase):
    def test_frozen_verb_baseline(self) -> None:
        self.assertEqual(tuple(FROZEN_VERBS), IR_VERBS)
        self.assertEqual("app.", APP_NAMESPACE)

    def test_前缀判定与动词提取(self) -> None:
        self.assertTrue(is_app_action("app.tap"))
        self.assertFalse(is_app_action("click"))
        self.assertFalse(is_app_action("mobile.tap"))
        self.assertFalse(is_app_action(""))
        self.assertEqual("assertVisible", app_verb("app.assertVisible"))
        self.assertIsNone(app_verb("click"))

    def test_基线外动词_人话点名全集(self) -> None:
        violation = app_action_violation("app.fly_to_moon")
        self.assertIsNotNone(violation)
        self.assertIn("fly_to_moon", violation)
        for verb in FROZEN_VERBS:
            self.assertIn(verb, violation, f"违例文案必须列出动词基线全集：{verb}")

    def test_合法动词不归违例(self) -> None:
        self.assertIsNone(app_action_violation("app.launch"))
        self.assertIsNone(app_action_violation("click"), "非 app.* 步不归本契约管辖")


class TestDualSideReconcile(unittest.TestCase):
    pass

    @staticmethod
    def _java_tokens(marker: str) -> list:
        src = _JAVA_CONTRACT.read_text(encoding="utf-8")
        block = re.search(marker + r"[^=]+= *(?:Set|List)\.of\((.*?)\);", src, re.S)
        assert block, f"UiActionContract.java 未解析到 {marker}（对账闸锚点缺失）"
        return re.findall(r'"([^"]+)"', block.group(1))

    def test_与java权威表动词逐枚一致(self) -> None:
        self.assertEqual(list(IR_VERBS), self._java_tokens(r"\bIR_VERBS"))

    def test_与java权威表特性旗逐枚一致(self) -> None:
        self.assertEqual(sorted(CAPABILITY_FLAGS), sorted(self._java_tokens(r"\bCAPABILITY_FLAGS")))

    def test_与java权威表探索黑名单逐枚一致(self) -> None:
        pass
        from actions_common import EXPLORE_FORBIDDEN_VERBS
        self.assertEqual(sorted(EXPLORE_FORBIDDEN_VERBS),
                         sorted(self._java_tokens(r"\bEXPLORE_FORBIDDEN_VERBS")))

    def test_与java权威表webview步旗键一致(self) -> None:
        pass
        src = _JAVA_CONTRACT.read_text(encoding="utf-8")
        java_flag = re.search(r'\bWEBVIEW_STEP_FLAG\s*=\s*"([^"]+)"', src)
        assert java_flag, "UiActionContract.java 未解析到 WEBVIEW_STEP_FLAG（对账闸锚点缺失）"
        from driver.mobile.contracts import WEBVIEW_STEP_FLAG as py_flag
        self.assertEqual(java_flag.group(1), py_flag)


if __name__ == "__main__":
    unittest.main()
