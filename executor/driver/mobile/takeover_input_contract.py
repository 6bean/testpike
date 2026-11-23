
from __future__ import annotations

TAKEOVER_INPUT_VERBS: frozenset[str] = frozenset({"tap", "swipe", "key", "text"})

_COORD_MIN = 0
_COORD_MAX = 10000
_KEYCODE_MIN = 0
_KEYCODE_MAX = 255
_TEXT_MIN_LEN = 1
_TEXT_MAX_LEN = 128

_ADB_BINARY = "adb"
_HDC_BINARY = "hdc"
_NO_INJECTION_PLATFORMS = ("MP", "IOS", "QUICKAPP")


class TakeoverInputError(ValueError):
    pass


class _CoordGate:
    pass

    @staticmethod
    def one(value, name: str) -> int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise TakeoverInputError(f"接管指令坐标 {name} 必须是整数（当前：{value!r}）")
        if not _COORD_MIN <= value <= _COORD_MAX:
            raise TakeoverInputError(
                f"接管指令坐标 {name} 越界（允许 {_COORD_MIN}..{_COORD_MAX}，当前：{value}）")
        return value

    @staticmethod
    def optional_int(value, name: str, lo: int, hi: int) -> int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise TakeoverInputError(f"接管指令 {name} 必须是整数（当前：{value!r}）")
        if not lo <= value <= hi:
            raise TakeoverInputError(f"接管指令 {name} 越界（允许 {lo}..{hi}，当前：{value}）")
        return value


_COORD = _CoordGate()


def validate_takeover_command(cmd: dict) -> str | None:
    pass
    if not isinstance(cmd, dict):
        return "接管指令必须是对象（JSON dict）"
    platform = cmd.get("platform")
    if not platform:
        return "接管指令缺 platform（注入装配按平台选载体，禁猜）"
    verb = cmd.get("verb")
    if verb not in TAKEOVER_INPUT_VERBS:
        return (f"接管指令动词 {verb!r} 不在词表（仅 "
                + "/".join(sorted(TAKEOVER_INPUT_VERBS)) + "）")
    try:
        _plan_reject_unsupported_platform(str(platform), str(verb))
        _gate_args(str(platform), str(verb), cmd)
    except TakeoverInputError as e:
        return str(e)
    return None


def _plan_reject_unsupported_platform(platform: str, verb: str) -> None:
    if platform in _NO_INJECTION_PLATFORMS:
        raise TakeoverInputError(
            f"平台 {platform} 无操作注入面（接管操作仅支持 ANDROID/HARMONY_NEXT，"
            "界面上该设备应置灰操作区——此指令被兜底闸丢弃）")


def _gate_args(platform: str, verb: str, cmd: dict) -> None:
    pass
    if verb == "tap":
        _COORD.one(cmd.get("x"), "x")
        _COORD.one(cmd.get("y"), "y")
    elif verb == "swipe":
        for name in ("x1", "y1", "x2", "y2"):
            _COORD.one(cmd.get(name), name)
    elif verb == "key":
        _COORD.optional_int(cmd.get("keycode"), "keycode", _KEYCODE_MIN, _KEYCODE_MAX)
    elif verb == "text":
        if platform == "HARMONY_NEXT":
            raise TakeoverInputError(
                "HARMONY_NEXT 暂不支持 text 注入（hdc uitest 文本输入无稳定映射实证，"
                "宁缺毋滥禁猜命令）")
        text = cmd.get("text")
        if not isinstance(text, str) or not (_TEXT_MIN_LEN <= len(text) <= _TEXT_MAX_LEN):
            raise TakeoverInputError(
                f"接管指令 text 长度须在 {_TEXT_MIN_LEN}..{_TEXT_MAX_LEN} 之间")
    else:
        raise TakeoverInputError(f"接管指令动词 {verb!r} 不在词表")


def plan_input_argv(cmd: dict) -> list[str]:
    pass
    platform = cmd.get("platform")
    if not platform:
        raise TakeoverInputError("接管指令缺 platform（注入装配按平台选载体，禁猜）")
    platform = str(platform)
    verb = str(cmd.get("verb"))
    serial = cmd.get("serial")
    _plan_reject_unsupported_platform(platform, verb)
    _gate_args(platform, verb, cmd)
    if platform == "ANDROID":
        return _adb_plan(verb, cmd, serial)
    if platform == "HARMONY_NEXT":
        return _hdc_plan(verb, cmd, serial)
    raise TakeoverInputError(f"平台 {platform} 无已知注入载体（禁猜命令）")


class _PlanShape:
    pass

    @staticmethod
    def prefix(binary: str, serial_flag: str | None, serial) -> list[str]:
        argv = [binary]
        if serial_flag and serial:
            argv += [serial_flag, str(serial)]
        argv.append("shell")
        return argv


def _adb_plan(verb: str, cmd: dict, serial) -> list[str]:
    argv = _PlanShape.prefix(_ADB_BINARY, "-s", serial) + ["input"]
    if verb == "tap":
        return argv + ["tap", str(cmd["x"]), str(cmd["y"])]
    if verb == "swipe":
        return argv + ["swipe", str(cmd["x1"]), str(cmd["y1"]),
                       str(cmd["x2"]), str(cmd["y2"])]
    if verb == "key":
        return argv + ["keyevent", str(cmd["keycode"])]
    return argv + ["text", str(cmd["text"]).replace(" ", "%s")]


def _hdc_plan(verb: str, cmd: dict, serial) -> list[str]:
    argv = _PlanShape.prefix(_HDC_BINARY, "-t", serial) + ["uitest", "uiInput"]
    if verb == "tap":
        return argv + ["click", str(cmd["x"]), str(cmd["y"])]
    if verb == "swipe":
        return argv + ["swipe", str(cmd["x1"]), str(cmd["y1"]),
                       str(cmd["x2"]), str(cmd["y2"])]
    raise TakeoverInputError(
        f"HARMONY_NEXT 暂不支持 {verb} 注入（uitest uiInput 无稳定映射实证，"
        "宁缺毋滥禁猜命令）")
