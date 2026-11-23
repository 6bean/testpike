
import http.server
import json
import os
import socket
import ssl
import subprocess
import tempfile
import threading

import pytest
from conftest import BASE_URL, assert_no_errors
from playwright.sync_api import expect

P12 = os.path.normpath(os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "core", "src", "test", "resources", "tls", "selfsigned.p12"))
CERT_NOT_AFTER = "2036-09-25"


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@pytest.fixture(scope="module")
def https_fixture():
    pass
    d = tempfile.mkdtemp(prefix="e2e-w1a-tls-")
    key_p = os.path.join(d, "key.pem")
    crt_p = os.path.join(d, "cert.pem")
    subprocess.run(["openssl", "pkcs12", "-in", P12, "-passin", "pass:changeit",
                    "-nocerts", "-nodes", "-out", key_p], check=True, capture_output=True)
    subprocess.run(["openssl", "pkcs12", "-in", P12, "-passin", "pass:changeit",
                    "-clcerts", "-nokeys", "-out", crt_p], check=True, capture_output=True)

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    port = _free_port()
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(crt_p, key_p)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    yield f"https://127.0.0.1:{port}/"
    httpd.shutdown()


@pytest.fixture
def cert_env_project(api_client, uniq, https_fixture):
    pass
    resp = api_client.post("/projects", json={"name": f"证书卡项目{uniq}", "code": f"W1A{uniq.upper()}"})
    assert resp.status_code == 200 and resp.json().get("code") == "0", resp.text[:300]
    data = resp.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    cfg = {"tls": {"mode": "insecure", "reason": "E2E 自签夹具联调"}}
    resp = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"w1a-env-{uniq}", "envType": 2, "baseUrl": https_fixture,
        "configJson": json.dumps(cfg, ensure_ascii=False),
        "endTypes": "web", "browsers": "chromium"})
    assert resp.status_code == 200 and str(resp.json().get("code")) == "0", resp.text[:300]
    env_id = resp.json()["data"]
    yield pid, env_id
    api_client.delete(f"/projects/{pid}")


def test_w1a_cert_card_badge_probe_reload(logged_in, collectors, cert_env_project):
    pass
    page = logged_in
    pid, env_id = cert_env_project

    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=8000)

    badge = page.locator('[data-test="env-cert-badge"]')
    expect(badge).to_be_visible(timeout=8000)
    expect(badge).to_contain_text("未验证TLS")

    unknown = page.locator('[data-test="env-cert-unknown"]')
    expect(unknown).to_be_visible(timeout=5000)

    with page.expect_response(
        lambda r: r.request.method == "POST" and f"/envs/{env_id}/health" in r.url) as resp_info:
        page.locator('[data-test="env-health-probe"]').click()
    body = resp_info.value.json()
    assert str(body.get("code")) == "0", f"探测失败: {str(body)[:200]}"

    expiry = page.locator('[data-test="env-cert-expiry"]')
    expect(expiry).to_contain_text(CERT_NOT_AFTER, timeout=8000)
    expect(page.locator('[data-test="env-cert-unknown"]')).to_have_count(0)

    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=8000)
    expect(page.locator('[data-test="env-cert-expiry"]')).to_contain_text(CERT_NOT_AFTER, timeout=8000)
    expect(page.locator('[data-test="env-cert-badge"]')).to_be_visible(timeout=5000)

    assert_no_errors(collectors, "W1a cert card badge probe reload")
