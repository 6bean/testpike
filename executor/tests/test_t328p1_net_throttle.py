
from __future__ import annotations

import http.client
import json
import pathlib
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

HERE = pathlib.Path(__file__).resolve().parent
EXECUTOR_ROOT = HERE.parent
sys.path.insert(0, str(EXECUTOR_ROOT))

from driver import net_throttle



class _OriginHandler(BaseHTTPRequestHandler):
    BODY_SIZE = 20_000
    hits = 0

    def log_message(self, *args):
        pass

    def do_GET(self):  # noqa: N802
        _OriginHandler.hits += 1
        body = b"x" * _OriginHandler.BODY_SIZE
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802
        _OriginHandler.hits += 1
        length = int(self.headers.get("Content-Length") or 0)
        echoed = self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Length", str(len(echoed)))
        self.end_headers()
        self.wfile.write(echoed)


@pytest.fixture()
def origin():
    _OriginHandler.hits = 0
    server = ThreadingHTTPServer(("127.0.0.1", 0), _OriginHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()
    server.server_close()


@pytest.fixture()
def proxy_factory(tmp_path):
    started: list[net_throttle.ThrottleProxy] = []

    def _start(spec, evidence_name="net.jsonl", seed=None):
        proxy = net_throttle.ThrottleProxy(
            spec, evidence_path=str(tmp_path / evidence_name), seed=seed)
        proxy.start()
        started.append(proxy)
        return proxy

    yield _start
    for p in started:
        p.stop()


def _wait_rows(path: pathlib.Path, n: int, timeout: float = 5.0) -> list[dict]:
    pass
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            rows = _read_rows(path)
            if len(rows) >= n:
                return rows
        time.sleep(0.05)
    raise AssertionError(f"观测行不足：期望 {n} 行，文件={path}")


def _read_rows(path: pathlib.Path) -> list[dict]:
    rows = []
    text = path.read_text(encoding="utf-8")
    for line in text.splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def _http_get(proxy, origin_url: str, path: str = "/data"):
    conn = http.client.HTTPConnection("127.0.0.1", proxy.port, timeout=10)
    conn.request("GET", f"{origin_url}{path}")
    resp = conn.getresponse()
    body = resp.read()
    conn.close()
    return resp.status, body



def test_parse_named_profile_maps_to_full_shaping_params():
    spec = net_throttle.parse_network_profile_args({"profile": "3G"})
    assert spec.bw_kbps > 0 and spec.latency_ms > 0
    assert 0 <= spec.loss_pct <= 100
    presets = net_throttle.PROFILE_PRESETS
    assert spec == presets["3g"]
    assert set(presets) == {"2g", "3g", "4g-weak", "high-latency"}
    assert presets["2g"].bw_kbps < presets["3g"].bw_kbps < presets["4g-weak"].bw_kbps
    assert presets["high-latency"].latency_ms >= presets["4g-weak"].latency_ms


def test_parse_custom_args_carries_bw_latency_loss():
    spec = net_throttle.parse_network_profile_args(
        {"custom": {"bw": 900, "latency": 120, "loss": 2.5}})
    assert spec.bw_kbps == 900
    assert spec.latency_ms == 120
    assert spec.loss_pct == 2.5
    assert spec.jitter_ms == 0


@pytest.mark.parametrize("bad", [
    {},
    {"profile": "5G"},
    {"profile": "3G", "custom": {"bw": 1, "latency": 1, "loss": 0}},
    {"custom": {"bw": 100, "latency": -5, "loss": 0}},
    {"custom": {"bw": 0, "latency": 10, "loss": 0}},
    {"custom": {"bw": 100, "latency": 10, "loss": 101}},
    {"custom": {"bw": "abc", "latency": 10, "loss": 0}},
    "3G",
])
def test_parse_rejects_bad_shape_with_human_message(bad):
    with pytest.raises(net_throttle.NetThrottleError) as err:
        net_throttle.parse_network_profile_args(bad)
    msg = str(err.value)
    assert msg and "networkProfile" in msg



def test_plain_proxy_forwards_body_unchanged_and_writes_evidence(
        proxy_factory, origin, tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=0, jitter_ms=0, loss_pct=0)
    proxy = proxy_factory(spec, "plain.jsonl")
    status, body = _http_get(proxy, origin)
    assert status == 200
    assert len(body) == _OriginHandler.BODY_SIZE
    rows = _wait_rows(tmp_path / "plain.jsonl", 1)
    assert len(rows) == 1
    row = rows[0]
    assert isinstance(row["ts"], float) and row["ts"] > 0
    assert row["durMs"] >= 0
    assert row["timeout"] is False
    assert row["retries"] == 0
    assert row["status"] == 200
    assert row["bytes"] == _OriginHandler.BODY_SIZE
    assert row["kind"] == "http"
    assert "127.0.0.1" in row["host"]


def test_post_body_streams_through_proxy(proxy_factory, origin, tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=0, jitter_ms=0, loss_pct=0)
    proxy = proxy_factory(spec, "post.jsonl")
    payload = b"hello-throttle" * 100
    conn = http.client.HTTPConnection("127.0.0.1", proxy.port, timeout=10)
    conn.request("POST", f"{origin}/echo", body=payload)
    resp = conn.getresponse()
    echoed = resp.read()
    conn.close()
    assert resp.status == 200
    assert echoed == payload



def test_latency_setting_delays_real_request(proxy_factory, origin, tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=150, jitter_ms=0, loss_pct=0)
    proxy = proxy_factory(spec, "delay.jsonl")
    t0 = time.monotonic()
    status, body = _http_get(proxy, origin)
    wall = time.monotonic() - t0
    assert status == 200 and len(body) == _OriginHandler.BODY_SIZE
    assert wall >= 0.15, f"延迟档未生效：wall={wall:.3f}s"
    row = _wait_rows(tmp_path / "delay.jsonl", 1)[0]
    assert row["durMs"] >= 150



def test_bandwidth_setting_paces_download_bytes(proxy_factory, origin, tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=800, latency_ms=100, jitter_ms=0, loss_pct=0)
    proxy = proxy_factory(spec, "bw.jsonl")
    status, body = _http_get(proxy, origin)
    assert status == 200
    row = _wait_rows(tmp_path / "bw.jsonl", 1)[0]
    assert row["durMs"] >= 240, f"带宽整形未生效：durMs={row['durMs']}"



def test_loss_100_drops_request_without_touching_origin(
        proxy_factory, origin, tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=0, jitter_ms=0, loss_pct=100)
    proxy = proxy_factory(spec, "loss.jsonl", seed=42)
    with pytest.raises((http.client.HTTPException, ConnectionError)):
        _http_get(proxy, origin)
    assert _OriginHandler.hits == 0
    row = _wait_rows(tmp_path / "loss.jsonl", 1)[0]
    assert row["status"] == "dropped"
    assert row["timeout"] is False



def test_connect_tunnel_streams_bytes_without_mitm(proxy_factory, origin,
                                                   tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=20, jitter_ms=0, loss_pct=0)
    proxy = proxy_factory(spec, "tunnel.jsonl")
    hostport = origin.split("//", 1)[1]
    s = socket.create_connection(("127.0.0.1", proxy.port), timeout=10)
    s.sendall(f"CONNECT {hostport} HTTP/1.1\r\nHost: {hostport}\r\n\r\n".encode())
    head = s.recv(4096)
    assert b"200" in head
    s.sendall(f"GET /tunnel HTTP/1.1\r\nHost: {hostport}\r\n\r\n".encode())
    chunks = []
    while True:
        data = s.recv(65536)
        if not data:
            break
        chunks.append(data)
    s.close()
    stream = b"".join(chunks)
    assert b"200 OK" in stream.split(b"\r\n\r\n", 1)[0]
    assert stream.count(b"x") == _OriginHandler.BODY_SIZE
    rows = _wait_rows(tmp_path / "tunnel.jsonl", 1)
    assert len(rows) == 1
    row = rows[0]
    assert row["kind"] == "connect"
    assert row["bytes"] >= _OriginHandler.BODY_SIZE
    assert row["durMs"] >= 20



def test_start_stop_lifecycle_and_distinct_ports(tmp_path, origin):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=0, jitter_ms=0, loss_pct=0)
    p1 = net_throttle.ThrottleProxy(spec, evidence_path=str(tmp_path / "p1.jsonl"))
    p2 = net_throttle.ThrottleProxy(spec, evidence_path=str(tmp_path / "p2.jsonl"))
    host1, port1 = p1.start()
    host2, port2 = p2.start()
    assert host1 == "127.0.0.1"
    assert port1 != port2 and port1 > 0 and port2 > 0
    status, _ = _http_get(p1, origin)
    assert status == 200
    p1.stop()
    p1.stop()
    with pytest.raises(OSError):
        socket.create_connection(("127.0.0.1", port1), timeout=2)
    status2, _ = _http_get(p2, origin)
    assert status2 == 200
    p2.stop()



def test_evidence_appends_one_row_per_request(proxy_factory, origin, tmp_path):
    spec = net_throttle.ThrottleSpec(
        bw_kbps=10_000_000, latency_ms=0, jitter_ms=0, loss_pct=0)
    proxy = proxy_factory(spec, "multi.jsonl")
    for _ in range(3):
        _http_get(proxy, origin)
    rows = _wait_rows(tmp_path / "multi.jsonl", 3)
    assert len(rows) == 3
    assert all(r["status"] == 200 for r in rows)
    ts = [r["ts"] for r in rows]
    assert ts == sorted(ts)
