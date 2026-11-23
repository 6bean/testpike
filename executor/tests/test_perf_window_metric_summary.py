"""Mobile performance window rollup trailing key (W5 phase-two B6 P4c-1).

The batch report surface consumes in-window aggregate numbers (median per
metric + read counts) that the executor computes ONCE from the sampling
sinks and trails on the result payload — the core side never re-parses raw
adb rows (reporting-channel adjudication, recon package).

Covers four contracts:
1. metric_value = the single reading surface shared by the threshold gate
 and the window rollup (no second conversion point for pssKb -> pssMb);
2. summarize_metrics = median/count rollup, unsampled metrics honestly
 absent (never 0-filled), non-whitelist keys ignored;
3. RunOutcome round-trips the trailing field across the subprocess result
 file, absent = no key ;
4. the /internal/tasks/result payload trails mobilePerfSummary only when a
 summary exists.
"""
from __future__ import annotations

from typing import Optional

import pytest

from driver.mobile.perf_assert import metric_value
from driver.mobile.perf_session import summarize_metrics


# ---------------------------------------------------------------- metric_value

def test_metric_value_reads_declared_metric_off_the_row() -> None:
    assert metric_value({"cpuPct": 30.0}, "cpuPct") == 30.0


def test_metric_value_converts_pss_kb_to_mb_through_single_source() -> None:
    # adb sinks speak pssKb; the metric surface speaks pssMb (1024 divisor is
    # owned by perf_assert, tests must not re-derive it).
    assert metric_value({"pssKb": 2048}, "pssMb") == 2.0


def test_metric_value_absent_or_non_numeric_reads_as_none_never_zero() -> None:
    assert metric_value({}, "jankPct") is None
    assert metric_value({"cpuPct": "unparseable"}, "cpuPct") is None
    assert metric_value({"cpuPct": True}, "cpuPct") is None


# ----------------------------------------------------------- summarize_metrics

def test_summarize_metrics_median_odd_and_even_sample_counts() -> None:
    odd = summarize_metrics([{"cpuPct": 10.0}, {"cpuPct": 30.0},
                             {"cpuPct": 20.0}], [])
    assert odd["metrics"]["cpuPct"] == {"median": 20.0, "count": 3}
    even = summarize_metrics([{"cpuPct": 10.0}, {"cpuPct": 20.0},
                              {"cpuPct": 30.0}, {"cpuPct": 40.0}], [])
    assert even["metrics"]["cpuPct"] == {"median": 25.0, "count": 4}


def test_summarize_metrics_folds_launch_sink_rows_into_launch_metrics() -> None:
    summary = summarize_metrics(
        [{"cpuPct": 5.0}],
        [{"launchTotalMs": 1000.0}, {"launchTotalMs": 3000.0,
                                     "launchWaitMs": 800.0}],
    )
    assert summary["metrics"]["launchTotalMs"] == {"median": 2000.0, "count": 2}
    assert summary["metrics"]["launchWaitMs"] == {"median": 800.0, "count": 1}
    assert summary["metrics"]["cpuPct"]["count"] == 1


def test_summarize_metrics_unsampled_metric_is_absent_not_zero() -> None:
    summary = summarize_metrics([{"cpuPct": 5.0}], [])
    assert "jankPct" not in summary["metrics"]
    assert "pssMb" not in summary["metrics"]


def test_summarize_metrics_returns_none_when_no_readings() -> None:
    assert summarize_metrics([], []) is None
    # rows exist but carry no whitelist metric = honestly no summary
    assert summarize_metrics([{"ts": 1}, {"note": "junk"}], []) is None


def test_summarize_metrics_ignores_non_whitelist_keys() -> None:
    summary = summarize_metrics([{"cpuPct": 1.0, "bogusMetric": 99.0,
                                  "pssKb": 1024}], [])
    assert set(summary["metrics"]) == {"cpuPct", "pssMb"}
    assert summary["metrics"]["pssMb"] == {"median": 1.0, "count": 1}


def test_summarize_metrics_sample_count_covers_sampling_rows_only() -> None:
    summary = summarize_metrics([{"cpuPct": 1.0}, {"cpuPct": 2.0},
                                 {"cpuPct": 3.0}],
                                [{"launchTotalMs": 10.0},
                                 {"launchTotalMs": 20.0}])
    assert summary["sampleCount"] == 3


# ----------------------------------------------------- RunOutcome round-trip

class _CapturingProtocolClient:
    """Offline capture of the result payload (protocol face of the contract;
    the live chain is covered by L4, never mocked for orchestration)."""

    def __init__(self) -> None:
        self.captured: Optional[dict] = None

    def _post(self, path: str, payload: dict) -> dict:
        assert path == "/internal/tasks/result"
        self.captured = payload
        return {"code": "0"}

    def submit_result(self, *args, **kwargs) -> dict:
        from protocol import ExecutorProtocolClient
        return ExecutorProtocolClient.submit_result(self, *args, **kwargs)


def test_run_outcome_trailing_field_round_trips_through_result_file() -> None:
    from driver.webdriver import RunOutcome

    summary = {"metrics": {"cpuPct": {"median": 20.0, "count": 3}},
               "sampleCount": 3}
    outcome = RunOutcome(result="pass", mobile_perf_summary=summary)
    restored = RunOutcome.from_dict(outcome.to_dict())
    assert restored.mobile_perf_summary == summary


def test_run_outcome_without_summary_keeps_payload_key_absent() -> None:
    from driver.webdriver import RunOutcome

    plain = RunOutcome(result="pass")
    assert "mobile_perf_summary" not in plain.to_dict()
    assert RunOutcome.from_dict(plain.to_dict()).mobile_perf_summary is None


def test_submit_result_trails_mobile_perf_summary_only_when_present() -> None:
    client = _CapturingProtocolClient()
    client.submit_result(1, "tk", "pass")
    assert "mobilePerfSummary" not in client.captured

    summary = {"metrics": {"jankPct": {"median": 1.5, "count": 2}},
               "sampleCount": 2}
    client2 = _CapturingProtocolClient()
    client2.submit_result(2, "tk", "pass", mobile_perf_summary=summary)
    assert client2.captured["mobilePerfSummary"] == summary
