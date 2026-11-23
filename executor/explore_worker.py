
from __future__ import annotations

import json
import os
import sys
import time

from app_worker import run_app_task
from config import ExecutorConfig
from driver.element_candidates import VIEWPORT_CLASSES_STEP_KEY
from driver.webdriver import RunOutcome, WebDriverExecutor
from site_survey_worker import SITE_SURVEY_ACTION, run_site_survey_task

ENVELOPE_KIND = "explore-act"
BUILTIN_ACTIONS = ("page-snapshot", "click-path", "input-path", "assert",
                   "element-candidates")
MOBILE_TARGET = "mobile"
MOBILE_ROAM_ACTIONS = ("tree-snapshot", "tap", "back", "swipe", "home")
TREE_ACTION = "tree-snapshot"
TREE_CARRIER_STEP = "app.screenshot"
TREE_ABSENT_NOTE = "无障碍树不可得（引擎未回传 view_tree 证据件，禁假造观测）"
EXPLORE_ASSERT_TIMEOUT_SEC = 5
SNAPSHOT_MAX_CHARS = 6000


def _parse_envelope(action_json: str) -> dict:
    pass
    try:
        envelope = json.loads(action_json or "")
    except Exception as e:
        raise ValueError(f"任务信封不是合法 JSON: {e}") from e
    if not isinstance(envelope, dict):
        raise TypeError("任务信封必须是 JSON 对象")
    if envelope.get("kind") != ENVELOPE_KIND:
        raise ValueError(f"任务信封 kind 非法（期望 {ENVELOPE_KIND}）: {envelope.get('kind')!r}")
    if envelope.get("target") == MOBILE_TARGET:
        return _check_mobile_shape(envelope)
    action = str(envelope.get("action") or "")
    if action == SITE_SURVEY_ACTION:
        # deterministic deep-crawl envelope: rules-shape is adjudicated by
        # the consumer itself (site_survey_worker names the missing key)
        return envelope
    if action not in BUILTIN_ACTIONS:
        raise ValueError(f"探索动作非法（白名单：{'、'.join(BUILTIN_ACTIONS)}）: {action!r}")
    url = str(envelope.get("url") or "")
    if not url:
        raise ValueError("信封 url 缺失（执行器无会话语境，探索动作必须自带目标 URL）")
    if not url.startswith(("http://", "https://")):
        raise ValueError(f"信封 url 非 http(s) 绝对址: {url!r}")
    return envelope


def _check_mobile_shape(envelope: dict) -> dict:
    pass
    action = str(envelope.get("action") or "")
    if action not in MOBILE_ROAM_ACTIONS:
        raise ValueError(f"移动探索动作非法（漫游白名单：{'、'.join(MOBILE_ROAM_ACTIONS)}）: "
                         f"{action!r}")
    engine = envelope.get("engine")
    if not isinstance(engine, dict) or not engine:
        raise ValueError("移动探索信封 engine 缺失（引擎描述符必随信封自带，"
                         "执行器无会话语境禁猜引擎）")
    lease = envelope.get("leaseRef")
    serial = str((lease or {}).get("serial") or "") if isinstance(lease, dict) else ""
    if not serial:
        raise ValueError("移动探索信封 leaseRef.serial 缺失（设备会话由 serial 定位，"
                         "探索动作必须自带设备序列号）")
    return envelope


def build_script_doc(envelope: dict) -> dict:
    pass
    action = envelope["action"]
    args = envelope.get("arguments") if isinstance(envelope.get("arguments"), dict) else {}
    if action == "page-snapshot":
        steps = [{"action": "snapshot"}]
    elif action == "element-candidates":
        step: dict = {"action": "element-candidates"}
        tiers = envelope.get(VIEWPORT_CLASSES_STEP_KEY)
        if isinstance(tiers, list) and tiers:
            step[VIEWPORT_CLASSES_STEP_KEY] = tiers
        steps = [step]
    elif action == "click-path":
        steps = [{"action": "click", "target": args.get("target") or {}}]
    elif action == "input-path":
        steps = [{"action": "input", "target": args.get("target") or {},
                  "value": str(args.get("value") or "")}]
    else:
        steps = [{"action": "expect", "text": args.get("text"),
                  "target": args.get("target") or {}}]
    return {"version": "explore-v1",
            "setup": [{"action": "goto", "target": {"url": envelope["url"]}}],
            "steps": steps}


def _tree_step(envelope: dict) -> dict:
    pass
    action = str(envelope.get("action") or "")
    raw = envelope.get("arguments")
    args: dict = dict(raw) if isinstance(raw, dict) else {}
    args["action"] = TREE_CARRIER_STEP if action == TREE_ACTION else f"app.{action}"
    return args


def _read_tree_detail(outcome: RunOutcome) -> str:
    pass
    for item in outcome.pending_artifacts:
        if str(item.get("kind") or "") != "dom_snapshot":
            continue
        path = str(item.get("localPath") or "")
        try:
            with open(path, "r", encoding="utf-8") as f:
                text = f.read()
        except OSError:
            continue
        if not text.strip():
            continue
        if len(text) > SNAPSHOT_MAX_CHARS:
            return text[:SNAPSHOT_MAX_CHARS] + "…（truncated）"
        return text
    return TREE_ABSENT_NOTE


def _run_mobile_explore_task(envelope: dict, started: float) -> RunOutcome:
    pass
    app_envelope = {"kind": "app-auto-run",
                    "runId": envelope.get("runId"),
                    "leaseRef": envelope.get("leaseRef"),
                    "engine": envelope["engine"],
                    "steps": [_tree_step(envelope)]}
    task = {"taskId": envelope.get("taskId"), "caseId": 0,
            "taskType": 6, "actionJson": json.dumps(app_envelope, ensure_ascii=False)}
    outcome = run_app_task(task)
    if str(envelope.get("action") or "") == TREE_ACTION and outcome.steps_timeline:
        outcome.steps_timeline[-1]["detail"] = _read_tree_detail(outcome)
    if outcome.duration_ms == 0:
        outcome.duration_ms = int((time.monotonic() - started) * 1000)
    return outcome


def run_explore_task(action_json: str, screenshots_dir: str,
                     session_state: dict | None = None) -> RunOutcome:
    pass
    started = time.monotonic()
    try:
        envelope = _parse_envelope(action_json)
    except (ValueError, TypeError) as e:
        return RunOutcome(result="fail", error_type=3,
                          error_detail=f"探索动作信封非法: {e}",
                          duration_ms=int((time.monotonic() - started) * 1000))
    if envelope.get("target") == MOBILE_TARGET:
        return _run_mobile_explore_task(envelope, started)
    if envelope.get("action") == SITE_SURVEY_ACTION:
        return run_site_survey_task(action_json, screenshots_dir, session_state)
    executor: WebDriverExecutor | None = None
    try:
        executor = WebDriverExecutor(env_base_url=None, headless=True,
                                     screenshots_dir=screenshots_dir,
                                     session_state=session_state,
                                     # Explore sessions arm the write-side guard:
                                     # an autosaving page must be detected even
                                     # though the action whitelist has no submit
                                     # verb (design pieces 1/2, single-action path).
                                     network_guard=True,
                                     script_limits={"screenshot-mode": 1,
                                                    "assert-timeout-sec":
                                                        EXPLORE_ASSERT_TIMEOUT_SEC})
        executor.start()
        return executor.run(json.dumps(build_script_doc(envelope), ensure_ascii=False),
                            case_title=f"explore-{envelope['action']}")
    except Exception as e:
        return RunOutcome(result="fail", error_type=3,
                          error_detail=f"探索动作执行异常: {e}",
                          duration_ms=int((time.monotonic() - started) * 1000))
    finally:
        if executor is not None:
            try:
                executor.stop()
            except Exception:
                pass


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("用法: explore_worker.py <task.json> <outcome.json>", file=sys.stderr)
        return 2
    task_f, out_f = argv[1], argv[2]
    try:
        with open(task_f, "r", encoding="utf-8") as f:
            task = json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"任务文件读取失败: {e}", file=sys.stderr)
        return 2

    outcome = run_explore_task(str(task.get("actionJson") or ""),
                               ExecutorConfig().screenshots_dir,
                               session_state=task.get("sessionState"))
    outcome.screenshots = [os.path.abspath(p) for p in outcome.screenshots if p]

    try:
        with open(out_f, "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        print(f"结果文件写入失败: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
