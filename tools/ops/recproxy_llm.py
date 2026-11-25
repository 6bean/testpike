#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Recording pass-through proxy for the LLM chain used by live verification windows.

The contract-injection windows need proof of what the prompt actually contained, so
every chat completion request is forwarded to the real upstream while its message
array is appended, one JSON object per line, to the hits file the windows tail.

Lifecycle (leak-pairing rule): the process writes a port-scoped pidfile at start,
refuses to double-bind, and removes the pidfile on exit; the hits file lives under
/tmp and is owned by the window that exported its path. Only the request body is
recorded -- headers such as Authorization never touch the log.

Modes:
  serve                 run the proxy until killed (foreground)
  probe                 print PROXY-ALIVE / PROXY-DOWN for the configured port
  --selftest            loopback fixture: fake upstream, real request, ledger check
"""

import argparse
import json
import os
import signal
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT_ENV = "TP_RECORD_PROXY_PORT"
UPSTREAM_ENV = "LLM_BASE_URL"
HITS_ENV = "TP_RECORD_HITS"
# Same fallback literal the verification windows read when the env key is unset;
# keeping both sides on one default is what prevents a silent no-ledger window.
HITS_DEFAULT = "/tmp/testpike-t158-prompts.jsonl"
DEFAULT_PORT = 18091
MAX_RECORD_BYTES = 4 * 1024 * 1024  # oversized payloads record a stub line, never the body


def hits_path():
    """Resolved hits file: the env key when set, otherwise the shared window default."""
    return os.environ.get(HITS_ENV, "").strip() or HITS_DEFAULT


def upstream_base(default=None):
    raw = os.environ.get(UPSTREAM_ENV, "").strip() or default or ""
    return raw.rstrip("/")


def pidfile_path(port):
    return "/tmp/tp-recproxy-%d.pid" % port


def append_record(body_bytes, path):
    """Append one ledger line for a forwarded request; malformed or huge bodies record a stub."""
    out = hits_path()
    if not out:
        return
    try:
        if len(body_bytes) > MAX_RECORD_BYTES:
            line = {"stub": "oversized", "bytes": len(body_bytes), "path": path}
        else:
            parsed = json.loads(body_bytes.decode("utf-8", "replace"))
            line = {"ts": int(__import__("time").time()), "path": path,
                    "messages": parsed.get("messages") if isinstance(parsed, dict) else parsed}
    except (ValueError, UnicodeDecodeError):
        line = {"stub": "non-json", "bytes": len(body_bytes), "path": path}
    with open(out, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(line, ensure_ascii=False) + "\n")


def make_handler(upstream):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):  # noqa: A002  keep the runtime log quiet
            pass

        def _relay(self, body):
            req = urllib.request.Request(upstream + self.path, data=body, method=self.command)
            for key in ("Content-Type", "Authorization", "Accept"):
                val = self.headers.get(key)
                if val:
                    req.add_header(key, val)
            try:
                resp = urllib.request.urlopen(req, timeout=900)
                status, headers, payload = resp.status, resp.headers, resp.read()
            except urllib.error.HTTPError as err:
                status, headers, payload = err.code, err.headers, err.read()
            except (urllib.error.URLError, OSError) as err:
                payload = json.dumps({"error": "upstream-unreachable", "cause": str(err)}).encode()
                headers = {"Content-Type": "application/json"}
                self._respond(502, headers, payload)
                return
            if body:
                append_record(body, self.path)
            self._respond(status, headers, payload)

        def _respond(self, status, headers, payload):
            self.send_response(status)
            ctype = (headers.get("Content-Type") if headers else None) or "application/json"
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            self._relay(self.rfile.read(length) if length else b"")

        def do_GET(self):
            if self.path.rstrip("/") in ("", "/health", "/v1/health"):
                self._respond(200, {"Content-Type": "text/plain"}, b"recproxy-ok")
            else:
                self._relay(b"")

    return Handler


def is_alive(port):
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=3) as resp:
            return b"recproxy-ok" in resp.read()
    except Exception:
        return False


def selftest():
    """Fake upstream on an ephemeral port; assert ledger line + verbatim passthrough."""
    from http.server import BaseHTTPRequestHandler as BH, ThreadingHTTPServer as TH
    hits = "/tmp/tp-recproxy-selftest-%d.jsonl" % os.getpid()
    os.environ[HITS_ENV] = hits

    class FakeUpstream(BH):
        def log_message(self, format, *args):  # noqa: A002  silence stdlib request echo
            pass

        def do_POST(self):
            n = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(n)
            echo = json.dumps({"echo": json.loads(body)["messages"][0]["content"]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(echo)))
            self.end_headers()
            self.wfile.write(echo)

    up = TH(("127.0.0.1", 0), FakeUpstream)
    up_port = up.server_address[1]
    threading.Thread(target=up.serve_forever, daemon=True).start()
    px = TH(("127.0.0.1", 0), make_handler("http://127.0.0.1:%d/v1" % up_port))
    px_port = px.server_address[1]
    threading.Thread(target=px.serve_forever, daemon=True).start()
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:%d/v1/chat/completions" % px_port,
            data=json.dumps({"model": "m", "messages": [{"role": "user", "content": "hello-mark"}]}).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            echoed = json.loads(resp.read())["echo"]
        with open(hits, encoding="utf-8") as fh:
            record = json.loads(fh.readline())
        assert echoed == "hello-mark", "passthrough body corrupted"
        assert record["messages"][0]["content"] == "hello-mark", "ledger missed messages"
        print("RECPROXY-SELFTEST-OK")
        return 0
    finally:
        px.shutdown(); up.shutdown()
        if os.path.exists(hits):
            os.remove(hits)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", nargs="?", default="serve", choices=["serve", "probe"])
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    port = int(os.environ.get(PORT_ENV, str(DEFAULT_PORT)))
    if args.mode == "probe":
        print("PROXY-ALIVE" if is_alive(port) else "PROXY-DOWN")
        return 0
    if is_alive(port):
        print("RECPROXY-ALREADY-RUNNING on :%d" % port)
        return 0
    up = upstream_base()
    if not up:
        print("RECPROXY-STATE-ERROR: %s unset and no override" % UPSTREAM_ENV, file=sys.stderr)
        return 2
    pid = pidfile_path(port)
    open(pid, "w").write(str(os.getpid()))
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(up))

    def _term(signum, frame):  # systemd stop must unwind serve_forever into the finally
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, _term)
    signal.signal(signal.SIGINT, _term)
    print("RECPROXY-LISTENING :%d -> %s hits=%s" % (port, up, hits_path()))
    try:
        server.serve_forever()
    finally:  # paired reclaim: the pidfile never outlives this process
        if os.path.exists(pid):
            os.remove(pid)


if __name__ == "__main__":
    sys.exit(main())
