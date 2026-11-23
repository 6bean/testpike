
from __future__ import annotations

import json
import sys
import time

from driver.sandbox_core import run_sandboxed_script
from driver.webdriver import RunOutcome

ENVELOPE_KIND = "tool-test-run"


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
    entry = str(envelope.get("scriptEntry") or "")
    if not entry or "/" in entry or "\\" in entry or ".." in entry:
        raise ValueError(f"scriptEntry 非法（仅允许纯文件名，禁路径分隔符）: {entry!r}")
    if not str(envelope.get("script") or "").strip():
        raise ValueError("工具脚本正文为空（信封 script 字段缺失）")
    return envelope


def run_tool_task(action_json: str) -> RunOutcome:
    pass
    started = time.monotonic()
    try:
        envelope = _parse_envelope(action_json)
    except (ValueError, TypeError) as e:
        return RunOutcome(result="fail", error_type=3, error_detail=f"任务信封非法: {e}",
                          duration_ms=int((time.monotonic() - started) * 1000))

    result = run_sandboxed_script(str(envelope["script"]), str(envelope["scriptEntry"]),
                                  input_text=str(envelope.get("input") or ""),
                                  budget_ms=float(envelope.get("timeoutMs") or 30000))
    duration = int((time.monotonic() - started) * 1000)
    budget_sec = max(float(envelope.get("timeoutMs") or 30000), 100.0) / 1000.0
    if result.timed_out:
        return RunOutcome(
            result="fail", error_type=3,
            error_detail=f"沙箱执行超过 {budget_sec:.0f} 秒未退出，已限时终止"
                         f"（IT-TOOL-03 超时分支）",
            duration_ms=duration)
    stdout_lines = (result.stdout or "").splitlines()[:200]
    if result.returncode == 0:
        return RunOutcome(result="pass", duration_ms=duration, console_logs=stdout_lines)
    return RunOutcome(
        result="fail", error_type=3,
        error_detail=f"脚本非零退出（exit={result.returncode}）："
                     + (result.stderr.strip() or "无 stderr 输出"),
        duration_ms=duration, console_logs=stdout_lines)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("用法: tool_worker.py <task.json> <outcome.json>", file=sys.stderr)
        return 2
    task_f, out_f = argv[1], argv[2]
    try:
        with open(task_f, "r", encoding="utf-8") as f:
            task = json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"任务文件读取失败: {e}", file=sys.stderr)
        return 2

    try:
        outcome = run_tool_task(str(task.get("actionJson") or ""))
    except Exception as e:
        outcome = RunOutcome(result="fail", error_type=3, error_detail=f"沙箱执行异常: {e}")

    try:
        with open(out_f, "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        print(f"结果文件写入失败: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
