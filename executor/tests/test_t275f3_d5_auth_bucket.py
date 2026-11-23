
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from perf_worker import normalize_frames


def _point(metric, ts, value, name):
    import json
    return json.dumps({"type": "Point", "metric": metric, "timestamp": ts,
                       "value": value, "tags": {"name": name}})


class TestD5认证分桶归帧:

    def test_认证点移出业务三线并单列分桶(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 5, "auth"),
            _point("http_req_duration", "2026-10-01T00:00:01.050Z", 300.0, "auth"),
            _point("http_reqs", "2026-10-01T00:00:01.100Z", 10, "step2"),
            _point("http_req_duration", "2026-10-01T00:00:01.150Z", 100.0, "step2"),
            _point("http_req_failed", "2026-10-01T00:00:01.200Z", 1, "step2"),
        ]
        frames = normalize_frames(lines)
        assert len(frames) == 1
        fr = frames[0]
        assert fr["rps"] == 10.0
        assert fr["p95Ms"] == 100.0
        assert fr["errorRate"] == 0.1
        assert fr["authMs"] == 300.0
        assert fr["authCount"] == 5

    def test_无认证标签场景_键不出禁零顶位(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 10, "step1"),
            _point("http_req_duration", "2026-10-01T00:00:01.100Z", 80.0, "step1"),
        ]
        frames = normalize_frames(lines)
        assert len(frames) == 1
        assert "authMs" not in frames[0]
        assert "authCount" not in frames[0]

    def test_认证风暴秒_业务错误率不被顶穿(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 100, "auth"),
            _point("http_req_failed", "2026-10-01T00:00:01.050Z", 50, "auth"),
            _point("http_req_duration", "2026-10-01T00:00:01.100Z", 900.0, "auth"),
            _point("http_reqs", "2026-10-01T00:00:01.200Z", 10, "step2"),
            _point("http_req_duration", "2026-10-01T00:00:01.300Z", 50.0, "step2"),
        ]
        frames = normalize_frames(lines)
        fr = frames[0]
        assert fr["rps"] == 10.0
        assert fr["p95Ms"] == 50.0
        assert fr["errorRate"] == 0.0
        assert fr["authCount"] == 100
        assert fr["authMs"] == 900.0

    def test_认证秒仅认证请求_业务三线帧仍出且业务键诚实(self):
        lines = [
            _point("http_reqs", "2026-10-01T00:00:01.000Z", 8, "auth"),
            _point("http_req_duration", "2026-10-01T00:00:01.100Z", 120.0, "auth"),
            _point("http_req_duration", "2026-10-01T00:00:01.200Z", 220.0, "auth"),
        ]
        frames = normalize_frames(lines)
        fr = frames[0]
        assert fr["rps"] == 0.0
        assert fr["errorRate"] == 0.0
        assert fr["authCount"] == 8
        assert fr["authMs"] == 170.0
