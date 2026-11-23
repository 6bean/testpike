
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import time

from actions_common import app_action_violation, is_app_action
from driver.env_resolver import EnvPlaceholderError, resolve_placeholders
from driver.mobile import viewport_scaling
from driver.mobile.adb_verb_runner import NET_TIMELINE_FILENAME
from driver.mobile.popup_selfheal import find_selfheal_plan
from driver.mobile.android_event_stream import start_input_stream
from driver.mobile.contracts import (
    capability_violations,
    step_references_credential,
    unresolved_cred_placeholders,
)
from driver.mobile.engine_factory import mobile_engine_factory
from driver.mobile.monitor_slice import build_failure_recording
from driver.mobile.launch_perf import evaluate_launch_step
from driver.mobile.perf_assert import (HOST_SIDE_VERBS, evaluate_perf_step,
                                       read_rows)
from driver.mobile.perf_session import (
    PERF_LAUNCHES_FILENAME,
    PERF_SAMPLES_FILENAME,
    PerfSession,
    perf_dir,
    resolve_perf_interval,
    resolve_target_pkg,
    summarize_metrics,
)
from driver.mobile.recording import (
    LEVEL_SCRCPY,
    LEVEL_UNAVAILABLE,
    collect_record_events,
    resolve_recording_source,
)
from driver.mobile.visual_fallback import (
    build_locate_anchor,
    build_locate_evidence_packet,
    decide_visual_locate,
    fallback_switch_on,
    parse_confidence_thresholds,
    post_selfcheck_decision,
)
from driver.webdriver import RunOutcome

ENVELOPE_KIND = "app-auto-run"

_DEFAULT_ERROR_TYPE = 3

_CLICKABLE_VERBS = frozenset({"tap", "longPress", "doubleTap"})

_LOCATOR_ERROR_TYPE = 2

# Script/profile defect class (03 result error_type ledger: 1).
_SCRIPT_ERROR_TYPE = 1


def _return_lease(task: dict, lease_ref, reason: str) -> None:
    pass
    if not isinstance(lease_ref, dict):
        return
    device_id = lease_ref.get("deviceId")
    api_base = task.get("_apiBaseUrl")
    executor_id = task.get("_executorId")
    if device_id is None or not api_base or executor_id is None:
        return
    from protocol import ExecutorProtocolClient
    client = ExecutorProtocolClient(str(api_base))
    client.executor_id = int(executor_id)
    client.release_device_lease(int(device_id), reason=reason)


def _build_visual_locate_channel(task: dict):
    pass
    api_base = task.get("_apiBaseUrl")
    executor_id = task.get("_executorId")
    if not api_base or executor_id is None:
        return None
    from protocol import ExecutorProtocolClient

    def channel(request: dict) -> dict:
        import base64
        shot = request.pop("screenshotBytes", None)
        body = dict(request)
        if shot:
            body["screenshotB64"] = base64.b64encode(bytes(shot)).decode()
            body["screenshotMime"] = "image/png"
        client = ExecutorProtocolClient(str(api_base))
        client.executor_id = int(executor_id)
        return client.visual_locate(body)

    return channel


def _build_ai_sample_reader(task: dict):
    pass
    api_base = task.get("_apiBaseUrl")
    executor_id = task.get("_executorId")
    if not api_base or executor_id is None:
        return None
    from protocol import ExecutorProtocolClient

    def reader(request: dict) -> dict:
        client = ExecutorProtocolClient(str(api_base))
        client.executor_id = int(executor_id)
        return client.ai_sample_cache_hit(request)

    return reader



_PKG_MIN_FREE_MB_ENV = "EXECUTOR_PKG_MIN_FREE_MB"
_PKG_MIN_FREE_MB_DEFAULT = 500


def _pkg_min_free_bytes() -> int:
    raw = os.environ.get(_PKG_MIN_FREE_MB_ENV, "")
    try:
        mb = int(raw) if raw.strip() else _PKG_MIN_FREE_MB_DEFAULT
    except ValueError:
        mb = _PKG_MIN_FREE_MB_DEFAULT
    return max(mb, 1) * 1024 * 1024


def _build_package_grant_channel(task: dict):
    pass
    api_base = task.get("_apiBaseUrl")
    executor_id = task.get("_executorId")
    if not api_base or executor_id is None:
        return None
    from protocol import ExecutorProtocolClient

    def channel(request: dict) -> dict:
        client = ExecutorProtocolClient(str(api_base))
        client.executor_id = int(executor_id)
        return client.grant_package_download(request)

    return channel


def _provision_package_sources(task: dict, envelope: dict, steps: list,
                               channel, tmp_files: list) -> RunOutcome | None:
    pass
    run_id = str(envelope.get("runId") or task.get("taskId") or "run")
    base_dir = os.path.join(tempfile.gettempdir(), "testpike-appauto",
                            f"install-{run_id}")
    min_free = _pkg_min_free_bytes()
    for idx, step in enumerate(steps, start=1):
        if str(step.get("action")) != "app.install" or step.get("localPath"):
            continue
        if channel is None:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"第 {idx} 步 app.install 需要包源下载，"
                                           "下载通道未接线（执行器缺 api 地址/执行器 id "
                                           "注入，请检查 runner 启动配置后重跑）")
        free = shutil.disk_usage(tempfile.gettempdir()).free
        if free < min_free:
            return RunOutcome(
                result="fail", error_type=_DEFAULT_ERROR_TYPE,
                error_detail=f"第 {idx} 步 app.install 磁盘剩余不足：执行机临时目录仅剩 "
                             f"{free // (1024 * 1024)} MB，低于最低要求 "
                             f"{min_free // (1024 * 1024)} MB"
                             f"（{_PKG_MIN_FREE_MB_ENV} 可配）——包源下载拒启动，"
                             "请先清理磁盘残留后重跑")
        ref = step.get("artifactRef") or step.get("pkg")
        try:
            grant = channel({"taskId": task.get("taskId"), "artifactRef": ref})
        except Exception as e:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"第 {idx} 步 app.install 包源下载授权失败: {e}")
        url = grant.get("downloadUrl") if isinstance(grant, dict) else None
        if not url:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"第 {idx} 步 app.install 下载授权应答缺 "
                                           f"downloadUrl（服务端应答漂移，禁猜包源）")
        expected_size = grant.get("sizeBytes")
        if (expected_size and shutil.disk_usage(tempfile.gettempdir()).free
                < min_free + int(expected_size)):
            return RunOutcome(
                result="fail", error_type=_DEFAULT_ERROR_TYPE,
                error_detail=f"第 {idx} 步 app.install 磁盘剩余不足：包体积 "
                             f"{int(expected_size) // (1024 * 1024)} MB + 最低余量 "
                             f"{min_free // (1024 * 1024)} MB 放不下，下载拒启动")
        file_name = str(grant.get("fileName") or f"{step.get('pkg') or 'app'}.apk")
        os.makedirs(base_dir, exist_ok=True)
        dest = os.path.join(base_dir, file_name)
        digest = hashlib.sha256()
        try:
            import requests

            resp = requests.get(url, stream=True, timeout=300)
            try:
                resp.raise_for_status()
                with open(dest, "wb") as f:
                    for chunk in resp.iter_content(chunk_size=1 << 16):
                        if chunk:
                            f.write(chunk)
                            digest.update(chunk)
            finally:
                closer = getattr(resp, "close", None)
                if callable(closer):
                    closer()
        except Exception as e:
            _cleanup_pkg_files([dest])
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"第 {idx} 步 app.install 包源下载失败: {e}")
        tmp_files.append(dest)
        want = grant.get("sha256")
        got = digest.hexdigest()
        if want and got != str(want).lower():
            return RunOutcome(
                result="fail", error_type=_DEFAULT_ERROR_TYPE,
                error_detail=f"第 {idx} 步 app.install 包源完整性对账不符：下载件 SHA-256 "
                             f"{got} ≠ 档案登记摘要 {want}（半包已即清，"
                             "禁假安装假通过，请重传包工件后重跑）")
        step["localPath"] = dest
    return None


def _cleanup_pkg_files(paths: list) -> None:
    pass
    for p in paths:
        try:
            os.remove(p)
        except OSError as e:
            print(f"[warn] 临时包文件即清失败（不中断）: {p} {e}", file=sys.stderr)


def _lease_return_reason(outcome: RunOutcome) -> str:
    pass
    if outcome.result == "pass":
        return "正常完成，App 会话收口归还设备"
    detail = outcome.error_detail or ""
    if "引擎会话准备失败" in detail:
        return "会话准备失败（设备绑定/引擎拉起），App 会话收口归还设备"
    failed = next((r for r in outcome.steps_timeline or []
                   if r.get("result") != "pass"), None)
    if failed:
        return (f"首错终局（第 {failed.get('stepNo')} 步 "
                f"{failed.get('action')} 失败），App 会话收口归还设备")
    return "执行失败收口，App 会话收口归还设备"


def _parse_envelope(task: dict) -> tuple[dict | None, RunOutcome | None]:
    pass
    raw = str(task.get("actionJson") or "")
    try:
        envelope = json.loads(raw or "{}")
    except Exception:
        return None, RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                                error_detail="APP 自动化任务信封不是合法 JSON，"
                                             "本次执行按脏单人话终止")
    if not isinstance(envelope, dict) or envelope.get("kind") != ENVELOPE_KIND:
        return None, RunOutcome(
            result="fail", error_type=_DEFAULT_ERROR_TYPE,
            error_detail=f"APP 自动化任务信封 kind 非法"
                         f"（期望 {ENVELOPE_KIND}）: "
                         f"{envelope.get('kind') if isinstance(envelope, dict) else '非对象'!r}")
    return envelope, None


def _steps_of(envelope: dict) -> list:
    steps = envelope.get("steps")
    return steps if isinstance(steps, list) else []


def _resolve_env_refs_in_steps(task: dict, envelope: dict | None,
                               steps: list) -> tuple[list, "RunOutcome | None"]:
    pass
    industry = (envelope or {}).get("industryParams")
    if not isinstance(industry, dict) or not industry:
        return steps, None
    vars_layer = dict(industry)
    explicit = task.get("envVars")
    if isinstance(explicit, dict):
        vars_layer.update(explicit)
    try:
        return resolve_placeholders(steps, vars_layer), None
    except EnvPlaceholderError as e:
        return steps, RunOutcome(
            result="fail", error_type=_DEFAULT_ERROR_TYPE,
            error_detail=f"步骤参数的环境引用无法解析：{e}"
                         "（该键也可由项目行业插件包的装配参数提供，"
                         "请核对环境参数与行业包装配后重跑）")


def run_app_task(task: dict) -> RunOutcome:
    pass
    started = time.monotonic()
    envelope, bad = _parse_envelope(task)
    if bad is not None:
        return bad

    steps = _steps_of(envelope)
    for idx, step in enumerate(steps, start=1):
        action = str((step or {}).get("action") or "")
        if not is_app_action(action):
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"第 {idx} 步动作 {action or '（空）'} 不是 "
                                           f"app.* IR 动作（APP 执行面动词前缀=app.）")
        violation = app_action_violation(action)
        if violation:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"第 {idx} 步：{violation}")
        leftover = unresolved_cred_placeholders(step)
        if leftover:
            keys = "、".join(sorted(set(leftover)))
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail="第 " + str(idx) + " 步存在未解析的凭据引用 "
                                           "{{CRED:" + keys + "}}——凭据实值应由派发前"
                                           "服务端注入，请检查该任务的凭据建档/启用状态后重跑")

    steps, env_ref_fail = _resolve_env_refs_in_steps(task, envelope, steps)
    if env_ref_fail is not None:
        return env_ref_fail

    adapter = task.get("_adapterOverride")
    if adapter is None:
        try:
            descriptor = envelope.get("engine")
            adapter = mobile_engine_factory(descriptor)
        except ValueError as e:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=str(e))
        mismatch = capability_violations(adapter, envelope.get("engine") or {})
        if mismatch:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=mismatch)

    ctx = {"runId": envelope.get("runId"), "leaseRef": envelope.get("leaseRef"),
           "taskId": task.get("taskId")}
    recording_source = None
    if (envelope or {}).get("record"):
        recording_source = resolve_recording_source((envelope or {}).get("engine"), adapter)
        if recording_source["level"] == LEVEL_UNAVAILABLE:
            return RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                              error_detail=f"录制会话不可用：{recording_source['reason']}"
                                           "（引擎会话未拉起，零步执行）")
        if recording_source["level"] == LEVEL_SCRCPY:
            lease = (envelope or {}).get("leaseRef") or {}
            capture = start_input_stream(lease.get("serial"))
            if capture is not None:
                recording_source["capture"] = capture
        ctx["_recordingSource"] = recording_source
    if task.get("_monitorFrameSink"):
        ctx["_monitorFrameSink"] = task["_monitorFrameSink"]
    channel = task.get("_visualLocateChannel")
    if not callable(channel):
        channel = _build_visual_locate_channel(task)
    if channel is not None:
        ctx["_visualLocateChannel"] = channel
    reader = task.get("_aiSampleReader")
    if not callable(reader):
        reader = _build_ai_sample_reader(task)
    if reader is not None:
        ctx["_aiSampleReader"] = reader
    if envelope.get("envProfile") is not None:
        ctx["envProfile"] = envelope["envProfile"]
    # viewport scaling reader injection (L2 stub explicit callable only, same
    # shape as _visualLocateChannel; absent key = real adb face, once per run)
    vp_reader = task.get("_viewportResolutionReader")
    if callable(vp_reader):
        ctx["_viewportResolutionReader"] = vp_reader
    pkg_channel = task.get("_packageGrantChannel")
    if not callable(pkg_channel):
        pkg_channel = _build_package_grant_channel(task)
    pkg_tmp: list[str] = []
    provision_fail = _provision_package_sources(task, envelope, steps, pkg_channel, pkg_tmp)
    if provision_fail is not None:
        _cleanup_pkg_files(pkg_tmp)
        return provision_fail
    try:
        outcome = _drive(adapter, steps, ctx, envelope)
    finally:
        _cleanup_pkg_files(pkg_tmp)
    if recording_source is not None:
        outcome.record_events = collect_record_events(adapter, recording_source)
        capture = recording_source.get("capture")
        stopper = getattr(capture, "stop", None)
        if callable(stopper):
            try:
                stopper()
            except Exception:
                pass

    try:
        _return_lease(task, envelope.get("leaseRef"), _lease_return_reason(outcome))
    except Exception as e:
        outcome.error_detail = ((outcome.error_detail or "") +
                                f"；[租约归还钩子异常（不覆盖执行结果）: {e}]").strip("；")
    if outcome.duration_ms == 0:
        outcome.duration_ms = int((time.monotonic() - started) * 1000)
    return outcome


def _platform_codes(envelope: dict) -> list[str]:
    engine = (envelope or {}).get("engine") or {}
    return [str(c).strip().upper() for c in (engine.get("platformCodes") or [])]


def _is_ios_envelope(envelope: dict) -> bool:
    pass
    return "IOS" in _platform_codes(envelope)


def _is_harmony_envelope(envelope: dict) -> bool:
    pass
    codes = _platform_codes(envelope)
    return "HARMONY_NEXT" in codes and "IOS" not in codes


def _viewport_transform_for(envelope: dict, ctx: dict) -> dict | None:
    """Build the per-run viewport scaling transform at the single dispatch face.

    Zero-touch by contract: without a declared ``resolutionBaseline`` key the
    device plane is never consulted and steps dispatch unchanged. The baseline
    rides the env profile payload projection (envelope.envProfile, core
    AppAutoDispatchSupport single source); the live device resolution is read
    once per run. L2 injects ctx["_viewportResolutionReader"] (explicit callable,
    never the real dispatch chain); production shells out to ``adb shell wm
    size`` inside viewport_scaling.read_device_resolution. An illegal declared
    baseline raises ValueError from make_transform (profile defect named by key,
    never masked as identity).
    """
    profile = (envelope or {}).get("envProfile") or {}
    raw = profile.get(viewport_scaling.BASELINE_PAYLOAD_KEY)
    if raw is None:
        return None
    reader = (ctx or {}).get("_viewportResolutionReader")
    read = reader if callable(reader) else viewport_scaling.read_device_resolution
    serial = str(((envelope.get("leaseRef") or {}).get("serial")) or "")
    return viewport_scaling.make_transform(raw, read(serial))


def _scaled_recorded_step(step: dict, transform: dict | None) -> dict:
    """Map recorded coordinate channels through the viewport transform.

    Only point channels (x/y) exist on recorded IR steps today (swipe/scroll
    carry engine-side direction keywords). Live-space dispatches — the visual
    fallback hit point, takeover input frames — must NOT come through here:
    their coordinates already match the device coordinate space.
    """
    if not transform or transform.get("identity"):
        return step
    if step.get("x") is None or step.get("y") is None:
        return step
    try:
        sx, sy = viewport_scaling.scale_point(transform,
                                             float(step["x"]), float(step["y"]))
    except (TypeError, ValueError):
        # channel present but not numeric = script defect owned by the engine
        # face; dispatch verbatim instead of inventing a second rejection here
        return step
    return dict(step, x=sx, y=sy)


def _drive(adapter, steps: list, ctx: dict, envelope: dict) -> RunOutcome:
    pass
    timeline: list[dict] = []
    pending: RunOutcome | None = None
    # Variant scaling is decided once per run BEFORE the engine session: an
    # illegal declared baseline rejects the run with zero steps and zero
    # prepare (profile defect must not burn a device lease).
    try:
        viewport = _viewport_transform_for(envelope, ctx)
    except ValueError as e:
        return RunOutcome(result="fail", error_type=_SCRIPT_ERROR_TYPE,
                          error_detail=str(e))
    viewport_note = (viewport or {}).get("note")
    artifacts: list[dict] = []
    art_dir = os.path.join(tempfile.gettempdir(), "testpike-appauto",
                           str(ctx.get("taskId") or ctx.get("runId") or "run"))
    pkg = resolve_target_pkg(envelope)
    session = None
    if ctx.get("taskId") is not None:
        if _is_ios_envelope(envelope):
            from driver.mobile.sim_perf import SimPerfSession
            session = SimPerfSession(
                udid=str(((envelope.get("leaseRef") or {}).get("serial")) or ""),
                interval=resolve_perf_interval(envelope),
                dir_path=perf_dir(ctx["taskId"]),
                frame_sink=ctx.get("_monitorFrameSink"))
        elif _is_harmony_envelope(envelope):
            pass
        elif pkg:
            session = PerfSession(serial=str(((envelope.get("leaseRef") or {})
                                              .get("serial")) or ""),
                                  pkg=pkg, interval=resolve_perf_interval(envelope),
                                  dir_path=perf_dir(ctx["taskId"]))
    try:
        try:
            adapter.prepare(ctx)
        except Exception as e:
            # The variant-scaling note is an honest-record obligation: when the
            # engine session never comes up there is no step row to carry it,
            # so it rides the failure detail instead of vanishing silently.
            detail = f"引擎会话准备失败（设备绑定/引擎拉起）: {e}"
            if viewport_note:
                detail = f"{detail}；{viewport_note}"
            pending = RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                                 error_detail=detail,
                                 steps_timeline=timeline)
        if session is not None and pending is None:
            session.start()
        if pending is None:
            for idx, step in enumerate(steps, start=1):
                action = str(step.get("action"))
                verb = action[len("app."):]
                t0 = time.monotonic()
                try:
                    if verb in HOST_SIDE_VERBS:
                        # Host-side verb: judged from the perf rows by
                        # perf_assert, launch timing is recorded on the host
                        # through the adb session — never routed to an engine
                        # adapter (viewport scaling does not apply here).
                        host_step = dict(step, verb=verb, artifactDir=art_dir)
                        res = (evaluate_launch_step(host_step, session)
                               if verb == "launchPerf"
                               else evaluate_perf_step(host_step, session))
                    else:
                        res = adapter.execute(_scaled_recorded_step(
                            dict(step, verb=verb, artifactDir=art_dir), viewport))
                except Exception as e:
                    res = {"result": "fail", "errorType": _DEFAULT_ERROR_TYPE,
                           "detail": f"引擎执行步异常: {e}"}
                if not isinstance(res, dict):
                    res = {"result": "fail", "errorType": _DEFAULT_ERROR_TYPE,
                           "detail": f"适配器 execute 返回不是结构化结果: {res!r}"}
                t1 = time.monotonic()
                row = {"stepNo": idx, "action": action,
                       "result": res.get("result"),
                       "durationMs": int((t1 - t0) * 1000),
                       "detail": res.get("detail")}
                if viewport_note and idx == 1:
                    # device-resolution unreadable = identity dispatch; the note
                    # belongs in the execution record (visible fallback, never
                    # silent), pinned once on the first row
                    row["detail"] = ((row["detail"] + "；" if row["detail"]
                                      else "") + viewport_note)
                if session is not None and session.active:
                    row["tsStart"] = round(session.wall_clock(t0), 3)
                    row["tsEnd"] = round(session.wall_clock(t1), 3)
                timeline.append(row)
                if res.get("result") != "pass":
                    _collect(adapter, step, row, artifacts, art_dir)
                    _attach_failure_recording(row, ctx, artifacts, art_dir)
                    _attach_net_timeline(row, ctx, artifacts, art_dir)
                    error_type = int(res.get("errorType") or _DEFAULT_ERROR_TYPE)
                    if _try_popup_selfheal(adapter, step, row, envelope,
                                           error_type, verb, art_dir,
                                           viewport=viewport):
                        _emit_monitor_frame(adapter, step, row, ctx, art_dir)
                        continue
                    if _try_visual_fallback(adapter, step, row, ctx, envelope,
                                            error_type, verb, artifacts, art_dir):
                        _emit_monitor_frame(adapter, step, row, ctx, art_dir)
                        continue
                    pending = RunOutcome(
                        result="fail",
                        error_type=error_type,
                        error_detail=f"第 {idx} 步 {action} 失败："
                                     f"{row.get('detail') or '（引擎未给归因详情）'}",
                        steps_timeline=timeline)
                    break
                _collect(adapter, step, row, artifacts, art_dir)
                _emit_monitor_frame(adapter, step, row, ctx, art_dir)
            if pending is None:
                pending = RunOutcome(result="pass", steps_timeline=timeline)
        pending.pending_artifacts = artifacts
        return pending
    finally:
        if session is not None:
            session.stop()
 # W5 phase-two P4c: the in-window rollup is computed right after the stop
        # (last-second rows included) and trails on the outcome — the batch
        # report surface consumes these aggregate numbers, never the raw rows.
        if ctx.get("taskId") is not None and pending is not None:
            _attach_mobile_perf_summary(pending, perf_dir(ctx["taskId"]))
        if ctx.get("taskId") is not None and timeline:
            last_row = timeline[-1]
            if isinstance(last_row, dict) and last_row.get("stepNo", 0) >= 1:
                _attach_perf_timeline(last_row, ctx, artifacts,
                                      perf_dir(ctx["taskId"]), art_dir)
        try:
            adapter.teardown()
        except Exception as e:
            timeline.append({"stepNo": 0, "action": "teardown",
                             "result": "warn", "detail": f"会话收异常: {e}"})


def _locate_inputs(artifacts: list[dict], step_no: int) -> tuple:
    pass
    shot_bytes: bytes | None = None
    cands: list = []
    for item in artifacts:
        if item.get("stepNo") != step_no:
            continue
        try:
            if item.get("kind") == "screenshot" and shot_bytes is None:
                with open(item["localPath"], "rb") as f:
                    shot_bytes = f.read()
            elif item.get("kind") == "dom_snapshot":
                with open(item["localPath"], "r", encoding="utf-8") as f:
                    loaded = json.load(f)
                cands = loaded if isinstance(loaded, list) else []
        except Exception:
            continue
    return shot_bytes, cands


def _read_sample_cache_hit(ctx: dict, step: dict, row: dict):
    pass
    reader = ctx.get("_aiSampleReader")
    if not callable(reader):
        return None
    lease_ref = ctx.get("leaseRef")
    device_id = lease_ref.get("deviceId") if isinstance(lease_ref, dict) else None
    try:
        hit = reader({"taskId": ctx.get("taskId"), "runId": ctx.get("runId"),
                      "stepNo": row["stepNo"],
                      "anchor": build_locate_anchor(row["stepNo"], step),
                      "deviceId": device_id})
    except Exception as e:
        row["detail"] = ((row.get("detail") or "")
                         + f"；样本库同屏票读取异常（{e}），按两源共识判定")
        return None
    if not isinstance(hit, dict) or hit.get("x") is None or hit.get("y") is None:
        return None
    return hit


def _popup_rules_of(envelope: dict) -> list:
    """Confirmed popup rules carried by the dispatch envelope.

    The envelope key ``popupRules`` holds server-approved rule rows (status=2
    in tp_appauto_popup_rule, human-approved only — the decision core re-checks
    the IR verb whitelist regardless). Missing/invalid = empty set: the heal is
    a pure bonus path and must stay silent when the library did not ship rules.
    """
    rules = envelope.get("popupRules")
    return rules if isinstance(rules, list) else []


def _clause_probe_step(clause: dict, art_dir: str) -> dict:
    """Bind one detect clause onto the adapter's own visibility verb.

    Single source discipline: presence is answered by the engine face that the
    runtime contract already defines (app.assertVisible), never by a second
    dump parser here. Text clauses carry the text payload; selector clauses
    merge the selector dict verbatim (locator translation stays the adapter's).
    """
    payload = {"text": clause["text"]} if "text" in clause else dict(
        clause.get("selector") or {})
    return {"action": "app.assertVisible", **payload,
            "verb": "assertVisible", "artifactDir": art_dir}


def _try_popup_selfheal(adapter, step: dict, row: dict, envelope: dict,
                        error_type: int, verb: str, art_dir: str,
                        viewport: dict | None = None) -> bool:
    pass
    if error_type != _LOCATOR_ERROR_TYPE:
        return False
    rules = _popup_rules_of(envelope)
    if not rules:
        return False

    def probe(clause: dict) -> bool:
        # rule clauses were learned against the baseline resolution too
        res = adapter.execute(_scaled_recorded_step(
            _clause_probe_step(clause, art_dir), viewport))
        return isinstance(res, dict) and res.get("result") == "pass"

    plan = find_selfheal_plan(rules, probe)
    if plan is None:
        return False
    dismiss = dict(plan["dismissStep"])
    dismiss_verb = str(dismiss.pop("action"))[len("app."):]
    got = adapter.execute(_scaled_recorded_step(
        dict(dismiss, verb=dismiss_verb, artifactDir=art_dir), viewport))
    if not isinstance(got, dict) or got.get("result") != "pass":
        row["detail"] = ((row.get("detail") or "") +
                         f"；弹窗自愈未生效（规则「{plan['title']}」的关闭动作"
                         "执行失败），保持原样报错")
        return False
    retried = adapter.execute(_scaled_recorded_step(
        dict(step, verb=verb, artifactDir=art_dir), viewport))
    if not isinstance(retried, dict) or retried.get("result") != "pass":
        row["detail"] = ((row.get("detail") or "") +
                         f"；弹窗已按规则「{plan['title']}」关闭，但本步重试仍"
                         "失败，保持原样报错")
        return False
    row["result"] = "pass"
    row["popupSelfheal"] = {"ruleId": plan["ruleId"], "title": plan["title"],
                            "matchedClause": plan["matchedClause"],
                            "dismissAction": f"app.{dismiss_verb}"}
    return True


def _try_visual_fallback(adapter, step: dict, row: dict, ctx: dict,
                         envelope: dict, error_type: int, verb: str,
                         artifacts: list[dict], art_dir: str) -> bool:
    pass
    if error_type != _LOCATOR_ERROR_TYPE:
        return False
    cfg = envelope.get("visualFallback")
    if not isinstance(cfg, dict) or not fallback_switch_on(cfg):
        return False
    if verb not in _CLICKABLE_VERBS:
        row["detail"] = ((row.get("detail") or "")
                         + f"；视觉定位兜底未接线：本步动词 {verb or '（空）'} 非点击类，"
                           "仅可兜底点击类（tap/longPress/doubleTap）改按坐标点击，"
                           "保持原样报错")
        return False
    channel = ctx.get("_visualLocateChannel")
    if not callable(channel):
        row["detail"] = ((row.get("detail") or "")
                         + "；视觉定位兜底开关已开但模型通道未接线"
                           "（core→ai-service 视觉调用通道缺位），保持原样报错")
        return False
    shot_bytes, cands = _locate_inputs(artifacts, row["stepNo"])
    try:
        vision = channel({"taskId": ctx.get("taskId"), "runId": ctx.get("runId"),
                          "stepNo": row["stepNo"], "action": row.get("action"),
                          "step": step, "a11yCandidates": cands,
                          "screenshotBytes": shot_bytes})
    except Exception as e:
        row["detail"] = ((row.get("detail") or "")
                         + f"；视觉兜底通道异常（{e}），保持原样报错")
        return False
    thresholds = parse_confidence_thresholds(
        str(cfg.get("ai.appauto.locate.confidence") or ""))
    cache_hit = _read_sample_cache_hit(ctx, step, row)
    decision = decide_visual_locate(vision, cands, cache_hit, thresholds)
    score = decision["score"]
    if decision["tier"] == "reject":
        row["detail"] = ((row.get("detail") or "")
                         + f"；视觉兜底弃点：{decision['rewrite_hint']}")
        row["locateEvidence"] = build_locate_evidence_packet(
            step_no=row["stepNo"], action=str(row.get("action")), score=score,
            vision=vision, a11y_candidates=cands, cache_hit=cache_hit,
            screenshot_key=None)
        return False
    point = decision["point"]
    retried = adapter.execute(dict(step, verb=verb, x=point[0], y=point[1]))
    if not isinstance(retried, dict) or retried.get("result") != "pass":
        got = (retried.get("detail") if isinstance(retried, dict) else None) \
            if isinstance(retried, dict) else retried
        row["detail"] = ((row.get("detail") or "")
                         + f"；AI 视觉点位直点重试仍失败：{got or '引擎未给归因详情'}")
        return False
    if decision["tier"] == "direct":
        row["result"] = "pass"
        row["aiLocate"] = {"tier": "direct", "score": score,
                           "badge": decision["badge"]}
        return True
    after_bytes = _recollect_shot_bytes(adapter, row, art_dir)
    frame_changed = (shot_bytes is not None and after_bytes is not None
                     and shot_bytes != after_bytes)
    verdict = post_selfcheck_decision(frame_changed, row["stepNo"],
                                      str(row.get("action")), score,
                                      vision=vision, a11y_candidates=cands,
                                      cache_hit=cache_hit)
    if verdict["action"] == "continue":
        row["result"] = "pass"
        row["aiLocate"] = {"tier": "selfcheck", "score": score,
                           "selfcheck": "frame-changed"}
        return True
    row["detail"] = verdict["detail"]
    row["locateEvidence"] = verdict["evidence"]
    return False


def _recollect_shot_bytes(adapter, row: dict, art_dir: str) -> bytes | None:
    pass
    try:
        art = adapter.collect({"stepNo": row["stepNo"], "step": {},
                               "artifactDir": art_dir})
    except Exception:
        return None
    items = [art] if isinstance(art, dict) else (art if isinstance(art, list) else [])
    for item in items:
        if isinstance(item, dict) and item.get("kind") == "screenshot":
            try:
                with open(item["localPath"], "rb") as f:
                    return f.read()
            except Exception:  # noqa: BLE001
                return None
    return None


def _emit_monitor_frame(adapter, step: dict, row: dict, ctx: dict,
                        art_dir: str) -> None:
    pass
    sink = ctx.get("_monitorFrameSink")
    if not sink:
        return
    watch = getattr(adapter, "watch_frame", None)
    if not callable(watch):
        return
    try:
        frame = watch(art_dir)
    except Exception:
        return
    if not isinstance(frame, dict) or not frame.get("image"):
        return
    frame = dict(frame, taskId=ctx.get("taskId"), stepNo=row.get("stepNo"),
                 sensitive=step_references_credential(step), ts=time.time())
    try:
        with open(sink, "a", encoding="utf-8") as f:
            f.write(json.dumps(frame, ensure_ascii=False) + "\n")
    except Exception:
        return


_ARTIFACT_DEFAULT_CONTENT_TYPES = {"dom_snapshot": "application/json",
                                   "log_tail": "text/plain",
                                   "recording": "video/mp4",
                                   "net_timeline": "text/plain",
                                   "perf_timeline": "text/plain"}

_EVIDENCE_SLOTS = {"screenshot": "screenshot", "dom_snapshot": "view_tree",
                   "log_tail": "log_tail", "recording": "record",
                   "net_timeline": "net_timeline",
                   "perf_timeline": "perf_timeline"}


def _attach_failure_recording(row: dict, ctx: dict, out: list[dict],
                              art_dir: str) -> None:
    pass
    sink = ctx.get("_monitorFrameSink")
    if not sink:
        return
    try:
        art = build_failure_recording(sink, row["stepNo"], art_dir)
    except Exception:
        return
    if not art:
        return
    art["stepNo"] = row["stepNo"]
    art["slot"] = _EVIDENCE_SLOTS["recording"]
    out.append(art)


def _attach_net_timeline(row: dict, ctx: dict, out: list[dict],
                         art_dir: str) -> None:
    pass
    if ctx.get("_netTimelineAttached"):
        return
    path = os.path.join(art_dir, NET_TIMELINE_FILENAME)
    if not os.path.isfile(path):
        return
    ctx["_netTimelineAttached"] = True
    out.append({"stepNo": row["stepNo"], "kind": "net_timeline",
                "fileName": NET_TIMELINE_FILENAME, "localPath": path,
                "contentType": _ARTIFACT_DEFAULT_CONTENT_TYPES["net_timeline"],
                "slot": _EVIDENCE_SLOTS["net_timeline"]})


def _attach_mobile_perf_summary(outcome, perf_base: str) -> None:
    """W5 phase-two P4c: trail the in-window sampling rollup on the outcome.

    Reads both sinks through the shared row parser (perf_assert.read_rows)
    and aggregates once via perf_session.summarize_metrics — the only
    aggregation point on the executor side, so core persists the numbers
    without re-parsing raw adb rows (no second metric-extraction source).
    No readable metric = outcome untouched (empty = no key, trailing-key
    zero-drift). Bypass rule: read_rows already swallows unreadable sinks
    honestly; the side channel never changes the execution verdict.
    """
    summary = summarize_metrics(
        read_rows(os.path.join(perf_base, PERF_SAMPLES_FILENAME)),
        read_rows(os.path.join(perf_base, PERF_LAUNCHES_FILENAME)),
    )
    if summary:
        outcome.mobile_perf_summary = summary


def _attach_perf_timeline(row: dict, ctx: dict, out: list[dict],
                          perf_base: str, art_dir: str) -> None:
    pass
    if ctx.get("_perfTimelineAttached"):
        return
    src = os.path.join(perf_base, PERF_SAMPLES_FILENAME)
    if not os.path.isfile(src):
        return
    dest = os.path.join(art_dir, PERF_SAMPLES_FILENAME)
    try:
        os.makedirs(art_dir, exist_ok=True)
        shutil.copy(src, dest)
    except OSError:
        return
    ctx["_perfTimelineAttached"] = True
    out.append({"stepNo": row["stepNo"], "kind": "perf_timeline",
                "fileName": PERF_SAMPLES_FILENAME, "localPath": dest,
                "contentType": _ARTIFACT_DEFAULT_CONTENT_TYPES["perf_timeline"],
                "slot": _EVIDENCE_SLOTS["perf_timeline"]})


def _collect(adapter, step: dict, row: dict, out: list[dict],
             art_dir: str) -> None:
    pass
    try:
        art = adapter.collect({"stepNo": row["stepNo"], "step": step,
                               "artifactDir": str(step.get("artifactDir") or art_dir)})
    except Exception:
        return
    items = [art] if isinstance(art, dict) else (art if isinstance(art, list) else [])
    for item in items:
        if not isinstance(item, dict) or not item.get("localPath"):
            continue
        kind = str(item.get("kind") or "screenshot")
        entry = {"stepNo": row["stepNo"], "kind": kind,
                 "fileName": os.path.basename(str(item["localPath"])),
                 "localPath": str(item["localPath"]),
                 "contentType": str(item.get("contentType")
                                    or _ARTIFACT_DEFAULT_CONTENT_TYPES.get(
                                        kind, "image/png"))}
        if row.get("result") != "pass" and kind in _EVIDENCE_SLOTS:
            entry["slot"] = _EVIDENCE_SLOTS[kind]
        out.append(entry)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("用法: app_worker.py <task.json> <outcome.json>", file=sys.stderr)
        return 2
    task_f, out_f = argv[1], argv[2]
    try:
        with open(task_f, "r", encoding="utf-8") as f:
            task = json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"任务文件读取失败: {e}", file=sys.stderr)
        return 2
    try:
        outcome = run_app_task(task)
    except Exception as e:
        outcome = RunOutcome(result="fail", error_type=_DEFAULT_ERROR_TYPE,
                             error_detail=f"APP 自动化执行异常: {e}")
    try:
        with open(out_f, "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        print(f"结果文件写入失败: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
