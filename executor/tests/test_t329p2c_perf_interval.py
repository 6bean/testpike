
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from driver.mobile.perf_session import (
    default_perf_interval,
    resolve_perf_interval,
)


def test_信封perfKeys间隔优先于环境变量():
    os.environ["EXECUTOR_PERF_SAMPLE_INTERVAL"] = "5"
    env = {"perfKeys": {"appauto.perf-sample-interval": "2"}}
    assert resolve_perf_interval(env) == 2.0
    del os.environ["EXECUTOR_PERF_SAMPLE_INTERVAL"]


def test_信封缺位回退环境变量():
    os.environ["EXECUTOR_PERF_SAMPLE_INTERVAL"] = "3"
    assert resolve_perf_interval({"engine": {}}) == 3.0
    assert resolve_perf_interval(None) == 3.0
    del os.environ["EXECUTOR_PERF_SAMPLE_INTERVAL"]


def test_信封非法值诚实回退非环境变量外的硬编码():
    os.environ["EXECUTOR_PERF_SAMPLE_INTERVAL"] = "5"
    for bad in ("0", "-1", "abc", "", None):
        env = {"perfKeys": {"appauto.perf-sample-interval": bad}}
        assert resolve_perf_interval(env) == 5.0
    del os.environ["EXECUTOR_PERF_SAMPLE_INTERVAL"]


def test_全缺省回退1s():
    os.environ.pop("EXECUTOR_PERF_SAMPLE_INTERVAL", None)
    assert resolve_perf_interval({}) == default_perf_interval() == 1.0
