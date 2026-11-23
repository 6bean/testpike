
from __future__ import annotations

from typing import Any, Optional
from urllib.parse import quote

import requests


class ProtocolError(Exception):
    pass

    def __init__(self, message: str, code: Optional[str] = None) -> None:
        super().__init__(message)
        self.code = code


class ExecutorProtocolClient:
    def __init__(self, api_base_url: str, timeout: float = 30.0) -> None:
        self.base = api_base_url.rstrip("/")
        self.timeout = timeout
        self.executor_id: Optional[int] = None

    def _post(self, path: str, payload: dict) -> dict:
        try:
            resp = requests.post(self.base + path, json=payload, timeout=self.timeout)
        except requests.RequestException as e:
            raise ProtocolError(f"请求失败 {path}: {e}") from e
        try:
            body = resp.json()
        except ValueError as e:
            raise ProtocolError(f"响应非 JSON（HTTP {resp.status_code}）: {resp.text[:200]}") from e
        if body.get("code") not in (0, "0", None):
            raise ProtocolError(f"业务错误 code={body.get('code')} msg={body.get('msg')}",
                                code=str(body.get("code")))
        return body.get("data") or {}

    def _get(self, path: str) -> dict:
        try:
            resp = requests.get(self.base + path, timeout=self.timeout)
        except requests.RequestException as e:
            raise ProtocolError(f"请求失败 {path}: {e}") from e
        try:
            body = resp.json()
        except ValueError as e:
            raise ProtocolError(f"响应非 JSON（HTTP {resp.status_code}）: {resp.text[:200]}") from e
        if body.get("code") not in (0, "0", None):
            raise ProtocolError(f"业务错误 code={body.get('code')} msg={body.get('msg')}",
                                code=str(body.get("code")))
        return body.get("data") or {}

    def register(self, payload: dict) -> int:
        pass
        data = self._post("/internal/executors/register", payload)
        executor_id = data.get("executorId")
        if executor_id is None:
            raise ProtocolError(f"register 响应缺少 executorId: {data}")
        self.executor_id = int(executor_id)
        return self.executor_id

    def heartbeat(self, running_tasks: int,
                  monitor_frames: Optional[list[dict]] = None,
                  components: Optional[list[dict]] = None,
                  net_proxy_audits: Optional[list[dict]] = None,
                  perf_reports: Optional[list[dict]] = None,
                  devices: Optional[list[dict]] = None,
                  device_project_scope: Optional[str] = None,
                  perf_partial: Optional[list[dict]] = None) -> dict:
        pass
        body: dict = {"runningTasks": running_tasks}
        if monitor_frames:
            body["monitorFrames"] = monitor_frames
        if components:
            body["components"] = components
        if net_proxy_audits:
            body["netProxyAudits"] = net_proxy_audits
        if perf_reports:
            body["perfReports"] = perf_reports
        if perf_partial:
            body["perfPartial"] = perf_partial
        if devices:
            body["devices"] = devices
        if device_project_scope:
            body["deviceProjectScope"] = device_project_scope
        return self._post(f"/internal/executors/heartbeat?executorId={self.executor_id}", body)

    def claim(self) -> Optional[dict]:
        pass
        data = self._post("/internal/tasks/claim", {"executorId": self.executor_id})
        return data.get("task")

    def submit_step_suggestion(self, payload: dict) -> dict:
        """WEB repair suggestion from the executor side (03 2.3 trailing
        channel). core AppautoStepHealingService.record stays the single
        gate: ownership reverse-lookup, WEB-shape checks, adoption lock."""
        return self._post("/internal/appauto/step-suggestions/record", payload)

    def submit_result(self, task_id: int, claim_token: str, result: str,
                      error_type: Optional[int] = None, error_detail: Optional[str] = None,
                      steps_timeline: Optional[list[dict]] = None,
                      screenshots: Optional[list[str]] = None,
                      duration_ms: Optional[int] = None,
                      log: Optional[str] = None,
                      console_violations: Optional[list[dict]] = None,
                      net_hits: Optional[list[dict]] = None,
                      db_digest: Optional[list[str]] = None,
                      protocol_snapshots: Optional[list[dict]] = None,
                      extracted_vars: Optional[dict] = None,
                      gen_seeds: Optional[list] = None,
                      perf_frames: Optional[list] = None,
                      perf_events: Optional[list] = None,
                      perf_status: Optional[int] = None,
                      record_events: Optional[dict] = None,
                      mock_notes: Optional[list[dict]] = None,
                      mobile_perf_summary: Optional[dict] = None,
                      session_auth_rejections: Optional[list[dict]] = None,
                      element_anchor_hits: Optional[list[dict]] = None,
                      step_heal_evidence: Optional[list[dict]] = None) -> dict:
        pass
        payload = {
            "taskId": task_id, "claimToken": claim_token, "result": result,
            "errorType": error_type, "errorDetail": error_detail,
            "stepsTimeline": steps_timeline, "screenshots": screenshots,
            "durationMs": duration_ms, "log": log,
        }
        if console_violations:
            payload["consoleViolations"] = console_violations
        if net_hits:
            payload["netHits"] = net_hits
        if session_auth_rejections:
            payload["sessionAuthRejections"] = session_auth_rejections
        if db_digest:
            payload["dbDigest"] = db_digest
        if protocol_snapshots:
            payload["protocolSnapshots"] = protocol_snapshots
        if extracted_vars:
            payload["vars"] = extracted_vars
        if gen_seeds:
            payload["genSeeds"] = gen_seeds
        if perf_frames:
            payload["perfFrames"] = perf_frames
        if perf_events:
            payload["perfEvents"] = perf_events
        if perf_status is not None:
            payload["perfStatus"] = perf_status
        if record_events:
            payload["recordEvents"] = record_events
 # (03 result trailing key mockNotes, U5 audit bucket) {path,mode[,status]}
 # rows collected by the web-route interceptor; empty = no key, which keeps
 # the old-core deserialization untouched .
        if mock_notes:
            payload["mockNotes"] = mock_notes
 # W5 phase-two (03 trailing key mobilePerfSummary): in-window mobile
 # sampling rollup numbers computed once by the executor perf modules —
 # core stores them without re-parsing raw rows. Empty = no key (
 # zero-drift trailing discipline, non-mobile tasks stay untouched).
        if mobile_perf_summary:
            payload["mobilePerfSummary"] = mobile_perf_summary
 # (03 trailing key elementAnchorHits, WE : one row per ledger
 # anchor attempt collected during element-reference resolution; core
 # recomputes confidence from these outcome rows — the executor only
 # reports what happened. Empty/None = key omitted (trailing-key
 # compatibility precedent , old cores deserialize untouched).
        if element_anchor_hits:
            payload["elementAnchorHits"] = element_anchor_hits
        if step_heal_evidence:
            payload["stepHealEvidence"] = step_heal_evidence
        return self._post("/internal/tasks/result", payload)

    def grant_artifact(self, task_id: int, kind: str, file_name: str) -> dict:
        pass
        return self._post("/internal/tasks/artifact",
                          {"taskId": task_id, "kind": kind, "fileName": file_name})

    def grant_package_download(self, request: dict) -> dict:
        pass
        return self._post("/internal/appauto/packages/download-grant", request)

    def get_reanchor_baseline(self, element_id: int) -> dict:
        """Evidence-plane baseline grant for the re-anchor compare port.

 api /internal/appauto/element-evidence-grant passthrough (core
 /internal/web-elements/evidence-grant reads the ledger row's
 evidence.screenshotKey plus the live revision and answers one
 presigned GET). data = {found, screenshotKey, url, revision};
 found=false or a null url means the baseline is unprovable — the
 caller degrades scale B to a proposal, never an implicit pass.
 Business errors surface as ProtocolError and the port reads them
 fail-closed .
 """
        return self._post("/internal/appauto/element-evidence-grant",
                          {"elementId": element_id})

    def grant_perf_artifact_download(self, object_key: str) -> dict:
        """Perf artifact download grant ( A-10; api /internal/perf/artifacts/
 download-grant).

 The claim envelope carries only the object-storage key of the compiled
 bundle; the presigned GET URL is fetched here and used in memory during
 the run (never written to step text or the database — same stance as
 grant_package_download). Authorization (key must be a perf-artifact
 bundle of a project the caller may read) is decided server-side in
 core; this channel performs zero judgment and surfaces business errors
 as ProtocolError for the worker to turn into a human-worded fail.
 """
        return self._post("/internal/perf/artifacts/download-grant",
                          {"objectKey": object_key})

    def submit_session_artifacts(self, task_id: int, claim_token: str,
                                 artifacts: dict) -> dict:
        pass
        return self._post("/internal/tasks/session-artifacts",
                          {"taskId": task_id, "claimToken": claim_token,
                           "artifacts": artifacts})

    def renew_device_lease(self, device_id: int) -> dict:
        pass
        return self._post(f"/internal/appauto/devices/{device_id}/lease/renew"
                          f"?executorId={self.executor_id}", {})

    def visual_locate(self, request: dict) -> dict:
        pass
        return self._post("/internal/appauto/visual-locate", request)

    def ai_sample_cache_hit(self, request: dict) -> dict:
        pass
        return self._post("/internal/appauto/ai-samples/cache-hit", request)

    def engine_recipe(self, code: str) -> dict:
        pass
        return self._get(f"/internal/cluster/engine-recipe?code={quote(code)}")

    def poll_commands(self, limit: int = 5) -> list[dict]:
        pass
        data = self._post("/internal/cluster/commands/poll",
                          {"executorId": self.executor_id, "limit": limit})
        cmds = data.get("commands") if isinstance(data, dict) else data
        return list(cmds or [])

    def poll_takeover_input(self, limit: int = 10) -> list[dict]:
        pass
        data = self._post("/internal/appauto/takeover/input/poll",
                          {"executorId": self.executor_id, "limit": limit})
        return list(data) if isinstance(data, list) else []

    def claim_capture_session(self) -> Optional[dict]:
        pass
        data = self._post("/internal/session-capture/claim",
                          {"executorId": self.executor_id})
        return data if isinstance(data, dict) else {}

    def report_capture_candidates(self, session_id: str, *, candidates: list[dict],
                                  failure_reason: Optional[str] = None) -> int:
        pass
        body: dict = {"executorId": self.executor_id, "candidates": list(candidates or [])}
        if failure_reason:
            body["failureReason"] = failure_reason
        data = self._post(f"/internal/session-capture/{session_id}/report", body)
        return int(data) if isinstance(data, (int, float)) else 0

    def commit_capture_values(self, session_id: str, *, entries: list[dict],
                              failure_reason: Optional[str] = None) -> int:
        """Commit-round value write-back ( U3b, 03 protocol tail:
 /internal/session-capture/{id}/commit-values).

 Value strings live only inside this in-flight request: this layer logs
 nothing and stores nothing (red lines 3/4). A non-empty failure reason
 is the honest invalidation answer — core then deletes the session
 terminally and the wizard falls back to the re-opening wording. Returns
 the rows core registered (-1 = no commit signal or the session is
 already gone; callers must not read that as success)."""
        body: dict = {"executorId": self.executor_id, "entries": list(entries or [])}
        if failure_reason:
            body["failureReason"] = failure_reason
        data = self._post(f"/internal/session-capture/{session_id}/commit-values", body)
        return int(data) if isinstance(data, (int, float)) else 0

    def report_command_progress(self, command_id: int, claimer_id: int,
                                step: str, message: str | None = None) -> dict:
        pass
        body: dict = {"commandId": command_id, "claimerId": claimer_id, "step": step}
        if message:
            body["message"] = message
        return self._post("/internal/cluster/commands/progress", body)

    def complete_command(self, command_id: int, claimer_id: int, success: bool,
                         result_detail: str | None = None) -> dict:
        pass
        body: dict = {"commandId": command_id, "claimerId": claimer_id,
                      "success": bool(success)}
        if result_detail:
            body["resultDetail"] = result_detail
        return self._post("/internal/cluster/commands/result", body)

    def release_device_lease(self, device_id: int,
                             reason: Optional[str] = None) -> dict:
        pass
        query = f"executorId={self.executor_id}"
        if reason:
            query += f"&reason={quote(reason)}"
        return self._post(f"/internal/appauto/devices/{device_id}/lease/release"
                          f"?{query}", {})
