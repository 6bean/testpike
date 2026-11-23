

import re
import unittest

from driver.genkit import (
    GenkitCallError,
    RANDOM_FUNCTIONS,
    id_card,
    id_card_check_digit,
    bank_card,
    luhn_valid,
    uscc,
    uscc_check_char,
    phone,
    chinese_name,
    email,
    date_time,
    json_format,
    json_minify,
    md5,
    sha256,
    base64_encode,
    base64_decode,
    invoke,
)
from driver.env_resolver import resolve_gen


class TestGenKit证件族校验位对齐(unittest.TestCase):
    pass

    def test_GB11643校验位与Java锚点向量一致(self):
        self.assertEqual(id_card_check_digit("11010519491231002"), "X")
        self.assertEqual(id_card_check_digit("44030519881206372"), "5")

    def test_生成身份证区划段取自内置表且校验位自洽(self):
        from driver.genkit import KNOWN_AREA_CODES
        for seed in (7, 20260930, 123456789):
            got = id_card(seed)
            self.assertEqual(18, len(got))
            self.assertIn(got[:6], KNOWN_AREA_CODES)
            self.assertEqual(id_card_check_digit(got[:17]), got[17])

    def test_同种子身份证可复现(self):
        self.assertEqual(id_card(7), id_card(7))
        self.assertNotEqual(id_card(7), id_card(8))

    def test_银行卡真实BIN加Luhn校验位(self):
        card = bank_card(11)
        self.assertEqual(19, len(card))
        self.assertTrue(luhn_valid(card), f"Luhn 校验应过: {card}")
        self.assertEqual(card, bank_card(11))

    def test_统一社会信用代码GB32100校验位自洽(self):
        code = uscc(5)
        self.assertEqual(18, len(code))
        self.assertEqual(uscc_check_char(code[:17]), code[17])
        self.assertEqual(code, uscc(5))

    def test_USCC非法字符人话拒绝(self):
        with self.assertRaises(GenkitCallError) as ctx:
            uscc_check_char("1101051949123100I")
        self.assertIn("非法字符", str(ctx.exception))

    def test_手机号号段合法(self):
        p = phone(3)
        self.assertEqual(11, len(p))
        self.assertTrue(re.fullmatch(r"1\d{10}", p))
        self.assertEqual(p, phone(3))

    def test_中文姓名与邮箱形态(self):
        self.assertTrue(2 <= len(chinese_name(9)) <= 4)
        self.assertTrue(re.fullmatch(r"[a-z0-9]{8}@\S+", email(2)))


class TestGenKit变换编码族(unittest.TestCase):
    def test_MD5与SHA256对齐已知摘要(self):
        self.assertEqual(md5("123456"), "e10adc3949ba59abbe56e057f20f883e")
        self.assertEqual(sha256("123456"),
                         "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92")

    def test_Base64编解码与非法输入人话拒绝(self):
        self.assertEqual(base64_encode("你好"), "5L2g5aW9")
        self.assertEqual(base64_decode("5L2g5aW9"), "你好")
        with self.assertRaises(GenkitCallError):
            base64_decode("!!!not-base64!!!")

    def test_JSON格式化压缩结构等价(self):
        import json
        pretty = json_format('{"a":1,"b":[2,3]}')
        self.assertEqual(json.loads(pretty), {"a": 1, "b": [2, 3]})
        self.assertEqual(json_minify('{"a": 1, "b": [2, 3]}'), '{"a":1,"b":[2,3]}')
        with self.assertRaises(GenkitCallError) as ctx:
            json_format("不是json")
        self.assertIn("不是合法的 JSON", str(ctx.exception))

    def test_日期时间默认格式(self):
        self.assertTrue(re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}",
                                     date_time(None)))


class TestGenKit注册表分发(unittest.TestCase):
    def test_未注册函数人话点名可用清单(self):
        with self.assertRaises(GenkitCallError) as ctx:
            invoke("aesEncrypt", {})
        msg = str(ctx.exception)
        self.assertIn("未注册的函数 aesEncrypt", msg)
        self.assertIn("idCard", msg)

    def test_seed非数字人话拒绝(self):
        with self.assertRaises(GenkitCallError) as ctx:
            invoke("idCard", {"seed": "abc"})
        self.assertIn("seed 必须是数字", str(ctx.exception))

    def test_编码族缺必填text人话拒绝(self):
        with self.assertRaises(GenkitCallError) as ctx:
            invoke("md5", {})
        self.assertIn("缺少必填参数 text", str(ctx.exception))


class TestGenKit扩族二十函数(unittest.TestCase):
    pass

    def test_注册表二十函数齐_双镜像一致(self):
        from driver.genkit import ALL_FUNCTION_NAMES
        self.assertEqual(33, len(ALL_FUNCTION_NAMES))
        for n in ("companyName", "address", "lngLat", "plateNumber", "iban",
                  "sha1", "urlEncode", "unicodeEscape", "radix", "timestampToDate",
                  "jsonPathExtract", "flatten", "camelCase", "trim", "wordCount",
                  "regexMatch"):
            self.assertIn(n, ALL_FUNCTION_NAMES, f"缺函数 {n}")
        # Signature family (auth preset building blocks): Java GenFunction mirror pins the same four.
        for n in ("timestamp", "nonce", "sortConcat", "hmacSha256"):
            self.assertIn(n, ALL_FUNCTION_NAMES, f"缺函数 {n}")

    def test_mock命名空间注册表_双镜像钉版(self):
        """ F5: the executor mirror's {{mock.*}} whitelist must stay the
 same closed set as common MockTemplateResolver.MOCK_FUNCTIONS — new
 members land in both mirrors in one change (pattern-literal anchor
 lives in test_t358_mock_template)."""
        from driver.env_resolver import MOCK_FUNCTIONS
        self.assertEqual(("pathVar", "query", "header", "body",
                          "now", "randomInt", "cycle", "session"), tuple(MOCK_FUNCTIONS))

    def test_新证件族形态与seed复现(self):
        self.assertTrue(re.fullmatch(r"[一-龥]{2,4}市[一-龥]{2,4}[一-龥]{2,4}有限公司",
                                     invoke("companyName", {"seed": 4})))
        self.assertTrue(invoke("address", {"seed": 4}).endswith("号"))
        self.assertTrue(re.fullmatch(r"\d{2,3}\.\d{6},\d{1,2}\.\d{6}",
                                     invoke("lngLat", {"seed": 4})))
        self.assertTrue(re.fullmatch(r"[京津沪粤苏浙鲁川][A-HJ-NP-Z][A-HJ-NP-Z0-9]{5}",
                                     invoke("plateNumber", {"seed": 4})))
        self.assertEqual(invoke("plateNumber", {"seed": 4}), invoke("plateNumber", {"seed": 4}))

    def test_IBAN_mod97自洽与篡改判非(self):
        from driver.genkit import iban, iban_remainder, iban_valid
        got = iban(7)
        self.assertTrue(re.fullmatch(r"DE\d{2}[0-9A-Z]{18}", got))
        self.assertEqual(1, iban_remainder(got))
        self.assertTrue(iban_valid(got))
        self.assertTrue(not iban_valid("DE00" + got[4:]))
        self.assertEqual(got, iban(7))

    def test_SHA1与URL编码与Unicode进制锚点(self):
        self.assertEqual("a9993e364706816aba3e25717850c26c9cd0d89d", invoke("sha1", {"text": "abc"}))
        self.assertEqual("a+b%26c%3D1", invoke("urlEncode", {"text": "a b&c=1"}))
        self.assertEqual("%E4%BD%A0%E5%A5%BD", invoke("urlEncode", {"text": "你好"}))
        self.assertEqual("\\u4f60\\u597da", invoke("unicodeEscape", {"text": "你好a"}))
        self.assertEqual("ff", invoke("radix", {"text": "255", "to": "16"}))
        self.assertEqual("255", invoke("radix", {"text": "ff", "from": "16", "to": "10"}))
        self.assertEqual("11111111", invoke("radix", {"text": "255", "to": "2"}))
        with self.assertRaises(GenkitCallError) as ctx:
            invoke("radix", {"text": "zz", "from": "16", "to": "10"})
        self.assertIn("不是合法的", str(ctx.exception))
        with self.assertRaises(GenkitCallError) as ctx2:
            invoke("radix", {"text": "ff", "to": "99"})
        self.assertIn("进制必须", str(ctx2.exception))

    def test_时间戳转日期UTC与毫秒自适应(self):
        self.assertEqual("2025-03-01 00:00:00", invoke("timestampToDate", {"text": "1740787200"}))
        self.assertEqual("2025-03-01", invoke("timestampToDate",
                                              {"text": "1740787200", "format": "yyyy-MM-dd"}))
        self.assertEqual("2025-03-01 00:00:00", invoke("timestampToDate", {"text": "1740787200000"}))

    def test_JSONPath拍平驼峰去空白词数正则锚点(self):
        self.assertEqual("20", invoke("jsonPathExtract",
                                       {"text": '{"a":{"b":[10,20]}}', "path": "$.a.b[1]"}))
        with self.assertRaises(GenkitCallError) as ctx:
            invoke("jsonPathExtract", {"text": '{"a":1}', "path": "$.x"})
        self.assertIn("未命中", str(ctx.exception))
        self.assertEqual("a.b=1\nc[0]=2\nc[1]=3",
                         invoke("flatten", {"text": '{"a":{"b":1},"c":[2,3]}'}))
        self.assertEqual("orderIdList", invoke("camelCase", {"text": "order_id-list"}))
        self.assertEqual("a b", invoke("trim", {"text": "  a b  "}))
        self.assertEqual("4", invoke("wordCount", {"text": "hello 世界 abc 123"}))
        self.assertEqual("42", invoke("regexMatch", {"text": "id=42", "pattern": r"id=(\d+)"}))
        with self.assertRaises(GenkitCallError) as ctx2:
            invoke("regexMatch", {"text": "abc", "pattern": r"id=(\d+)"})
        self.assertIn("未命中", str(ctx2.exception))


class TestResolveGen模板接线(unittest.TestCase):
    pass

    def test_显式seed同串多占位符全部替换(self):
        doc = {"body": {"idc": "${gen.idCard(seed=7)}",
                        "dup": "${gen.idCard(seed=7)}"},
               "tel": "${gen.phone(seed=3)}"}
        out = resolve_gen(doc, [])
        self.assertEqual(out["body"]["idc"], out["body"]["dup"])
        self.assertEqual(out["tel"], phone(3))

    def test_递归重建不改原对象(self):
        doc = {"setup": [{"value": "${gen.uscc(seed=5)}"}]}
        out = resolve_gen(doc, [])
        self.assertEqual(doc["setup"][0]["value"], "${gen.uscc(seed=5)}")
        self.assertEqual(out["setup"][0]["value"], uscc(5))

    def test_缺省seed系统随机_回填实seed可复现(self):
        sink: list = []
        out = resolve_gen({"v": "${gen.idCard()}"}, sink)
        self.assertEqual(18, len(out["v"]))
        self.assertEqual(1, len(sink))
        row = sink[0]
        self.assertEqual("idCard", row["fn"])
        self.assertIsInstance(row["seed"], int)
        again = resolve_gen({"v": "${gen.idCard(seed=%d)}" % row["seed"]}, [])
        self.assertEqual(out["v"], again["v"])
        two = resolve_gen({"a": "${gen.email()}", "b": "${gen.email()}"}, [])
        self.assertNotEqual(two["a"], two["b"])

    def test_sink只记随机族零实值零入参(self):
        sink: list = []
        resolve_gen({"m": "${gen.md5(text=123456)}",
                     "d": "${gen.dateTime()}",
                     "p": "${gen.phone(seed=3)}"}, sink)
        self.assertEqual([{"fn": "phone", "seed": 3}], sink)

    def test_变换族入参含逗号与等号正确解析(self):
        out = resolve_gen({"v": "${gen.jsonMinify(text={\"a\": 1, \"b\": 2})}"}, [])
        self.assertEqual(out["v"], '{"a":1,"b":2}')

    def test_未注册函数fail_closed进resolver(self):
        with self.assertRaises(GenkitCallError):
            resolve_gen({"v": "${gen.aesEncrypt(text=x)}"}, [])

    def test_无gen占位符文档恒等零漂移(self):
        doc = {"steps": [{"action": "click", "target": {"css": "#a"}}]}
        out = resolve_gen(doc, [])
        self.assertEqual(out, doc)

    def test_非字符串值原样保留(self):
        out = resolve_gen({"count": 3, "ok": True, "nil": None}, [])
        self.assertEqual({"count": 3, "ok": True, "nil": None}, out)


class TestRunOutcome事件流尾追键(unittest.TestCase):
    pass

    def test_roundtrip_gen_seeds透传(self):
        from driver.webdriver import RunOutcome
        o = RunOutcome(result="pass", gen_seeds=[{"fn": "idCard", "seed": 7}])
        back = RunOutcome.from_dict(o.to_dict())
        self.assertEqual([{"fn": "idCard", "seed": 7}], back.gen_seeds)

    def test_空seed事件to_dict不出键_旧core零影响(self):
        from driver.webdriver import RunOutcome
        self.assertNotIn("gen_seeds", RunOutcome(result="pass").to_dict())


class TestApiRunnerGen接线(unittest.TestCase):
    pass

    def _run(self, body, assertions=None):
        import json as _json
        import threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        from api_runner import run_api_task

        received = {}

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0) or 0)
                raw = self.rfile.read(length) if length else b"{}"
                received["body"] = _json.loads(raw.decode("utf-8"))
                payload = b'{"code":0}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *a):
                pass

        srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        port = srv.server_address[1]
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            envelope = _json.dumps({
                "kind": "api-test-run", "method": "POST",
                "envBaseUrl": f"http://127.0.0.1:{port}",
                "urlPath": "/submit", "body": body,
                "execPolicy": {"allowHosts": "127.0.0.1", "denyHosts": ""},
                "assertions": assertions or [{"type": "status", "expect": 200}],
            })
            outcome = run_api_task(envelope)
        finally:
            srv.shutdown()
        return outcome, received["body"]

    def test_显式seed_请求体真发送替换值_事件流回填(self):
        outcome, sent = self._run({"idc": "${gen.idCard(seed=7)}"})
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        self.assertEqual(sent["idc"], id_card(7))
        self.assertEqual([{"fn": "idCard", "seed": 7}], outcome.gen_seeds)

    def test_缺省seed_回填实seed可重放(self):
        outcome, sent = self._run({"tel": "${gen.phone()}"})
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        self.assertEqual(1, len(outcome.gen_seeds))
        row = outcome.gen_seeds[0]
        self.assertEqual("phone", row["fn"])
        self.assertEqual(sent["tel"], phone(row["seed"]))

    def test_未注册函数_fail_closed_归3人话点名(self):
        from api_runner import run_api_task
        import json as _json
        outcome = run_api_task(_json.dumps({
            "kind": "api-test-run", "method": "GET", "urlPath": "/x",
            "assertions": [{"type": "status", "expect": 200}],
            "body": {"v": "${gen.aesEncrypt(text=x)}"}}))
        self.assertEqual(outcome.result, "fail")
        self.assertEqual(3, outcome.error_type)
        self.assertIn("未注册的函数", outcome.error_detail or "")

    def test_无gen占位符信封零回归_不出事件键(self):
        outcome, sent = self._run({"a": "plain"},
                                  assertions=[{"type": "status", "expect": 200}])
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        self.assertEqual(sent, {"a": "plain"})
        self.assertEqual([], outcome.gen_seeds)
        from driver.webdriver import RunOutcome
        self.assertNotIn("gen_seeds", outcome.to_dict())


class TestGenKitSignatureFamily(unittest.TestCase):
    """Auth preset building blocks: timestamp, nonce, lexicographic concat, HMAC-SHA256.

    Anchor vectors computed with openssl/md5sum, mirrored one-for-one by
    GenKitTest in Java so both languages produce byte-identical signatures."""

    def test_hmac_sha256_known_anchor_vector(self):
        self.assertEqual(
            invoke("hmacSha256", {"text": "The quick brown fox jumps over the lazy dog",
                                  "key": "key"}),
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")

    def test_hmac_sha256_missing_key_rejected(self):
        with self.assertRaises(GenkitCallError):
            invoke("hmacSha256", {"text": "abc"})

    def test_sort_concat_orders_by_key(self):
        self.assertEqual(
            invoke("sortConcat", {"text": "timestamp=1&nonce=abc&appKey=k"}),
            "appKey=k&nonce=abc&timestamp=1")

    def test_sort_concat_custom_separator(self):
        self.assertEqual(
            invoke("sortConcat", {"text": "b=2;a=1", "separator": ";"}), "a=1;b=2")

    def test_timestamp_seconds_and_millis(self):
        self.assertTrue(re.fullmatch(r"\d{10}", invoke("timestamp", {})))
        self.assertTrue(re.fullmatch(r"\d{13}", invoke("timestamp", {"unit": "ms"})))
        with self.assertRaises(GenkitCallError):
            invoke("timestamp", {"unit": "us"})

    def test_nonce_length_and_seed_reproducible(self):
        value = invoke("nonce", {"length": "8"})
        self.assertTrue(re.fullmatch(r"[a-z0-9]{8}", value))
        self.assertEqual(invoke("nonce", {"seed": 5, "length": "12"}),
                         invoke("nonce", {"seed": 5, "length": "12"}))

    def test_nonce_registered_as_random_family(self):
        self.assertIn("nonce", RANDOM_FUNCTIONS)
        self.assertNotIn("hmacSha256", RANDOM_FUNCTIONS)


if __name__ == "__main__":
    unittest.main()
