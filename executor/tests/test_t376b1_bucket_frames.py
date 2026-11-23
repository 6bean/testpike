
from __future__ import annotations

import json

from perf_worker import HIST_BOUNDARIES, normalize_frames, normalize_gatling_frames


def _point(metric, ts, value, name, status=None, error=None):
    tags = {"name": name}
    if status is not None:
        tags["status"] = str(status)
    if error is not None:
        tags["error"] = error
    return json.dumps({"type": "Point", "metric": metric, "timestamp": ts,
                       "value": value, "tags": tags})


class TestHistogramBoundariesMirror:
    pass

    def test_桶界27档升序且封顶60000(self):
        assert len(HIST_BOUNDARIES) == 27
        assert all(HIST_BOUNDARIES[i] < HIST_BOUNDARIES[i + 1]
                   for i in range(len(HIST_BOUNDARIES) - 1))
        assert HIST_BOUNDARIES[0] == 5.0
        assert HIST_BOUNDARIES[-1] == 60000.0


class TestK6BucketColumns:

    def _frames(self, lines):
        return normalize_frames(lines)

    def test_聚合帧带桶列与直方图(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 2, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.010Z", 30.0, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.020Z", 80.0, "step1"),
        ]
        frames = self._frames(lines)
        assert len(frames) == 1
        fr = frames[0]
        assert fr["reqCount"] == 2.0
        assert fr["durationSumMs"] == 110.0
        assert fr["stepKey"] == ""
        assert fr["failCount"] == 0.0
        hist = fr["hist"]
        assert len(hist) == 27
        assert hist[5] == 1 and hist[9] == 1
        assert sum(hist) == 2
        assert "errorClass" not in fr

    def test_错误分类五键按状态码归桶(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 3, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.010Z", 50.0, "step1"),
            _point("http_req_failed", "2026-10-01T00:00:01.020Z", 1, "step1", status=503),
            _point("http_req_failed", "2026-10-01T00:00:01.030Z", 1, "step1", status=404),
            _point("http_req_failed", "2026-10-01T00:00:01.040Z", 1, "step1"),
        ]
        fr = self._frames(lines)[0]
        assert fr["reqCount"] == 3.0 and fr["failCount"] == 3.0
        ec = fr["errorClass"]
        assert set(ec) == {"timeout", "refused", "http4xx", "http5xx", "biz"}
        assert ec["http5xx"] == 1 and ec["http4xx"] == 1 and ec["biz"] == 1

    def test_连接错误与超时归桶(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 2, "step1"),
            _point("http_req_failed", "2026-10-01T00:00:01.010Z", 1, "step1",
                   error="Golang error: dial tcp: connection refused"),
            _point("http_req_failed", "2026-10-01T00:00:01.020Z", 1, "step1",
                   error="context deadline exceeded"),
        ]
        fr = self._frames(lines)[0]
        ec = fr["errorClass"]
        assert ec["refused"] == 1 and ec["timeout"] == 1

    def test_认证点不入业务桶列与分步帧(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 5, "auth"),
            _point("http_req_duration", "2026-10-01T00:00:01.010Z", 9999.0, "auth"),
            _point("http_reqs", "2026-10-01T00:00:01.020Z", 2, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.030Z", 40.0, "step1"),
        ]
        frames = self._frames(lines)
        agg = [f for f in frames if f["stepKey"] == ""][0]
        assert agg["reqCount"] == 2.0 and agg["durationSumMs"] == 40.0
        assert agg["authCount"] == 5.0
        assert sum(agg["hist"]) == 1

    def test_多步场景追加分步帧(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 2, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.010Z", 30.0, "step1"),
            _point("http_reqs", "2026-10-01T00:00:01.020Z", 3, "step2"),
            _point("http_req_duration", "2026-10-01T00:00:01.030Z", 60.0, "step2"),
            _point("http_req_failed", "2026-10-01T00:00:01.040Z", 1, "step2", status=500),
        ]
        frames = self._frames(lines)
        by_key = {f["stepKey"]: f for f in frames}
        assert set(by_key) == {"", "step1", "step2"}
        agg = by_key[""]
        assert agg["rps"] == 5.0 and agg["reqCount"] == 5.0 and agg["failCount"] == 1.0
        s1 = by_key["step1"]
        assert s1["rps"] == 2.0 and s1["reqCount"] == 2.0 and s1["errorRate"] == 0.0
        s2 = by_key["step2"]
        assert s2["reqCount"] == 3.0 and s2["failCount"] == 1.0
        assert s2["errorClass"]["http5xx"] == 1
        assert s1["secondOffset"] == agg["secondOffset"] == 0

    def test_存量断言零漂移_旧四键不动(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 2, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.010Z", 100.0, "step1"),
        ]
        fr = self._frames(lines)[0]
        assert fr["secondOffset"] == 0 and fr["rps"] == 2.0
        assert fr["p95Ms"] == 100.0 and fr["errorRate"] == 0.0
        assert "ttftMs" not in fr and "itlMs" not in fr


class TestGatlingBucketColumns:

    def test_gatling双请求名_分步帧与桶列(self):
        points = [
            {"name": "step1", "startMs": 1000, "endMs": 1030, "ok": True,
             "message": "", "groups": []},
            {"name": "step2", "startMs": 1100, "endMs": 1250, "ok": False,
             "message": "status is 503", "groups": []},
        ]
        frames = normalize_gatling_frames(points)
        by_key = {f["stepKey"]: f for f in frames}
        assert set(by_key) == {"", "step1", "step2"}
        agg = by_key[""]
        assert agg["reqCount"] == 2.0 and agg["failCount"] == 1.0
        assert agg["durationSumMs"] == 180.0
        assert agg["errorClass"]["http5xx"] == 1
        assert sum(agg["hist"]) == 2
        assert by_key["step1"]["rps"] == 1.0 and by_key["step2"]["rps"] == 1.0

    def test_gatling单请求名_聚合独存零漂移(self):
        points = [{"name": "step1", "startMs": 500, "endMs": 520, "ok": True,
                   "message": "", "groups": []}]
        frames = normalize_gatling_frames(points)
        assert len(frames) == 1
        fr = frames[0]
        assert fr["stepKey"] == "" and fr["reqCount"] == 1.0
        assert fr["durationSumMs"] == 20.0
