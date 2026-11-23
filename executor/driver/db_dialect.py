
from __future__ import annotations

import logging
import re
import time
from collections import OrderedDict
from typing import Any, Dict, List, Optional, Tuple

from driver.expect_match import (ExpectMatchError, match_range, match_regex,
                                 match_text)

log = logging.getLogger("driver.db_dialect")

READ_ONLY_FIRST_WORDS = ("SELECT",)
EXEC_FIRST_WORDS = ("INSERT", "UPDATE", "DELETE")
EXEC_FORBIDDEN_TOKENS = ("DROP", "TRUNCATE", "ALTER", "GRANT", "REVOKE", "CREATE")
EXEC_ACTION = "sql.exec"

_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_NAMED_PARAM = re.compile(r"(?<!:):([A-Za-z_]\w*)")
_FORBIDDEN_TOKEN = re.compile(r"\b(" + "|".join(EXEC_FORBIDDEN_TOKENS) + r")\b")
_WHERE_CLAUSE = re.compile(r"\bWHERE\b")

DEFAULT_INTERVAL_MS = 2000
MIN_INTERVAL_MS = 1000
DEFAULT_TIMEOUT_SECONDS = 30
CONNECT_TIMEOUT_SECONDS = 5
DEFAULT_MAX_POOL = 2
DEFAULT_MAX_ROWS = 1000

_EXPECT_TYPES = ("scalar", "row_count", "columns")


class DbExecError(ValueError):
    pass



def _strip_literals_and_comments(sql: str) -> str:
    pass
    out: List[str] = []
    i, n = 0, len(sql)
    while i < n:
        c = sql[i]
        if c in ("'", '"'):
            quote = c
            i += 1
            while i < n:
                if sql[i] == quote:
                    if i + 1 < n and sql[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            out.append(" ")
        elif c == "-" and sql.startswith("--", i):
            eol = sql.find("\n", i)
            i = n if eol < 0 else eol
        elif c == "/" and sql.startswith("/*", i):
            end = sql.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _split_statements(stripped: str) -> List[str]:
    return [p.strip() for p in stripped.split(";") if p.strip()]


def _first_word(statement: str) -> str:
    m = _WORD.search(statement)
    return m.group().upper() if m else ""


def validate_sql_step(action: str, sql: Optional[str],
                      params: Optional[Dict[str, Any]] = None) -> None:
    pass
    if not sql or not sql.strip():
        raise DbExecError(f"{action} 必须给定 sql（SQL 文本不得为空）")
    if "{{" in sql or "${" in sql:
        raise DbExecError(
            "SQL 文本禁内插占位符（检测到 {{ 或 ${）——取值请用 params 具名绑定 :name，"
            "连接名占位符只写在 conn 字段")
    stripped = _strip_literals_and_comments(sql)
    statements = _split_statements(stripped)
    if not statements:
        raise DbExecError(f"{action} 的 sql 无有效语句（仅注释/空白）")
    if len(statements) > 1:
        raise DbExecError(
            f"一次 sql 只允许一条语句（检测到 {len(statements)} 条，分号拆句）"
            "——多语句可绕过黑名单词法闸，请拆成多步")
    statement = statements[0]
    allowed = EXEC_FIRST_WORDS if action == EXEC_ACTION else READ_ONLY_FIRST_WORDS
    first = _first_word(statement)
    if first not in allowed:
        raise DbExecError(
            f"{action} 的 SQL 首关键字仅支持 {' / '.join(sorted(allowed))}，"
            f"当前为「{first or '(无关键字)'}」")
    if action == EXEC_ACTION:
        forbidden = _FORBIDDEN_TOKEN.search(statement.upper())
        if forbidden:
            raise DbExecError(
                f"sql.exec 禁用 {forbidden.group(1)} 等 DDL/权限语句（永久黑名单，"
                "仅允许 INSERT / UPDATE / DELETE 受控 DML）")
        if first in ("UPDATE", "DELETE") and not _WHERE_CLAUSE.search(
                statement.upper()):
            raise DbExecError(
                f"{first} 语句必须带 WHERE 条件（无 WHERE 的全表变更已拒"
                "——防误清生产/测试数据）")
    bound = params if isinstance(params, dict) else {}
    missing: List[str] = []
    for m in _NAMED_PARAM.finditer(statement):
        name = m.group(1)
        if name not in bound and name not in missing:
            missing.append(name)
    if missing:
        raise DbExecError(
            "SQL 中的具名参数 :" + " / :".join(missing)
            + " 在 params 中缺少绑定值（所有 :name 必须在 params 给出，禁字面量拼接）")



class Dialect:
    pass

    def __init__(self, key: str, label: str, url_prefix: str, driver_label: str) -> None:
        self.key = key
        self.label = label
        self.url_prefix = url_prefix
        self.driver_label = driver_label

    def to_driver_sql(self, sql: str) -> Tuple[str, List[str]]:
        pass
        out: List[str] = []
        names: List[str] = []
        i, n = 0, len(sql)
        while i < n:
            c = sql[i]
            if c in ("'", '"'):
                quote = c
                j = i + 1
                while j < n:
                    if sql[j] == quote:
                        if j + 1 < n and sql[j + 1] == quote:
                            j += 2
                            continue
                        j += 1
                        break
                    j += 1
                out.append(sql[i:j])
                i = j
                continue
            if c == "%":
                out.append("%%")
                i += 1
                continue
            if c == ":" and i + 1 < n and sql[i + 1] != ":":
                m = _NAMED_PARAM.match(sql, i)
                if m:
                    name = m.group(1)
                    if name not in names:
                        names.append(name)
                    out.append(f"%({name})s")
                    i = m.end()
                    continue
            out.append(c)
            i += 1
        return "".join(out), names

    def connect(self, spec: Dict[str, Any], timeout: int = CONNECT_TIMEOUT_SECONDS):
        pass
        host, port, database = self._parse_url(spec)
        auth = {"user": str(spec.get("user") or ""),
                "secret": str(spec.get("password") or "")}
        name = str(spec.get("name") or spec.get("id") or "(未命名连接)")
        try:
            if self.key == "pg":
                import psycopg2
                conn = psycopg2.connect(host=host, port=port, dbname=database,
                                        connect_timeout=timeout, **{
                                            "user": auth["user"],
                                            "password": auth["secret"]})
            else:
                import pymysql
                conn = pymysql.connect(host=host, port=port, database=database,
                                       connect_timeout=timeout, autocommit=True, **{
                                           "user": auth["user"],
                                           "password": auth["secret"]})
        except Exception as e:
            raise DbExecError(
                f"数据库连接「{name}」建立失败（{self.label} {host}:{port}/{database}"
                f"）：{' '.join(str(e).split())[:200]} ——环境不可达或凭据被拒，"
                "请检查连接配置（密码不回显）") from None
        try:
            conn.autocommit = True
        except Exception:
            pass
        return conn

    def _parse_url(self, spec: Dict[str, Any]) -> Tuple[str, int, str]:
        raw = str(spec.get("url") or "").strip()
        low = raw.lower()
        if low.startswith("jdbc:"):
            raw = raw[len("jdbc:"):]
            low = raw.lower()
        if "://" not in raw:
            raise DbExecError(
                f"连接地址无法解析（{self.label} 需 {self.url_prefix}//主机:库名 形态）："
                f"{raw[:120]}")
        rest = raw.split("://", 1)[1]
        rest = rest.split("?", 1)[0]
        hostpart, _, database = rest.partition("/")
        if ":" in hostpart:
            host, _, port_s = hostpart.partition(":")
            try:
                port = int(port_s)
            except ValueError:
                raise DbExecError(
                    f"连接地址端口非法：{hostpart}") from None
        else:
            host, port = hostpart, (5432 if self.key == "pg" else 3306)
        if not host:
            raise DbExecError(f"连接地址缺少主机名：{raw[:120]}")
        return host, port, database


class DbDialectRegistry:
    pass

    def __init__(self) -> None:
        self._dialects: "OrderedDict[str, Dialect]" = OrderedDict()
        self._register("pg", "PostgreSQL", "jdbc:postgresql:", "psycopg2")
        self._register("mysql", "MySQL", "jdbc:mysql:", "PyMySQL")

    def _register(self, key: str, label: str, url_prefix: str, driver: str) -> None:
        self._dialects[key] = Dialect(key, label, url_prefix, driver)

    def supported(self) -> List[str]:
        return [d.label for d in self._dialects.values()]

    def require(self, name: Optional[str]) -> Dialect:
        pass
        entry = self._dialects.get(name.strip().lower()) if name else None
        if entry is None:
            raise DbExecError(
                f"不支持的数据库方言「{name}」，当前支持：{'、'.join(self.supported())}")
        return entry



class ConnectionPool:
    pass

    def __init__(self, registry: Optional[DbDialectRegistry] = None) -> None:
        self.registry = registry or DbDialectRegistry()
        self._conns: "OrderedDict[Any, Any]" = OrderedDict()

    def size(self) -> int:
        return len(self._conns)

    def acquire(self, spec: Dict[str, Any]):
        pass
        key = spec.get("id", spec.get("name"))
        hit = self._conns.get(key)
        if hit is not None and not self._dead(hit[0]):
            self._conns.move_to_end(key)
            return hit[0]
        if hit is not None:
            self._close_quietly(key)
        dialect = self.registry.require(spec.get("dialect"))
        conn = dialect.connect(spec)
        self._conns[key] = (conn, _resolve_max_pool(spec))
        self._conns.move_to_end(key)
        cap = min(c for _, c in self._conns.values())
        while len(self._conns) > cap:
            oldest = next(iter(self._conns))
            log.warning("连接池达上限 %s，置换最久未用连接（id=%s）", cap, oldest)
            self._close_quietly(oldest)
        return conn

    def close_all(self) -> None:
        for key in list(self._conns):
            self._close_quietly(key)

    def sql_assert(self, spec: Dict[str, Any], step: Dict[str, Any],
                   policy: Optional[Dict[str, Any]] = None,
                   deadline_s: Optional[float] = None) -> str:
        pass
        return _sql_assert(self, spec, step, policy, deadline_s)

    def sql_exec(self, spec: Dict[str, Any], step: Dict[str, Any],
                 policy: Optional[Dict[str, Any]] = None) -> str:
        pass
        return _sql_exec(self, spec, step, policy)

    def sql_query(self, spec: Dict[str, Any], step: Dict[str, Any],
                  policy: Optional[Dict[str, Any]] = None) -> Tuple[str, Dict[str, str]]:
        pass
        return _sql_query(self, spec, step, policy)

    def _close_quietly(self, key) -> None:
        conn = self._conns.pop(key, None)
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    @staticmethod
    def _dead(conn) -> bool:
        try:
            return bool(getattr(conn, "closed", False))
        except Exception:  # noqa: BLE001
            return False



def _resolve_max_pool(spec: Dict[str, Any]) -> int:
    try:
        cap = int(spec.get("maxPool"))
    except (TypeError, ValueError):
        cap = DEFAULT_MAX_POOL
    return cap if cap > 0 else DEFAULT_MAX_POOL


def _sql_assert(pool: ConnectionPool, spec: Dict[str, Any], step: Dict[str, Any],
               policy: Optional[Dict[str, Any]] = None,
               deadline_s: Optional[float] = None) -> str:
    pass
    policy = policy if isinstance(policy, dict) else {}
    sql = step.get("sql")
    params = step.get("params") if isinstance(step.get("params"), dict) else {}
    validate_sql_step("sql.assert", sql, params)
    expect = step.get("expect") if isinstance(step.get("expect"), dict) else {}
    etype = str(expect.get("type") or "scalar")
    if etype not in _EXPECT_TYPES:
        raise DbExecError(
            f"sql.assert expect.type 不支持「{etype}」"
            f"（当前支持：{' / '.join(_EXPECT_TYPES)}，04 契约 §2.1.2）")

    retry = step.get("retry") if isinstance(step.get("retry"), dict) else {}
    interval_ms = _resolve_interval_ms(step, policy)
    budget_s = _resolve_budget_s(policy, deadline_s)
    name = _resolve_conn_name(spec)

    dialect = pool.registry.require(spec.get("dialect"))
    conn = pool.acquire(spec)
    text, names = dialect.to_driver_sql(str(sql))

    deadline = time.monotonic() + budget_s
    attempt = 0
    last_actual = None
    while True:
        attempt += 1
        rows, columns = _execute_query(conn, text, params, names)
        try:
            last_actual = _judge(etype, expect, rows, columns)
            break
        except ExpectMatchError:
            if not retry:
                raise
            if time.monotonic() >= deadline:
                elapsed_ms = int(budget_s * 1000)
                raise DbExecError(
                    f"轮询 {attempt} 次（{elapsed_ms}ms）后期望仍不满足："
                    f"末次实际={last_actual}（连接「{name}」，"
                    "sql.assert.timeout-seconds 可调）") from None
            time.sleep(interval_ms / 1000.0)
    if attempt > 1:
        return (f"sql.assert {etype} 通过：实际={last_actual}"
                f"（轮询 {attempt} 次，间隔 {interval_ms}ms）")
    return f"sql.assert {etype} 通过：实际={last_actual}"


def _resolve_conn_name(spec: Dict[str, Any]) -> str:
    return str(spec.get("name") or spec.get("id") or "(未命名连接)")


def _sql_exec(pool: ConnectionPool, spec: Dict[str, Any], step: Dict[str, Any],
              policy: Optional[Dict[str, Any]] = None) -> str:
    pass
    sql = step.get("sql")
    params = step.get("params") if isinstance(step.get("params"), dict) else {}
    validate_sql_step(EXEC_ACTION, sql, params)
    name = _resolve_conn_name(spec)
    if spec.get("readonly") in (True, 1, "1", "true"):
        raise DbExecError(
            f"数据库连接「{name}」为只读连接（readonly），sql.exec 受控 DML 已拒"
            "——造数/清理请改用读写连接（管理页「数据库连接」readonly 关）")
    dialect = pool.registry.require(spec.get("dialect"))
    conn = pool.acquire(spec)
    text, names = dialect.to_driver_sql(str(sql))
    try:
        with conn.cursor() as cur:
            cur.execute(text, {k: params[k] for k in names} if names else None)
            rowcount = cur.rowcount
    except DbExecError:
        raise
    except Exception as e:
        raise DbExecError(
            f"SQL 执行失败：{' '.join(str(e).split())[:200]}") from None
    first = _first_word(_split_statements(_strip_literals_and_comments(str(sql)))[0])
    return f"sql.exec {first} 影响 {rowcount} 行（连接「{name}」）"


def _sql_query(pool: ConnectionPool, spec: Dict[str, Any], step: Dict[str, Any],
               policy: Optional[Dict[str, Any]] = None) -> Tuple[str, Dict[str, str]]:
    pass
    policy = policy if isinstance(policy, dict) else {}
    sql = step.get("sql")
    params = step.get("params") if isinstance(step.get("params"), dict) else {}
    validate_sql_step("sql.query", sql, params)
    name = _resolve_conn_name(spec)
    max_rows = _resolve_max_rows(policy)
    dialect = pool.registry.require(spec.get("dialect"))
    conn = pool.acquire(spec)
    text, names = dialect.to_driver_sql(str(sql))
    try:
        with conn.cursor() as cur:
            cur.execute(text, {k: params[k] for k in names} if names else None)
            columns = [d[0] for d in (cur.description or [])]
            fetched = cur.fetchmany(max_rows + 1)
    except DbExecError:
        raise
    except Exception as e:
        raise DbExecError(
            f"SQL 执行失败：{' '.join(str(e).split())[:200]}") from None
    truncated = len(fetched) > max_rows
    rows = list(fetched[:max_rows])
    detail = f"sql.query 返回 {len(rows)} 行（连接「{name}」"
    detail += (f"，超 sql.max-rows={max_rows} 已截断取前 {max_rows} 行"
               if truncated else "，行数在上限内") + "）"
    save_as = step.get("saveAs")
    leaves: Dict[str, str] = {}
    if save_as:
        prefix = str(save_as)
        if not rows:
            raise DbExecError(
                f"sql.query saveAs {prefix} 捕获失败：查询无行返回"
                "——捕获值必须确定性可寻址（检查 WHERE 条件/前序造数）")
        if len(rows) > 1:
            raise DbExecError(
                f"sql.query saveAs {prefix} 捕获失败：查询返回 {len(rows)} 行"
                f"（仅单行可捕获，多行请加 WHERE 收窄，04 §2.1.2）")
        row = rows[0]
        if len(columns) == 1:
            leaves[prefix] = _render(row[0])
        else:
            for col, val in zip(columns, row):
                leaves[f"{prefix}.{col}"] = _render(val)
        detail += f"；saveAs {prefix} → {len(leaves)} 个标量变量"
    return detail, leaves


def _resolve_max_rows(policy: Dict[str, Any]) -> int:
    pass
    raw = policy.get("sqlMaxRows")
    try:
        cap = int(raw) if raw is not None else DEFAULT_MAX_ROWS
    except (TypeError, ValueError):
        cap = DEFAULT_MAX_ROWS
    return cap if cap > 0 else DEFAULT_MAX_ROWS


def _execute_query(conn, text: str, params: Dict[str, Any],
                   names: List[str]) -> Tuple[List[tuple], List[str]]:
    pass
    try:
        with conn.cursor() as cur:
            cur.execute(text, {k: params[k] for k in names} if names else None)
            columns = [d[0] for d in (cur.description or [])]
            rows = cur.fetchall()
        return list(rows), columns
    except DbExecError:
        raise
    except Exception as e:
        raise DbExecError(
            f"SQL 执行失败：{' '.join(str(e).split())[:200]}") from None


def _judge(etype: str, expect: Dict[str, Any], rows: List[tuple],
           columns: List[str]) -> str:
    pass
    if etype == "scalar":
        if len(rows) > 1 or (rows and len(rows[0]) > 1):
            raise DbExecError(
                f"expect.type=scalar 要求单行单列，实际 {len(rows)} 行"
                f"×{len(rows[0]) if rows else 0} 列（请改 row_count/columns 或收窄 WHERE）")
        raw = _render(rows[0][0] if rows else None)
        _match_value(raw, expect)
        return raw
    if etype == "row_count":
        raw = str(len(rows))
        _match_value(raw, expect)
        return raw
    actual = list(columns)
    want = [str(c) for c in (expect.get("columns") or [])]
    if actual != want:
        raise ExpectMatchError(
            f"断言不满足：结果列 {actual} 与期望 {want} 不一致（有序比对）")
    return "、".join(actual)


def _match_value(raw: str, expect: Dict[str, Any]) -> None:
    pass
    mtype = str(expect.get("matchType") or "exact")
    if mtype == "range":
        match_range(raw, _as_float(expect.get("min")), _as_float(expect.get("max")),
                    str(expect.get("bounds") or "closed"))
    elif mtype == "regex":
        match_regex(raw, str(expect.get("pattern") or ""))
    elif mtype in ("contains", "exact"):
        match_text(raw, str(expect.get("text", "")), exact=(mtype == "exact"))
    else:
        raise DbExecError(
            f"expect.matchType 非法值 {mtype!r}"
            "（白名单：contains / exact / regex / range，04 契约 §2.1.1）")


def _as_float(value) -> Optional[float]:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        raise DbExecError(f"expect.min/max 不是数值：{value!r}") from None


def _render(value) -> str:
    if value is None:
        return "NULL"
    return str(value)


def _resolve_interval_ms(step: Dict[str, Any], policy: Dict[str, Any]) -> int:
    pass
    retry = step.get("retry") if isinstance(step.get("retry"), dict) else {}
    raw = retry.get("intervalMs")
    if raw is None:
        raw = policy.get("sqlAssertIntervalMs")
    try:
        interval = int(raw) if raw is not None else DEFAULT_INTERVAL_MS
    except (TypeError, ValueError):
        interval = DEFAULT_INTERVAL_MS
    if interval < MIN_INTERVAL_MS:
        log.warning("sql.assert intervalMs=%s 低于下限，已钳制为 %sms（1s 下限，"
                    "防高频轮询打爆被测库，04 §2.1.2）", interval, MIN_INTERVAL_MS)
        interval = MIN_INTERVAL_MS
    return interval


def _resolve_budget_s(policy: Dict[str, Any], deadline_s: Optional[float]) -> float:
    if deadline_s is not None:
        return float(deadline_s)
    raw = policy.get("sqlAssertTimeoutSeconds")
    try:
        return float(raw) if raw is not None else float(DEFAULT_TIMEOUT_SECONDS)
    except (TypeError, ValueError):
        return float(DEFAULT_TIMEOUT_SECONDS)
