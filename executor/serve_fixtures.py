
import base64
import functools
import hashlib
import hmac
import http.server
import json
import os
import sys
import threading
import time

# Remappable via env (parallel slots and installs must not collide on the
# shipped default): FIXTURE_PORT wins, legacy PORT still honored.
PORT = int(os.environ.get("FIXTURE_PORT", os.environ.get("PORT", "8899")))
BASE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")

# ---- AV login-state fixture : captcha page + short-lived JWT + cookies
# + device-binding switch, so L4 expiry-circuit-breaker and non-replayable
# states run against a self-built target instead of mocks (see authoritative
# analysis doc section 6). Fixed captcha = shape simulation only; no captcha
# recognition lives anywhere in the product (security red line 1).
CAPTCHA_FIXED = "7K3Q"
JWT_SECRET = os.environ.get("FIXTURE_LOGIN_JWT_SECRET", "fixture-insecure-secret")
JWT_TTL_DEFAULT_S = 60
SESSION_COOKIE = "tp_fixture_session"
# R57: server keeps no per-session storage — tokens are stateless HMAC JWTs
# and the only mutable state is the binding flag below, so nothing accumulates.
_BINDING_LOCK = threading.Lock()
_DEVICE_BOUND = False


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _issue_jwt(subject: str, ttl_s: int):
    now = int(time.time())
    header = _b64url(json.dumps({"alg": "HS256", "typ": "JWT"},
                                separators=(",", ":")).encode())
    payload = _b64url(json.dumps({"sub": subject, "iat": now, "exp": now + ttl_s},
                                 separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode()
    sig = _b64url(hmac.new(JWT_SECRET.encode(), signing_input, hashlib.sha256).digest())
    return f"{signing_input.decode()}.{sig}", now + ttl_s


def _verify_jwt(token: str):
    """Return (ok, reason, claims). reason=None when ok; expired and malformed
    are told apart so the breaker paths can assert on the honest wording."""
    try:
        header_b64, payload_b64, sig_b64 = token.split(".")
        signing_input = f"{header_b64}.{payload_b64}".encode()
        expect = hmac.new(JWT_SECRET.encode(), signing_input, hashlib.sha256).digest()
        given = base64.urlsafe_b64decode(sig_b64 + "=" * (-len(sig_b64) % 4))
        if not hmac.compare_digest(expect, given):
            return False, "bad-signature", None
        claims = json.loads(base64.urlsafe_b64decode(payload_b64 + "=" * (-len(payload_b64) % 4)))
        if int(claims.get("exp", 0)) <= int(time.time()):
            return False, "expired", claims
        return True, None, claims
    except Exception:  # noqa: BLE001 — malformed token is simply unauthenticated
        return False, "malformed", None

DB_TARGET = {
    "host": os.environ.get("DB_TARGET_HOST", "127.0.0.1"),
    "port": int(os.environ.get("DB_TARGET_PORT", "15432")),
    "dbname": os.environ.get("DB_TARGET_DB", "tp_b11_target"),
    "user": os.environ.get("DB_TARGET_USER", os.environ.get("DB_USERNAME", "testpike")),
    "password": os.environ.get("DB_TARGET_PASSWORD", os.environ.get("DB_PASSWORD", "")),
}


def _db_connect():
    import psycopg2
    return psycopg2.connect(**DB_TARGET)


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=BASE_DIR, **kwargs)


    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self) -> bytes:
        pass
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0] or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
            return data
        length = int(self.headers.get("Content-Length", 0) or 0)
        return self.rfile.read(length) if length else b"{}"

    def do_POST(self):
        if self.path == "/api/login":
            self._read_body()
            return self._json(200, {"code": 0, "data": {"login": "ok"}})
        if self.path == "/api/captcha-login":
            return self._captcha_login()
        if self.path == "/api/device-binding":
            return self._set_device_binding()
        if self.path == "/api/order":
            return self._create_order()
        if self.path.startswith("/api/"):
            return self._json(404, {"code": 404, "message": "未知接口"})
        return super().do_POST()

    # ---- AV login-state fixture routes ----

    def _captcha_login(self):
        """Validate the fixed captcha server-side, then answer a short-lived
        JWT and mirror it into a login cookie (both shapes the capture chain
        consumes). Wrong captcha = honest 401; nothing is persisted either way."""
        try:
            req = json.loads(self._read_body().decode("utf-8"))
        except Exception:  # noqa: BLE001 — keep the fixture diagnosable, no 500 trace
            return self._json(400, {"code": 400, "message": "bad request body"})
        if not isinstance(req, dict):
            return self._json(400, {"code": 400, "message": "request body should be a JSON object"})
        username = str(req.get("username") or "").strip()
        if not username:
            return self._json(400, {"code": 400, "message": "username required"})
        if str(req.get("captcha") or "").strip() != CAPTCHA_FIXED:
            return self._json(401, {"code": 401, "message": "captcha mismatch"})
        ttl = req.get("expiresIn")
        try:
            ttl = int(ttl) if ttl is not None else JWT_TTL_DEFAULT_S
        except (TypeError, ValueError):
            return self._json(400, {"code": 400, "message": "expiresIn should be an integer"})
        token, exp = _issue_jwt(username, ttl)
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Set-Cookie", f"{SESSION_COOKIE}={token}; Path=/; HttpOnly; SameSite=Lax")
        body = json.dumps({"code": 0, "data": {"token": token, "tokenType": "Bearer",
                                               "expiresIn": ttl, "expiresAt": exp}},
                          ensure_ascii=False).encode("utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _set_device_binding(self):
        """Toggle the replay-rejection mode; the switch itself is unauthenticated
        so L4 can arm it before login (fixture control plane, not a target API)."""
        global _DEVICE_BOUND
        try:
            req = json.loads(self._read_body().decode("utf-8"))
            flag = bool(req.get("on"))
        except Exception:  # noqa: BLE001
            return self._json(400, {"code": 400, "message": "expected {\"on\": true|false}"})
        with _BINDING_LOCK:
            _DEVICE_BOUND = flag
            current = flag
        return self._json(200, {"code": 0, "data": {"on": current}})

    def _protected(self):
        """Bearer token or login cookie, same acceptance rules; with device
        binding on, a valid token replayed without X-Client-Fingerprint is
        rejected (the non-replayable shape the capture chain must report honestly)."""
        token = None
        auth = self.headers.get("Authorization") or ""
        if auth.startswith("Bearer "):
            token = auth[len("Bearer "):].strip()
        if token is None:
            for part in (self.headers.get("Cookie") or "").split(";"):
                name, _, value = part.strip().partition("=")
                if name == SESSION_COOKIE:
                    token = value
        if not token:
            return self._json(401, {"code": 401, "message": "login required"})
        ok, reason, claims = _verify_jwt(token)
        if not ok:
            detail = "token expired" if reason == "expired" else "token not valid"
            return self._json(401, {"code": 401, "message": detail,
                                    "reason": reason or "malformed"})
        with _BINDING_LOCK:
            bound = _DEVICE_BOUND
        if bound and not (self.headers.get("X-Client-Fingerprint") or "").strip():
            return self._json(403, {"code": 403,
                                    "message": "device-bound session: X-Client-Fingerprint required"})
        return self._json(200, {"code": 0, "data": {"account": (claims or {}).get("sub"),
                                                    "scope": "vip",
                                                    "protected": "fixture-protected-data"}})

    def _create_order(self):
        try:
            req = json.loads(self._read_body().decode("utf-8"))
        except Exception:
            return self._json(400, {"code": 400, "message": "请求体不是合法 JSON"})
        if not isinstance(req, dict):
            return self._json(400, {"code": 400, "message": "请求体应为 JSON 对象"})
        order_no = str(req.get("orderNo") or "").strip()
        if not order_no:
            return self._json(400, {"code": 400, "message": "缺少 orderNo"})
        try:
            conn = _db_connect()
        except Exception as exc:
            return self._json(503, {"code": 503,
                                    "message": f"目标库不可达（先跑 python3 scripts/db_target.py bootstrap 起目标库）：{exc}"})
        try:
            with conn, conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO t_order(order_no,user_id,status) VALUES (%s,%s,'PENDING') RETURNING id",
                    (order_no, int(req.get("userId") or 0)))
                new_id = cur.fetchone()[0]
        except Exception as exc:  # noqa: BLE001
            return self._json(500, {"code": 500, "message": f"落库失败：{exc}"})
        finally:
            conn.close()
        return self._json(200, {"code": 0, "data": {"id": new_id, "orderNo": order_no,
                                                    "status": "PENDING"}})

    def do_GET(self):
        if self.path.startswith("/api/order/"):
            return self._get_order(self.path[len("/api/order/"):])
        if self.path == "/api/protected":
            return self._protected()
        if self.path == "/api/device-binding":
            with _BINDING_LOCK:
                return self._json(200, {"code": 0, "data": {"on": _DEVICE_BOUND}})
        if self.path.startswith("/api/"):
            return self._json(404, {"code": 404, "message": "未知接口"})
        if self.path in ("/login-captcha", "/login-captcha.html"):
            self.path = "/captcha-login.html"
        if self.path in ("/login", "/"):
            self.path = "/login.html"
        return super().do_GET()

    def _get_order(self, order_no):
        try:
            conn = _db_connect()
        except Exception as exc:  # noqa: BLE001
            return self._json(503, {"code": 503, "message": f"目标库不可达：{exc}"})
        try:
            with conn, conn.cursor() as cur:
                cur.execute("SELECT id,status FROM t_order WHERE order_no=%s", (order_no,))
                row = cur.fetchone()
        finally:
            conn.close()
        if row is None:
            return self._json(404, {"code": 404, "message": f"订单不存在：{order_no}"})
        return self._json(200, {"code": 0, "data": {"id": row[0], "orderNo": order_no,
                                                    "status": row[1]}})

    def log_message(self, fmt, *args):
        sys.stderr.write(f"[fixtures] {fmt % args}\n")


if __name__ == "__main__":
    port = PORT
    argv = sys.argv[1:]
    if "--port" in argv:
        port = int(argv[argv.index("--port") + 1])
    elif os.environ.get("PORT"):
        port = int(os.environ["PORT"])
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), functools.partial(Handler))
    print(f"fixtures server on :{port} dir={BASE_DIR}", flush=True)
    server.serve_forever()
