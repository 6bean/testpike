
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any



class ConditionError(ValueError):
    pass


@dataclass(frozen=True)
class _Token:
    kind: str  # num str bool null ident op lparen rparen eof
    value: Any
    pos: int


_KEYWORDS = {"true": ("bool", True), "false": ("bool", False), "null": ("null", None)}
_OPS = ("==", "!=", "<=", ">=", "&&", "||", "<", ">", "!", "(", ")", "and", "or", "not")
_TOKEN_RE = re.compile(
    r"""\s*(?:
        (?P<num>\d+(?:\.\d+)?)
      | (?P<str>'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")
      | (?P<word>[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)
      | (?P<op>==|!=|<=|>=|&&|\|\||[<>()!])
      | (?P<bad>[^\s])
    )""",
    re.VERBOSE)


def _tokenize(text: str) -> list[_Token]:
    tokens: list[_Token] = []
    pos = 0
    while pos < len(text):
        m = _TOKEN_RE.match(text, pos)
        if not m:
            raise ConditionError(f"condition 含无法识别的字符：'{text[pos]}'（位置 {pos}）")
        pos = m.end()
        if m.lastgroup == "bad":
            raise ConditionError(f"condition 含文法外字符：'{m.group('bad')}'"
                                 "（仅支持 04 契约简单表达式：字面量/比较/and-or-not/括号）")
        if m.lastgroup == "word":
            word = m.group("word")
            if "." not in word and word in _KEYWORDS:
                kind, value = _KEYWORDS[word]
                tokens.append(_Token(kind, value, m.start("word")))
            elif "." not in word and word in ("and", "or", "not"):
                tokens.append(_Token("op", word, m.start("word")))
            elif "__" in word:
                raise ConditionError(f"condition 含文法外标识符：'{word}'（禁 dunder/代码注入）")
            else:
                tokens.append(_Token("ident", word, m.start("word")))
        elif m.lastgroup == "num":
            raw = m.group("num")
            tokens.append(_Token("num", float(raw) if "." in raw else int(raw),
                                 m.start("num")))
        elif m.lastgroup == "str":
            tokens.append(_Token("str", _unquote(m.group("str")), m.start("str")))
        else:
            tokens.append(_Token("op", m.group("op"), m.start("op")))
    tokens.append(_Token("eof", None, pos))
    return tokens


def _unquote(raw: str) -> str:
    body = raw[1:-1]
    out: list[str] = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            out.append({"n": "\n", "t": "\t", "\\": "\\", "'": "'", '"': '"'}.get(nxt, nxt))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


class _Parser:
    def __init__(self, tokens: list[_Token], scope: dict) -> None:
        self._t = tokens
        self._i = 0
        self._scope = scope

    def _peek(self) -> _Token:
        return self._t[self._i]

    def _next(self) -> _Token:
        tok = self._t[self._i]
        self._i += 1
        return tok

    def _expect_op(self, op: str) -> None:
        tok = self._next()
        if not (tok.kind == "op" and tok.value == op):
            raise ConditionError(f"condition 语法错误：位置 {tok.pos} 期望 '{op}'，"
                                 f"实际 {_describe(tok)}")

    def parse(self) -> Any:
        value = self._or_expr()
        if self._peek().kind != "eof":
            raise ConditionError(f"condition 语法错误：位置 {self._peek().pos} "
                                 f"存在多余内容 {_describe(self._peek())}")
        return value

    def _or_expr(self) -> Any:
        left = self._and_expr()
        while self._peek().kind == "op" and self._peek().value in ("||", "or"):
            self._next()
            right = self._and_expr()
            left = bool(left) or bool(right)
        return left

    def _and_expr(self) -> Any:
        left = self._not_expr()
        while self._peek().kind == "op" and self._peek().value in ("&&", "and"):
            self._next()
            right = self._not_expr()
            left = bool(left) and bool(right)
        return left

    def _not_expr(self) -> Any:
        tok = self._peek()
        if tok.kind == "op" and tok.value in ("!", "not"):
            self._next()
            return not bool(self._not_expr())
        return self._cmp_expr()

    def _cmp_expr(self) -> Any:
        left = self._primary()
        tok = self._peek()
        if tok.kind == "op" and tok.value in ("==", "!=", "<", "<=", ">", ">="):
            self._next()
            right = self._primary()
            return _compare(tok.value, left, right)
        return left

    def _primary(self) -> Any:
        tok = self._next()
        if tok.kind in ("num", "str", "bool", "null"):
            return tok.value
        if tok.kind == "op" and tok.value == "(":
            inner = self._or_expr()
            self._expect_op(")")
            return inner
        if tok.kind == "ident":
            path = str(tok.value).split(".")
            return self._resolve(path, tok.pos)
        raise ConditionError(f"condition 语法错误：位置 {tok.pos} 期望值，"
                             f"实际 {_describe(tok)}")

    def _resolve(self, path: list[str], pos: int) -> Any:
        cur: Any = self._scope
        for i, seg in enumerate(path):
            if isinstance(cur, dict) and seg in cur:
                cur = cur[seg]
            else:
                defined = "、".join(sorted(str(k) for k in self._scope)) or "（无）"
                raise ConditionError(
                    f"condition 未定义变量 {'.'.join(path)}（位置 {pos}；"
                    f"可用变量：{defined}）")
        return cur


def _describe(tok: _Token) -> str:
    if tok.kind == "eof":
        return "表达式结束"
    return f"'{tok.value}'"


def _compare(op: str, left: Any, right: Any) -> bool:
    if op in ("==", "!="):
        eq = _loose_eq(left, right)
        return eq if op == "==" else not eq
    if _truthy_number(left) is None or _truthy_number(right) is None:
        raise ConditionError(f"condition 比较不支持的运算数：{left!r} {op} {right!r}"
                             "（大小比较仅限数值/布尔）")
    a = float(left)
    b = float(right)
    return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b}[op]


def _loose_eq(a: Any, b: Any) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        na, nb = _truthy_number(a), _truthy_number(b)
        return na is not None and nb is not None and na == nb
    if a is None or b is None:
        return a is None and b is None
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    if isinstance(a, str) and isinstance(b, str):
        return a == b
    return a == b


def _truthy_number(v: Any) -> float | None:
    if isinstance(v, bool):
        return 1.0 if v else 0.0
    if isinstance(v, (int, float)):
        return float(v)
    return None


def evaluate_condition(text: str, scope: dict) -> bool:
    pass
    if text is None or not str(text).strip():
        raise ConditionError("if 动作缺少 condition 表达式")
    tokens = _tokenize(str(text))
    parser = _Parser(tokens, scope or {})
    return bool(parser.parse())
