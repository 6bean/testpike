"""Same-origin stack forwarder for transport-death E2E spectra (stdlib only).

Why this exists (measured verdict): the vite dev proxy keeps the
DOWNSTREAM browser socket hanging when its upstream JVM is SIGKILLed --
a direct curl to admin died in 0.04s (incomplete chunked read) while the
same request through vite 5173 hung the full 300s read timeout. A live
SSE stream torn at the server therefore never reaches the browser as a
connection loss, and the resume-door spectrum can only assert against a
transport that propagates upstream EOF/reset the way a production
reverse proxy does. This forwarder restores exactly that semantic:

 path prefix /api -> admin (TP_ADMIN_PORT, default 8081)
 path prefix /open -> api svc (TP_API_PORT, default 8082)
 everything else -> vite dev server (5173), WebSocket upgrades
 (HMR) piped through untouched.

One transaction per connection on the routed path: `Connection: close`
is enforced toward the upstream so per-connection first-request routing
stays exact even though the browser would happily reuse keep-alive
sockets, and the downstream socket is closed the instant the upstream
EOF/reset arrives -- that close is the signal the browser's fetch
streaming reader needs to reject with a network error.

Test-side helper: instantiate with start, point the E2E page at
`base_url` (same-host/same-port keeps the SPA origin coherent for
localStorage token injection).
"""
import os
import socket
import threading

_BUF = 65536
_MAX_HEAD = 256 * 1024
_CONNECT_TIMEOUT = 30.0


def _force_close_head(head: bytes) -> bytes:
    """Rewrite a request head to carry exactly one Connection: close."""
    lines = head.split(b"\r\n")
    kept = [lines[0]]
    for line in lines[1:]:
        if line.lower().startswith(b"connection:"):
            continue
        kept.append(line)
    kept.append(b"Connection: close")
    return b"\r\n".join(kept)


def _pump(src: socket.socket, dst: socket.socket, stop: threading.Event):
    """Copy bytes src -> dst until EOF/error or the sibling direction stopped.

    Returns True when the source reached its natural EOF (or error), False
    when the shared stop event ended the copy early.
    """
    try:
        while not stop.is_set():
            data = src.recv(_BUF)
            if not data:
                return True
            dst.sendall(data)
    except OSError:
        return True
    return False


class OriginForwarder:
    """Threaded path-routing forwarder; each connection carries one HTTP
    transaction (or one piped WebSocket upgrade)."""

    def __init__(self, host: str = "127.0.0.1", port: int = 0):
        admin_port = int(os.environ.get("TP_ADMIN_PORT", "8081"))
        api_port = int(os.environ.get("TP_API_PORT", "8082"))
        vite_port = int(os.environ.get("TP_VITE_PORT", "5173"))
        # (prefix, target) in order; the EMPTY prefix last is the default.
        self.routes = [
            ("/api", ("127.0.0.1", admin_port)),
            ("/open", ("127.0.0.1", api_port)),
            ("", ("127.0.0.1", vite_port)),
        ]
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind((host, port))
        self._server.listen(128)
        self.port = self._server.getsockname()[1]
        # Browser-facing origin: `localhost` matches the default E2E BASE_URL
        # style so both legs of inject/goto see ONE localStorage origin.
        self.base_url = f"http://localhost:{self.port}"
        self._running = True
        self._acceptor = threading.Thread(target=self._accept_loop, daemon=True)

    def start(self) -> str:
        self._acceptor.start()
        return self.base_url

    def stop(self):
        self._running = False
        try:
            self._server.close()
        except OSError:
            pass

    def _route(self, path: str):
        for prefix, target in self.routes:
            if prefix and path.startswith(prefix):
                return target
        return self.routes[-1][1]

    def _accept_loop(self):
        while self._running:
            try:
                client, _ = self._server.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(client,),
                             daemon=True).start()

    def _handle(self, client: socket.socket):
        try:
            head, upgrade, upstream, rest = self._read_head_and_connect(client)
        except OSError:
            try:
                client.close()
            except OSError:
                pass
            return
        if head is None or upstream is None:
            try:
                client.close()
            except OSError:
                pass
            return
        try:
            upstream.sendall(
                (_force_close_head(head) if not upgrade else head) + b"\r\n\r\n")
            if rest:
                upstream.sendall(rest)
            stop = threading.Event()

            def pump_upstream_down():
                # Upstream EOF/reset reached: stop reading and TEAR the
                # downstream socket now — this close (FIN after the flushed
                # partial bytes) is the signal a browser fetch reader needs
                # to reject with a network error mid chunked-stream.
                _pump(upstream, client, stop)
                stop.set()
                try:
                    client.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            back = threading.Thread(target=pump_upstream_down, daemon=True)
            back.start()
            # Symmetric: browser going away (nav/abort) tears the upstream.
            _pump(client, upstream, stop)
            stop.set()
            try:
                upstream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            back.join(timeout=5)
        except OSError:
            pass
        finally:
            for sock in (upstream, client):
                try:
                    sock.close()
                except OSError:
                    pass

    def _read_head_and_connect(self, client: socket.socket):
        """Buffer until the head block ends, route it, open the upstream.

        Returns (head, is_upgrade, upstream_socket, leftover_body_bytes);
        (None, False, None, b"") when the client sent no usable request.
        """
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = client.recv(_BUF)
            if not chunk:
                return None, False, None, b""
            head += chunk
            if len(head) > _MAX_HEAD:
                raise OSError("request head exceeds the forwarder cap")
        head_bytes, rest = head.split(b"\r\n\r\n", 1)
        lines = head_bytes.split(b"\r\n")
        try:
            _method, raw_target, _version = lines[0].split(b" ", 2)
        except ValueError:
            return None, False, None, b""
        path = raw_target.split(b"?", 1)[0].decode("latin-1")
        upgrade = any(l.lower().startswith(b"upgrade:") for l in lines[1:])
        target = self._route(path)
        upstream = socket.create_connection(target, timeout=_CONNECT_TIMEOUT)
        # Bound the CONNECT phase only. create_connection keeps its timeout
        # applied to every later recv, which would raise socket.timeout after
        # 30s of idle traffic and the pump would tear the downstream as if
        # the upstream had EOF'd. Long-lived piped connections are idle by
        # design (HMR websockets between heartbeats, quiet SSE thinking
        # gaps): a measured 30s-idle teardown drove the vite dev client into
        # its "server connection lost -> polling -> reload" loop and made
        # the tear spectra race a spurious document reload every ~30s. A
        # blocking socket ends the copy on the real EOF/reset that this
        # forwarder exists to propagate.
        upstream.settimeout(None)
        return head_bytes, upgrade, upstream, rest
