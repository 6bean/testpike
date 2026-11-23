
from __future__ import annotations

import re
from typing import Optional

LANGUAGE_PYTHON = "python"

TIMEOUT_MIN_MS = 1
TIMEOUT_MAX_MS = 30_000

DEFAULT_TIMEOUT_MS = 30_000

_FORBIDDEN_CATEGORIES: list = [
    ("网络访问", ["socket", "requests", "urllib", "urllib2", r"http\.client",
                  "ftplib", "smtplib", "telnetlib"]),
    ("子进程派生", ["subprocess", "multiprocessing", r"os\.system", r"os\.popen",
                    r"os\.fork", r"os\.execv", "Popen"]),
    ("文件访问", ["shutil", "pathlib", r"os\.remove", r"os\.mkdir", r"os\.rename",
                  r"os\.unlink", r"\bopen\s*\("]),
    ("沙箱逃逸", ["eval", "exec", "compile", "__import__", "importlib",
                  "builtins", "globals", "locals"]),
]

_COMPILED: list = [
    (label, [re.compile(p if "\\" in p else rf"\b{p}\b") for p in words])
    for label, words in _FORBIDDEN_CATEGORIES
]


class CodeStepViolation(ValueError):
    pass


def _language_violation(step: dict) -> Optional[str]:
    value = step.get("language")
    if value is None or not str(value).strip():
        return f"code.step 必须给定 language（当前仅支持 {LANGUAGE_PYTHON}）"
    text = str(value).strip()
    if text.lower() == LANGUAGE_PYTHON:
        return None
    if text.lower() in ("js", "javascript"):
        return (f"code.step 语言「{text}」沙箱暂未开放（B8 定版口径=受限 Python 片段，"
                "js 片段请改用既有动作原语表达）")
    return f"code.step 语言「{text}」不在支持列表（当前仅 {LANGUAGE_PYTHON}）"


def _sandbox_violation(code: str) -> Optional[str]:
    if not code.strip():
        return "code.step 必须给定 code（代码正文不得为空）"
    for label, patterns in _COMPILED:
        for pattern in patterns:
            hit = pattern.search(code)
            if hit:
                return (f"code.step 代码命中沙箱禁制「{label}」（命中 {hit.group()}）——"
                        "自定义代码步骤默认禁网/禁子进程/禁文件访问（IT-B8-01 三禁默认），"
                        "请改用既有动作原语（http.request/sql.query/expect）表达")
    return None


def _timeout_violation(timeout_ms) -> Optional[str]:
    if timeout_ms is None:
        return None
    try:
        value = int(str(timeout_ms).strip())
    except (TypeError, ValueError):
        return (f"code.step 的 timeoutMs 非法（{timeout_ms}）——须为 "
                f"{TIMEOUT_MIN_MS}-{TIMEOUT_MAX_MS} 毫秒（与工具试跑沙箱预算同源上限）")
    if value < TIMEOUT_MIN_MS or value > TIMEOUT_MAX_MS:
        return (f"code.step 的 timeoutMs 非法（{value}）——须为 "
                f"{TIMEOUT_MIN_MS}-{TIMEOUT_MAX_MS} 毫秒（与工具试跑沙箱预算同源上限）")
    return None


def validate_code_step(step: dict) -> None:
    pass
    for violation in (_language_violation(step),
                      _sandbox_violation(str(step.get("code") or "")),
                      _timeout_violation(step.get("timeoutMs"))):
        if violation:
            raise CodeStepViolation(violation)
