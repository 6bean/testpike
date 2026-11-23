"""app.perf host-side threshold-line assertion core (W5 phase-two B6 perf chain).

Engine-agnostic by design: the verdict is computed here on the executor host
from the perf sampling session's JSONL rows, never inside an engine adapter.
A step carries a threshold line ``<metric><op><number>`` (op one of ``<=``,
``<``, ``>=``, ``>``) and is evaluated against the NEWEST readable sample
row — window averages and period-over-period medians belong to the batch
report surface, not to this gate.

Honesty rules (strict-testing): missing session, unreadable sample file or a
missing metric key in the newest row all fail with a human-readable pointer
naming the supported surface; filling 0 or guessing is forbidden.

Single-source discipline: HOST_SIDE_VERBS is the only host-side verb list —
app_worker's dispatch pre-gate consumes this set, second copies elsewhere are
a drift violation (red line 6).
"""
from __future__ import annotations

import json
import re
from typing import Any, Optional

# Host-side verbs: dispatched to this module before (and instead of) the
# engine adapter — viewport scaling and device drivers do not apply to
# sample-file assertions.
HOST_SIDE_VERBS: frozenset = frozenset({"perf", "launchPerf"})

# Metric whitelist read off the newest row of its owning sink. ``pssMb`` is
# derived from the sampled ``pssKb`` through the single conversion point
# below, so case authors never have to translate KB thresholds by hand.
# ``launchTotalMs``/``launchWaitMs`` come from the launch-timing sink written
# by app.launchPerf (launch_perf.py) — a separate file on purpose, so the
# periodic probe rows can never race a launch assertion.
SUPPORTED_METRICS: frozenset = frozenset({
    "jankPct", "pssMb", "cpuPct", "tempC", "totalFrames", "jankyFrames",
    "launchTotalMs", "launchWaitMs",
})

# Metrics whose newest row lives in the launch-timing sink (single routing point).
_LAUNCH_METRICS: frozenset = frozenset({"launchTotalMs", "launchWaitMs"})

# Threshold-line grammar: metric + comparison operator + number.
_EXPR_PATTERN = re.compile(
    r"^\s*([A-Za-z][A-Za-z0-9]*)\s*(<=|>=|<|>)\s*(-?\d+(?:\.\d+)?)\s*$"
)

# adb meminfo samples KB; the assertion surface speaks MB (single source).
_KB_PER_MB = 1024.0


def _metric_catalog() -> str:
    """Whitelist listing shared by every rejection wording (fix-without-docs)."""
    return " / ".join(sorted(SUPPORTED_METRICS))


def _fail(detail: str) -> dict:
    return {"result": "fail", "detail": detail}


def _pass(detail: str) -> dict:
    return {"result": "pass", "detail": detail}


def read_rows(path: Optional[str]) -> list[dict]:
    """Parse every well-formed JSONL row of one sink (absent/unreadable = []).

    Malformed lines are skipped honestly (device-side broken-row precedent);
    an absent or unreadable file is treated as "no samples", never as zeros.
    Shared by the threshold gate (newest row) and the window rollup (all
    rows) so a second row-parsing copy never appears on the perf sinks.
    """
    if not path:
        return []
    rows: list[dict] = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict):
                    rows.append(row)
    except OSError:
        return []
    return rows


def _latest_row(path: Optional[str]) -> Optional[dict]:
    """Return the newest parseable JSONL row of one sink, or None if unreadable."""
    rows = read_rows(path)
    return rows[-1] if rows else None


def metric_value(latest: dict, metric: str) -> Optional[float]:
    """Read one metric off a sink row; None = not sampled (never 0).

    Single reading surface for both the step threshold gate (newest row of
    its owning sink) and the batch window rollup (summarize_metrics over all
    rows), including the pssKb -> pssMb conversion — a second divisor or a
    second key lookup anywhere else is a drift violation.
    """
    raw = latest.get("pssKb") if metric == "pssMb" else latest.get(metric)
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return None
    value = float(raw)
    if metric == "pssMb":
        value = value / _KB_PER_MB
    return value


def _compare(value: float, op: str, threshold: float) -> bool:
    if op == "<=":
        return value <= threshold
    if op == "<":
        return value < threshold
    if op == ">=":
        return value >= threshold
    return value > threshold


def evaluate_perf_step(step: dict, session: Optional[Any]) -> dict:
    """Evaluate one app.perf threshold line against the newest perf sample.

    Returns ``{"result": "pass"|"fail", "detail": ...}``; every branch names
    the concrete reading or the supported surface (no bare pass, no silent
    zero-fill).
    """
    expr = step.get("expr")
    if expr is None or not str(expr).strip():
        return _fail("app.perf 步缺少 expr 阈值行——格式=指标+比较符（<=、<、>=、>）+数值，"
                     "如 jankPct<=10；支持指标：" + _metric_catalog())
    match = _EXPR_PATTERN.match(str(expr))
    if match is None:
        return _fail(f"app.perf 阈值行「{expr}」无法解析——格式=指标+比较符（<=、<、>=、>）"
                     "+数值，如 jankPct<=10；支持指标：" + _metric_catalog())
    metric, op, threshold = match.group(1), match.group(2), float(match.group(3))
    if metric not in SUPPORTED_METRICS:
        return _fail(f"app.perf 指标「{metric}」未支持（未纳入本包采样白名单）；"
                     "支持指标：" + _metric_catalog())
    if session is None:
        return _fail("app.perf 无法评估：性能采样会话缺位（本任务未建立采样通道，读数不可得，"
                     "禁假过）；支持指标：" + _metric_catalog())
    if metric in _LAUNCH_METRICS:
        latest = _latest_row(getattr(session, "launches_path", None))
        if latest is None:
            return _fail("app.perf 无法评估：启动耗时通道尚无样例行"
                         "（需先执行 app.launchPerf 步真实冷/热启动被测应用，"
                         "读数不可得，禁假过）")
    else:
        latest = _latest_row(getattr(session, "samples_path", None))
        if latest is None:
            return _fail("app.perf 无法评估：性能采样会话尚无样本行"
                         "（采样未产出，读数不可得，禁假过）")
    value = metric_value(latest, metric)
    if value is None:
        return _fail(f"app.perf 无法评估：最新样本行缺少指标「{metric}」"
                     "（该路采样缺位，禁按 0 猜值）")
    verdict_expr = f"{metric} {op} {threshold}"
    if _compare(value, op, threshold):
        return _pass(f"app.perf 通过：阈值行 {expr}，最新采样读数 {metric}={value}")
    return _fail(f"app.perf 未达阈值：{expr}，最新采样读数 {metric}={value}"
                 f"（要求 {verdict_expr}）")
