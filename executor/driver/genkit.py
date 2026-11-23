

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import random
import re
from typing import Any, Dict, Optional

KNOWN_AREA_CODES = (
    "110101", "110105", "120101", "310101", "310104", "310115",
    "320102", "330102", "330106", "340102", "440103", "440305",
    "440307", "500103", "510104", "610102")

_ID_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)

_ID_CHECK_CHARS = "10X98765432"

_USCC_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY"

_USCC_WEIGHTS = (1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28)

_PHONE_PREFIXES = (
    "133", "153", "180", "181", "189", "130", "131", "132", "155", "156",
    "185", "186", "134", "135", "136", "137", "138", "139", "150", "151",
    "152", "157", "158")

_CARD_BINS = ("622202", "621226", "622848", "621483", "628288")

_SURNAMES = ("赵", "钱", "孙", "李", "周", "吴", "郑", "王",
             "冯", "陈", "蒋", "沈", "韩", "杨", "朱", "秦", "许", "何", "吕", "施")

_GIVEN_CHARS = ("伟", "芳", "娜", "秀英", "敏", "静", "丽", "强",
                "磊", "军", "洋", "勇", "艳", "杰", "娟", "涛", "明", "超",
                "秀兰", "霞", "平", "刚", "桂英")

_EMAIL_DOMAINS = ("example.com", "test.org", "demo.cn", "mail.test")

_EMAIL_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

RANDOM_FUNCTIONS = frozenset(
    {"idCard", "bankCard", "uscc", "phone", "chineseName", "email",
     "companyName", "address", "lngLat", "plateNumber", "iban", "nonce"})

ALL_FUNCTION_NAMES = (
    "idCard", "bankCard", "uscc", "phone", "chineseName", "email",
    "dateTime", "md5", "sha256", "base64Encode", "base64Decode",
    "jsonFormat", "jsonMinify",
    "companyName", "address", "lngLat", "plateNumber", "iban",
    "sha1", "urlEncode", "unicodeEscape", "radix", "timestampToDate",
    "jsonPathExtract", "flatten", "camelCase", "trim", "wordCount", "regexMatch",
    "timestamp", "nonce", "sortConcat", "hmacSha256")


class GenkitCallError(ValueError):
    pass


def _random_for(seed: Optional[int]) -> random.Random:
    return random.Random() if seed is None else random.Random(seed)


def _pick(pool, rng: random.Random) -> str:
    return pool[rng.randrange(len(pool))]



def id_card(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    area = _pick(KNOWN_AREA_CODES, rng)
    year = 1950 + rng.randrange(56)
    month = 1 + rng.randrange(12)
    day = 1 + rng.randrange(28)
    birth = f"{year:04d}{month:02d}{day:02d}"
    seq = "".join(str(rng.randrange(10)) for _ in range(3))
    first17 = area + birth + seq
    return first17 + id_card_check_digit(first17)


def id_card_check_digit(first17: str) -> str:
    pass
    if first17 is None or len(first17) != 17 or not first17.isdigit():
        raise GenkitCallError("身份证前 17 位必须是纯数字，无法计算校验位")
    total = sum(int(first17[i]) * _ID_WEIGHTS[i] for i in range(17))
    return _ID_CHECK_CHARS[total % 11]


def luhn_check_digit(partial_digits: str) -> str:
    pass
    total = 0
    double_it = True
    for i in range(len(partial_digits) - 1, -1, -1):
        d = int(partial_digits[i])
        if double_it:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        double_it = not double_it
    return str((10 - total % 10) % 10)


def luhn_valid(card_number: str) -> bool:
    pass
    if not card_number or not card_number.isdigit():
        return False
    return card_number[-1] == luhn_check_digit(card_number[:-1])


def bank_card(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    digits = list(_pick(_CARD_BINS, rng))
    while len(digits) < 18:
        digits.append(str(rng.randrange(10)))
    partial = "".join(digits)
    return partial + luhn_check_digit(partial)


def uscc_check_char(first17: str) -> str:
    pass
    if first17 is None or len(first17) != 17:
        raise GenkitCallError("统一社会信用代码前 17 位长度必须为 17")
    total = 0
    for i, ch in enumerate(first17):
        value = _USCC_CHARS.find(ch)
        if value < 0:
            raise GenkitCallError("统一社会信用代码含非法字符（禁用 I/O/S/Z/V）")
        total += value * _USCC_WEIGHTS[i]
    check = 31 - total % 31
    return _USCC_CHARS[0 if check == 31 else check]


def uscc(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    code = "91" + _pick(KNOWN_AREA_CODES, rng)
    code += "".join(_USCC_CHARS[rng.randrange(len(_USCC_CHARS))] for _ in range(9))
    return code + uscc_check_char(code)


def phone(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    digits = list(_pick(_PHONE_PREFIXES, rng))
    while len(digits) < 11:
        digits.append(str(rng.randrange(10)))
    return "".join(digits)


def chinese_name(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    return _pick(_SURNAMES, rng) + _pick(_GIVEN_CHARS, rng)


def email(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    local = "".join(_EMAIL_ALPHABET[rng.randrange(len(_EMAIL_ALPHABET))]
                    for _ in range(8))
    return local + "@" + _pick(_EMAIL_DOMAINS, rng)


_COMPANY_CITIES = ("深圳", "上海", "北京", "广州", "杭州", "南京", "成都", "武汉")
_COMPANY_WORDS = ("鑫源", "华腾", "联合", "鼎信", "恒润", "天泽", "睿达", "中科")
_COMPANY_TRADES = ("科技", "信息", "贸易", "网络", "智能装备", "数据服务")
_ADDRESS_CITIES = ("深圳市", "上海市", "北京市", "广州市", "杭州市", "南京市", "成都市", "武汉市")
_ADDRESS_DISTRICTS = ("南山区", "浦东新区", "朝阳区", "天河区", "西湖区", "鼓楼区", "武侯区", "洪山区")
_ADDRESS_ROADS = ("中山路", "科技园南路", "人民大道", "解放路", "望江路", "和平街", "创新大道", "滨江路")
_PLATE_PROVINCES = ("京", "津", "沪", "粤", "苏", "浙", "鲁", "川")
_PLATE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"


def company_name(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    return (_pick(_COMPANY_CITIES, rng) + "市" + _pick(_COMPANY_WORDS, rng)
            + _pick(_COMPANY_TRADES, rng) + "有限公司")


def address(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    return (_pick(_ADDRESS_CITIES, rng) + _pick(_ADDRESS_DISTRICTS, rng)
            + _pick(_ADDRESS_ROADS, rng) + str(1 + rng.randrange(999)) + "号")


def lng_lat(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    lng = 73.5 + rng.random() * (135.1 - 73.5)
    lat = 18.1 + rng.random() * (53.6 - 18.1)
    return f"{lng:.6f},{lat:.6f}"


def plate_number(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    tail = _PLATE_LETTERS + "0123456789"
    return (_pick(_PLATE_PROVINCES, rng)
            + _PLATE_LETTERS[rng.randrange(len(_PLATE_LETTERS))]
            + "".join(tail[rng.randrange(len(tail))] for _ in range(5)))


def _digits_mod97(expanded: str) -> int:
    pass
    remainder = 0
    for ch in expanded:
        if ch.isdigit():
            remainder = (remainder * 10 + int(ch)) % 97
        elif ("A" <= ch <= "Z") or ("a" <= ch <= "z"):
            value = ord(ch.upper()) - ord("A") + 10
            remainder = (remainder * 100 + value) % 97
        else:
            raise GenkitCallError("IBAN 计算含非法字符")
    return remainder


def iban_check_digits(country_code: str, bban: str) -> str:
    pass
    check = 98 - _digits_mod97(bban + country_code + "00")
    return f"{check:02d}"


def iban(seed: Optional[int] = None) -> str:
    pass
    rng = _random_for(seed)
    bban = "".join(str(rng.randrange(10)) for _ in range(18))
    return "DE" + iban_check_digits("DE", bban) + bban


def iban_remainder(iban_str: str) -> int:
    pass
    if iban_str is None or len(iban_str) < 5:
        raise GenkitCallError("IBAN 长度不足，无法校验")
    normalized = (iban_str[4:] + iban_str[:4]).upper().replace(" ", "")
    for ch in normalized:
        if not (ch.isdigit() or ("A" <= ch <= "Z")):
            raise GenkitCallError("IBAN 含非法字符，无法校验")
    return _digits_mod97(normalized)


def iban_valid(iban_str: str) -> bool:
    pass
    try:
        return iban_remainder(iban_str) == 1
    except GenkitCallError:
        return False



_PATTERN_TOKENS = (
    ("yyyy", "%Y"), ("MM", "%m"), ("dd", "%d"),
    ("HH", "%H"), ("mm", "%M"), ("ss", "%S"))


def _pattern_to_strftime(fmt: str) -> str:
    out = fmt
    for java_token, py_token in _PATTERN_TOKENS:
        out = out.replace(java_token, py_token)
    return out


def date_time(fmt: Optional[str] = None) -> str:
    pass
    import datetime as _dt
    now = _dt.datetime.now()
    if not fmt or not fmt.strip():
        return now.strftime("%Y-%m-%d %H:%M:%S")
    try:
        return now.strftime(_pattern_to_strftime(fmt))
    except (ValueError, TypeError):
        raise GenkitCallError(f"日期格式 {fmt} 不被支持（可用如 yyyy-MM-dd HH:mm:ss）")


def json_format(text: str) -> str:
    pass
    return _render_json(text, pretty=True)


def json_minify(text: str) -> str:
    pass
    return _render_json(text, pretty=False)


def timestamp_to_date(ts: str, fmt: Optional[str] = None, zone: Optional[str] = None) -> str:
    pass
    import datetime as _dt
    if ts is None or not str(ts).strip():
        raise GenkitCallError("时间戳为空，无法转换为日期")
    try:
        value = int(str(ts).strip())
    except ValueError:
        raise GenkitCallError("时间戳必须是纯数字（秒或毫秒），无法转换")
    if abs(value) >= 1_000_000_000_000:
        value //= 1000
    java_pattern = (fmt or "").strip() or "yyyy-MM-dd HH:mm:ss"
    strftime = _pattern_to_strftime(java_pattern)
    moment = _dt.datetime.fromtimestamp(value, _dt.timezone.utc)
    if zone and zone.strip().upper() != "UTC":
        try:
            import zoneinfo
            moment = moment.astimezone(zoneinfo.ZoneInfo(zone.strip()))
        except Exception:
            raise GenkitCallError(f"时区「{zone}」无法识别（可用如 UTC、Asia/Shanghai）")
    return moment.strftime(strftime)


def _json_path_root(text: str):
    tree = _read_json_tree(text)
    return tree


def json_path_extract(text: str, path: str) -> str:
    pass
    import re as _re
    if not path:
        raise GenkitCallError("jsonPathExtract 缺少必填参数 path")
    node = _json_path_root(text)
    rest = path[2:] if path.startswith("$.") else (path[1:] if path.startswith("$") else path)
    for part in rest.split("."):
        if not part:
            continue
        bracket = part.find("[")
        name = part if bracket < 0 else part[:bracket]
        if name:
            if not _re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                raise GenkitCallError(f"JSONPath 路径「{path}」含不支持的语法段")
            if not isinstance(node, dict) or name not in node:
                raise GenkitCallError(f"JSONPath 「{path}」在文档中未命中")
            node = node[name]
        if bracket >= 0:
            suffix = part[bracket:]
            if not _re.fullmatch(r"(\[\d+\])+", suffix):
                raise GenkitCallError(f"JSONPath 路径「{path}」含不支持的语法段")
            for idx_text in _re.findall(r"\[(\d+)]", suffix):
                idx = int(idx_text)
                if not isinstance(node, list) or idx >= len(node):
                    raise GenkitCallError(f"JSONPath 「{path}」在文档中未命中")
                node = node[idx]
    if isinstance(node, (dict, list)):
        raise GenkitCallError(f"JSONPath 「{path}」取到的是对象/数组，本函数仅支持标量值")
    if node is None:
        return "null"
    if node is True:
        return "true"
    if node is False:
        return "false"
    return str(node)


def flatten(text: str) -> str:
    pass
    lines: list = []
    _flatten_into(_json_path_root(text), "", lines)
    return "\n".join(lines)


def _flatten_into(node, prefix: str, lines: list) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            _flatten_into(v, (k if not prefix else prefix + "." + k), lines)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _flatten_into(v, f"{prefix}[{i}]", lines)
    else:
        if node is None:
            value = "null"
        elif node is True:
            value = "true"
        elif node is False:
            value = "false"
        else:
            value = str(node)
        lines.append(f"{prefix}={value}")


def camel_case(text: str) -> str:
    pass
    out = []
    upper_next = False
    for ch in text:
        if ch in ("_", "-", " ", "\t"):
            upper_next = bool(out)
        elif upper_next:
            out.append(ch.upper())
            upper_next = False
        else:
            out.append(ch)
    return "".join(out)


def trim(text: str) -> str:
    pass
    return text.strip()


def word_count(text: str) -> str:
    pass
    if text is None or not text.strip():
        return "0"
    return str(len(text.split()))


def regex_match(text: str, pattern: str) -> str:
    pass
    if not pattern:
        raise GenkitCallError("regexMatch 缺少必填参数 pattern")
    try:
        compiled = re.compile(pattern)
    except re.error:
        raise GenkitCallError("正则表达式不合法，请检查后重试")
    m = compiled.search(text or "")
    if not m:
        raise GenkitCallError(f"正则「{pattern}」在文本中未命中")
    if m.groups() and m.group(1) is not None:
        return m.group(1)
    return m.group()


def _read_json_tree(text: str):
    if text is None or not text.strip():
        raise GenkitCallError("JSON 文本为空，不是合法的 JSON，无法处理")
    try:
        return json.loads(text)
    except (ValueError, TypeError):
        raise GenkitCallError("文本不是合法的 JSON，请检查后重试")


def _render_json(text: str, pretty: bool) -> str:
    tree = _read_json_tree(text)
    if pretty:
        return json.dumps(tree, ensure_ascii=False, indent=2)
    return json.dumps(tree, ensure_ascii=False, separators=(",", ":"))



def md5(text: str) -> str:
    return _digest_hex("md5", text)


def sha256(text: str) -> str:
    return _digest_hex("sha256", text)


def sha1(text: str) -> str:
    return _digest_hex("sha1", text)


def _digest_hex(algorithm: str, text: str) -> str:
    if text is None:
        raise GenkitCallError("待摘要文本为空")
    return hashlib.new(algorithm, text.encode("utf-8")).hexdigest()


def base64_encode(text: str) -> str:
    if text is None:
        raise GenkitCallError("待编码文本为空")
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def base64_decode(text: str) -> str:
    if text is None or not text.strip():
        raise GenkitCallError("待解码文本为空，不是合法的 Base64")
    try:
        return base64.b64decode(text, validate=True).decode("utf-8")
    except (binascii.Error, ValueError, UnicodeDecodeError):
        raise GenkitCallError("文本不是合法的 Base64，请检查后重试")


def url_encode(text: str) -> str:
    pass
    from urllib.parse import quote_plus
    return (quote_plus(text, encoding="utf-8", errors="strict")
            .replace("~", "%7E").replace("%2A", "*"))


def unicode_escape(text: str) -> str:
    pass
    out = []
    for ch in text:
        if ord(ch) < 0x80:
            out.append(ch)
        else:
            out.append("\\u%04x" % ord(ch))
    return "".join(out)


def radix(text: str, from_base: Optional[str] = None, to_base: Optional[str] = None) -> str:
    pass
    try:
        base_from = int(from_base) if from_base else 10
    except ValueError:
        raise GenkitCallError("进制必须是数字（2 到 36 之间）")
    try:
        base_to = int(to_base) if to_base else None
    except ValueError:
        raise GenkitCallError("进制必须是数字（2 到 36 之间）")
    for base in (base_from, base_to):
        if base is None or base < 2 or base > 36:
            raise GenkitCallError("进制必须在 2 到 36 之间")
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    stripped = text.strip()
    if not stripped:
        raise GenkitCallError("待转换数字串为空")
    try:
        value = int(stripped, base_from)
    except ValueError:
        raise GenkitCallError(f"「{text}」不是合法的 {base_from} 进制数，或目标进制超出 2-36")
    if value == 0:
        return "0"
    negative = value < 0
    value = abs(value)
    out = ""
    while value:
        value, rem = divmod(value, base_to)
        out = digits[rem] + out
    return ("-" if negative else "") + out


# ------------------------------------------------------------------ Signature family

def timestamp(unit: Optional[str] = None) -> str:
    """Epoch time as text: 10-digit seconds (default) or 13-digit milliseconds."""
    import time
    if unit is None or unit == "" or unit == "s":
        return str(int(time.time()))
    if unit == "ms":
        return str(int(time.time() * 1000))
    raise GenkitCallError("timestamp 的 unit 只能是 s 或 ms：" + str(unit))


def nonce(seed: Optional[int] = None, length: Optional[str] = None) -> str:
    """Random lowercase alphanumeric nonce (default 16, range 8-64); seed reproducible."""
    size = 16
    if length not in (None, ""):
        try:
            size = int(str(length).strip())
        except ValueError:
            raise GenkitCallError("nonce 的 length 必须是数字：" + str(length))
        if size < 8 or size > 64:
            raise GenkitCallError("nonce 的 length 必须在 8 到 64 之间")
    rng = _random_for(seed)
    alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return "".join(rng.choice(alphabet) for _ in range(size))


def sort_concat(text: str, separator: Optional[str] = None) -> str:
    """Re-order k=v pairs lexicographically and join (default separator &)."""
    if text is None or not text.strip():
        raise GenkitCallError("sortConcat 缺少 k=v 参数串")
    sep = separator if separator else "&"
    pairs = [p.strip() for p in text.split(sep) if p.strip()]
    return sep.join(sorted(pairs))


def hmac_sha256(text: str, key: Optional[str]) -> str:
    """HMAC-SHA256 over UTF-8 text; key comes from @cred resolution upstream."""
    import hmac as _hmac
    if text is None:
        raise GenkitCallError("待签名文本为空")
    if not key:
        raise GenkitCallError("hmacSha256 缺少密钥 key（认证模板侧请引用 @cred，禁明文密钥入模板）")
    return _hmac.new(key.encode("utf-8"), text.encode("utf-8"),
                     hashlib.sha256).hexdigest()



def invoke(name: str, args: Dict[str, Any]) -> str:
    pass
    if name not in ALL_FUNCTION_NAMES:
        raise GenkitCallError("genkit 未注册的函数 " + str(name)
                              + "，当前可用函数：" + "、".join(ALL_FUNCTION_NAMES))
    safe = args or {}
    seed = _seed_of(safe)
    if name == "idCard":
        return id_card(seed)
    if name == "bankCard":
        return bank_card(seed)
    if name == "uscc":
        return uscc(seed)
    if name == "phone":
        return phone(seed)
    if name == "chineseName":
        return chinese_name(seed)
    if name == "email":
        return email(seed)
    if name == "dateTime":
        return date_time(_string_of(safe, "format"))
    if name == "md5":
        return md5(_required_text(safe, name))
    if name == "sha256":
        return sha256(_required_text(safe, name))
    if name == "base64Encode":
        return base64_encode(_required_text(safe, name))
    if name == "base64Decode":
        return base64_decode(_required_text(safe, name))
    if name == "jsonFormat":
        return json_format(_required_text(safe, name))
    if name == "jsonMinify":
        return json_minify(_required_text(safe, name))
    if name == "companyName":
        return company_name(seed)
    if name == "address":
        return address(seed)
    if name == "lngLat":
        return lng_lat(seed)
    if name == "plateNumber":
        return plate_number(seed)
    if name == "iban":
        return iban(seed)
    if name == "sha1":
        return sha1(_required_text(safe, name))
    if name == "urlEncode":
        return url_encode(_required_text(safe, name))
    if name == "unicodeEscape":
        return unicode_escape(_required_text(safe, name))
    if name == "radix":
        return radix(_required_text(safe, name),
                     _string_of(safe, "from"), _string_of(safe, "to"))
    if name == "timestampToDate":
        return timestamp_to_date(_required_text(safe, name),
                                 _string_of(safe, "format"), _string_of(safe, "zone"))
    if name == "jsonPathExtract":
        return json_path_extract(_required_text(safe, name), _string_of(safe, "path"))
    if name == "flatten":
        return flatten(_required_text(safe, name))
    if name == "camelCase":
        return camel_case(_required_text(safe, name))
    if name == "trim":
        return trim(_required_text(safe, name))
    if name == "wordCount":
        return word_count(_required_text(safe, name))
    if name == "regexMatch":
        return regex_match(_required_text(safe, name), _string_of(safe, "pattern"))
    if name == "timestamp":
        return timestamp(_string_of(safe, "unit"))
    if name == "nonce":
        return nonce(seed, _string_of(safe, "length"))
    if name == "sortConcat":
        return sort_concat(_required_text(safe, name), _string_of(safe, "separator"))
    if name == "hmacSha256":
        return hmac_sha256(_required_text(safe, name), _string_of(safe, "key"))
    raise GenkitCallError("genkit 未注册的函数 " + str(name))


def _seed_of(args: Dict[str, Any]) -> Optional[int]:
    seed = args.get("seed")
    if seed is None:
        return None
    if isinstance(seed, bool):
        raise GenkitCallError("seed 必须是数字，模板函数才能按种子复现")
    if isinstance(seed, (int, float)):
        return int(seed)
    try:
        return int(str(seed))
    except ValueError:
        raise GenkitCallError("seed 必须是数字，模板函数才能按种子复现")


def _string_of(args: Dict[str, Any], key: str) -> Optional[str]:
    value = args.get(key)
    return None if value is None else str(value)


def _required_text(args: Dict[str, Any], name: str) -> str:
    text = _string_of(args, "text")
    if text is None:
        raise GenkitCallError("函数 " + name + " 缺少必填参数 text")
    return text


def new_random_seed() -> int:
    pass
    return random.randrange(10 ** 15)
