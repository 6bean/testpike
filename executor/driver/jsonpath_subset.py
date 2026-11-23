
from __future__ import annotations

import re
from typing import Any, Dict

JSON_PATH_SUBSET = re.compile(r"^\$((\.[A-Za-z_]\w*)|\[[0-9]+\])*$")

_SEGMENT = re.compile(r"\.([A-Za-z_]\w*)|\[([0-9]+)\]")

_MSG_SUBSET = ("JsonPath 仅支持受限子集（例 $.data.items[0].status）：禁 filter（[?(...)]）、"
               "script、通配（* / ..）、切片（[1:2]）表达式，当前值「{path}」")


class JsonPathError(ValueError):
    pass


def evaluate_json_path(obj: Any, path: str) -> Any:
    pass
    if not path or not JSON_PATH_SUBSET.match(path):
        raise JsonPathError(_MSG_SUBSET.format(path=path))
    current = obj
    walked = "$"
    for seg in _SEGMENT.finditer(path):
        key, index = seg.group(1), seg.group(2)
        if key is not None:
            walked += f".{key}"
            if isinstance(current, dict) and key in current:
                current = current[key]
            else:
                raise JsonPathError(
                    f"路径「{path}」不存在：{walked} 处无对应字段（检查 expect.body.path 与响应结构）")
        else:
            idx = int(index)
            walked += f"[{idx}]"
            if isinstance(current, list) and idx < len(current):
                current = current[idx]
            else:
                raise JsonPathError(
                    f"路径「{path}」不存在：{walked} 处无对应下标（数组越界或非数组）")
    if isinstance(current, (dict, list)):
        raise JsonPathError(
            f"JsonPath 仅支持标量路径（对象/数组不入变量域，例 $.data.items[0].n），"
            f"当前「{path}」命中的是{'对象' if isinstance(current, dict) else '数组'}")
    return current


def scalar_leaves(value: Any, name: str) -> Dict[str, Any]:
    pass
    out: Dict[str, Any] = {}

    def walk(node: Any, key: str) -> None:
        if isinstance(node, dict):
            for k, child in node.items():
                walk(child, f"{key}.{k}")
        elif isinstance(node, list):
            for i, child in enumerate(node):
                walk(child, f"{key}[{i}]")
        else:
            out[key] = node

    walk(value, name)
    return out
