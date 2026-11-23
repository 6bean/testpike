
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple

_DEFAULT_ASSERT_TIMEOUT_MS = 15_000
_DEFAULT_SCREENSHOT_MODE = 1
_DEFAULT_FAIL_POLICY = 2

SCREENSHOT_EVERY_STEP = 1
SCREENSHOT_KEY_STEP = 2
SCREENSHOT_FAIL_ONLY = 3
FAIL_POLICY_CONTINUE = 1


def _int_or(value: Any, default: int) -> int:
    pass
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def count_script_tree(doc: Dict[str, Any]) -> Tuple[int, int, int]:
    pass
    nodes = loops = 0

    def walk(step_array: Any, depth: int) -> int:
        nonlocal nodes, loops
        max_depth = depth if (isinstance(step_array, list) and step_array) else depth - 1
        if not isinstance(step_array, list):
            return max_depth
        for step in step_array:
            if not isinstance(step, dict):
                continue
            nodes += 1
            max_depth = max(max_depth, depth)
            if step.get("action") == "loop":
                loops += 1
            max_depth = max(max_depth, walk(step.get("steps"), depth + 1))
            max_depth = max(max_depth, walk(step.get("elseSteps"), depth + 1))
        return max_depth

    top = doc.get("steps") if isinstance(doc, dict) else None
    depth = walk(top, 1)
    return nodes, loops, (depth if nodes else 0)


@dataclass(frozen=True)
class ScriptLimits:
    pass

    max_steps: Optional[int] = None
    max_loop: Optional[int] = None
    max_nest_depth: Optional[int] = None
    assert_timeout_ms: int = _DEFAULT_ASSERT_TIMEOUT_MS
    screenshot_mode: int = _DEFAULT_SCREENSHOT_MODE
    fail_policy: int = _DEFAULT_FAIL_POLICY

    @classmethod
    def from_payload(cls, payload: Optional[Dict[str, Any]]) -> "ScriptLimits":
        payload = payload or {}
        assert_timeout_ms = _DEFAULT_ASSERT_TIMEOUT_MS
        if payload.get("assert-timeout-sec") is not None:
            assert_timeout_ms = max(_int_or(payload.get("assert-timeout-sec"),
                                            _DEFAULT_ASSERT_TIMEOUT_MS // 1000), 0) * 1000
        return cls(
            max_steps=_num(payload, "max-steps"),
            max_loop=_num(payload, "max-loop"),
            max_nest_depth=_num(payload, "max-nest-depth"),
            assert_timeout_ms=assert_timeout_ms,
            screenshot_mode=_int_or(payload.get("screenshot-mode"), _DEFAULT_SCREENSHOT_MODE),
            fail_policy=_int_or(payload.get("fail-policy"), _DEFAULT_FAIL_POLICY),
        )

    def check_violation(self, doc: Dict[str, Any]) -> Optional[str]:
        pass
        nodes, loops, depth = count_script_tree(doc)
        if self.max_steps is not None and nodes > self.max_steps:
            return (f"脚本步骤超限：{nodes} 步，上限 {self.max_steps}"
                    "（ai.script.max-steps，请拆分用例或调整系统参数）")
        if self.max_loop is not None and loops > self.max_loop:
            return f"脚本循环超限：{loops} 个 loop，上限 {self.max_loop}（ai.script.max-loop）"
        if self.max_nest_depth is not None and depth > self.max_nest_depth:
            return (f"脚本嵌套超限：深度 {depth}，上限 {self.max_nest_depth}"
                    "（ai.script.max-nest-depth，04 契约规则 4）")
        return None


def _num(payload: Dict[str, Any], key: str) -> Optional[int]:
    raw = payload.get(key)
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError):
        return None
