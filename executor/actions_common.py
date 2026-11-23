
from __future__ import annotations

from typing import Optional

APP_NAMESPACE = "app."

# IR verb baseline, thirty-one frozen-order entries (mirrors Java IR_VERBS;
# never enumerate either side separately — the two reconcile gates turn a
# drift red)
IR_VERBS: tuple = (
    "launch", "close", "tap", "longPress", "doubleTap", "input", "clearInput",
    "swipe", "scroll", "back", "screenshot", "assertVisible", "assertNotVisible",
    "assertText", "waitVisible", "extractText",
    "deepLink", "openUrl", "rotation", "home", "background", "foreground",
    "clearData", "grantPermission", "wakeUnlock", "networkProfile",
    "install", "uninstall", "verifyVersion", "perf", "launchPerf",
)

CAPABILITY_FLAGS: frozenset = frozenset({
    "webview-hybrid", "network-throttle", "record-native", "flutter", "image-matching",
})

EXPLORE_FORBIDDEN_VERBS: frozenset = frozenset({
    "uninstall", "clearData", "install",
})

_IR_VERB_SET: frozenset = frozenset(IR_VERBS)


def is_app_action(action: str) -> bool:
    pass
    return bool(action) and action.startswith(APP_NAMESPACE)


def app_verb(action: str) -> Optional[str]:
    pass
    if not is_app_action(action):
        return None
    return action[len(APP_NAMESPACE):]


def is_baseline_verb(action: str) -> bool:
    pass
    return app_verb(action) in _IR_VERB_SET


def app_action_violation(action: str) -> Optional[str]:
    pass
    verb = app_verb(action)
    if verb is None:
        return None
    if verb in _IR_VERB_SET:
        return None
    return ("app." + verb + " 不在抽象动作 IR 基线（04 §2.2）——引擎无关动作仅基线 "
            + str(len(IR_VERBS)) + " 枚动词："
            + " / ".join(IR_VERBS)
            + "（换引擎零改用例是 IR 的命门；平台专属能力请走引擎描述符能力位而非新增动词）")
