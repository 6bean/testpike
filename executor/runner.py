
from __future__ import annotations

import json
import logging
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from typing import Optional

import requests

from config import ExecutorConfig
from command_consumer import CommandContext, detect_host, poll_and_consume
from capture.capture_round import poll_and_run_capture_round
from driver.mobile.adb_verb_runner import AdbVerbRunner
from driver.mobile.perf_session import (
    PERF_CRASHES_FILENAME,
    PERF_SAMPLES_FILENAME,
    perf_dir,
    summarize_rows,
)
from driver.webdriver import RunOutcome, WebDriverExecutor
from protocol import ExecutorProtocolClient, ProtocolError
from takeover_input_consumer import poll_and_inject_takeover_input

log = logging.getLogger("testpike.executor")

_ACTIVE_PROCS: dict[int, object] = {}
_CANCEL_REQUESTED: set[int] = set()
_CLAIM_TOKENS: dict[int, str] = {}
_ACTIVE_LEASES: dict[int, dict] = {}
_ACTIVE_LOCK = threading.Lock()
_MONITOR_SINKS: dict[int, tuple] = {}
_MONITOR_REQUEUE: list[dict] = []
MONITOR_DRAIN_MAX_FRAMES = 20
_NET_SERIAL_SEEN: dict[str, float] = {}
_PROXY_AUDIT_LAST: list[float] = [0.0]

_WORKER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "task_worker.py")
_TOOL_WORKER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tool_worker.py")
_EXPLORE_WORKER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "explore_worker.py")
_API_WORKER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "api_runner.py")
_PERF_WORKER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "perf_worker.py")
_APP_WORKER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "app_worker.py")
_TASK_TYPE_TOOL_TEST_RUN = 2
_TASK_TYPE_EXPLORE_ACT = 3
_TASK_TYPE_API_TEST_RUN = 4
_TASK_TYPE_PERF_RUN = 5
_TASK_TYPE_APP_AUTO_RUN = 6


def _worker_for(task_type: int) -> str:
    pass
    if task_type == _TASK_TYPE_TOOL_TEST_RUN:
        return _TOOL_WORKER_PATH
    if task_type == _TASK_TYPE_EXPLORE_ACT:
        return _EXPLORE_WORKER_PATH
    if task_type == _TASK_TYPE_API_TEST_RUN:
        return _API_WORKER_PATH
    if task_type == _TASK_TYPE_PERF_RUN:
        return _PERF_WORKER_PATH
    if task_type == _TASK_TYPE_APP_AUTO_RUN:
        return _APP_WORKER_PATH
    return _WORKER_PATH


def _lease_of_task(task: dict) -> Optional[dict]:
    pass
    if int(task.get("taskType") or 1) != _TASK_TYPE_APP_AUTO_RUN:
        return None
    try:
        envelope = json.loads(str(task.get("actionJson") or "{}"))
    except Exception:
        return None
    lease = envelope.get("leaseRef") if isinstance(envelope, dict) else None
    if isinstance(lease, dict) and lease.get("deviceId") is not None:
        return {"deviceId": lease["deviceId"], "serial": lease.get("serial")}
    return None


def _worker_task_payload(task: dict, client: ExecutorProtocolClient,
                         cfg, monitor_sink: str | None = None,
                         perf_partial_sink: str | None = None) -> dict:
    pass
    payload = dict(task)
    payload["_executorId"] = client.executor_id
    payload["_apiBaseUrl"] = cfg.api_base_url
    if monitor_sink:
        payload["_monitorFrameSink"] = monitor_sink
    if perf_partial_sink:
        payload["_perfPartialSink"] = perf_partial_sink
    return payload


def renew_active_leases(client: ExecutorProtocolClient) -> None:
    pass
    with _ACTIVE_LOCK:
        leases = dict(_ACTIVE_LEASES)
    for task_id, lease in leases.items():
        try:
            client.renew_device_lease(int(lease["deviceId"]))
        except Exception as e:
            log.warning("任务 %s 设备租约续租失败（TTL 兜底回收）: %s", task_id, e)


def release_lease_for_task(client: ExecutorProtocolClient, task_id: int,
                           reason: str) -> None:
    pass
    with _ACTIVE_LOCK:
        lease = _ACTIVE_LEASES.pop(task_id, None)
    if lease is None:
        return
    try:
        client.release_device_lease(int(lease["deviceId"]), reason=reason)
    except Exception as e:
        log.warning("任务 %s 设备租约兜底归还失败（TTL 兜底回收）: %s", task_id, e)



def collect_proxy_audits(interval_sec: float = 300.0,
                         now: float | None = None) -> list[dict]:
    pass
    stamp = time.monotonic() if now is None else now
    if stamp - _PROXY_AUDIT_LAST[0] < interval_sec:
        return []
    _PROXY_AUDIT_LAST[0] = stamp
    with _ACTIVE_LOCK:
        seen = sorted(_NET_SERIAL_SEEN)
        busy = {str(lease.get("serial")) for lease in _ACTIVE_LEASES.values()
                if lease.get("serial")}
    adb_runner = AdbVerbRunner()
    rows: list[dict] = []
    for serial in seen:
        if not serial or serial in busy:
            continue
        try:
            row = adb_runner.audit_net_proxy(serial)
        except Exception as e:
            log.warning("设备 %s 弱网代理巡检失败（下轮重试）: %s", serial, e)
            continue
        if row:
            rows.append(row)
    return rows



_PERF_TASKS: dict[int, dict] = {}
_PERF_REQUEUE: list[dict] = []
_PERF_CRASH_REPORTED: set[tuple] = set()
PERF_DRAIN_MAX_ROWS = 7200


def _read_jsonl_tail(path: str, limit: int = PERF_DRAIN_MAX_ROWS) -> list[dict]:
    pass
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    rows: list[dict] = []
    for line in lines[-limit:] if lines else []:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


def _summarize_perf_dir(task_id: int, base: str) -> dict | None:
    pass
    samples = _read_jsonl_tail(os.path.join(base, PERF_SAMPLES_FILENAME))
    crashes = _read_jsonl_tail(os.path.join(base, PERF_CRASHES_FILENAME))
    fresh = []
    for ev in crashes:
        key = (task_id, ev.get("ts"), ev.get("kind"))
        if key in _PERF_CRASH_REPORTED:
            continue
        _PERF_CRASH_REPORTED.add(key)
        fresh.append(ev)
    summary = summarize_rows(samples, fresh)
    if summary is None:
        return None
    summary["taskId"] = task_id
    if len(samples) >= PERF_DRAIN_MAX_ROWS:
        summary["cappedAt"] = PERF_DRAIN_MAX_ROWS
    return summary


def collect_perf_reports() -> list[dict]:
    pass
    with _ACTIVE_LOCK:
        tasks = dict(_PERF_TASKS)
    rows: list[dict] = []
    for task_id, entry in tasks.items():
        base = str(entry.get("dir") or "")
        if not base:
            continue
        try:
            summary = _summarize_perf_dir(task_id, base)
            if summary is not None:
                rows.append(summary)
        except Exception as e:
            log.warning("任务 %s 性能采样摘要排空失败（下轮重试）: %s", task_id, e)
    return rows


def register_perf_session(task_id: int) -> None:
    pass
    with _ACTIVE_LOCK:
        _PERF_TASKS[task_id] = {"dir": perf_dir(task_id)}


def retire_perf_session(task_id: int) -> None:
    pass
    with _ACTIVE_LOCK:
        entry = _PERF_TASKS.pop(task_id, None)
    if entry is None:
        return
    base = str(entry.get("dir") or "")
    if not base:
        return
    try:
        summary = _summarize_perf_dir(task_id, base)
        if summary is not None:
            with _ACTIVE_LOCK:
                _PERF_REQUEUE.append(summary)
    except OSError:
        pass



def monitor_session_active() -> bool:
    pass
    with _ACTIVE_LOCK:
        return bool(_MONITOR_SINKS)


def drain_monitor_frames() -> list[dict]:
    pass
    frames: list[dict] = list(_MONITOR_REQUEUE)
    _MONITOR_REQUEUE.clear()
    with _ACTIVE_LOCK:
        sinks = dict(_MONITOR_SINKS)
    for task_id, (path, offset) in sinks.items():
        try:
            size = os.path.getsize(path)
        except OSError:
            continue
        if size < offset:
            offset = 0
        try:
            with open(path, "r", encoding="utf-8") as f:
                f.seek(offset)
                lines = f.readlines()
                sinks[task_id] = (path, f.tell())
        except OSError:
            continue
        for line in lines:
            line = line.strip()
            if not line:
                continue
            try:
                frame = json.loads(line)
            except ValueError:
                continue
            if isinstance(frame, dict) and frame.get("image"):
                frames.append(frame)
    with _ACTIVE_LOCK:
        for task_id, pos in sinks.items():
            _MONITOR_SINKS[task_id] = pos
    if len(frames) > MONITOR_DRAIN_MAX_FRAMES:
        dropped = len(frames) - MONITOR_DRAIN_MAX_FRAMES
        log.warning("监看帧单轮超限丢旧 %s 帧（体积防炸闸，core 环形双闸兜底）", dropped)
        frames = frames[-MONITOR_DRAIN_MAX_FRAMES:]
    return frames


def requeue_monitor_frames(frames: list[dict]) -> None:
    pass
    if not frames:
        return
    merged = list(_MONITOR_REQUEUE) + list(frames)
    _MONITOR_REQUEUE.clear()
    _MONITOR_REQUEUE.extend(merged[-MONITOR_DRAIN_MAX_FRAMES:])


# ── Perf partial-frame channel drain (heartbeat tails perfPartial, segment b2) ──
# perf_worker (task_type=5 child process) appends incremental frames to a
# task-scoped JSONL line file every flush window (key row = {runId, seq, frames},
# the 03 §2.2 perfPartial trailing key); the heartbeat loop drains it incrementally
# and tails it onto the heartbeat — registration precedes the child process start,
# retirement drains one last time (the final window's frames never drop), same
# posture as _MONITOR_SINKS. Rows carry runId only, never projectId — the core
# reverse lookup owns attribution (misdelivery guard).
_PERF_PARTIAL_SINKS: dict[int, tuple] = {}
# Heartbeat channel failure / per-round cap eviction = drained rows requeue for the
# next beat (curve frames must never silently evaporate)
_PERF_PARTIAL_REQUEUE: list[dict] = []
# Per-round row cap (flush window × concurrent runs bounded against heartbeat size;
# over-cap drops oldest with a named count, core second-anchor idempotency is the
# second gate — same double-gate posture as monitor frames)
PERF_PARTIAL_DRAIN_MAX_ROWS = 20


def drain_perf_partial_rows() -> list[dict]:
    """Drain each running perf task's partial-frame line file incrementally (the
    byte offset advances after reading = a row is never resent).

    Broken lines skip honestly (a half-written child line must not crash the
    heartbeat); the requeue channel (last failed beat / this round's cap eviction)
    takes the slot first so older seq rows stay in order; over-cap drops the oldest
    with a named warning. Missing sink/file = honest zero rows (the task shipped
    nothing yet)."""
    rows: list[dict] = list(_PERF_PARTIAL_REQUEUE)
    _PERF_PARTIAL_REQUEUE.clear()
    with _ACTIVE_LOCK:
        sinks = dict(_PERF_PARTIAL_SINKS)
    for task_id, (path, offset) in sinks.items():
        try:
            size = os.path.getsize(path)
        except OSError:
            continue  # line file absent = nothing shipped yet (honest zero)
        if size < offset:
            offset = 0  # file recycled/rebuilt (defensive reset, never on the happy path)
        try:
            with open(path, "r", encoding="utf-8") as f:
                f.seek(offset)
                lines = f.readlines()
                sinks[task_id] = (path, f.tell())
        except OSError:
            continue
        for line in lines:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except ValueError:
                continue  # broken line skipped honestly
            if isinstance(row, dict) and row.get("runId") is not None:
                rows.append(row)
    with _ACTIVE_LOCK:
        for task_id, pos in sinks.items():
            _PERF_PARTIAL_SINKS[task_id] = pos
    if len(rows) > PERF_PARTIAL_DRAIN_MAX_ROWS:
        dropped = len(rows) - PERF_PARTIAL_DRAIN_MAX_ROWS
        log.warning("perf partial rows over the per-round cap: dropped %s oldest "
                    "(volume gate; core second-anchor idempotency backstops)", dropped)
        rows = rows[-PERF_PARTIAL_DRAIN_MAX_ROWS:]  # keep the newest
    return rows


def requeue_perf_partial_rows(rows: list[dict]) -> None:
    """Failed heartbeat = drained rows requeue for the next beat (no silent
    evaporation); bounded requeue keeps the newest (same posture as monitor frames)."""
    if not rows:
        return
    merged = list(_PERF_PARTIAL_REQUEUE) + list(rows)
    _PERF_PARTIAL_REQUEUE.clear()
    _PERF_PARTIAL_REQUEUE.extend(merged[-PERF_PARTIAL_DRAIN_MAX_ROWS:])


def retire_perf_partial_sink(task_id: int) -> None:
    """Perf session end = last drain before deregistration: residual rows requeue
    so the next heartbeat ships them (the final flush window never drops), then the
    sink entry goes away."""
    with _ACTIVE_LOCK:
        entry = _PERF_PARTIAL_SINKS.pop(task_id, None)
    if entry is None:
        return
    path, offset = entry
    rows: list[dict] = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            f.seek(offset)
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict) and row.get("runId") is not None:
                    rows.append(row)
    except OSError:
        return
    requeue_perf_partial_rows(rows)


def retire_monitor_sink(task_id: int) -> None:
    pass
    with _ACTIVE_LOCK:
        entry = _MONITOR_SINKS.pop(task_id, None)
    if entry is None:
        return
    path, offset = entry
    frames: list[dict] = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            f.seek(offset)
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    frame = json.loads(line)
                except ValueError:
                    continue
                if isinstance(frame, dict) and frame.get("image"):
                    frames.append(frame)
    except OSError:
        return
    requeue_monitor_frames(frames)


def send_heartbeat(client: ExecutorProtocolClient, running_tasks: int,
                   components: list | None = None,
                   net_proxy_audits: list | None = None,
                   perf_reports: list | None = None,
                   devices: list | None = None,
                   project_scope: str | None = None,
                   perf_partial_rows: list | None = None) -> dict:
    pass
    frames = drain_monitor_frames()
    partial_rows = (list(perf_partial_rows) if perf_partial_rows is not None
                    else drain_perf_partial_rows())
    with _ACTIVE_LOCK:
        queued, _PERF_REQUEUE[:] = list(_PERF_REQUEUE), []
    merged = queued + list(perf_reports or [])
    try:
        return client.heartbeat(running_tasks, monitor_frames=frames or None,
                                components=components or None,
                                net_proxy_audits=net_proxy_audits or None,
                                perf_reports=merged or None,
                                devices=devices or None,
                                device_project_scope=project_scope or None,
                                perf_partial=partial_rows or None)
    except Exception:
        requeue_monitor_frames(frames)
        requeue_perf_partial_rows(partial_rows)
        with _ACTIVE_LOCK:
            _PERF_REQUEUE.extend(merged)
        raise


def _upload_screenshots(client: ExecutorProtocolClient, task_id: int,
                        local_paths: list[Optional[str]]) -> list[str]:
    pass
    keys: list[str] = []
    for path in local_paths:
        if not path or not os.path.exists(path):
            continue
        try:
            grant = client.grant_artifact(task_id, "screenshot", os.path.basename(path))
            with open(path, "rb") as f:
                resp = requests.put(grant["uploadUrl"], data=f.read(),
                                    headers={"Content-Type": "image/png"}, timeout=60)
            if resp.status_code in (200, 201):
                keys.append(grant["objectKey"])
                log.info("截图已上传: %s", grant["objectKey"])
            else:
                log.warning("截图上传失败 HTTP %s: %s", resp.status_code, resp.text[:200])
        except Exception as e:
            log.warning("截图上传异常: %s", e)
    return keys


def _upload_pending_artifacts(client: ExecutorProtocolClient, task_id: int,
                              pending: list[dict],
                              steps_timeline: list[dict]) -> None:
    pass
    by_no = {row.get("stepNo"): row for row in steps_timeline
             if isinstance(row, dict)}
    for item in pending:
        path = item.get("localPath")
        row = by_no.get(item.get("stepNo"))
        if not path or not os.path.exists(path):
            continue
        try:
            grant = client.grant_artifact(task_id, str(item.get("kind") or "visual"),
                                          str(item.get("fileName") or "visual.png"))
            with open(path, "rb") as f:
                resp = requests.put(grant["uploadUrl"], data=f.read(),
                                    headers={"Content-Type": str(
                                        item.get("contentType") or "image/png")},
                                    timeout=60)
            if resp.status_code in (200, 201):
                if row is not None:
                    row.setdefault("artifactKey", grant["objectKey"])
                    slot = item.get("slot")
                    if slot:
                        row.setdefault("evidence", {})[str(slot)] = grant["objectKey"]
            else:
                log.warning("视觉产物上传失败 HTTP %s: %s",
                            resp.status_code, resp.text[:200])
        except Exception as e:
            log.warning("视觉产物上传异常: %s", e)


def _cleanup_orphan_drivers() -> None:
    pass
    try:
        subprocess.run(["pkill", "-f", "playwright/driver/node"],
                       capture_output=True, timeout=10)
    except Exception:
        pass


def _kill_task_group(proc) -> None:
    pass
    pid = getattr(proc, "pid", None)
    if not pid:
        return
    try:
        os.killpg(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except Exception as e:
        log.warning("任务进程组 %s 定向清理失败（降级等待自然退出）: %s", pid, e)


def _drain_after_timeout(popen) -> str:
    pass
    try:
        tail = popen.communicate(timeout=5)
        return (tail[1] or "")[-500:] if tail else ""
    except Exception:
        return ""


def _read_outcome(out_f: str, stderr: str) -> RunOutcome:
    pass
    if os.path.exists(out_f):
        try:
            with open(out_f, "r", encoding="utf-8") as f:
                return RunOutcome.from_dict(json.load(f))
        except Exception as e:  # noqa: BLE001
            return RunOutcome(result="fail", error_type=3,
                              error_detail=f"执行器子进程结果解析失败: {e}")
    return RunOutcome(
        result="fail", error_type=3,
        error_detail=f"执行器子进程异常退出（无结果文件）: {(stderr or '')[-500:]}",
        duration_ms=0)


def cancel_task_process(client: ExecutorProtocolClient, task_id: int,
                        claim_token: Optional[str] = None) -> bool:
    pass
    with _ACTIVE_LOCK:
        proc = _ACTIVE_PROCS.get(task_id)
        if proc is None and task_id not in _ACTIVE_PROCS:
            return False
        _CANCEL_REQUESTED.add(task_id)
        token = claim_token or _CLAIM_TOKENS.get(task_id) or ""
    if proc is not None:
        try:
            proc.terminate()
        except Exception as e:
            log.warning("任务 %s terminate 异常（可能已自然退出）: %s", task_id, e)
        wait = getattr(proc, "wait", None)
        if callable(wait):
            try:
                wait(timeout=10)
            except Exception:  # noqa: BLE001
                pass
        _kill_task_group(proc)
    exit_code = getattr(proc, "returncode", None) if proc is not None else None
    try:
        client.submit_result(task_id, token, "cancelled",
                             error_detail=f"进程已按终止指令退出（exit code {exit_code}）")
        log.info("任务 %s 已终止并回传 cancelled（exit=%s）", task_id, exit_code)
    except ProtocolError as e:
        log.error("任务 %s 终止回传失败: %s", task_id, e)
    return True


def apply_cancel_directives(client: ExecutorProtocolClient, resp: dict,
                            claim_tokens: Optional[dict] = None) -> None:
    pass
    if not isinstance(resp, dict):
        return
    for raw in resp.get("cancelTaskIds") or []:
        try:
            task_id = int(raw)
        except (TypeError, ValueError):
            continue
        token = (claim_tokens or {}).get(task_id)
        cancel_task_process(client, task_id, claim_token=token)


def _report_session_artifacts(client: ExecutorProtocolClient, task_id: int,
                              claim_token: str, outcome) -> None:
    pass
    artifacts = getattr(outcome, "session_artifacts", None) or {}
    if not artifacts:
        return
    try:
        client.submit_session_artifacts(task_id, claim_token, artifacts)
        log.info("任务 %s 会话工件已回注（键数 %s）", task_id, len(artifacts))
    except Exception as e:
        log.warning("任务 %s 会话工件回注失败（异机续跑将走重登兜底）: %s", task_id, e)


def execute_task(client: ExecutorProtocolClient, task: dict, cfg: ExecutorConfig) -> None:
    pass
    task_id = int(task["taskId"])
    claim_token = task.get("claimToken", "")
    started = time.time()
    worker_path = _worker_for(int(task.get("taskType") or 1))
    server_timeout_ms = task.get("timeoutMs")
    timeout_sec = (server_timeout_ms or cfg.task_timeout_ms) / 1000.0

    outcome: Optional[RunOutcome] = None
    lease = _lease_of_task(task)
    if lease is not None:
        with _ACTIVE_LOCK:
            _ACTIVE_LEASES[task_id] = lease
            if lease.get("serial"):
                _NET_SERIAL_SEEN.setdefault(str(lease["serial"]), time.monotonic())
    is_app_task = int(task.get("taskType") or 1) == _TASK_TYPE_APP_AUTO_RUN
    monitor_sink = (os.path.join(tempfile.gettempdir(), f"tp-monitor-{task_id}.jsonl")
                    if is_app_task else None)
    if monitor_sink:
        with _ACTIVE_LOCK:
            _MONITOR_SINKS[task_id] = (monitor_sink, 0)
 # Perf partial-frame channel registration ( b2, same posture as the monitor
    # sink: the child appends rows per flush window, the heartbeat drains and tails
    # them onto perfPartial; finally drains one last time, deregisters, deletes the
    # file = the final window never drops)
    is_perf_task = int(task.get("taskType") or 1) == _TASK_TYPE_PERF_RUN
    perf_partial_sink = (os.path.join(tempfile.gettempdir(),
                                      f"tp-perf-partial-{task_id}.jsonl")
                         if is_perf_task else None)
    if perf_partial_sink:
        with _ACTIVE_LOCK:
            _PERF_PARTIAL_SINKS[task_id] = (perf_partial_sink, 0)
    if is_app_task:
        register_perf_session(task_id)
    try:
        with tempfile.TemporaryDirectory(prefix=f"tp-task-{task_id}-") as td:
            task_f = os.path.join(td, "task.json")
            out_f = os.path.join(td, "outcome.json")
            with open(task_f, "w", encoding="utf-8") as f:
                json.dump(_worker_task_payload(task, client, cfg,
                                               monitor_sink=monitor_sink,
                                               perf_partial_sink=perf_partial_sink), f,
                          ensure_ascii=False)
            with _ACTIVE_LOCK:
                _ACTIVE_PROCS[task_id] = None
                _CLAIM_TOKENS[task_id] = claim_token
            stderr = ""
            try:
                popen = subprocess.Popen(
                    [sys.executable, worker_path, task_f, out_f],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                    start_new_session=True)
                with _ACTIVE_LOCK:
                    _ACTIVE_PROCS[task_id] = popen
                try:
                    _stdout, stderr = popen.communicate(timeout=timeout_sec + 30)
                except subprocess.TimeoutExpired:
                    stderr = _drain_after_timeout(popen)
                    log.error("任务 %s 执行卡死（>%ss 未返回）——子进程已终止", task_id, timeout_sec)
                    _cleanup_orphan_drivers()
                    outcome = RunOutcome(
                        result="fail", error_type=3,
                        error_detail=f"执行器卡死防护：任务超过 {timeout_sec:.0f}s 未返回，已终止（进程级隔离）",
                        duration_ms=int((time.time() - started) * 1000))
                    if lease is not None:
                        release_lease_for_task(
                            client, task_id,
                            reason=f"执行超时（>{timeout_sec:.0f}s）被终止，设备随会话回收归还")
                if outcome is None:
                    outcome = _read_outcome(out_f, stderr)
            finally:
                with _ACTIVE_LOCK:
                    _ACTIVE_PROCS.pop(task_id, None)
                    _CLAIM_TOKENS.pop(task_id, None)
                    _ACTIVE_LEASES.pop(task_id, None)
                    cancelled_by_directive = task_id in _CANCEL_REQUESTED
                    _CANCEL_REQUESTED.discard(task_id)
                if monitor_sink:
                    retire_monitor_sink(task_id)
                    try:
                        os.remove(monitor_sink)
                    except OSError:
                        pass
 # Session end: last drain of residual partial rows (the final window never
                # drops) + deregistration + line-file cleanup
                if perf_partial_sink:
                    retire_perf_partial_sink(task_id)
                    try:
                        os.remove(perf_partial_sink)
                    except OSError:
                        pass
                if is_app_task:
                    retire_perf_session(task_id)
                    shutil.rmtree(perf_dir(task_id), ignore_errors=True)
            if cancelled_by_directive:
                log.info("任务 %s 已按终止指令回传，本执行支让位（禁二次回传）", task_id)
                return
    except Exception as e:
        outcome = RunOutcome(result="fail", error_type=3,
                             error_detail=f"执行器子进程启动失败: {e}")

    elapsed = int((time.time() - started) * 1000)
    if outcome.duration_ms == 0:
        outcome.duration_ms = elapsed

    screenshots = _upload_screenshots(client, task_id, outcome.screenshots)
    _upload_pending_artifacts(client, task_id, outcome.pending_artifacts,
                              outcome.steps_timeline)
    _report_session_artifacts(client, task_id, claim_token, outcome)
    console_log = "\n".join(outcome.console_logs[:200])
    try:
        for _sug in getattr(outcome, "step_suggestions", None) or []:
            _sug["taskId"] = task_id
            try:
                client.submit_step_suggestion(_sug)
            except ProtocolError as e:
                log.warning("任务 %s 修复建议单回传失败: %s", task_id, e)
        resp = client.submit_result(
            task_id, claim_token, outcome.result,
            error_type=outcome.error_type, error_detail=outcome.error_detail,
            steps_timeline=outcome.steps_timeline, screenshots=screenshots,
            duration_ms=outcome.duration_ms, log=console_log or None,
            console_violations=outcome.console_violations or None,
            net_hits=outcome.net_hits or None,
            session_auth_rejections=outcome.session_auth_rejections or None,
            db_digest=outcome.db_digest or None,
            protocol_snapshots=outcome.protocol_snapshots or None,
            extracted_vars=outcome.extracted_vars or None,
            gen_seeds=outcome.gen_seeds or None,
            perf_frames=outcome.perf_frames or None,
            perf_events=outcome.perf_events or None,
            perf_status=outcome.perf_status,
            record_events=getattr(outcome, "record_events", None),
 # (03 trailing key mockNotes) web-route audit bucket passthrough
 # (empty = no key; the core-side report face counts the rows)
            mock_notes=getattr(outcome, "mock_notes", None) or None,
 # (03 trailing key mobilePerfSummary) in-window mobile sampling rollup
 # (None/empty = no key; the numbers are computed once by the executor
 # perf modules, core only persists them)
            mobile_perf_summary=getattr(outcome, "mobile_perf_summary", None),
 # (03 trailing key elementAnchorHits) per-attempt ledger rows (WE 3b);
 # empty = key omitted (trailing-key compatibility precedent)
            element_anchor_hits=outcome.element_anchor_hits or None,
            step_heal_evidence=outcome.heal_evidence or None)
        log.info("任务 %s 回传成功（%s）: %s", task_id, outcome.result, resp)
    except ProtocolError as e:
        log.error("任务 %s 结果回传失败: %s", task_id, e)


class _TimeoutGuard:
    pass

    def __init__(self, timeout_sec: float, task_id: int) -> None:
        self.timeout_sec = timeout_sec
        self.task_id = task_id
        self._deadline = time.monotonic() + timeout_sec
        self._timer: Optional[threading.Timer] = None

    def __enter__(self) -> "_TimeoutGuard":
        self._timer = threading.Timer(self.timeout_sec, self._fire)
        self._timer.daemon = True
        self._timer.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        if self._timer:
            self._timer.cancel()
        if exc_type is _TaskTimeout:
            return True
        return False

    def _fire(self) -> None:
        _TIMEOUT_FLAGS[self.task_id] = True
        log.warning("任务 %s 触发超时标记（%ss）", self.task_id, self.timeout_sec)


class _TaskTimeout(Exception):
    pass


_TIMEOUT_FLAGS: dict[int, bool] = {}


def should_reregister(err: ProtocolError, last_attempt: Optional[float],
                      min_interval: float = 10.0, now: Optional[float] = None) -> bool:
    pass
    if getattr(err, "code", None) != "40401":
        return False
    now = now or time.time()
    return last_attempt is None or now - last_attempt >= min_interval


def run_forever(cfg: Optional[ExecutorConfig] = None) -> None:
    pass
    cfg = cfg or ExecutorConfig()
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    client = ExecutorProtocolClient(cfg.api_base_url, cfg.request_timeout)

    for attempt in range(10):
        try:
            executor_id = client.register(cfg.register_payload)
            log.info("注册成功 executorId=%s（attempt=%s）", executor_id, attempt)
            break
        except ProtocolError as e:
            log.warning("注册失败（第 %s 次）: %s", attempt + 1, e)
            time.sleep(5)
    else:
        raise SystemExit("注册连续失败，退出")

    heartbeat_lock = threading.Lock()
    running_tasks = [0]
    reg_attempt: list[Optional[float]] = [None]
    try:
        from capabilities import collect_components
        components_reported = collect_components(executor_version=cfg.version)
    except Exception as e:  # noqa: BLE001
        log.warning("部件版本探测失败（本轮心跳不带扩报键）: %s", e)
        components_reported = []

    def _maybe_reregister(err: ProtocolError) -> None:
        pass
        if not should_reregister(err, reg_attempt[0]):
            return
        try:
            with heartbeat_lock:
                client.register(cfg.register_payload)
            log.info("自愈重注册成功 executorId=%s", client.executor_id)
        except ProtocolError as e2:
            log.warning("自愈重注册失败: %s", e2)
        reg_attempt[0] = time.time()

    def _heartbeat_loop() -> None:
        while True:
            started_at = time.monotonic()
            try:
                try:
                    audits = collect_proxy_audits(cfg.proxy_audit_interval)
                except Exception as e:
                    log.warning("弱网代理巡检异常（心跳主链零影响）: %s", e)
                    audits = []
                try:
                    perf = collect_perf_reports()
                except Exception as e:  # noqa: BLE001
                    log.warning("性能采样摘要排空异常（心跳主链零影响）: %s", e)
                    perf = []
                try:
                    from device_discovery import collect_devices
                    devices = collect_devices(cfg.device_discovery_interval)
                except Exception as e:  # noqa: BLE001
                    log.warning("设备枚举异常（心跳主链零影响）: %s", e)
                    devices = None
                with heartbeat_lock:
                    resp = send_heartbeat(client, running_tasks[0],
                                          components=components_reported,
                                          net_proxy_audits=audits or None,
                                          perf_reports=perf or None,
                                          devices=devices,
                                          project_scope=cfg.device_project_scope or None)
                apply_cancel_directives(client, resp)
                renew_active_leases(client)
                log.debug("心跳: %s", resp)
            except ProtocolError as e:
                log.warning("心跳失败: %s", e)
                _maybe_reregister(e)
            interval = (cfg.monitor_heartbeat_interval
                        if monitor_session_active() else cfg.heartbeat_interval)
            time.sleep(max(0.5, interval - (time.monotonic() - started_at)))

    threading.Thread(target=_heartbeat_loop, daemon=True, name="heartbeat").start()

    def _refresh_components() -> None:
        pass
        nonlocal components_reported
        try:
            from capabilities import collect_components
            components_reported = collect_components(executor_version=cfg.version)
            with heartbeat_lock:
                client.register(cfg.register_payload)
            log.info("装/卸引擎后能力面已重报（部件 %s 项）", len(components_reported))
        except Exception as e:
            log.warning("能力面重报失败（心跳沿用旧清单，TTL/下轮兜底）: %s", e)

    command_ctx = None
    while True:
        try:
            task = client.claim()
        except ProtocolError as e:
            log.warning("claim 失败: %s", e)
            _maybe_reregister(e)
            time.sleep(cfg.claim_interval)
            continue
        if not task:
            try:
                if command_ctx is None:
                    command_ctx = CommandContext(host=detect_host())
                processed = poll_and_consume(client, command_ctx,
                                             on_components=_refresh_components)
                if processed:
                    continue
            except ProtocolError as e:
                log.warning("命令消费轮失败（下轮再试）: %s", e)
                _maybe_reregister(e)
            except Exception as e:
                log.warning("命令消费异常（下轮再试）: %s", e)
            try:
                takeover_io = poll_and_inject_takeover_input(client)
                if takeover_io.injected or takeover_io.dropped or takeover_io.failed:
                    continue
            except ProtocolError as e:
                log.warning("接管输入消费轮失败（下轮再试）: %s", e)
                _maybe_reregister(e)
            except Exception as e:
                log.warning("接管输入消费异常（下轮再试）: %s", e)
            try:
                capture_round = poll_and_run_capture_round(client)
                if capture_round is not None:
                    continue
            except ProtocolError as e:
                log.warning("采集会话消费轮失败（下轮再试）: %s", e)
                _maybe_reregister(e)
            except Exception as e:
                log.warning("采集会话消费异常（下轮再试）: %s", e)
            time.sleep(cfg.claim_interval)
            continue
        log.info("领取任务 %s（caseId=%s）", task.get("taskId"), task.get("caseId"))
        with heartbeat_lock:
            running_tasks[0] += 1
        try:
            execute_task(client, task, cfg)
        finally:
            with heartbeat_lock:
                running_tasks[0] = max(0, running_tasks[0] - 1)


if __name__ == "__main__":
    run_forever()
