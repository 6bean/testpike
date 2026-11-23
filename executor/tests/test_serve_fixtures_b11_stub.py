

import json
import os
import socket
import subprocess
import sys
import time
import unittest

import psycopg2
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
EXEC_DIR = os.path.dirname(HERE)
TARGET_DB = os.environ.get("DB_TARGET_DB", "tp_b11_target")
CREDS = {
    "host": "127.0.0.1",
    "port": int(os.environ.get("DB_TARGET_PORT", "15432")),
    "dbname": TARGET_DB,
    "user": os.environ.get("DB_USERNAME", "testpike"),
    "password": os.environ.get("DB_PASSWORD", ""),
}


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _req(method, url, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8"))
        except Exception:
            return e.code, {"_raw": raw[:200].decode("utf-8", "replace")}


class StubFixtureTest(unittest.TestCase):
    pass

    @classmethod
    def setUpClass(cls):
        try:
            probe = psycopg2.connect(**CREDS)
            probe.close()
        except Exception as exc:  # noqa: BLE001
            raise unittest.SkipTest(
                f"目标库 {TARGET_DB} 不可达——先跑 python3 scripts/db_target.py bootstrap 起目标库（夹具环境未就绪，非回归）：{exc}")
        cls.port = _free_port()
        env = {**os.environ, "PORT": str(cls.port)}
        cls.proc = subprocess.Popen(
            [sys.executable, "serve_fixtures.py", "--port", str(cls.port)],
            cwd=EXEC_DIR, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                code, _ = _req("GET", f"http://127.0.0.1:{cls.port}/api/nope")
                if code in (404, 200):
                    return
            except Exception:  # noqa: BLE001
                time.sleep(0.2)
        cls.proc.terminate()
        raise RuntimeError("桩 10s 未就绪")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            cls.proc.kill()

    def setUp(self):
        self.base = f"http://127.0.0.1:{self.port}"
        self.order_no = f"L2TEST-{os.getpid()}-{int(time.time() * 1000) % 100000}"

    def _db_rows(self):
        conn = psycopg2.connect(**CREDS)
        try:
            with conn, conn.cursor() as cur:
                cur.execute("SELECT status FROM t_order WHERE order_no=%s", (self.order_no,))
                return cur.fetchall()
        finally:
            conn.close()

    def test_post_order_writes_target_db_and_readback(self):
        code, payload = _req("POST", f"{self.base}/api/order",
                             {"orderNo": self.order_no, "userId": 1001})
        self.assertEqual(200, code, payload)
        self.assertEqual(0, payload["code"])
        self.assertEqual(self.order_no, payload["data"]["orderNo"])
        self.assertEqual("PENDING", payload["data"]["status"])
        self.assertEqual([("PENDING",)], self._db_rows())
        code2, got = _req("GET", f"{self.base}/api/order/{self.order_no}")
        self.assertEqual(200, code2, got)
        self.assertEqual("PENDING", got["data"]["status"])

    def test_get_unknown_order_404(self):
        code, payload = _req("GET", f"{self.base}/api/order/NO-SUCH-ORDER-XYZ")
        self.assertEqual(404, code)
        self.assertIn("不存在", payload.get("message", ""))

    def test_missing_order_no_400(self):
        code, payload = _req("POST", f"{self.base}/api/order", {"userId": 1})
        self.assertEqual(400, code)
        self.assertIn("orderNo", payload.get("message", ""))

    def test_unknown_api_path_still_404(self):
        code, _ = _req("GET", f"{self.base}/api/forbidden")
        self.assertEqual(404, code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
