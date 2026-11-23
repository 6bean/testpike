
from __future__ import annotations

import datetime
import http.server
import ipaddress
import json
import shutil
import ssl
import tempfile
import threading
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from driver.webdriver import run_action_json

_DIR = tempfile.mkdtemp(prefix="w1a_tls_fixtures_")


def _name(cn: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def _issue(subject_cn: str, sans: bool = False, ca: bool = False,
           client: bool = False, issuer_key=None):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    issuer_cn = subject_cn if ca else "w1a-test-ca"
    b = (x509.CertificateBuilder()
         .subject_name(_name(subject_cn))
         .issuer_name(_name(issuer_cn))
         .public_key(key.public_key())
         .serial_number(x509.random_serial_number())
         .not_valid_before(now - datetime.timedelta(days=1))
         .not_valid_after(now + datetime.timedelta(days=365)))
    if ca:
        b = b.add_extension(x509.BasicConstraints(ca=True, path_length=None),
                            critical=True)
        b = b.add_extension(
            x509.KeyUsage(digital_signature=True, content_commitment=False,
                          key_encipherment=False, data_encipherment=False,
                          key_agreement=False, key_cert_sign=True,
                          crl_sign=True, encipher_only=False,
                          decipher_only=False),
            critical=True)
    else:
        b = b.add_extension(
            x509.KeyUsage(digital_signature=True, content_commitment=False,
                          key_encipherment=True, data_encipherment=False,
                          key_agreement=False, key_cert_sign=False,
                          crl_sign=False, encipher_only=False,
                          decipher_only=False),
            critical=True)
        b = b.add_extension(
            x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH,
                                   x509.oid.ExtendedKeyUsageOID.CLIENT_AUTH]),
            critical=False)
    id_src = issuer_key or key
    b = (b.add_extension(
            x509.SubjectKeyIdentifier.from_public_key(key.public_key()),
            critical=False)
         .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(
                id_src.public_key()),
            critical=False))
    if sans:
        b = b.add_extension(x509.SubjectAlternativeName(
            [x509.DNSName("localhost"),
             x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
    if client:
        b = b.add_extension(x509.BasicConstraints(ca=False, path_length=None),
                            critical=True)
    return key, b


class _PlainHandler(http.server.BaseHTTPRequestHandler):
    pass

    protocol_version = "HTTP/1.1"
    saw_client_cert = False

    def do_GET(self):
        try:
            _PlainHandler.saw_client_cert = bool(self.connection.getpeercert())
        except Exception:  # noqa: BLE001
            _PlainHandler.saw_client_cert = False
        if self.path == "/":
            body = (b"<html><head><title>TP-TLS-OK</title>"
                    b"</head><body>ok</body></html>")
            ctype = "text/html"
        else:
            body, ctype = b'{"ok":true}', "application/json"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class _Server:
    def __init__(self, tls_ctx: ssl.SSLContext | None = None):
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _PlainHandler)
        scheme = "http"
        if tls_ctx is not None:
            self.httpd.socket = tls_ctx.wrap_socket(self.httpd.socket,
                                                    server_side=True)
            scheme = "https"
        self.base = f"{scheme}://127.0.0.1:{self.httpd.server_address[1]}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def _write_pair(key, cert, tag):
    kf = f"{_DIR}/{tag}-key.pem"
    cf = f"{_DIR}/{tag}-cert.pem"
    with open(kf, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.TraditionalOpenSSL,
                                  serialization.NoEncryption()))
    with open(cf, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    return cf, kf


_ca_key, _ca_b = _issue("w1a-test-ca", ca=True)
_ca_cert = _ca_b.sign(_ca_key, hashes.SHA256())
CA_FILE = f"{_DIR}/ca.pem"
with open(CA_FILE, "wb") as _f:
    _f.write(_ca_cert.public_bytes(serialization.Encoding.PEM))
with open(CA_FILE) as _f:
    CA_PEM = _f.read()

_srv_key, _srv_b = _issue("localhost", sans=True, issuer_key=_ca_key)
_srv_cert = _srv_b.sign(_ca_key, hashes.SHA256())
_cli_key, _cli_b = _issue("w1a-mtls-client", client=True, issuer_key=_ca_key)
_cli_cert = _cli_b.sign(_ca_key, hashes.SHA256())
CLI_CERT, CLI_KEY = _write_pair(_cli_key, _cli_cert, "client")
with open(CLI_CERT) as _f:
    CLI_PEM = _f.read()
with open(CLI_KEY) as _f:
    CLI_KEY_PEM = _f.read()

_https_server: _Server | None = None
_mtls_server: _Server | None = None
_plain_server: _Server | None = None


def setUpModule():
    global _https_server, _mtls_server, _plain_server
    _plain_server = _Server()
    srv_cert, srv_key = _write_pair(_srv_key, _srv_cert, "server")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(srv_cert, srv_key)
    _https_server = _Server(ctx)
    ctx2 = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx2.load_cert_chain(srv_cert, srv_key)
    ctx2.verify_mode = ssl.CERT_REQUIRED
    ctx2.load_verify_locations(CA_FILE)
    _mtls_server = _Server(ctx2)


def tearDownModule():
    for s in (_https_server, _mtls_server, _plain_server):
        if s:
            s.close()
    shutil.rmtree(_DIR, ignore_errors=True)


def _policy():
    return {"allowHosts": "127.0.0.1", "denyHosts": "",
            "timeoutSeconds": 10, "netAssertEnabled": 0}


def _tls_run(tls_policy, base, action="http"):
    if action == "http":
        step = {"action": "http.request", "method": "GET",
                "url": base + "/ping", "expect": {"status": 200}}
    else:
        step = {"action": "goto", "url": base + "/"}
    doc = {"version": "v2", "steps": [step]}
    return run_action_json(json.dumps(doc), exec_policy=_policy(),
                           tls_policy=tls_policy, env_base_url=base, headless=True)


class TestHttp链路档位接线(unittest.TestCase):
    pass

    def test_缺字段_http明文步照常通过_尾追兼容(self):
        outcome = _tls_run(None, _plain_server.base)
        self.assertEqual("pass", outcome.result,
                         f"无 tlsPolicy 明文步零破坏: {outcome.error_detail}")
        self.assertEqual("strict", outcome.protocol_snapshots[-1].get("tlsMode"),
                         "事件帧尾追键=决议档位（缺字段失败安全兜底 strict）")

    def test_strict自签证书_人话证书红归环境类(self):
        outcome = _tls_run({"mode": "strict"}, _https_server.base)
        self.assertEqual("fail", outcome.result)
        self.assertEqual(3, outcome.error_type)
        self.assertIn("证书", outcome.error_detail or "",
                      "SSL 证书错必点名「证书校验失败」，禁吞成不可达措辞")

    def test_insecure档_照常通过且帧记insecure(self):
        outcome = _tls_run({"mode": "insecure"}, _https_server.base)
        self.assertEqual("pass", outcome.result, outcome.error_detail)
        self.assertEqual("insecure",
                         outcome.protocol_snapshots[-1].get("tlsMode"))

    def test_custom_ca档带CA_通过且帧记custom_ca(self):
        outcome = _tls_run({"mode": "custom_ca", "caPem": CA_PEM},
                           _https_server.base)
        self.assertEqual("pass", outcome.result, outcome.error_detail)
        self.assertEqual("custom_ca",
                         outcome.protocol_snapshots[-1].get("tlsMode"))

    def test_custom_ca缺CA材料_fail_closed人话拒发(self):
        outcome = _tls_run({"mode": "custom_ca"}, _https_server.base)
        self.assertEqual("fail", outcome.result)
        self.assertEqual(3, outcome.error_type)
        self.assertIn("CA", outcome.error_detail or "",
                      "缺材料=下发异常点名，禁静默按默认档放行")

    def test_mTLS成对_http链路真握手通过(self):
        _PlainHandler.saw_client_cert = False
        outcome = _tls_run({"mode": "custom_ca", "caPem": CA_PEM,
                            "clientCertPem": CLI_PEM,
                            "clientKeyPem": CLI_KEY_PEM}, _mtls_server.base)
        self.assertEqual("pass", outcome.result, outcome.error_detail)
        self.assertTrue(_PlainHandler.saw_client_cert,
                        "服务端必收到客户端证书（真握手）")

    def test_mTLS服务端要证书执行器无对_握手断归3(self):
        outcome = _tls_run({"mode": "custom_ca", "caPem": CA_PEM},
                           _mtls_server.base)
        self.assertEqual("fail", outcome.result)
        self.assertEqual(3, outcome.error_type)

    def test_脏mode值_失败安全strict镜像兜底(self):
        outcome = _tls_run({"mode": "WEIRD-VALUE"}, _https_server.base)
        self.assertEqual("fail", outcome.result)
        self.assertIn("证书", outcome.error_detail or "",
                      "未知档=strict 兜底（绝不静默放宽）")


class TestPlaywright链路接线(unittest.TestCase):
    pass

    def test_insecure档_goto自签https页通过(self):
        outcome = _tls_run({"mode": "insecure"}, _https_server.base, action="ui")
        self.assertEqual("pass", outcome.result, outcome.error_detail)

    def test_strict档_goto自签https页失败(self):
        outcome = _tls_run(None, _https_server.base, action="ui")
        self.assertEqual("fail", outcome.result,
                         "strict 下自签证书必被内核拒（未接线=此条红）")

    def test_mTLS成对_goto服务端要证书页通过(self):
        _PlainHandler.saw_client_cert = False
        outcome = _tls_run({"mode": "insecure", "clientCertPem": CLI_PEM,
                            "clientKeyPem": CLI_KEY_PEM},
                           _mtls_server.base, action="ui")
        self.assertEqual("pass", outcome.result, outcome.error_detail)
        self.assertTrue(_PlainHandler.saw_client_cert,
                        "playwright client_certificates 真交付（1.61 实测口径）")


if __name__ == "__main__":
    unittest.main()
