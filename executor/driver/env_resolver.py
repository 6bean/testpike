

from __future__ import annotations

import json
import logging
import re
from typing import Any, Dict, Optional
from urllib.parse import urljoin

from driver.genkit import (
    GenkitCallError,
    RANDOM_FUNCTIONS,
    date_time,
    invoke as genkit_invoke,
    new_random_seed,
)

log = logging.getLogger("testpike.executor")

_ENV_PATTERN = re.compile(r"\{\{ENV:([A-Za-z_][A-Za-z0-9_]*)\}\}")

RESERVED_BASE_URL_KEYS = ("URL", "BASE_URL")


class EnvPlaceholderError(ValueError):
    pass

    def __init__(self, missing_keys: list) -> None:
        self.missing_keys = list(missing_keys)
        joined = "、".join(self.missing_keys)
        super().__init__(f"环境缺少参数 {joined}，请在项目环境参数中配置")


def _walk(node, subst):
    pass
    if isinstance(node, dict):
        return {k: _walk(v, subst) for k, v in node.items()}
    if isinstance(node, list):
        return [_walk(item, subst) for item in node]
    if isinstance(node, str):
        return subst(node)
    return node


def _substitute_string(text: str, vars: Dict[str, str],
                       env_base_url: Optional[str], missing: dict) -> str:
    pass

    def repl(m: re.Match) -> str:
        key = m.group(1)
        if key in vars:
            return str(vars[key])
        if env_base_url and key in RESERVED_BASE_URL_KEYS:
            return env_base_url
        missing[key] = None
        return m.group(0)

    return _ENV_PATTERN.sub(repl, text)


def resolve_placeholders(node, vars: Dict[str, str],
                         env_base_url: Optional[str] = None) -> Any:
    pass
    missing: dict = {}
    resolved = _walk(node,
                     lambda s: _substitute_string(s, vars or {}, env_base_url,
                                                  missing))
    if missing:
        raise EnvPlaceholderError(list(missing))
    return resolved


def resolve_url_template(url: str, vars: Dict[str, str],
                         env_base_url: Optional[str]) -> str:
    pass
    missing: dict = {}
    resolved = _substitute_string(url, vars or {}, env_base_url, missing)
    if missing:
        raise EnvPlaceholderError(list(missing))
    if env_base_url and resolved.startswith("/"):
        return urljoin(env_base_url.rstrip("/") + "/", resolved.lstrip("/"))
    return resolved


_VAR_PATTERN = re.compile(r"\{\{VAR:([A-Za-z0-9_][A-Za-z0-9_.\-]*)\}\}")

_SCOPED_KEY = r"[A-Za-z0-9_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5.\-]*"

_RUN_PATTERN = re.compile(r"\{\{RUN\.(" + _SCOPED_KEY + r")\}\}")

_GLOBAL_PATTERN = re.compile(r"\{\{global\.(" + _SCOPED_KEY + r")\}\}")


class VarUndefinedError(ValueError):
    pass

    def __init__(self, missing_vars: list) -> None:
        self.missing_vars = list(missing_vars)
        joined = "、".join(self.missing_vars)
        super().__init__(f"变量 {joined} 未定义（检查前序 saveAs）")


class VarStore:
    pass

    def __init__(self, initial: Optional[Dict[str, Any]] = None,
                 alias_map: Optional[Dict[str, str]] = None) -> None:
        self._values: Dict[str, Any] = dict(initial or {})
        self._alias_map: Dict[str, str] = dict(alias_map or {})

    def set(self, name: str, value: Any) -> None:
        if name in self._values:
            log.warning("变量 %s 重名覆盖（后存覆盖前，前值已弃）", name)
        self._values[name] = value

    def get(self, name: str) -> Any:
        return self._values[name]

    def lookup_alias(self, name: str) -> Optional[str]:
        pass
        return self._alias_map.get(name)

    def as_dict(self) -> Dict[str, Any]:
        pass
        return dict(self._values)

    def __contains__(self, name: str) -> bool:
        return name in self._values


def resolve_vars(node, store: "VarStore") -> Any:
    pass
    missing: dict = {}

    def repl_for(pattern):
        def repl(m: re.Match) -> str:
            name = store.lookup_alias(m.group(1)) or m.group(1)
            if name in store:
                return str(store.get(name))
            missing[name] = None
            return m.group(0)
        return repl

    def subst(text: str) -> str:
        text = _VAR_PATTERN.sub(repl_for(_VAR_PATTERN), text)
        return _RUN_PATTERN.sub(repl_for(_RUN_PATTERN), text)

    resolved = _walk(node, subst)
    if missing:
        raise VarUndefinedError(list(missing))
    return resolved


class GlobalVarUndefinedError(ValueError):
    pass

    def __init__(self, missing_keys: list) -> None:
        self.missing_keys = list(missing_keys)
        joined = "、".join(self.missing_keys)
        super().__init__(f"跨集合变量池中不存在键 {joined}，"
                         "请先在前序集合执行「保存为全局变量」步骤产出该键")


def resolve_global(node, store: "VarStore") -> Any:
    pass
    missing: dict = {}

    def subst(text: str) -> str:
        def repl(m: re.Match) -> str:
            key = m.group(1)
            prefixed = "global." + key
            if prefixed in store:
                return str(store.get(prefixed))
            if key in store:
                return str(store.get(key))
            missing[key] = None
            return m.group(0)
        return _GLOBAL_PATTERN.sub(repl, text)

    resolved = _walk(node, subst)
    if missing:
        raise GlobalVarUndefinedError(list(missing))
    return resolved


_ALIAS_HEAD = r"[A-Za-z0-9_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5\-]*"
_ALIAS_BARE_PATTERN = re.compile(
    r"\{\{(" + _ALIAS_HEAD + r")\.(" + _SCOPED_KEY + r")\}\}")

_ALIAS_RESERVED_HEADS = frozenset(
    {"ENV", "DATA", "PACK", "VAR", "SESSION", "RUN", "global", "CRED"})


def resolve_alias(node, store: "VarStore") -> Any:
    pass
    def subst(text: str) -> str:
        def repl(m: re.Match) -> str:
            head, field = m.group(1), m.group(2)
            if head in _ALIAS_RESERVED_HEADS:
                return m.group(0)
            canonical = store.lookup_alias(head)
            candidates: list = []
            if canonical is not None:
                candidates.append(canonical)
                field_canon = store.lookup_alias(field)
                if field_canon is not None:
                    candidates.append(canonical + "." + field_canon)
                candidates.append(canonical + "." + field)
            candidates.append(head + "." + field)
            for key in candidates:
                if key in store:
                    return str(store.get(key))
            return m.group(0)
        return _ALIAS_BARE_PATTERN.sub(repl, text)

    return _walk(node, subst)


_GEN_PATTERN = re.compile(r"\$\{gen\.([A-Za-z][A-Za-z0-9]*)(?:\((.*?)\))?\}")


def _parse_gen_args(text: str) -> dict:
    pass
    args: dict = {}
    current_key: Optional[str] = None
    for segment in text.split(","):
        m = re.match(r"^([A-Za-z][A-Za-z0-9]*)=(.*)$", segment, re.DOTALL)
        if m:
            current_key = m.group(1)
            args[current_key] = m.group(2)
        elif current_key is not None:
            args[current_key] += "," + segment
        elif segment.strip():
            raise GenkitCallError(
                f"gen 函数参数「{segment.strip()}」不是 k=v 形态，无法解析")
    return args


def resolve_gen(node, seed_events: list) -> Any:
    pass
    def subst(text: str) -> str:
        def repl(m: re.Match) -> str:
            name = m.group(1)
            args = _parse_gen_args(m.group(2) or "")
            if name in RANDOM_FUNCTIONS:
                if "seed" not in args:
                    args["seed"] = new_random_seed()
                seed_events.append({"fn": name, "seed": int(args["seed"])})
            return genkit_invoke(name, args)
        return _GEN_PATTERN.sub(repl, text)

    return _walk(node, subst)


_DATA_PATTERN = re.compile(r"\{\{DATA:([A-Za-z_][A-Za-z0-9_]*)\}\}")


class DataKeyMissingError(ValueError):
    pass

    def __init__(self, missing_keys: list) -> None:
        self.missing_keys = list(missing_keys)
        joined = "、".join(self.missing_keys)
        super().__init__(f"数据池中不存在键 {joined}，请在数据池补录该键名或修改用例引用")


def resolve_data(node, data_vars: Optional[Dict[str, str]] = None) -> Any:
    pass
    vars = data_vars or {}
    missing: dict = {}

    def subst(text: str) -> str:
        def repl(m: re.Match) -> str:
            key = m.group(1)
            if key in vars:
                return str(vars[key])
            missing[key] = None
            return m.group(0)
        return _DATA_PATTERN.sub(repl, text)

    resolved = _walk(node, subst)
    if missing:
        raise DataKeyMissingError(list(missing))
    return resolved


_SESSION_PATTERN = re.compile(r"\{\{SESSION:([A-Za-z_][A-Za-z0-9_]*)\}\}")


class SessionArtifactMissingError(ValueError):
    pass

    def __init__(self, missing_keys: list) -> None:
        self.missing_keys = list(missing_keys)
        joined = "、".join(self.missing_keys)
        super().__init__(f"会话工件中不存在键 {joined}，"
                         "请先执行登录步骤产出该工件（或确认异机搬运的工件仍在有效期内）")


def resolve_session(node, session_artifacts: Optional[Dict[str, str]] = None) -> Any:
    pass
    vars = session_artifacts or {}
    missing: dict = {}

    def subst(text: str) -> str:
        def repl(m: re.Match) -> str:
            key = m.group(1)
            if key in vars:
                return str(vars[key])
            missing[key] = None
            return m.group(0)
        return _SESSION_PATTERN.sub(repl, text)

    resolved = _walk(node, subst)
    if missing:
        raise SessionArtifactMissingError(list(missing))
    return resolved


# ------------------------------------------------------- mock template domain
# {{mock.*}} placeholders — executor mirror of common/genkit/MockTemplateResolver.java
# (AG design doc section 6.2 F5). Mirror discipline: the pattern literal, the
# closed whitelist and the fail-closed message family stay aligned with the
# Java side; tests/test_t358_mock_template pins the registry both ways.
# Single-parser red line: the mock namespace resolves first, then ${gen.*}
# rides the existing resolve_gen — one evaluator, no second template engine.
_MOCK_PATTERN = re.compile(r"\{\{mock\.([A-Za-z][A-Za-z0-9]*)([^{}]*)\}\}",
                           re.DOTALL)

# Closed namespace whitelist (mirror of Java MOCK_FUNCTIONS; new members must
# land in both mirrors in the same change).
MOCK_FUNCTIONS = ("pathVar", "query", "header", "body",
                  "now", "randomInt", "cycle", "session")

# Dot-key echo shape (header names may carry dashes, same as Java ECHO_KEY).
_MOCK_ECHO_KEY = re.compile(r"^\.([A-Za-z][A-Za-z0-9_.\-]*)$")

# Parenthesised call shape fn(args...).
_MOCK_CALL_ARGS = re.compile(r"^\((.*)\)$", re.DOTALL)

# Named argument k=v (same convention as the gen family).
_MOCK_NAMED = re.compile(r"^([A-Za-z][A-Za-z0-9]*)=(.*)$", re.DOTALL)


class MockTemplateError(ValueError):
    """Fail-closed rejection of a malformed mock placeholder: the message names
    the exact token and lists what is available — a raw token never leaks."""


def _mock_scalar_text(value):
    """JSON scalar to response text (mirrors Java JsonNode.asText: booleans
    lowercase, null renders as the literal "null")."""
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    return str(value)


def _mock_available(keys):
    shown = "、".join("「" + str(k) + "」" for k in keys)
    return shown or "（无）"


def _mock_split_top_level(raw):
    """Split on commas while respecting single quotes (dates and payloads may
    carry commas) — mirror of Java splitTopLevel."""
    segments = []
    current = ""
    in_quote = False
    for ch in (raw or ""):
        if ch == "'":
            in_quote = not in_quote
            current += ch
        elif ch == "," and not in_quote:
            segments.append(current)
            current = ""
        else:
            current += ch
    segments.append(current)
    return segments


def _mock_unquote(value, token):
    if not value.endswith("'") or len(value) < 2:
        raise MockTemplateError("mock 模板「" + token + "」参数引号不成对")
    inner = value[1:-1]
    if "'" in inner:
        raise MockTemplateError("mock 模板「" + token + "」参数引号不成对")
    return inner


def _mock_positional(rest, token, optional_call):
    """Positional arguments of a parenthesised call (quoted segments unwrapped;
    k=v named arguments are consumed separately)."""
    if not rest:
        if optional_call:
            return []
        raise MockTemplateError("mock 模板「" + token + "」需要括号参数，正确形态见提示")
    call = _MOCK_CALL_ARGS.match(rest)
    if not call:
        raise MockTemplateError("mock 模板「" + token + "」参数需写在括号内")
    positional = []
    for segment in _mock_split_top_level(call.group(1)):
        text = segment.strip()
        if not text:
            continue
        if text.startswith("'"):
            positional.append(_mock_unquote(text, token))
        elif _MOCK_NAMED.match(text):
            continue
        else:
            positional.append(text)
    return positional


def _mock_named(rest):
    named = {}
    call = _MOCK_CALL_ARGS.match(rest) if rest else None
    if not call:
        return named
    for segment in _mock_split_top_level(call.group(1)):
        m = _MOCK_NAMED.match(segment.strip())
        if m:
            named[m.group(1)] = m.group(2)
    return named


def _mock_int_bound(raw, field, token):
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError):
        raise MockTemplateError(
            "mock 模板「" + token + "」的 " + field + " 必须是整数，当前「"
            + str(raw) + "」") from None


def _mock_echo_key(rest, token, fn):
    m = _MOCK_ECHO_KEY.match(rest)
    if not m:
        raise MockTemplateError(
            "mock 模板「" + token + "」形态不合法：{{mock." + fn
            + ".键名}} 点号后需非空键名")
    return m.group(1)


def _mock_path_var(rest, token, env):
    key = _mock_echo_key(rest, token, "pathVar")
    value = env.path_vars.get(key)
    if value is None:
        raise MockTemplateError(
            "mock 模板引用「" + token + "」：本次命中未捕获路径变量 " + key
            + "（stub 的模板 path 需含 {" + key + "} 段）")
    return str(value)


def _mock_query(rest, token, env):
    key = _mock_echo_key(rest, token, "query")
    values = env.query.get(key)
    if not values:
        raise MockTemplateError(
            "mock 模板引用「" + token + "」：本次请求不带查询键 " + key
            + "，可用键：" + _mock_available(env.query.keys()))
    return str(values[0])


def _mock_header(rest, token, env):
    key = _mock_echo_key(rest, token, "header")
    value = env.headers.get(key.lower())
    if value is None:
        raise MockTemplateError(
            "mock 模板引用「" + token + "」：本次请求不带该请求头"
            "（键名判定不区分大小写）")
    return str(value)


def _mock_session(rest, token, env):
    # Lane echo : the resolved scenario lane the traffic plane injected
    # into the context headers; refuse to echo empty instead of pretending.
    value = env.headers.get("x-tp-mock-session")
    if value is None or str(value).strip() == "":
        raise MockTemplateError(
            "mock 模板引用「" + token + "」：本次请求未解析出仿真泳道"
            "（泳道由流量面按 请求头>Cookie>自动分配 的顺序解析后注入模板上下文）")
    return str(value)


def _mock_body(rest, token, env):
    from driver.jsonpath_subset import evaluate_json_path
    trimmed = (rest or "").strip()
    if not (trimmed.startswith("'") and trimmed.endswith("'")
            and len(trimmed) >= 3):
        raise MockTemplateError(
            "mock 模板「" + token + "」需单个带引号的取值路径，"
            "正确形态 {{mock.body '$.data.name'}}")
    path = trimmed[1:-1]
    if not env.body_json or not str(env.body_json).strip():
        raise MockTemplateError(
            "mock 模板引用「" + token + "」：本次请求无正文，无法取值")
    try:
        obj = json.loads(env.body_json)
    except (TypeError, ValueError):
        raise MockTemplateError(
            "mock 模板 body 取值要求请求正文为合法 JSON，当前无法解析") from None
    return _mock_scalar_text(evaluate_json_path(obj, path))


def _mock_now(rest, token, env):
    positional = _mock_positional(rest, token, True)
    if len(positional) > 1:
        raise MockTemplateError("mock 模板「" + token + "」至多一个日期格式参数")
    # genkit date_time owns the default pattern — one clock implementation
    # (same routing as the Java side through GenKit.dateTime)
    return date_time(positional[0] if positional else None)


def _mock_random_int(rest, token, env):
    positional = _mock_positional(rest, token, False)
    named = _mock_named(rest)
    if len(positional) != 2:
        raise MockTemplateError(
            "mock 模板「" + token + "」需两个整数边界，正确形态 {{mock.randomInt(1,99)}}")
    low = _mock_int_bound(positional[0], "min", token)
    high = _mock_int_bound(positional[1], "max", token)
    if low > high:
        raise MockTemplateError(
            "mock 模板「" + token + "」边界颠倒：min=" + str(low) + " > max=" + str(high))
    seed_raw = named.get("seed")
    if seed_raw is None or not str(seed_raw).strip():
        seed = new_random_seed()
    else:
        try:
            seed = int(str(seed_raw).strip())
        except ValueError:
            raise MockTemplateError(
                "mock 模板「" + token + "」的 seed 必须是数字（按种子复现），当前「"
                + str(seed_raw) + "」") from None
    env.seed_events.append({"fn": "randomInt", "seed": seed})
    import random as _random
    return str(_random.Random(seed).randint(low, high))


def _mock_cycle(rest, token, env):
    positional = _mock_positional(rest, token, False)
    if not positional or len(positional) > 2:
        raise MockTemplateError(
            "mock 模板「" + token + "」需一个列表引用（请求查询键多值）与可选步长，"
            "正确形态 {{mock.cycle('ids',5)}}")
    key = positional[0]
    step = _mock_int_bound(positional[1], "step", token) if len(positional) == 2 else 1
    if step < 1:
        raise MockTemplateError(
            "mock 模板「" + token + "」步长必须 ≥ 1，当前 " + str(step))
    values = env.query.get(key)
    if not values:
        raise MockTemplateError(
            "mock 模板 cycle 引用「" + key + "」：本次请求不带该查询键的多值列表，"
            "可用键：" + _mock_available(env.query.keys()))
    pos = env.cursors.get(token, 0)
    env.cursors[token] = pos + step
    return str(values[pos % len(values)])


_MOCK_BRANCHES = {
    "pathVar": _mock_path_var,
    "query": _mock_query,
    "header": _mock_header,
    "body": _mock_body,
    "now": _mock_now,
    "randomInt": _mock_random_int,
    "cycle": _mock_cycle,
    "session": _mock_session,
}


class _MockRenderEnv:
    """One render pass: the served-request snapshot plus per-reference cycle
    cursors (mirror of MockRequestContext + resolver state)."""

    def __init__(self, request, seed_events):
        request = request or {}
        self.path_vars = request.get("path_vars") or {}
        self.query = request.get("query") or {}
        self.headers = {str(k).lower(): v
                        for k, v in (request.get("headers") or {}).items()}
        self.body_json = request.get("body_json")
        self.seed_events = seed_events
        self.cursors = {}


def resolve_mock(node, request, seed_events: list) -> Any:
    """Recursively render {{mock.*}} placeholders against one served request.

    Echo semantics: the response references fields of this request. The
    request argument mirrors MockRequestContext — method / path / path_vars /
    query (key to values in arrival order) / headers (lowercase-normalised) /
    body_json (raw text or None). After the mock namespace resolves, ${gen.*}
    rides resolve_gen: one evaluator, and randomInt shares the {fn, seed}
    event sink with the gen random family (name + seed only, never values).
    Cycle cursors rotate per reference within one render; every malformed
    shape fails closed with a human-readable message naming what is available.
    """
    env = _MockRenderEnv(request, seed_events)

    def subst(text: str) -> str:
        def repl(m: re.Match) -> str:
            name = m.group(1)
            if name not in MOCK_FUNCTIONS:
                raise MockTemplateError(
                    "mock 模板未注册的函数「" + name + "」，当前可用函数："
                    + "、".join(MOCK_FUNCTIONS))
            return _MOCK_BRANCHES[name](m.group(2), m.group(0), env)
        rendered = _MOCK_PATTERN.sub(repl, text)
        return resolve_gen(rendered, seed_events)

    return _walk(node, subst)
