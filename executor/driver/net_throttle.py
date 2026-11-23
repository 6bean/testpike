
from __future__ import annotations

import http.client
import json
import logging
import random
import selectors
import socket
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

log = logging.getLogger("testpike-executor")

UPSTREAM_TIMEOUT_SECONDS = 30.0
PACING_CHUNK_BYTES = 4096
_HOP_BY_HOP = frozenset({
    "connection", "proxy-authenticate", "proxy-authorization", "te",
    "trailer", "transfer-encoding", "keep-alive", "proxy-connection",
})


class NetThrottleError(ValueError):
    pass


@dataclass(frozen=True)
class ThrottleSpec:
    pass
    bw_kbps: float
    latency_ms: float
    jitter_ms: float
    loss_pct: float


PROFILE_PRESETS: dict[str, ThrottleSpec] = {
    "2g": ThrottleSpec(bw_kbps=50, latency_ms=700, jitter_ms=250, loss_pct=5.0),
    "3g": ThrottleSpec(bw_kbps=1600, latency_ms=200, jitter_ms=60, loss_pct=1.0),
    "4g-weak": ThrottleSpec(bw_kbps=6000, latency_ms=80, jitter_ms=30,
                            loss_pct=0.5),
    "high-latency": ThrottleSpec(bw_kbps=10_000, latency_ms=1500, jitter_ms=200,
                                 loss_pct=0.0),
}


def _num(value, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise NetThrottleError(
            f"app.networkProfile 的 custom.{field} 必须是数值（kbps/毫秒/百分比），"
            f"当前值「{value!r}」")
    return float(value)


def parse_network_profile_args(args) -> ThrottleSpec:
    pass
    if not isinstance(args, dict) or not args:
        raise NetThrottleError(
            "app.networkProfile 需要 profile（预设档名）或 custom"
            "（{bw,latency,loss} 自定义）二选一参数，当前形如「"
            f"{args!r}」")
    has_profile = "profile" in args
    has_custom = "custom" in args
    if has_profile == has_custom:
        raise NetThrottleError(
            "app.networkProfile 的 profile 与 custom 二选一（不可同时给或都不给），"
            f"当前参数「{args!r}」")
    if has_profile:
        key = str(args["profile"]).strip().lower()
        preset = PROFILE_PRESETS.get(key)
        if preset is None:
            raise NetThrottleError(
                f"app.networkProfile 未知预设档「{args['profile']}」，可选："
                + " / ".join(sorted(PROFILE_PRESETS)) + "（或改用 custom 自定义）")
        return preset
    custom = args["custom"]
    if not isinstance(custom, dict):
        raise NetThrottleError(
            "app.networkProfile 的 custom 必须是对象"
            f"（{{bw,latency,loss}}），当前值「{custom!r}」")
    missing = {"bw", "latency", "loss"} - set(custom)
    if missing:
        raise NetThrottleError(
            "app.networkProfile 的 custom 缺字段 " + "、".join(sorted(missing))
            + "（bw=带宽 kbps / latency=延迟 ms / loss=丢包率 %）")
    bw = _num(custom["bw"], "bw")
    latency = _num(custom["latency"], "latency")
    loss = _num(custom["loss"], "loss")
    jitter = _num(custom.get("jitter", 0), "jitter")
    if bw <= 0:
        raise NetThrottleError(
            f"app.networkProfile 的 custom.bw（带宽 kbps）必须大于 0，当前「{bw}」")
    if latency < 0 or jitter < 0:
        raise NetThrottleError(
            "app.networkProfile 的 custom.latency/jitter（毫秒）不能为负，当前"
            f" latency=「{latency}」 jitter=「{jitter}」")
    if loss < 0 or loss > 100:
        raise NetThrottleError(
            f"app.networkProfile 的 custom.loss（丢包率 %）须在 0~100 之间，当前「{loss}」")
    return ThrottleSpec(bw_kbps=bw, latency_ms=latency, jitter_ms=jitter,
                        loss_pct=loss)


class _EvidenceWriter:
    pass

    def __init__(self, path: str):
        self._path = path
        self._lock = threading.Lock()

    def append(self, row: dict) -> None:
        try:
            line = json.dumps(row, ensure_ascii=False)
            with self._lock:
                with open(self._path, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
        except OSError:
            log.warning("弱网观测行落盘失败（旁路吞掉，不影响转发）：%s",
                        self._path, exc_info=True)


@dataclass
class _ProxyContext:
    pass
    spec: ThrottleSpec
    rng: random.Random
    evidence: _EvidenceWriter


class _ThrottleHandler(BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"
    timeout = UPSTREAM_TIMEOUT_SECONDS

    log_message = lambda *args: None

    def _ctx(self) -> _ProxyContext:
        return self.server.tp_ctx


    def _dropped(self) -> bool:
        spec = self._ctx().spec
        if spec.loss_pct <= 0:
            return False
        return self._ctx().rng.random() * 100.0 < spec.loss_pct

    def _apply_latency(self) -> None:
        spec = self._ctx().spec
        delay = spec.latency_ms
        if spec.jitter_ms > 0:
            delay += self._ctx().rng.uniform(0, spec.jitter_ms)
        if delay > 0:
            time.sleep(delay / 1000.0)

    def _paced_send(self, sock: socket.socket, data: bytes) -> None:
        bps = self._ctx().spec.bw_kbps * 1000.0 / 8.0
        if bps <= 0:
            sock.sendall(data)
            return
        view = memoryview(data)
        for i in range(0, len(data), PACING_CHUNK_BYTES):
            chunk = bytes(view[i:i + PACING_CHUNK_BYTES])
            sock.sendall(chunk)
            time.sleep(len(chunk) / bps)


    def _row(self, kind: str, target: str, method: str, ts: float,
             dur_ms: float, status, total_bytes: int, timeout: bool) -> None:
        self._ctx().evidence.append({
            "ts": ts, "durMs": round(dur_ms, 3), "timeout": timeout,
            "retries": 0, "status": status, "bytes": total_bytes,
            "kind": kind, "host": target, "method": method,
        })


    def _forward(self) -> None:
        ts = time.time()
        t0 = time.monotonic()
        parts = urlsplit(self.path)
        host = parts.hostname or ""
        port = parts.port or 80
        target = f"{host}:{port}"
        if self._dropped():
            self._row("http", target, self.command, ts,
                      (time.monotonic() - t0) * 1000.0, "dropped", 0, False)
            self.close_connection = True
            return
        self._apply_latency()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        try:
            conn = http.client.HTTPConnection(host, port,
                                              timeout=UPSTREAM_TIMEOUT_SECONDS)
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in _HOP_BY_HOP}
            conn.request(self.command, parts.path or "/", body=body,
                         headers=headers)
            resp = conn.getresponse()
            self.send_response(resp.status, resp.reason or None)
            for k, v in resp.getheaders():
                if k.lower() not in _HOP_BY_HOP:
                    self.send_header(k, v)
            self.end_headers()
            self.wfile.flush()
            sent = 0
            while True:
                chunk = resp.read(PACING_CHUNK_BYTES)
                if not chunk:
                    break
                self._paced_send(self.connection, chunk)
                sent += len(chunk)
            self._row("http", target, self.command, ts,
                      (time.monotonic() - t0) * 1000.0, resp.status, sent,
                      False)
            conn.close()
        except socket.timeout:
            self._row("http", target, self.command, ts,
                      (time.monotonic() - t0) * 1000.0, "timeout", 0, True)
        except (OSError, http.client.HTTPException) as exc:
            self._row("http", target, self.command, ts,
                      (time.monotonic() - t0) * 1000.0,
                      f"error:{type(exc).__name__}", 0, False)
        self.close_connection = True

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = do_OPTIONS = do_PATCH = \
        _forward


    def do_CONNECT(self) -> None:  # noqa: N802
        ts = time.time()
        t0 = time.monotonic()
        host, _, port_s = self.path.rpartition(":")
        target = f"{host}:{port_s}"
        if self._dropped():
            self._row("connect", target, "CONNECT", ts,
                      (time.monotonic() - t0) * 1000.0, "dropped", 0, False)
            self.close_connection = True
            return
        self._apply_latency()
        try:
            upstream = socket.create_connection(
                (host, int(port_s)), timeout=UPSTREAM_TIMEOUT_SECONDS)
        except OSError:
            self._row("connect", target, "CONNECT", ts,
                      (time.monotonic() - t0) * 1000.0, "error:upstream", 0,
                      False)
            self.close_connection = True
            return
        self.wfile.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
        self.wfile.flush()
        client = self.connection
        residual = self.rfile.peek(65536) \
            if hasattr(self.rfile, "peek") else b""
        if residual:
            self.rfile.read(len(residual))
            upstream.sendall(residual)
        client.settimeout(UPSTREAM_TIMEOUT_SECONDS)
        upstream.settimeout(UPSTREAM_TIMEOUT_SECONDS)
        sel = selectors.DefaultSelector()
        sel.register(client, selectors.EVENT_READ, "client")
        sel.register(upstream, selectors.EVENT_READ, "upstream")
        open_ends = {"client": True, "upstream": True}
        counter = {"down": 0}
        timed_out = False
        while open_ends["client"] or open_ends["upstream"]:
            try:
                events = sel.select(timeout=UPSTREAM_TIMEOUT_SECONDS)
            except OSError:
                break
            if not events:
                timed_out = True
                break
            for key, _events in events:
                which = key.data
                if not open_ends[which]:
                    continue
                src = key.fileobj
                dst = upstream if which == "client" else client
                try:
                    data = src.recv(65536)
                except (OSError, socket.timeout):
                    data = b""
                if not data:
                    open_ends[which] = False
                    try:
                        sel.unregister(src)
                    except (KeyError, ValueError):
                        pass
                    try:
                        dst.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    continue
                try:
                    if which == "upstream":
                        self._paced_send(client, data)
                        counter["down"] += len(data)
                    else:
                        dst.sendall(data)
                except OSError:
                    open_ends["client"] = False
                    open_ends["upstream"] = False
                    break
        sel.close()
        try:
            upstream.close()
        except OSError:
            pass
        self._row("connect", target, "CONNECT", ts,
                  (time.monotonic() - t0) * 1000.0,
                  "timeout" if timed_out else "closed",
                  counter["down"], timed_out)
        self.close_connection = True


class ThrottleProxy:
    pass

    def __init__(self, spec: ThrottleSpec, evidence_path: str, seed=None,
                 listen_host: str = "127.0.0.1"):
        self._spec = spec
        self._evidence = _EvidenceWriter(evidence_path)
        self._rng = random.Random(seed)
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self._host = listen_host
        self._port = 0
        self._stopped = False

    @property
    def port(self) -> int:
        return self._port

    @property
    def proxy_address(self) -> str:
        pass
        return f"{self._host}:{self._port}"

    def start(self) -> tuple[str, int]:
        pass
        self._server = ThreadingHTTPServer((self._host, 0), _ThrottleHandler)
        self._server.tp_ctx = _ProxyContext(spec=self._spec, rng=self._rng,
                                            evidence=self._evidence)
        self._port = self._server.server_address[1]
        self._thread = threading.Thread(
            target=self._server.serve_forever, daemon=True,
            name="tp-net-throttle")
        self._thread.start()
        return self._host, self._port

    def stop(self) -> None:
        pass
        if self._stopped:
            return
        self._stopped = True
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
