
from __future__ import annotations

import json
import queue
import subprocess
import threading
import time
from typing import Optional


class BridgeCrashError(RuntimeError):
    pass

    def __init__(self, exit_code: Optional[int], stderr_tail: str, method: str = ""):
        self.exit_code = exit_code
        self.stderr_tail = (stderr_tail or "")[-2000:]
        super().__init__(
            f"引擎桥进程已退出（exit={exit_code}）"
            + (f"，调用方法={method}" if method else "")
            + f"；桥侧输出尾：{self.stderr_tail or '（无输出）'}")


def encode_request(request_id: int, method: str, params: dict) -> str:
    pass
    return json.dumps({"jsonrpc": "2.0", "id": request_id,
                       "method": method, "params": params},
                      ensure_ascii=False) + "\n"


def parse_frame(raw: str) -> dict:
    pass
    text = (raw or "").strip()
    if not text:
        raise ValueError("桥帧为空行（协议=一行一 JSON 的 NDJSON）")
    try:
        frame = json.loads(text)
    except json.JSONDecodeError as e:
        raise ValueError(f"桥帧不是合法 JSON: {text[:200]}（{e}）") from e
    if not isinstance(frame, dict):
        raise ValueError(f"桥帧不是 JSON 对象: {text[:200]}")
    return frame


class NodeBridge:
    pass

    def __init__(self, script_path: str, timeout_sec: float = 30.0,
                 extra_env: Optional[dict] = None, node_bin: str = "node"):
        self.script_path = script_path
        self.timeout_sec = timeout_sec
        self.extra_env = extra_env or {}
        self.node_bin = node_bin
        self._proc: Optional[subprocess.Popen] = None
        self._next_id = 0
        self._lock = threading.Lock()
        self._frame_q: "queue.Queue" = queue.Queue()
        self._stderr_buf: list[str] = []
        self._drain_thread: Optional[threading.Thread] = None

    def start(self) -> None:
        import os
        env = {**os.environ, **self.extra_env}
        try:
            self._proc = subprocess.Popen(
                [self.node_bin, self.script_path],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, encoding="utf-8",
                env=env, start_new_session=True)
        except FileNotFoundError as e:
            raise RuntimeError(f"引擎桥启动失败：找不到 {self.node_bin} "
                               "（Node 属环境件需自行安装，testPike 不随包分发）") from e
        self._drain_thread = threading.Thread(target=self._drain_stderr, daemon=True)
        self._drain_thread.start()
        self._stdout_thread = threading.Thread(target=self._pump_stdout, daemon=True)
        self._stdout_thread.start()

    def _pump_stdout(self) -> None:
        pass
        proc = self._proc
        if proc is None or proc.stdout is None:
            return
        for line in proc.stdout:
            self._frame_q.put(line)
        self._frame_q.put(None)

    def _drain_stderr(self) -> None:
        proc = self._proc
        if proc is None or proc.stderr is None:
            return
        for line in proc.stderr:
            self._stderr_buf.append(line.rstrip())

    def alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def call(self, method: str, params: Optional[dict] = None) -> dict:
        pass
        proc = self._proc
        if proc is None or proc.poll() is not None:
            raise BridgeCrashError(proc.poll() if proc else None,
                                   "\n".join(self._stderr_buf), method)
        with self._lock:
            if proc.poll() is not None:
                raise BridgeCrashError(proc.poll(), "\n".join(self._stderr_buf), method)
            self._next_id += 1
            request_id = self._next_id
            try:
                proc.stdin.write(encode_request(request_id, method, params or {}))
                proc.stdin.flush()
            except (BrokenPipeError, OSError) as e:
                code = proc.poll()
                raise BridgeCrashError(code, "\n".join(self._stderr_buf), method) from e
            frame = self._read_response_frame(request_id, method)
        if "error" in frame:
            err = frame.get("error") or {}
            raise RuntimeError(f"桥方法 {method} 返回错误："
                               f"{err.get('message', err)}（code={err.get('code')}）")
        return frame.get("result") or {}

    def _read_response_frame(self, request_id: int, method: str) -> dict:
        pass
        deadline = time.monotonic() + self.timeout_sec
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(
                    f"引擎桥方法 {method} 响应超时（>{self.timeout_sec:.0f}s）"
                    "——桥进程可能挂死，已留取证日志")
            try:
                line = self._frame_q.get(timeout=min(remaining, 0.5))
            except queue.Empty:
                if self._proc is None or self._proc.poll() is not None:
                    code = self._proc.poll() if self._proc else None
                    raise BridgeCrashError(code, "\n".join(self._stderr_buf), method)
                continue
            if line is None:
                proc = self._proc
                code = proc.poll() if proc else None
                raise BridgeCrashError(code, "\n".join(self._stderr_buf), method)
            frame = parse_frame(line)
            if frame.get("id") == request_id:
                return frame
    def stop(self) -> None:
        proc = self._proc
        if proc is None:
            return
        try:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
        except OSError:
            pass
        finally:
            self._proc = None
