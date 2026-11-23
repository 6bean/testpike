
from __future__ import annotations

import json
import re
import sys
import time

import httpx

from driver.env_resolver import (
    VarStore,
    resolve_alias,
    resolve_gen,
    resolve_global,
    resolve_placeholders,
    resolve_session,
    resolve_vars,
)
from driver.expect_match import MATCH_TYPES, ExpectMatchError, in_range, match_regex
from driver.http_gate import HttpExecError, check_url_gates
from driver.jsonpath_subset import JsonPathError, evaluate_json_path
from driver.mock_route import (
    is_honest_miss,  # honest-miss single source (driver/mock_route)
)
from driver.snapshot import build_step_snapshot
from driver.tls_client import (
    MODE_CUSTOM_CA,
    MODE_INSECURE,
    TlsMaterial,
    http_tls_kwargs,
    normalize_policy,
)
from driver.webdriver import RunOutcome

ENVELOPE_KIND = "api-test-run"
# Supported auth types (W1c auth extension): merged into request headers/query after
# envelope decode, never persisted in the request definition. Crypto keys are constrained
# to @cred/ references by the save-gate contract; runtime sees resolved values only.
AUTH_SUPPORTED = ("bearer", "header", "basic", "api-key", "signature")
DEFAULT_TIMEOUT_MS = 30000
# stage B: WebSocket assertion vocabulary — executor-side mirror of
# common ApiWsContract.WS_ASSERTION_TYPES (save gate + assembler stay authoritative;
# this second gate refuses any non-ws row on the ws face, fail closed).
WS_ASSERTION_TYPES = ("ws.receive", "ws.order", "ws.frequency")
# Collection window default when a ws row omits withinMs (bounded by timeoutMs).
DEFAULT_WS_WINDOW_MS = 3000


def _parse_envelope(action_json: str) -> dict:
    pass
    try:
        envelope = json.loads(action_json or "")
    except Exception as e:
        raise ValueError(f"任务信封不是合法 JSON: {e}") from e
    if not isinstance(envelope, dict):
        raise TypeError("任务信封必须是 JSON 对象")
    if envelope.get("kind") != ENVELOPE_KIND:
        raise ValueError(f"任务信封 kind 非法（期望 {ENVELOPE_KIND}）: {envelope.get('kind')!r}")
    method = str(envelope.get("method") or "").strip().upper()
    if not method:
        raise ValueError("信封缺少 method（GET/POST/PUT/DELETE/PATCH）")
    url_path = str(envelope.get("urlPath") or "").strip()
    if not url_path:
        raise ValueError("信封缺少 urlPath（目标环境档案内相对路径）")
    lowered = url_path.lower()
    if "://" in lowered or lowered.startswith("//"):
        raise HttpExecError(
            f"目标地址必须是环境档案 base 内的相对路径（保存闸同语义执行侧复检，"
            f"禁任意绝对址）：{url_path!r}")
    rows = envelope.get("assertions")
    if not isinstance(rows, list):
        raise TypeError("断言组必须是数组（WS2 表单行列表）")
    return envelope


def _leftover_cred_refs(node) -> list:
    """Collect credential-reference literals still present in the auth tree.

    Mirror of the server-side CredPlaceholderInjector leftover-gate semantics
    (single dialect, red line: the executor never reverse-queries the vault).
    Catches both dialects the save gate accepts: whole/embedded @cred/ and {{CRED:.
    """
    found: list = []
    if isinstance(node, dict):
        for value in node.values():
            found.extend(_leftover_cred_refs(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_leftover_cred_refs(item))
    elif isinstance(node, str):
        if "@cred/" in node:
            found.append("@cred/…" + node.split("@cred/")[-1][:24])
        if "{{CRED:" in node:
            found.append("{{CRED:…" + node.split("{{CRED:")[-1][:24])
    return found


_CRED_KEY_EMBEDDED = re.compile(r"\{\{CRED:([A-Za-z_][A-Za-z0-9_]*)\}\}")
_CRED_KEY_WHOLE = re.compile(r"^@cred/([A-Za-z_][A-Za-z0-9_]*)$")
_CRED_KEY_FRAGMENT = re.compile(r"@cred/([A-Za-z_][A-Za-z0-9_]*)")


def _leftover_cred_ref_keys(node) -> list:
    """Collect credential-reference KEY NAMES left in a dispatched variable pool.

    Same dialect as the auth leftover gate above and the server-side
    CredPlaceholderInjector (single dialect rule). Only key names are returned,
    never surrounding values, so the error message stays zero-secret by
    construction: whole-string @cred/key plus embedded {{CRED:key}} forms (the
    identifier family mirrors the injector; non-matching text is not a vault
    reference at all).
    """
    found: list = []
    if isinstance(node, dict):
        for value in node.values():
            found.extend(_leftover_cred_ref_keys(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_leftover_cred_ref_keys(item))
    elif isinstance(node, str):
        whole = _CRED_KEY_WHOLE.match(node.strip())
        if whole:
            found.append(whole.group(1))
        else:
            found.extend(_CRED_KEY_EMBEDDED.findall(node))
            found.extend(_CRED_KEY_FRAGMENT.findall(node))
    return found


def _auth_headers(auth: dict, headers: dict) -> tuple[dict, dict]:
    """Merge auth into request headers (plus query pairs) for five types.

    Returns (headers, query_pairs). Unsupported types fail with an honest
    human-readable name instead of pretending success. Signature assembly rows
    carry templates already resolved fresh upstream by resolve_gen (single parser
    rule) — this function only injects resolved values.
    Credential references are gated upstream (_leftover_cred_refs before the gen
    parser), so every value reaching here is already resolved by the core
    assembly point.
    """
    if not auth:
        return headers, {}
    atype = str(auth.get("type") or "").strip().lower()
    if atype == "bearer":
        token = str(auth.get("token") or "").strip()
        if not token:
            raise HttpExecError("bearer 认证缺少 token（环境档案凭据未配置或下发缺失）")
        return {**headers, "Authorization": f"Bearer {token}"}, {}
    if atype == "header":
        name = str(auth.get("name") or "").strip()
        value = str(auth.get("value") or "")
        if not name:
            raise HttpExecError("header 认证缺少头名（auth.name）")
        return {**headers, name: value}, {}
    if atype == "basic":
        username = str(auth.get("username") or "")
        password = str(auth.get("password") or "")
        if not username or not password:
            raise HttpExecError(
                "basic 认证缺少 username 或 password（环境档案凭据未配置或下发缺失）")
        import base64
        packed = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        return {**headers, "Authorization": f"Basic {packed}"}, {}
    if atype == "api-key":
        name = str(auth.get("name") or "").strip()
        value = str(auth.get("value") or "")
        if not name:
            raise HttpExecError("api-key 认证缺少键名（auth.name）")
        placement = str(auth.get("placement") or "header").strip().lower()
        if placement == "query":
            return headers, {name: value}
        if placement == "header":
            return {**headers, name: value}, {}
        raise HttpExecError(
            f"api-key 注入位置「{placement}」不支持（仅 header / query）"
            "——请调整请求定义")
    if atype == "signature":
        rows = auth.get("assemblies")
        if not isinstance(rows, list) or not rows:
            raise HttpExecError(
                "signature 认证缺少装配行（auth.assemblies 至少一行 "
                "{into,name,value}，value=genkit 模板串）")
        merged_headers = dict(headers)
        merged_query: dict = {}
        for row in rows:
            if not isinstance(row, dict):
                raise HttpExecError(f"signature 装配行必须是对象（into/name/value），当前「{row!r}」")
            into = str(row.get("into") or "header").strip().lower()
            name = str(row.get("name") or "").strip()
            value = str(row.get("value") or "")
            if not name:
                raise HttpExecError("signature 装配行缺少 name（注入键名）")
            if into == "query":
                merged_query[name] = value
            elif into == "header":
                merged_headers[name] = value
            else:
                raise HttpExecError(
                    f"signature 装配位置「{into}」不支持（仅 header / query）")
        return merged_headers, merged_query
    raise HttpExecError(
        f"认证类型「{atype or '（空）'}」未支持（当前仅 "
        + " / ".join(AUTH_SUPPORTED)
        + " 注入）——请调整请求定义，勿假装无认证执行")


def _resolve_schema_doc(row: dict, contract_schemas: object, status: int):
    """Resolve the schema document a 'schema' assertion row judges against.

    inline = the pasted document (object or JSON text literal);
    contract = the envelope contractSchemas map keyed by response code — the
    row's explicit 'code' when given, otherwise the actual response status
    (mirrors the contract detail view). A missing source, unparseable text or
    unknown code raises HttpExecError: fail closed in plain words, because a
    schema that cannot be loaded must never silently pass (fake-green gate).
    """
    via = str(row.get("via") or "")
    if via == "inline":
        doc = row.get("schema")
        if isinstance(doc, str):
            try:
                doc = json.loads(doc)
            except ValueError:
                raise HttpExecError(
                    "inline JSON Schema 不可解析（粘贴内容不是合法 JSON）："
                    f"{doc[:60]}") from None
        if doc is None:
            raise HttpExecError("inline 结构校验断言缺少 schema 文档")
        return doc
    if not isinstance(contract_schemas, dict) or not contract_schemas:
        raise HttpExecError(
            "结构校验断言直引契约 schema，但信封未携带 contractSchemas"
            "（该端点未上传契约或契约无响应 schema）——请先上传接口契约，"
            "或改用 inline 粘贴 JSON Schema")
    code = row.get("code")
    key = str(status if code is None else code)
    doc = contract_schemas.get(key)
    if doc is None:
        raise HttpExecError(
            f"契约无响应码 {key} 的 schema（契约可用码："
            + " / ".join(sorted(str(k) for k in contract_schemas))
            + "）——请改直引码或改用 inline 粘贴")
    if isinstance(doc, str):
        try:
            doc = json.loads(doc)
        except ValueError:
            raise HttpExecError(
                f"契约码 {key} 的 schema 文本不可解析（JSON 已损坏）："
                f"{doc[:60]}") from None
    return doc


def _extract_captured_value(row: dict, payload_getter, resp: httpx.Response):
    """Resolve one extract row's value by its source (body / header / cookie).

    Returns ``(value, miss_hint)``. body keeps the legacy JsonPath lane (a bad or
    missing path raises JsonPathError upward = environment/contract family, same
    as before). header/cookie read by ``name``: an absent name on the row is a
    saved-shape mistake (fail closed, family 3), while a name not present on the
    response yields a miss hint so the caller fails the row honestly without
    storing None. A source outside the three-value enum never silently defaults.
    """
    source = str(row.get("source") or "body").strip().lower()
    if source not in ("body", "header", "cookie"):
        raise HttpExecError(
            f"提取来源 source 仅支持 body / header / cookie（缺省 body），"
            f"当前值「{source}」")
    if source == "body":
        return evaluate_json_path(payload_getter(), str(row.get("path"))), None
    name = str(row.get("name") or "").strip()
    if not name:
        raise HttpExecError(
            f"按 {source} 提取变量必须给定名称 name（例 name=X-Auth-Token）")
    actual = resp.headers.get(name) if source == "header" else resp.cookies.get(name)
    if actual is None:
        lane = "响应头" if source == "header" else "响应 Cookie"
        return None, f"{lane}「{name}」不存在（提取未落变量，后续引用将点名缺失）"
    return actual, None


def _judge_rows(rows: list, status: int, duration_ms: int, resp: httpx.Response,
                body_text: str, var_store: VarStore, url: str,
                session_capture: dict | None = None,
                contract_schemas: dict | None = None) -> list:
    pass
    detail_rows: list = []
    payload_cache: object = ...

    def _payload():
        nonlocal payload_cache
        if payload_cache is ...:
            try:
                payload_cache = json.loads(body_text or "")
            except ValueError:
                raise HttpExecError(
                    f"响应不是 JSON，无法按 JsonPath 判定/捕获：{url}"
                    "（Content-Type 或端点返回形态问题=环境问题）") from None
        return payload_cache

    for row in rows:
        if not isinstance(row, dict):
            raise TypeError(f"断言行必须是对象（type/op/expect 表单行），当前值「{row!r}」")
        rtype = str(row.get("type") or "")
        op = str(row.get("op") or "")
        expect = row.get("expect")
        entry = {"type": rtype, "op": op, "expect": expect, "passed": False, "actual": None}
        if rtype == "status":
            entry["actual"] = status
            entry["passed"] = status == int(expect)
        elif rtype == "duration":
            entry["actual"] = duration_ms
            pair = expect if isinstance(expect, list) else []
            lo = float(pair[0]) if pair and len(pair) >= 1 else None
            hi = float(pair[1]) if len(pair) >= 2 else (None if pair else 0.0)
            if not pair:
                raise ExpectMatchError("耗时断言 expect 必须是 [min,max] 数值对（例 [0,3000]）")
            entry["passed"] = in_range(float(duration_ms), lo, hi,
                                        str(row.get("bounds") or "closed"))
        elif rtype in ("body", "extract"):
            if rtype == "body":
                actual = evaluate_json_path(_payload(), str(row.get("path")))
                entry["passed"] = _match_op(op, str(actual), expect, row)
                entry["actual"] = str(actual)
            else:
                # Three-source extraction (AV correction row; source defaults to body
                # so legacy envelopes keep the JsonPath path with zero drift). A miss
                # on header/cookie fails the row honestly and stores nothing — a None
                # variable would fake a capture and poison later {{VAR}} references.
                actual, miss_hint = _extract_captured_value(row, _payload, resp)
                entry["actual"] = miss_hint if miss_hint else actual
                entry["passed"] = miss_hint is None
                if entry["passed"]:
                    var_store.set(str(row.get("saveAs")), actual)
                    as_session = str(row.get("asSession") or "").strip()
                    if as_session and session_capture is not None:
                        session_capture[as_session] = str(actual)
        elif rtype == "header":
            name = str(row.get("name") or "")
            actual = resp.headers.get(name)
            entry["actual"] = actual
            if actual is None:
                entry["passed"] = False
            else:
                entry["passed"] = _match_op(op, actual, expect, row)
        elif rtype == "schema":
            # Lazy import keeps the module load-free for non-schema suites;
            # jsonschema is a pinned pure-Python requirement (no venv conflict).
            from jsonschema import Draft202012Validator
            from jsonschema.exceptions import SchemaError
            doc = _resolve_schema_doc(row, contract_schemas, status)
            try:
                Draft202012Validator.check_schema(doc)
            except SchemaError as e:
                raise HttpExecError(
                    f"schema 文档本身非法（不是合法 JSON Schema）：{e.message}"
                    "（请修正 schema 来源，勿假装校验通过）") from None
            errors = sorted(Draft202012Validator(doc).iter_errors(_payload()),
                            key=lambda err: [str(p) for p in err.absolute_path])
            via = str(row.get("via") or "")
            entry["expect"] = ("inline 粘贴 schema" if via == "inline"
                               else f"契约 schema(码={row.get('code') if row.get('code') is not None else status})")
            entry["actual"] = ("符合" if not errors else
                               f"{list(errors[0].absolute_path) and '$.' + '.'.join(str(p) for p in errors[0].absolute_path) or '$'}: {errors[0].message}")
            entry["passed"] = not errors
        else:
            raise HttpExecError(
                f"断言类型「{rtype}」执行器未支持（当前支持 status/duration/body/header/extract/schema）")
        detail_rows.append(entry)
    return detail_rows


def _match_op(op: str, actual_text: str, expect, row: dict) -> bool:
    pass
    if op not in MATCH_TYPES:
        raise ExpectMatchError(
            f"op 非法值 {op!r}（白名单：{'、'.join(MATCH_TYPES)}，禁自由表达式）")
    if op == "range":
        pair = expect if isinstance(expect, list) else []
        if not pair:
            raise ExpectMatchError("区间断言 expect 必须是 [min,max] 数值对（例 [0,3000]）")
        lo = float(pair[0]) if pair[0] is not None else None
        hi = float(pair[1]) if len(pair) >= 2 and pair[1] is not None else None
        return in_range(_strip_number(actual_text), lo, hi,
                        str(row.get("bounds") or "closed"))
    if op == "regex":
        pattern = str(row.get("pattern") if row.get("pattern") is not None else expect)
        try:
            match_regex(actual_text, pattern)
        except ExpectMatchError:
            return False
        return True
    from driver.expect_match import match_text
    try:
        match_text(actual_text, str(expect), op == "exact")
    except ExpectMatchError:
        return False
    return True


def _strip_number(raw_text: str):
    pass
    from driver.expect_match import parse_range_number
    num = parse_range_number(raw_text, "")
    if num is None:
        raise ExpectMatchError(f"断言不满足：实际值「{raw_text}」不是数值，无法区间比较")
    return num


# --- orchestration vocabulary consumption (precondition / loop) ---
# Single-source mirror of common ApiOrchestrationContract domains. The core save
# gate already refuses out-of-domain rows; this is the second gate (a hand-built
# or stale envelope must fail closed here, never silently run or silently pass).

_PRE_STATUS_KEY = "last_status"  # pool key the core assembler/run chain maintains
_PRE_FIELDS = ("status", "var")
_STATUS_OPS = ("eq", "ne", "in")
_VAR_OPS = ("exists", "absent", "eq", "ne", "matches")
_LOOP_MODES = ("count", "until")


def _validate_precondition_row(row, where: str) -> None:
    """Vocabulary guard for one precondition row (illegal shape raises ValueError = class 3)."""
    if not isinstance(row, dict):
        raise ValueError(f"{where}必须是对象（field/op/expect 表单行），当前值「{row!r}」")
    field = str(row.get("field") or "")
    if field not in _PRE_FIELDS:
        raise ValueError(f"{where}字段仅支持 status / var（上一跳状态码 / 变量池键名），"
                         f"当前值「{field}」")
    op = str(row.get("op") or "")
    ops = _STATUS_OPS if field == "status" else _VAR_OPS
    if op not in ops:
        raise ValueError(f"{where}（field={field}）仅支持 {' / '.join(ops)}，当前值「{op}」")


def _precondition_row_met(row, status, var_store) -> bool:
    """Evaluate one precondition row against the last status / variable pool.

    status=None means the previous-hop status is unknown (undecidable — the
    caller turns that into an honest skip, never a fake verdict). Values from
    the pool arrive as strings (ENC-decrypted wire form) — compare textually
    for eq/ne/matches, and coerce status expectations to int.
    """
    _validate_precondition_row(row, "前置条件")
    field = str(row.get("field"))
    op = str(row.get("op"))
    expect = row.get("expect")
    if field == "status":
        if status is None:
            raise _PreconditionUndecidable(
                "前置条件判定需要上一跳状态码，但本跳是集合首跳且无上一跳状态——诚实跳过")
        if op == "in":
            if not isinstance(expect, list) or not expect:
                raise ValueError("状态码 in 前置条件的 expect 必须是非空整数数组，例 [200, 201]")
            return any(int(status) == int(e) for e in expect)
        same = int(status) == int(expect)
        return same if op == "eq" else not same
    name = str(row.get("name") or "")
    pool = var_store.as_dict()
    if op == "exists":
        return name in pool
    if op == "absent":
        return name not in pool
    actual = str(pool.get(name, ""))
    if op == "eq":
        return actual == str(expect)
    if op == "ne":
        return actual != str(expect)
    # matches: delegate the operator grammar to the expect_match single source
    return _match_op(str(row.get("matchType") or "contains"), actual, expect, row)


class _PreconditionUndecidable(Exception):
    """Raised when a status precondition has no previous-hop status to read."""


def _loop_policy(loop_row) -> tuple:
    pass
    if not isinstance(loop_row, dict):
        raise ValueError(f"循环配置必须是对象（mode/times/until），当前值「{loop_row!r}」")
    mode = str(loop_row.get("mode") or "")
    if mode not in _LOOP_MODES:
        raise ValueError(f"循环模式仅支持 count / until（固定次数 / 条件停止），当前值「{mode}」")
    delay_raw = loop_row.get("delayMs") or 0
    delay_ms = int(delay_raw)
    if delay_ms < 0 or delay_ms > 3_600_000:
        raise ValueError("循环间隔 delayMs 必须是 0~3600000 的整数毫秒")
    if mode == "count":
        times = int(loop_row.get("times") or 0)
        if times < 1 or times > 100:
            raise ValueError("固定次数循环 times 必须在 1~100 之间，当前值「" + str(times) + "」")
        return mode, times, None, delay_ms
    max_times = int(loop_row.get("maxTimes") or 0)
    if max_times < 1 or max_times > 100:
        raise ValueError("条件停止循环必须给出整数 maxTimes 保护上限（1~100），"
                         "防止永不满足时停不下来")
    until = loop_row.get("until")
    if not isinstance(until, dict) or not until:
        raise ValueError("条件停止循环必须给出 until 停止条件行（field/op/expect 表单行）")
    _validate_precondition_row(until, "循环停止条件")
    return mode, max_times, until, delay_ms


def _pool_last_status(var_store) -> int | None:
    """Previous-hop status from the variable pool (None = undecidable)."""
    raw = var_store.as_dict().get(_PRE_STATUS_KEY)
    if raw is None or str(raw).strip() == "":
        return None
    try:
        return int(str(raw).strip())
    except ValueError:
        return None


def _precondition_skip_reason(row, var_store) -> str | None:
    """Skip verdict for the request-level precondition row (None = run it).

    An undecidable status row (no previous-hop value in the pool) and an
    unsatisfied row both answer with a human-readable skip reason — the
    executor sends zero requests and fakes no verdict. Vocabulary violations
    propagate as ValueError (error class 3, fail closed).
    """
    try:
        met = _precondition_row_met(row, _pool_last_status(var_store), var_store)
    except _PreconditionUndecidable as e:
        return str(e)
    if met:
        return None
    return (f"前置条件不满足（field={row.get('field')} op={row.get('op')} "
            f"expect={row.get('expect')}）——本跳诚实跳过，未发送请求")


def _first_fail_message(rows: list, status: int, duration_ms: int, url: str,
                        method: str) -> str:
    pass
    for row in rows:
        if not row["passed"]:
            if row["type"] == "status":
                return (f"断言不满足：实际 status={status} 期望 status={row['expect']}"
                        f"（{method} {url}）")
            return (f"断言不满足：{row['type']} 行期望「{row['expect']}」，"
                    f"实际「{row['actual']}」（{method} {url}）")
    return (f"断言不满足：耗时 {duration_ms}ms 不在期望区间"
            f"（{method} {url}）")


# --- stage B: WebSocket consumption branch (envelope protocol="ws") ---
# Mirrors the common ApiWsContract vocabulary at the consumer side (second gate,
# no second verdict copy): the save gate and the core assembler remain the
# authorities, this face refuses anything the contract does not name. Honesty
# rule (W1c fix-3): a ws run without assertions is judged by reachability only —
# an established handshake is never reported as "assertions passed".

def _ws_url(env_base: str, url_path: str) -> str:
    """Compose the ws target from the environment base (scheme swapped only)."""
    base = (env_base or "").strip()
    low = base.lower()
    if low.startswith("https://"):
        ws_base = "wss://" + base[len("https://"):]
    elif low.startswith("http://"):
        ws_base = "ws://" + base[len("http://"):]
    elif low.startswith(("ws://", "wss://")):
        ws_base = base
    else:
        raise HttpExecError(
            f"环境 base「{base}」不是 http/https/ws 起点，无法建立 WebSocket 连接"
            "（请检查环境档案 base 配置）")
    return ws_base.rstrip("/") + "/" + str(url_path).lstrip("/")


def _ws_ssl_context(tls_policy: dict | None, material: TlsMaterial,
                    url: str) -> object | None:
    """wss TLS material resolution mirroring the http face (same fail-closed).

    Plain ws:// needs no context; insecure skips verification (stamped in the
    snapshot, never silent); custom_ca without CA material fails closed.
    """
    if not url.lower().startswith("wss://"):
        return None
    import ssl as _ssl
    policy = normalize_policy(tls_policy)
    if policy["mode"] == MODE_INSECURE:
        ctx = _ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = _ssl.CERT_NONE
        return ctx
    if policy["mode"] == MODE_CUSTOM_CA:
        if not material.ca_path:
            raise HttpExecError(
                "TLS 决议为 custom_ca 但未随 claim 下发 CA 材料（wss 链路）"
                "——拒绝按默认档放行，请检查环境档案证书配置")
        return _ssl.create_default_context(cafile=material.ca_path)
    return _ssl.create_default_context()


def _ws_collect(ws, window_ms: int, sink: list, *, want: int | None = None,
                predicate=None) -> None:
    """Drain the socket into `sink` until the window closes / want reached / hit.

    ConnectionClosedOK = the server ended the stream (normal, stop quietly);
    ConnectionClosedError = transport broke (class 3, honest); TimeoutError =
    the measurement window elapsed (normal end for frequency/receive rows).
    """
    from websockets.exceptions import ConnectionClosedError, ConnectionClosedOK
    deadline = time.monotonic() + max(window_ms, 1) / 1000.0
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        try:
            message = ws.recv(timeout=remaining)
        except TimeoutError:
            return
        except ConnectionClosedOK:
            return
        except ConnectionClosedError as e:
            raise HttpExecError(
                f"WebSocket 连接被服务端异常断开（{e}）——被测服务不稳定或已重启"
            ) from e
        sink.append(str(message))
        if want is not None and len(sink) >= want:
            return
        if predicate is not None and predicate(sink[-1]):
            return


def _judge_ws_rows(rows: list, ws, started: float, budget_ms: int,
                   url: str) -> list:
    """Judge ws assertion rows against the live socket (three-type vocabulary)."""
    detail_rows: list = []
    received_all: list = []
    for row in rows:
        if not isinstance(row, dict):
            raise TypeError(f"断言行必须是对象（type/op/expect 表单行），当前值「{row!r}」")
        rtype = str(row.get("type") or "")
        if rtype not in WS_ASSERTION_TYPES:
            raise HttpExecError(
                f"断言类型「{rtype}」不适用于 WebSocket 传输面"
                f"（当前仅 {' / '.join(WS_ASSERTION_TYPES)}；HTTP 型断言请切回 http 协议）")
        window_raw = row.get("withinMs")
        window_ms = int(window_raw) if window_raw not in (None, "") else DEFAULT_WS_WINDOW_MS
        if window_ms < 1 or window_ms > budget_ms:
            raise ExpectMatchError(
                f"ws 断言窗口 withinMs 必须在 1~{budget_ms}ms（信封 timeoutMs 预算）内，"
                f"当前值「{window_ms}」")
        expect = row.get("expect")
        entry = {"type": rtype, "op": str(row.get("op") or ""),
                 "expect": expect, "passed": False, "actual": None}
        if rtype == "ws.receive":
            op = entry["op"] or "contains"
            if op not in MATCH_TYPES:
                raise ExpectMatchError(
                    f"op 非法值 {op!r}（白名单：{'、'.join(MATCH_TYPES)}，禁自由表达式）")
            hit_at = len(received_all)

            def _hit(text: str, _op=op, _expect=expect, _row=row) -> bool:
                return _match_op(_op, text, _expect, _row)

            _ws_collect(ws, window_ms, received_all, predicate=_hit)
            new_slice = received_all[hit_at:]
            matched = next((t for t in new_slice if _hit(t)), None)
            entry["passed"] = matched is not None
            entry["actual"] = (matched if matched is not None else
                               f"窗口 {window_ms}ms 内收到 {len(new_slice)} 条未命中")
        elif rtype == "ws.order":
            if not isinstance(expect, list) or not expect:
                raise ExpectMatchError(
                    "ws.order 的 expect 必须是非空字符串数组（期望到达序列）")
            want_n = len(expect)
            mark = len(received_all)
            _ws_collect(ws, window_ms, received_all, want=want_n)
            got = received_all[mark:mark + want_n]
            entry["passed"] = got == [str(e) for e in expect]
            entry["actual"] = got
        else:  # ws.frequency
            try:
                want_count = int(expect)
            except (TypeError, ValueError):
                raise ExpectMatchError(
                    f"ws.frequency 的 expect 必须是整数（窗口内最少消息条数），当前「{expect!r}」")
            mark = len(received_all)
            _ws_collect(ws, window_ms, received_all, want=want_count)
            count = len(received_all) - mark
            entry["passed"] = count >= want_count
            entry["actual"] = count
        detail_rows.append(entry)
    return detail_rows


def _first_ws_fail_message(rows: list, url: str) -> str:
    """Human summary naming the first unsatisfied ws row (no truncation)."""
    for row in rows:
        if not row["passed"]:
            return (f"WS 断言不满足：{row['type']} 行期望「{row['expect']}」，"
                    f"实际「{row['actual']}」（{url}）")
    return f"WS 断言不满足（{url}）"


def _run_ws_task(envelope: dict, env_base: str, var_store: VarStore,
                 started: float, session_consumed: bool) -> RunOutcome:
    """Execute the WebSocket leg of an api-test-run envelope (protocol=ws).

    Connects for real (websockets sync client), sends the optional body message,
    judges ws.* rows against the live stream and returns the verdict snapshot.
    No ws assertion = pure reachability verdict (honesty red line: an open
    socket is a pass on connectivity only, never on asserted content).
    """
    from websockets.sync.client import connect
    from websockets.exceptions import InvalidStatus

    url = _ws_url(env_base, envelope["urlPath"])
    policy = check_url_gates(envelope.get("execPolicy"), env_base, url)
    headers, auth_query = _auth_headers(dict(envelope.get("auth") or {}),
                                        dict(envelope.get("headers") or {}))
    if auth_query:
        import urllib.parse as _up
        url = url + ("&" if "?" in url else "?") + _up.urlencode(auth_query)
    material = TlsMaterial(normalize_policy(envelope.get("tlsPolicy")))
    timeout_ms = int(envelope.get("timeoutMs") or policy.get("timeoutMs")
                     or DEFAULT_TIMEOUT_MS)
    try:
        ssl_ctx = _ws_ssl_context(envelope.get("tlsPolicy"), material, url)
        try:
            ws = connect(url, additional_headers=headers or None,
                         ssl=ssl_ctx,
                         open_timeout=max(1.0, min(timeout_ms / 1000.0, 30.0)))
        except InvalidStatus as e:
            raise HttpExecError(
                f"WebSocket 握手被目标服务拒绝（{e}）——{url}"
                "（路径不存在或服务未开放 ws 端点，环境问题）") from e
        except OSError as e:
            raise HttpExecError(
                f"连接 {url} 失败（被测服务不可达或连接被拒绝）"
                "——环境问题请检查网络/服务在线") from e
        with ws:
            body = envelope.get("body")
            if body is not None and body != "":
                ws.send(body if isinstance(body, str)
                        else json.dumps(body, ensure_ascii=False))
            rows = _judge_ws_rows(list(envelope.get("assertions") or []),
                                  ws, started, timeout_ms, url)
    finally:
        material.cleanup()
    duration = int((time.monotonic() - started) * 1000)
    request_view = {"url": url, "headers": headers, "body": body}
    response_view = {"url": url}
    snapshot = build_step_snapshot(1, "api-test-ws-run", request_view,
                                   response_view, rows)
    extracted = dict(var_store.as_dict())
    if rows and not all(r["passed"] for r in rows):
        return RunOutcome(result="fail", error_type=1,
                          error_detail=_first_ws_fail_message(rows, url),
                          duration_ms=duration, protocol_snapshots=[snapshot],
                          extracted_vars=extracted)
    return RunOutcome(result="pass", duration_ms=duration,
                      protocol_snapshots=[snapshot], extracted_vars=extracted)


def run_api_task(action_json: str, session_artifacts: dict | None = None,
                 step_aliases: dict | None = None) -> RunOutcome:
    pass
    started = time.monotonic()
    session_capture: dict = {}
    gen_events: list = []
    session_consumed = "{{SESSION:" in (action_json or "")
    try:
        envelope = _parse_envelope(action_json)
        env_base = str(envelope.get("envBaseUrl") or "")
 # (03 trailing key): a run whose case declares a mock-space
        # binding carries envelope mock={spaceKey,baseUrl,snapshotVersion}; the
        # target base is rewritten to the traffic-plane prefix BEFORE placeholder
        # resolution so {{ENV:*}} reads resolve through the stand-in too (the
        # "environment-variable substitute" ruling — the authoritative
        # tp_project_env row is never touched, the rewrite is envelope-local).
        # No key = zero drift (old envelope shape unchanged). A dirty mock
        # segment fails closed: silently running against the real environment
        # would misreport the tested scope.
        mock = envelope.pop("mock", None)
        mock_notes: list = []
        if mock is not None:
            if not isinstance(mock, dict):
                raise ValueError(
                    "挂载仿真空间的 mock 信封段形态非法（应为对象）：" + repr(mock)[:120])
            space_key = str(mock.get("spaceKey") or "").strip()
            mock_base = str(mock.get("baseUrl") or "").strip()
            if not space_key or not mock_base:
                raise ValueError(
                    "挂载仿真空间的 mock 信封段缺少 spaceKey/baseUrl——"
                    "禁静默回退真实环境（会谎报测试范围），请联系 core 版本与派发链")
            env_base = mock_base.rstrip("/")
        env_vars = dict(envelope.get("envVars") or {})
        envelope = resolve_placeholders(envelope, env_vars, env_base or None)
        # Variable-pool credential leftover gate (mirror of the auth gate below):
        # the core assembly point injects vault references inside the dispatched
        # pool with the same single injector; a leftover @cred/ or {{CRED:}}
        # literal here means that chain broke. The pool is flattened into the
        # variable store and consumed into the request, so the scan must run
        # BEFORE consumption. Names the referenced keys only (zero secret value),
        # and the executor never reverse-queries the vault.
        pool_leftover = _leftover_cred_ref_keys(envelope.get("vars") or {})
        if pool_leftover:
            raise HttpExecError(
                "变量池存在未解析的凭据引用（键：" + "、".join(sorted(set(pool_leftover)))
                + "）——凭据实值应由派发前服务端装配点注入，请检查这些键在凭据保险箱的"
                  "建档/启用状态后重跑，执行器禁反查保险箱")
        var_store = VarStore(dict(envelope.get("vars") or {}),
                             alias_map=dict(step_aliases or {}))
        envelope.pop("vars", None)
        envelope = resolve_vars(envelope, var_store)
        envelope = resolve_global(envelope, var_store)
        envelope = resolve_alias(envelope, var_store)
        # Credential leftover gate: @cred/ and {{CRED:} references inside the auth
        # section are resolved by the core assembly point before dispatch; seeing a
        # literal here means the dispatch chain broke (server skipped injection or
        # the envelope bypassed the single assembler). Scanned BEFORE resolve_gen —
        # a {{CRED:sk}} nested in a gen template would otherwise be swallowed as a
        # literal key into the digest, checking after that point would be fake-green.
        # The executor never reverse-queries the vault: fail closed naming the
        # reference with zero secret value (mirror of the mobile-chain leftover gate).
        leftover = _leftover_cred_refs(envelope.get("auth"))
        if leftover:
            raise HttpExecError(
                "认证配置存在未解析的凭据引用（" + "、".join(sorted(set(leftover)))
                + "）——凭据实值应由派发前服务端装配点注入，请检查该键在凭据保险箱的"
                  "建档/启用状态后重跑，执行器禁反查保险箱")
        envelope = resolve_gen(envelope, gen_events)
        if session_consumed:
            envelope = resolve_session(envelope, session_artifacts or {})
        # step 3 (03 trailing keys precondition/loop): the
        # precondition gates the whole request — an unsatisfied row skips this
        # hop honestly (manual_skip, zero request sent), and the loop row sets
        # the replay policy around the send. Absent keys = single shot (legacy
        # envelope shape, byte-identical behavior).
        precondition = envelope.pop("precondition", None)
        loop_row = envelope.pop("loop", None)
        if precondition is not None:
            skip_reason = _precondition_skip_reason(precondition, var_store)
            if skip_reason is not None:
                return RunOutcome(result="manual_skip", error_detail=skip_reason,
                                  duration_ms=int((time.monotonic() - started) * 1000),
                                  extracted_vars=dict(var_store.as_dict()),
                                  gen_seeds=list(gen_events))
        mode, budget, until_row, delay_ms = (
            _loop_policy(loop_row) if loop_row is not None else ("count", 1, None, 0))
        loop_stats = {"loop_count": 1 if loop_row is not None else 0}
        # stage B: transport branch (envelope protocol trailing key).
        # Absent/blank/http = the legacy path byte-identical (zero drift); ws =
        # real WebSocket session; anything else fails closed naming the value
        # (never silently run as HTTP — vocabulary mirror of ApiWsContract).
        protocol = str(envelope.get("protocol") or "").strip().lower() or "http"
        if protocol == "ws":
            if loop_row is not None:
                raise HttpExecError(
                    "WebSocket 传输面本期不支持循环回放（loop 仅适用 http）"
                    "——请移除循环配置或切回 http 协议")
            return _run_ws_task(envelope, env_base, var_store, started,
                                session_consumed)
        if protocol != "http":
            raise HttpExecError(
                f"传输协议「{protocol}」执行器未支持（当前仅 http / ws）"
                "——拒绝按 HTTP 假装执行，请检查协议配置")
        attempt = 0
        while True:
            attempt += 1
            if attempt > 1 and delay_ms:
                time.sleep(delay_ms / 1000.0)
            url_path = str(envelope["urlPath"])
            url = (env_base.rstrip("/") + "/" + url_path.lstrip("/")
                   if env_base else url_path)
            policy = check_url_gates(envelope.get("execPolicy"), env_base or None, url)
            # auth merge returns (headers, query_pairs); query placement keys are
            # appended to the gated url here (gates only look at host/path, query
            # never relaxes them).
            headers, auth_query = _auth_headers(dict(envelope.get("auth") or {}),
                                                dict(envelope.get("headers") or {}))
            if auth_query:
                import urllib.parse as _up
                url = url + ("&" if "?" in url else "?") + _up.urlencode(auth_query)
            tls_policy = normalize_policy(envelope.get("tlsPolicy"))
            material = TlsMaterial(tls_policy)
            timeout_ms = int(envelope.get("timeoutMs") or policy.get("timeoutMs")
                             or DEFAULT_TIMEOUT_MS)
            body = envelope.get("body")
            request_view = {"method": str(envelope["method"]).upper(), "url": url,
                            "headers": headers, "body": body}
            try:
                resp = httpx.request(str(envelope["method"]).upper(), url,
                                     headers=headers,
                                     json=body if isinstance(body, (dict, list)) else None,
                                     content=body if isinstance(body, str) else None,
                                     timeout=timeout_ms / 1000.0,
                                     follow_redirects=False,
                                     **http_tls_kwargs(tls_policy, material, url))
            except httpx.ConnectError as e:
                raise HttpExecError(
                    f"连接 {url} 失败（被测服务不可达或连接被拒绝）"
                    "——环境问题请检查网络/服务在线") from e
            except httpx.TimeoutException as e:
                # connect-phase timeouts (ConnectTimeout, incl. WSL/firewall silent
                # drops) share the unreachable wording; read timeouts name the budget
                kind = ("连接超时：被测服务不可达或被静默丢包"
                        if isinstance(e, httpx.ConnectTimeout)
                        else f"单请求超过 {timeout_ms}ms 未返回（exec 预算可配）")
                raise HttpExecError(
                    f"{kind}——环境问题（{url}）") from e
            except httpx.HTTPError as e:
                # remaining transport errors (ReadError family) also map to the
                # unreachable wording; never leak the exception class name
                raise HttpExecError(
                    f"请求 {url} 传输失败（被测服务响应异常或连接中断）"
                    "——环境问题请检查网络/服务在线") from e
            finally:
                material.cleanup()
            duration = int((time.monotonic() - started) * 1000)
            body_text = resp.text
            # Loop replay: count mode runs out its full budget; until mode keeps
            # going while the stop row is unsatisfied and the maxTimes guard
            # still allows (the guard bounds a never-satisfied condition).
            if attempt < budget and (until_row is None or not
                                     _precondition_row_met(until_row,
                                                           resp.status_code, var_store)):
                continue
            break
        loop_stats["loop_iterations"] = attempt
        # (report honesty face): a bound run stamps one audit
        # note per served answer, carrying the traffic plane's provenance
        # header verbatim (X-TP-Mock-Source: llm = the AI fallback answer;
        # plain stubs stay unlabeled). The core report face counts the llm
        # dimension out of these rows — an AI guess must never be tallied
        # as a deterministic mock answer.
        if mock is not None:
            if is_honest_miss(resp.status_code, body_text):
                mock_notes.append({"path": url, "mode": "passthrough"})
            else:
                note = {"path": url,
                        "mode": "stub" if resp.status_code < 400 else "fault",
                        "status": resp.status_code}
                provenance = resp.headers.get("X-TP-Mock-Source")
                if provenance:
                    note["source"] = provenance
                mock_notes.append(note)
        response_view = {"status": resp.status_code, "url": str(resp.url),
                         "headers": dict(resp.headers), "body": body_text}
        rows = _judge_rows(list(envelope.get("assertions") or []), resp.status_code,
                           duration, resp, body_text, var_store, url,
                           session_capture=session_capture,
                           contract_schemas=envelope.get("contractSchemas"))
        snapshot = build_step_snapshot(1, "api-test-run", request_view, response_view,
                                       rows, tls_mode=tls_policy["mode"])
        # publish this hop's status as last_status so the next
        # hop's precondition row can decide run-vs-skip over the shared pool.
        var_store.set(_PRE_STATUS_KEY, str(resp.status_code))
        extracted = dict(var_store.as_dict())
        if session_consumed and resp.status_code in (401, 403) and \
                not any(r["type"] == "status" and r["expect"] in (401, 403)
                        for r in rows):
            return RunOutcome(
                result="fail", error_type=4,
                error_detail="会话工件失效，重登续跑：跨机搬运的登录工件被被测拒绝"
                             f"（HTTP {resp.status_code}），请重跑含登录步骤的用例"
                             "以刷新批次会话工件",
                duration_ms=duration, protocol_snapshots=[snapshot],
                extracted_vars=extracted, session_artifacts=dict(session_capture),
                gen_seeds=list(gen_events),
                mock_notes=mock_notes or None)
        all_passed = all(r["passed"] for r in rows)
        if all_passed:
            return RunOutcome(result="pass", duration_ms=duration,
                              protocol_snapshots=[snapshot], extracted_vars=extracted,
                              session_artifacts=dict(session_capture),
                              gen_seeds=list(gen_events),
                              loop_count=loop_stats["loop_count"],
                              loop_iterations=loop_stats["loop_iterations"],
                              mock_notes=mock_notes or None)
        return RunOutcome(result="fail", error_type=1,
                          error_detail=_first_fail_message(
                              rows, resp.status_code, duration, url,
                              str(envelope["method"]).upper()),
                          duration_ms=duration, protocol_snapshots=[snapshot],
                          extracted_vars=extracted,
                          session_artifacts=dict(session_capture),
                          gen_seeds=list(gen_events),
                          loop_count=loop_stats["loop_count"],
                          loop_iterations=loop_stats["loop_iterations"],
                          mock_notes=mock_notes or None)
    except ExpectMatchError as e:
        return RunOutcome(result="fail", error_type=1, error_detail=str(e),
                          duration_ms=int((time.monotonic() - started) * 1000))
    except HttpExecError as e:
        return RunOutcome(result="fail", error_type=3, error_detail=str(e),
                          duration_ms=int((time.monotonic() - started) * 1000))
    except (ValueError, TypeError, JsonPathError) as e:
        return RunOutcome(result="fail", error_type=3,
                          error_detail=f"任务信封/执行前置非法: {e}",
                          duration_ms=int((time.monotonic() - started) * 1000),
                          gen_seeds=list(gen_events))
    except Exception as e:
        return RunOutcome(result="fail", error_type=3,
                          error_detail=f"API 执行异常: {e}",
                          duration_ms=int((time.monotonic() - started) * 1000))


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("用法: api_runner.py <task.json> <outcome.json>", file=sys.stderr)
        return 2
    task_f, out_f = argv[1], argv[2]
    try:
        with open(task_f, "r", encoding="utf-8") as f:
            task = json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"任务文件读取失败: {e}", file=sys.stderr)
        return 2

    try:
        outcome = run_api_task(str(task.get("actionJson") or ""),
                               dict(task.get("sessionArtifacts") or {}),
                               dict(task.get("stepAliases") or {}))
    except Exception as e:
        outcome = RunOutcome(result="fail", error_type=3, error_detail=f"API 执行异常: {e}")

    try:
        with open(out_f, "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        print(f"结果文件写入失败: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
