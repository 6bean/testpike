
from __future__ import annotations

import shutil
import subprocess
import threading
import time

_SWIPE_MIN_DELTA = 20

_KEY_KINDS = {"KEY_BACK": "back", "KEY_HOME": "home"}

_X_CODES = ("ABS_MT_POSITION_X", "ABS_X")
_Y_CODES = ("ABS_MT_POSITION_Y", "ABS_Y")

_KEY_VALUE_NAMES = {"DOWN": 1, "UP": 0, "AUTOREPEAT": 2}


def _value_of(token: str):
    pass
    if token in _KEY_VALUE_NAMES:
        return _KEY_VALUE_NAMES[token]
    try:
        return int(token, 16)
    except ValueError:
        return None


def parse_getevent_lines(lines) -> list[dict]:
    pass
    return _parse_rows([(None, line) for line in lines or []])


def _parse_rows(rows) -> list[dict]:
    events: list[dict] = []
    gesture = None          # {"ts","x","y","start":(x,y)}
    pending_x = pending_y = None
    key_down = None
    for ts, line in rows:
        parts = line.split()
        try:
            i = next(k for k, p in enumerate(parts) if p.startswith("EV_"))
        except StopIteration:
            continue
        if len(parts) < i + 3:
            continue
        kind_code, code, value_token = parts[i], parts[i + 1], parts[i + 2]
        if kind_code == "EV_ABS":
            if code in _X_CODES:
                pending_x = _abs_value(value_token)
            elif code in _Y_CODES:
                pending_y = _abs_value(value_token)
            continue
        if kind_code != "EV_SYN":
            if kind_code != "EV_KEY":
                continue
            value = _value_of(value_token)
            if value is None:
                continue
            if code == "BTN_TOUCH":
                if value == 1 and gesture is None:
                    gesture = {"ts": ts, "x": None, "y": None, "start": None}
                elif value == 1 and gesture is not None:
                    continue
                elif value == 0 and gesture is not None:
                    events.append(_close_gesture(gesture, ts))
                    gesture = None
                continue
            if value == 1:
                key_down = (code, ts)
            elif value == 0 and key_down and key_down[0] == code:
                events.append({"kind": _KEY_KINDS.get(code, "key"),
                               "target": None, "detail": code, "ts": key_down[1]})
                key_down = None
            continue
        if gesture is not None:
            if pending_x is not None:
                gesture["x"] = pending_x
            if pending_y is not None:
                gesture["y"] = pending_y
            if (gesture["start"] is None and gesture["x"] is not None
                    and gesture["y"] is not None):
                gesture["start"] = (gesture["x"], gesture["y"])
    return events


def _abs_value(token: str):
    try:
        return int(token, 16)
    except ValueError:
        return None


def _close_gesture(gesture: dict, end_ts) -> dict:
    start = gesture.get("start")
    x, y = gesture.get("x"), gesture.get("y")
    if start is None and x is not None:
        start = (x, y)
    if start and x is not None and y is not None:
        dx, dy = x - start[0], y - start[1]
        if max(abs(dx), abs(dy)) >= _SWIPE_MIN_DELTA:
            direction = (("up" if dy < 0 else "down") if abs(dy) >= abs(dx)
                         else ("left" if dx < 0 else "right"))
            return {"kind": "swipe", "target": direction,
                    "detail": f"位移({start[0]},{start[1]})→({x},{y})",
                    "ts": gesture["ts"] or end_ts}
    return {"kind": "tap", "target": None,
            "detail": f"坐标({x},{y})", "ts": gesture["ts"] or end_ts}


class _GeteventCapture:
    pass

    def __init__(self, proc: subprocess.Popen):
        self._proc = proc
        self._rows: list[tuple] = []
        self._reader = threading.Thread(target=self._pump, daemon=True)
        self._reader.start()

    def _pump(self):
        try:
            for line in self._proc.stdout:
                self._rows.append((time.time(), line.rstrip("\n")))
        except (OSError, ValueError):
            pass

    def events(self) -> list[dict]:
        if self._proc.poll() is None:
            self._reader.join(timeout=0.5)
        else:
            self._reader.join(timeout=1.0)
        return _parse_rows(list(self._rows))

    def stop(self):
        if self._proc.poll() is None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                self._proc.kill()
        self._reader.join(timeout=1.0)


def start_input_stream(serial: str | None = None):
    pass
    adb = shutil.which("adb")
    if adb is None:
        return None
    argv = [adb] + (["-s", serial] if serial else []) + ["shell", "getevent", "-l"]
    try:
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True)
    except OSError:
        return None
    return _GeteventCapture(proc)
