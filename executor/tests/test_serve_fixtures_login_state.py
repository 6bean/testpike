"""L2 self-check for the AV login-state fixture routes .

Authoritative = analysis doc section 6: the 8899 station must really serve a
captcha login page, answer short-lived JWTs with a mirrored login cookie and
expose a device-binding switch that makes token replay fail without
X-Client-Fingerprint. The L4 expiry-breaker and non-replayable spectra assert
against these shapes, so the fixture itself is verified zero-mock here: real
subprocess server on a throwaway port, real HTTP, real wall-clock expiry.

Run: cd executor && python3 -m unittest tests.test_serve_fixtures_login_state -v
"""

import json
import os
import socket
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
EXEC_DIR = os.path.dirname(HERE)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LoginStateFixtureStubTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = _free_port()
        cls.proc = subprocess.Popen(
            [sys.executable, os.path.join(EXEC_DIR, "serve_fixtures.py"),
             "--port", str(cls.port)],
            cwd=EXEC_DIR, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", cls.port), timeout=0.5):
                    return
            except OSError:
                time.sleep(0.1)
        cls.proc.kill()
        raise AssertionError("fixture server did not come up within 10s")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:  # noqa: PERF201 - bounded teardown (R46)
            cls.proc.kill()

    def _base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def _post_json(self, path, payload, headers=None):
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(self._base() + path, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read()), dict(resp.headers)
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read() or b"{}"), dict(exc.headers)

    def _get(self, path, headers=None):
        req = urllib.request.Request(self._base() + path)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                raw = resp.read()
                try:
                    return resp.status, json.loads(raw), dict(resp.headers)
                except json.JSONDecodeError:
                    return resp.status, raw.decode("utf-8", "replace"), dict(resp.headers)
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read() or b"{}"), dict(exc.headers)

    # ---- captcha face -------------------------------------------------------

    def test_wrong_captcha_honestly_rejected(self):
        status, payload, _ = self._post_json(
            "/api/captcha-login", {"username": "qa", "password": "x", "captcha": "0000"})
        self.assertEqual(status, 401)
        self.assertEqual(payload["message"], "captcha mismatch")

    def test_right_captcha_answers_jwt_and_cookie(self):
        status, payload, headers = self._post_json(
            "/api/captcha-login", {"username": "qa", "password": "x", "captcha": "7K3Q"})
        self.assertEqual(status, 200)
        data = payload["data"]
        self.assertEqual(data["tokenType"], "Bearer")
        self.assertEqual(data["expiresIn"], 60)
        self.assertGreater(data["expiresAt"], int(time.time()))
        self.assertEqual(len(data["token"].split(".")), 3)
        set_cookie = headers.get("Set-Cookie", "")
        self.assertIn("tp_fixture_session=", set_cookie)
        self.assertIn(data["token"], set_cookie)

    def test_captcha_page_is_served_with_captcha_field(self):
        status, html, _ = self._get("/login-captcha")
        self.assertEqual(status, 200)
        self.assertIn('id="captcha"', html)
        self.assertIn("/api/captcha-login", html)

    # ---- protected resource acceptance faces --------------------------------

    def _login(self, ttl=None):
        payload = {"username": "qa", "password": "x", "captcha": "7K3Q"}
        if ttl is not None:
            payload["expiresIn"] = ttl
        _, body, _ = self._post_json("/api/captcha-login", payload)
        return body["data"]["token"]

    def test_protected_requires_some_token(self):
        status, payload, _ = self._get("/api/protected")
        self.assertEqual(status, 401)
        self.assertEqual(payload["message"], "login required")

    def test_protected_accepts_bearer_and_cookie(self):
        token = self._login()
        status, payload, _ = self._get("/api/protected",
                                       {"Authorization": f"Bearer {token}"})
        self.assertEqual(status, 200)
        self.assertEqual(payload["data"]["account"], "qa")
        status, payload, _ = self._get("/api/protected",
                                       {"Cookie": f"tp_fixture_session={token}"})
        self.assertEqual(status, 200)

    def test_expired_token_reports_expired_reason(self):
        token = self._login(ttl=1)
        time.sleep(2)
        status, payload, _ = self._get("/api/protected",
                                       {"Authorization": f"Bearer {token}"})
        self.assertEqual(status, 401)
        self.assertEqual(payload["reason"], "expired")

    def test_tampered_token_reports_not_valid(self):
        token = self._login()
        header, payload, _ = token.split(".")
        status, body, _ = self._get("/api/protected",
                                    {"Authorization": f"Bearer {header}.{payload}.AAA"})
        self.assertEqual(status, 401)
        self.assertEqual(body["reason"], "bad-signature")

    # ---- device-binding replay face ------------------------------------------

    def test_device_binding_blocks_replay_without_fingerprint(self):
        token = self._login()
        try:
            self._post_json("/api/device-binding", {"on": True})
            status, payload, _ = self._get("/api/protected",
                                           {"Authorization": f"Bearer {token}"})
            self.assertEqual(status, 403)
            self.assertIn("X-Client-Fingerprint", payload["message"])
            status, _, _ = self._get("/api/protected",
                                     {"Authorization": f"Bearer {token}",
                                      "X-Client-Fingerprint": "device-a"})
            self.assertEqual(status, 200)
        finally:
            # leave the control plane in its shipped state for other spectra
            self._post_json("/api/device-binding", {"on": False})
        status, _, _ = self._get("/api/protected", {"Authorization": f"Bearer {token}"})
        self.assertEqual(status, 200)

    def test_binding_state_roundtrip_via_get(self):
        self._post_json("/api/device-binding", {"on": True})
        status, payload, _ = self._get("/api/device-binding")
        self.assertEqual((status, payload["data"]["on"]), (200, True))
        self._post_json("/api/device-binding", {"on": False})
        _, payload, _ = self._get("/api/device-binding")
        self.assertFalse(payload["data"]["on"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
