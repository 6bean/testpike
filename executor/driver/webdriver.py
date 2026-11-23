
from __future__ import annotations

import json
import logging
import os
import re
import tempfile
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from playwright.sync_api import Page, TimeoutError as PlaywrightTimeoutError

from driver.code_step_contract import (DEFAULT_TIMEOUT_MS, CodeStepViolation,
                                       validate_code_step)
from driver.condition_expr import ConditionError, evaluate_condition
from driver.db_dialect import (ConnectionPool, DbDialectRegistry, DbExecError)
from driver.element_candidates import (
    VIEWPORT_CLASSES_STEP_KEY, collect_element_candidates)
from driver.env_resolver import (
    DataKeyMissingError,
    EnvPlaceholderError,
    VarStore,
    VarUndefinedError,
    resolve_data,
    resolve_gen,
    resolve_placeholders,
    resolve_url_template,
    resolve_vars,
)
from driver.explore_network_guard import (ExploreNetworkGuard,
                                          describe_write_event)
from driver.genkit import GenkitCallError
from driver.mock_route import (exempt_honest_miss_failures, mount_mock_routes,
                                parse_binding)
from driver.session_context import (
    collect_session_auth_rejections,
    storage_state_kwargs,
)
from driver.expect_match import (DEFAULT_STRIP_CHARS, MATCH_TYPES,
                                 ExpectMatchError, match_range, match_regex,
                                 match_text)
from driver.jsonpath_subset import JsonPathError, scalar_leaves
from driver.sandbox_core import run_sandboxed_script
from driver.console_assert import (ConsoleAssertError, ConsoleObserver,
                                   assert_console,
                                   validate_console_assert_step)
from driver.net_observer import (NetAssertError, NetObserver, assert_net,
                                 validate_net_assert_step)
from driver.script_limits import (FAIL_POLICY_CONTINUE, SCREENSHOT_EVERY_STEP,
                                  SCREENSHOT_FAIL_ONLY, SCREENSHOT_KEY_STEP,
                                  ScriptLimits, count_script_tree)
from driver.snapshot import build_step_snapshot
from driver import re_anchor
from driver.tls_client import (TlsMaterial, http_tls_kwargs, normalize_policy,
                               playwright_context_options)
from driver.visual_assert_contract import (VisualAssertViolation,
                                           validate_visual_assert_step)
from driver.visual_diff import diff_ratio, overlay_diff_png

STEP_TIMEOUT_MS = 15_000

_ITER_PATTERN = re.compile(r"\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}")
_CONTAINER_ACTIONS = ("loop", "if")
_WAIT_STATES = ("visible", "attached", "detached", "hidden")
SNAPSHOT_MAX_CHARS = 6000

POLL_MIN_INTERVAL_MS = 1000

log = logging.getLogger("testpike.executor")


from driver.http_gate import HttpExecError, check_url_gates



def _index_connections(connections) -> dict:
    pass
    indexed: dict = {}
    for item in connections or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "")
        if not name:
            continue
        if name in indexed:
            log.warning("连接名 %s 重复下发，后录覆盖前录（零凭据实值）", name)
        indexed[name] = item
    return indexed


class IterationVarError(ValueError):
    pass


def resolve_iteration_vars(node: Any, scope: dict) -> Any:
    pass
    if scope is None:
        return node
    missing: dict = {}

    def sub(node):
        if isinstance(node, dict):
            return {k: sub(v) for k, v in node.items()}
        if isinstance(node, list):
            return [sub(v) for v in node]
        if isinstance(node, str):
            def repl(m):
                name = m.group(1)
                if name in scope:
                    return str(scope[name])
                missing[name] = None
                return m.group(0)
            return _ITER_PATTERN.sub(repl, node)
        return node

    resolved = sub(node)
    if missing:
        avail = "、".join(sorted(scope)) or "（无）"
        raise IterationVarError(
            f"未定义迭代变量 {'、'.join(missing)}（当前可用：{avail}）")
    return resolved


@dataclass
class _ExecCtx:
    pass

    next_no: int = 1
    iterations: int = 0
    screenshots: list = field(default_factory=list)
    failed: list = field(default_factory=list)
    pending_artifacts: list = field(default_factory=list)

    def take_no(self) -> int:
        n = self.next_no
        self.next_no += 1
        return n

    def collect(self, sr: "StepResult") -> None:
        if sr.screenshot_key:
            self.screenshots.append(sr.screenshot_key)
        if sr.status == "fail" and sr.sub_failures == 0:
            self.failed.append((sr.step_no, sr.action, sr.detail or ""))


@dataclass
class StepResult:
    pass
    step_no: int
    action: str
    start_at: str
    end_at: str
    status: str = "ok"
    detail: Optional[str] = None
    screenshot_key: Optional[str] = None
    children: Optional[list] = None
    sub_failures: int = 0
    step_key: Optional[str] = None

    def to_dict(self) -> dict:
        out = {"stepNo": self.step_no, "action": self.action,
               "startAt": self.start_at, "endAt": self.end_at, "status": self.status,
               "detail": self.detail, "screenshotKey": self.screenshot_key}
        if self.children is not None:
            out["children"] = self.children
        if self.step_key is not None:
            out["stepKey"] = self.step_key
        return out


@dataclass
class RunOutcome:
    pass
    result: str  # pass / fail
    error_type: Optional[int] = None
    error_detail: Optional[str] = None
    steps_timeline: list[dict] = field(default_factory=list)
    screenshots: list[str] = field(default_factory=list)
    duration_ms: int = 0
    console_logs: list[str] = field(default_factory=list)
    loop_count: int = 0
    loop_iterations: int = 0
    console_violations: list[dict] = field(default_factory=list)
    net_hits: list[dict] = field(default_factory=list)
    db_digest: list[str] = field(default_factory=list)
    protocol_snapshots: list[dict] = field(default_factory=list)
 # (03 result trailing key sessionAuthRejections, face 3 34b) same-origin
    # 401/403 rows observed while a ledger login state was injected; empty
    # = key omitted. Adjudication authority is core-side (zero executor policy).
    session_auth_rejections: list[dict] = field(default_factory=list)
    pending_artifacts: list[dict] = field(default_factory=list)
    extracted_vars: dict = field(default_factory=dict)
    session_artifacts: dict = field(default_factory=dict)
    gen_seeds: list = field(default_factory=list)
    perf_frames: list = field(default_factory=list)
    perf_events: list = field(default_factory=list)
    perf_status: Optional[int] = None
    record_events: Optional[dict] = None
 # (03 result trailing key mockNotes, U5 audit bucket) one lightweight entry
 # per intercepted request {path,mode[,status]} — the report face counts them
 # to state "this case passed under N mock answers". Empty = no key (
 # trailing-key zero-drift; unbound tasks never grow the bucket).
    mock_notes: list = field(default_factory=list)
 # W5 phase-two (03 result trailing key mobilePerfSummary): in-window
 # aggregate numbers of the mobile sampling sinks
 # {metrics:{<metric>:{median,count}}, sampleCount}. Readings and unit
 # conversion are owned by the executor perf modules — core only stores the
 # numbers and never re-parses raw rows (a second metric-extraction source
 # would drift). None/empty = no key ;
 # non-mobile tasks stay untouched.
    mobile_perf_summary: Optional[dict] = None
 # WE (03 result trailing key elementAnchorHits): one row per
 # ledger-anchor attempt {elementId,name,viewport,anchorType,hit,tried};
 # core recomputes confidence from outcomes (tp_web_element_anchor_stat).
 # Empty = no key .
    element_anchor_hits: list = field(default_factory=list)
    # 8.4 heal evidence rows + WEB repair suggestions awaiting flush
    # (empty = key omitted, trailing-key discipline)
    heal_evidence: list = field(default_factory=list)
    step_suggestions: list = field(default_factory=list)
    # Explore write-guard rows (03 result trailing keys writeEvents/
    # failures): baseline-outside write requests attributed to action steps
    # and network failure triples (>=400 -> error_type 1, requestfailed ->
    # 3). Empty = key omitted ( trailing discipline; normal-case
    # envelopes byte-identical).
    write_events: list = field(default_factory=list)
    net_failures: list = field(default_factory=list)

    def to_dict(self) -> dict:
        pass
        out = {
            "result": self.result,
            "error_type": self.error_type,
            "error_detail": self.error_detail,
            "steps_timeline": self.steps_timeline,
            "screenshots": self.screenshots,
            "duration_ms": self.duration_ms,
            "console_logs": self.console_logs,
            "loop_count": self.loop_count,
            "loop_iterations": self.loop_iterations,
            "console_violations": self.console_violations,
            "net_hits": self.net_hits,
            "db_digest": self.db_digest,
            "protocol_snapshots": self.protocol_snapshots,
            "pending_artifacts": self.pending_artifacts,
            "extracted_vars": self.extracted_vars,
            "session_artifacts": self.session_artifacts,
        }
        if self.gen_seeds:
            out["gen_seeds"] = self.gen_seeds
        if self.perf_frames:
            out["perf_frames"] = self.perf_frames
        if self.perf_events:
            out["perf_events"] = self.perf_events
        if self.perf_status is not None:
            out["perf_status"] = self.perf_status
        if self.record_events:
            out["record_events"] = self.record_events
 # mock audit bucket trails (empty = no key, zero-drift for old core)
        if self.mock_notes:
            out["mock_notes"] = self.mock_notes
 # mobile perf window rollup trails (None/empty = no key, same discipline)
        if self.mobile_perf_summary:
            out["mobile_perf_summary"] = self.mobile_perf_summary
 # anchor hit/miss rows trail (empty = no key, same gen_seeds posture)
        if self.element_anchor_hits:
            out["element_anchor_hits"] = self.element_anchor_hits
        if self.heal_evidence:
            out["heal_evidence"] = self.heal_evidence
        if self.step_suggestions:
            out["step_suggestions"] = self.step_suggestions
        if self.write_events:
            out["writeEvents"] = self.write_events
        if self.net_failures:
            out["failures"] = self.net_failures
        return out

    @classmethod
    def from_dict(cls, data: dict) -> "RunOutcome":
        pass
        return cls(
            result=str(data.get("result", "fail")),
            error_type=data.get("error_type"),
            error_detail=data.get("error_detail"),
            steps_timeline=data.get("steps_timeline") or [],
            screenshots=data.get("screenshots") or [],
            duration_ms=int(data.get("duration_ms") or 0),
            console_logs=data.get("console_logs") or [],
            loop_count=int(data.get("loop_count") or 0),
            loop_iterations=int(data.get("loop_iterations") or 0),
            console_violations=data.get("console_violations") or [],
            net_hits=data.get("net_hits") or [],
            db_digest=data.get("db_digest") or [],
            protocol_snapshots=data.get("protocol_snapshots") or [],
            pending_artifacts=data.get("pending_artifacts") or [],
            extracted_vars=dict(data.get("extracted_vars") or {}),
            session_artifacts=dict(data.get("session_artifacts") or {}),
            gen_seeds=list(data.get("gen_seeds") or []),
            perf_frames=list(data.get("perf_frames") or []),
            perf_events=list(data.get("perf_events") or []),
            perf_status=data.get("perf_status"),
            record_events=data.get("record_events"),
            mock_notes=list(data.get("mock_notes") or []),
            mobile_perf_summary=data.get("mobile_perf_summary"),
            element_anchor_hits=list(data.get("element_anchor_hits") or []),
            heal_evidence=list(data.get("heal_evidence") or []),
            step_suggestions=list(data.get("step_suggestions") or []),
            write_events=list(data.get("writeEvents") or []),
            net_failures=list(data.get("failures") or []),
        )


class BrowserLaunchError(RuntimeError):
    pass


_SUPPORTED_BROWSERS = frozenset({"chromium", "firefox", "webkit"})


def _launch_kernel(pw, name: str, headless: bool):
    pass
    if name not in _SUPPORTED_BROWSERS:
        raise BrowserLaunchError(
            f"不支持的浏览器内核「{name}」，合法值：{'、'.join(sorted(_SUPPORTED_BROWSERS))}")
    kernel = getattr(pw, name, None)
    if kernel is None:
        raise BrowserLaunchError(f"执行机缺少浏览器内核 {name}（当前 Playwright 版本不支持）")
    try:
        return kernel.launch(headless=headless)
    except Exception as e:
        raise BrowserLaunchError(
            f"执行机缺少浏览器内核 {name} 或启动失败：{e}") from e


def _visual_step_key(case_id: int, seq: int, step: dict) -> Optional[str]:
    pass
    if not isinstance(step, dict) or step.get("action") != "visual.assert":
        return None
    raw = step.get("name")
    name = str(raw).strip() if raw is not None else ""
    return f"case:{case_id}:step:{seq}:{name or 'shot'}"


class WebDriverExecutor:
    pass

    def __init__(self, env_base_url: Optional[str] = None, headless: bool = True,
                 screenshots_dir: Optional[str] = None,
                 env_vars: Optional[dict] = None,
                 script_limits: Optional[dict] = None,
                 exec_policy: Optional[dict] = None,
                 connections: Optional[list] = None,
                 data_vars: Optional[dict] = None,
                 browser: Optional[str] = None,
                 visual_baselines: Optional[dict] = None,
                 tls_policy: Optional[dict] = None,
                 mock_binding: Optional[dict] = None,
                 session_state: Optional[dict] = None,
                 element_anchors: Optional[dict] = None,
                 network_guard: bool = False,
                 case_id: int = 0):
        self.env_base_url = env_base_url
        self._base_env_vars: dict = dict(env_vars or {})
        self.env_vars: dict = dict(self._base_env_vars)
        self._base_data_vars: dict = dict(data_vars or {})
        self.data_vars: dict = dict(self._base_data_vars)
        self.var_store: VarStore = VarStore()
        self.net_observer: Optional[NetObserver] = None
        # Write-side guard for explore/survey sessions (design pieces 1/2
        # action-step wiring). Construction-time switch only: the normal
        # case-execution channel never arms it, so ordinary runs keep zero
        # extra listeners and zero envelope keys .
        self._network_guard_enabled = bool(network_guard)
        self.network_guard: Optional[ExploreNetworkGuard] = None
        self.console_observer: Optional[ConsoleObserver] = None
        self._base_limits = ScriptLimits.from_payload(script_limits)
        self.limits: ScriptLimits = self._base_limits
        self._base_exec_policy: Optional[dict] = exec_policy
        self.exec_policy: Optional[dict] = exec_policy
 # claim elementAnchors channel (03 protocol 2.3 trailing key, WE-S1): the
 # dispatch snapshot binding for element-reference targets. Construction
 # baseline + per-run override, same discipline as exec_policy; old core /
 # no references = None = reference steps refuse in plain words (never a
 # silent inline guess), inline steps zero-drift.
        self._base_element_anchors: Optional[dict] = element_anchors
        self._element_anchors: Optional[dict] = element_anchors
        # WE per-run hit/miss ledger rows collected during anchor
        # resolution; trails out on the result envelope (element_anchor_hits).
        self._element_anchor_hits: list[dict] = []
        # WE equivalent re-anchor ports (design 8.1). The compare
        # port + envelope floor arrive with the core delivery; unwired means
        # scale B is unprovable = suggestion route, never a silent pass.
        # Piece 2b wires it post-construction in task_worker
        # (build_visual_compare_port) — elementId + bytes -> similarity|None.
        self._visual_compare: Optional[Callable[[Any, bytes], Optional[float]]] = None
        self._heal_evidence: list[dict] = []
        self._suggestions: list[dict] = []
        self._current_step_no: int = 0
        self._base_connections: dict = _index_connections(connections)
        self.connections: dict = dict(self._base_connections)
        self.db_pool = ConnectionPool(DbDialectRegistry())
        self.visual_baselines: dict = dict(visual_baselines or {})
        self.tls_policy: dict = normalize_policy(tls_policy)
        self._tls_material = TlsMaterial(self.tls_policy)
        self.case_id = case_id
        # (03 protocol 2.3 mock trailing key, U5 three modes) a dirty binding on
        # the claim is refused in plain words at construction time — never a
        # silent fallback to the real environment. No key = no mount, zero drift.
        self._mock_binding = parse_binding(mock_binding)
        self.mock_notes: list = []
        # (03 protocol 2.3 sessionState trailing key, face 3) core already
        # resolved the ledger row into a Playwright storage_state document; this
        # side only hands it to the single context build point in start().
        # context_options source, a second one here is the forbidden drift).
        self._session_state = session_state
        # (34b) set in start() once storage_state actually lands in the
        # context; the auth-rejection relay evidence plane stays silent
        # for every run without a consumed ledger state.
        self._session_state_in_use = False
        self.screenshots_dir = screenshots_dir
        self._headless = headless
        self.browser = (browser or "").strip() or "chromium"
        self._browser = None
        self._context = None

    def start(self) -> None:
        from playwright.sync_api import sync_playwright
        self._pw = sync_playwright().start()
        self._browser = _launch_kernel(self._pw, self.browser, self._headless)
        state_kwargs = storage_state_kwargs(self._session_state)
        # (34b) consumption flag: the rejection relay only reports for runs
        # that actually ran with a ledger login state in the context.
        self._session_state_in_use = bool(state_kwargs)
        self._context = self._browser.new_context(
            viewport={"width": self._VIEWPORT_SIZE[0],
                      "height": self._VIEWPORT_SIZE[1]},
            **playwright_context_options(self.tls_policy, self._tls_material,
                                         self.env_base_url),
            # login-state injection at the one context build
            # point: {} when no sessionState channel arrived, so the kwargs the
            # kernel receives stay identical to the legacy shape.
            **state_kwargs)
        # (03 protocol 2.3 mock trailing key, U5 three modes) mount the route
        # interceptor only when a binding exists — the kernel on the mock-server
        # side does every adjudication (single rule library, zero matcher here);
        # no binding = zero mount, old shape untouched (dirty shapes were already
        # refused at construction, they never reach this point).
        self.mock_notes = mount_mock_routes(
            self._context, lambda: self.env_base_url, self._mock_binding or None)

    def open_page(self) -> Page:
        """One long-lived page for loop-shaped consumers (site survey BFS).

        The scripted run() path keeps its own per-run page; deterministic
        crawls need a single page whose cookies and origin state persist
        across navigations. Requires start() first (context built there).
        """
        if self._context is None:
            raise RuntimeError("open_page 需先 start()（context 尚未建立）")
        return self._context.new_page()

    def stop(self) -> None:
        if self._browser:
            self._browser.close()
        if getattr(self, "_pw", None):
            self._pw.stop()
        self.db_pool.close_all()
        self._tls_material.cleanup()

    def run(self, action_json: str, case_title: str = "",
            env_base_url: Optional[str] = None,
            env_vars: Optional[dict] = None,
            script_limits: Optional[dict] = None,
            var_store: Optional[VarStore] = None,
            exec_policy: Optional[dict] = None,
            connections: Optional[list] = None,
            data_vars: Optional[dict] = None,
            element_anchors: Optional[dict] = None) -> RunOutcome:
        pass
        if env_base_url is not None:
            self.env_base_url = env_base_url
        self.env_vars = dict(env_vars if env_vars is not None else self._base_env_vars)
        self.limits = (ScriptLimits.from_payload(script_limits)
                       if script_limits is not None else self._base_limits)
        self.var_store = var_store if var_store is not None else VarStore()
        self.exec_policy = (exec_policy if exec_policy is not None
                            else self._base_exec_policy)
        self.connections = (_index_connections(connections)
                            if connections is not None
                            else dict(self._base_connections))
 # mock audit bucket is cleared in place per run — the route handler closure
 # holds this very list object (re-assignment would break the chain), and a
 # shared executor across runs must not leak run 1's notes into run 2's
 # outcome (same isolation contract as var_store / snapshots).
        self.data_vars = dict(data_vars if data_vars is not None else self._base_data_vars)
        self._element_anchors = (element_anchors if element_anchors is not None
                                 else self._base_element_anchors)
        self.mock_notes.clear()
        self.db_pool.close_all()
        start_ts = time.time()
        try:
            doc = json.loads(action_json)
        except json.JSONDecodeError as e:
            return self._fail(2, f"动作 JSON 解析失败: {e}")

        if isinstance(doc, dict):
            violation = self.limits.check_violation(doc)
            if violation:
                return self._fail(3, violation)

        try:
            doc = resolve_placeholders(doc, self.env_vars, self.env_base_url)
        except EnvPlaceholderError as e:
            return self._fail(3, str(e))

        try:
            doc = resolve_data(doc, self.data_vars)
        except DataKeyMissingError as e:
            return self._fail(3, str(e))

        self._gen_seeds = []
        # WE per-run fresh hit-report bucket (no cross-run leak,
        # same discipline as the seed/net observers).
        self._element_anchor_hits = []
        self._heal_evidence = []
        self._suggestions = []
        self._current_step_no = 0
        try:
            doc = resolve_gen(doc, self._gen_seeds)
        except GenkitCallError as e:
            return RunOutcome(result="fail", error_type=3, error_detail=str(e),
                              gen_seeds=list(self._gen_seeds),
                              element_anchor_hits=list(self._element_anchor_hits))

        page = self._context.new_page()
        page.set_default_timeout(STEP_TIMEOUT_MS)
        console_obs = ConsoleObserver()
        self.console_observer = console_obs
        page.on("console", console_obs.record)
        self.net_observer = NetObserver(page)
        self.network_guard = self._mount_network_guard(page)
        ctx = _ExecCtx()
        steps: list[dict] = []
        self._snapshots: list[dict] = []
        sequence = list(doc.get("setup", []) or []) + list(doc.get("steps", []) or [])
        top_step_keys = [_visual_step_key(self.case_id, i, s)
                         for i, s in enumerate(sequence, start=1)]
        try:
            if not sequence:
                return self._fail(2, "动作 JSON 没有可执行步骤（setup/steps 均为空）")
            self._exec_sequence(page, sequence, ctx, steps, case_title,
                                step_keys=top_step_keys)
            duration = int((time.time() - start_ts) * 1000)
            report = (f"[report] loopCount={count_script_tree(doc)[1]} "
                      f"loopIterations={ctx.iterations}")
            evidence = self._evidence(console_obs, steps)
            guard_rows = self._guard_outcome_rows(steps)
            if ctx.failed:
                first_no, first_action, first_detail = ctx.failed[0]
                return RunOutcome(
                    result="fail", error_type=self._map_failure(first_detail),
                    error_detail=f"失败 {len(ctx.failed)} 步，首个失败步骤 {first_no}"
                                 f" [{first_action}]: {first_detail}",
                    steps_timeline=steps, screenshots=ctx.screenshots,
                    duration_ms=duration, console_logs=console_obs.lines + [report],
                    loop_count=count_script_tree(doc)[1], loop_iterations=ctx.iterations,
                    pending_artifacts=list(ctx.pending_artifacts),
                    gen_seeds=list(self._gen_seeds),
                    element_anchor_hits=list(self._element_anchor_hits),
                    heal_evidence=list(self._heal_evidence),
                    step_suggestions=list(self._suggestions),
                    mock_notes=list(self.mock_notes),
                    **evidence,
                    **guard_rows)
            return RunOutcome(result="pass", steps_timeline=steps,
                              screenshots=ctx.screenshots, duration_ms=duration,
                              console_logs=console_obs.lines + [report],
                              loop_count=count_script_tree(doc)[1],
                              loop_iterations=ctx.iterations,
                              pending_artifacts=list(ctx.pending_artifacts),
                              gen_seeds=list(self._gen_seeds),
                              element_anchor_hits=list(self._element_anchor_hits),
                              heal_evidence=list(self._heal_evidence),
                              step_suggestions=list(self._suggestions),
                              mock_notes=list(self.mock_notes),
                              **evidence,
                              **guard_rows)
        except PlaywrightTimeoutError as e:
            return RunOutcome(result="fail", error_type=3,
                              error_detail=f"页面操作超时（{STEP_TIMEOUT_MS}ms）: {e}",
                              steps_timeline=steps, screenshots=ctx.screenshots,
                              duration_ms=int((time.time() - start_ts) * 1000),
                              console_logs=console_obs.lines,
                              protocol_snapshots=self._snapshots,
                              mock_notes=list(self.mock_notes),
                              **self._guard_outcome_rows(steps))
        except Exception as e:
            return RunOutcome(result="fail", error_type=3,
                              error_detail=f"执行异常: {e}",
                              steps_timeline=steps, screenshots=ctx.screenshots,
                              duration_ms=int((time.time() - start_ts) * 1000),
                              console_logs=console_obs.lines,
                              protocol_snapshots=self._snapshots,
                              mock_notes=list(self.mock_notes),
                              **self._guard_outcome_rows(steps))
        finally:
            page.close()

    # ------------------------------------------------------- write-side network guard
    def _mount_network_guard(self, page: Page) -> Optional[ExploreNetworkGuard]:
        """Arm the per-run write guard for explore/survey sessions.

        Returns None while the construction switch is off (normal case runs
        stay listener-free). The guard consumes this run's NetObserver feed
        (single response source, no second CDP socket); it only subscribes
        ``requestfailed`` on the page itself.
        """
        if not self._network_guard_enabled:
            return None
        return ExploreNetworkGuard(page, self.net_observer)

    def _guard_action_begin(self, step_no: int) -> None:
        """Open the action window so later traffic is step-attributed."""
        if self.network_guard is not None:
            self.network_guard.begin_action(step_no)

    def _guard_outcome_rows(self, steps: list[dict]) -> dict:
        """Flush the guard into envelope trailing keys + step annotations.

        Baseline-outside write verbs become ``write_events`` rows; each row
        also appends its operation-facing one-liner to the attributed step's
        timeline detail. ``net_failures`` keeps the >=400 / requestfailed
        triples minus honest-miss passthrough rows — the mock kernel's
        coverage gap is an environment fact already booked in mock_notes,
        reporting it as a product defect would be a false alarm (single
        exemption ledger, no second book). Empty lists trail no key.
        """
        if self.network_guard is None:
            return {}
        account = self.network_guard.account()
        write_events = account["writeEvents"]
        failures = exempt_honest_miss_failures(account["failures"],
                                               self.mock_notes)
        for event in write_events:
            for row in steps:
                if row.get("stepNo") == event["stepNo"]:
                    text = describe_write_event(event)
                    row["detail"] = (f"{row['detail']}；{text}"
                                     if row.get("detail") else text)
        out: dict = {}
        if write_events:
            out["write_events"] = write_events
        if failures:
            out["net_failures"] = failures
        return out

    def _exec_sequence(self, page: Page, sequence: list, ctx: _ExecCtx,
                       out_timeline: list, case_title: str,
                       iter_scope: Optional[dict] = None,
                       step_keys: Optional[list] = None) -> bool:
        pass
        for idx, step in enumerate(sequence):
            step_key = step_keys[idx] if step_keys is not None else None
            step = self._bind_step_vars(step, iter_scope, ctx, out_timeline,
                                        case_title)
            if step is None:
                continue
            sr = self._exec_step(page, step, ctx, case_title, iter_scope=iter_scope,
                                 step_key=step_key)
            # WE heal evidence + suggestions ride the global
            # sequential number (ctx.take_no inside _exec_step) — the
            # board and the report badge reconcile on that same number.
            self._current_step_no = sr.step_no
            out_timeline.append(sr.to_dict())
            ctx.collect(sr)
            if sr.status == "fail" and self.limits.fail_policy != FAIL_POLICY_CONTINUE:
                return True
        return False

    def _bind_step_vars(self, step, iter_scope, ctx: _ExecCtx,
                        out_timeline: list, case_title: str):
        pass
        try:
            step = resolve_iteration_vars(step, iter_scope)
            return resolve_vars(step, self.var_store)
        except (IterationVarError, VarUndefinedError) as e:
            no = ctx.take_no()
            ts = _now_iso()
            sr = StepResult(step_no=no, action=str(step.get("action", "")),
                            start_at=ts, end_at=ts, status="fail", detail=str(e))
            out_timeline.append(sr.to_dict())
            ctx.failed.append((no, sr.action, sr.detail))
            return None

    def _exec_step(self, page: Page, step: dict, ctx: _ExecCtx, case_title: str,
                   iter_scope: Optional[dict] = None,
                   step_key: Optional[str] = None) -> StepResult:
        action = str(step.get("action", ""))
        start_at = _now_iso()
        sr = StepResult(step_no=ctx.take_no(), action=action,
                        start_at=start_at, end_at=start_at, step_key=step_key)
        if action in _CONTAINER_ACTIONS:
            try:
                (self._exec_loop if action == "loop" else self._exec_if)(
                    page, step, sr, ctx, case_title, iter_scope)
            except Exception as e:
                sr.status = "fail"
                sr.detail = f"{type(e).__name__}: {e}"
            sr.end_at = _now_iso()
            return sr
        try:
            if action == "goto":
                self._goto(page, step)
                if self.network_guard is not None:
                    # everything seen during the load is the natural request
                    # surface; only traffic after an action can flag a write
                    self.network_guard.end_baseline()
            elif action == "click":
                self._guard_action_begin(sr.step_no)
                self._click(page, step)
            elif action == "input":
                self._guard_action_begin(sr.step_no)
                self._input(page, step)
            elif action == "select":
                self._select(page, step)
            elif action == "expect":
                self._expect(page, step)
            elif action == "wait":
                self._wait(page, step)
            elif action == "screenshot":
                sr.screenshot_key = self._screenshot(page, step)
                sr.end_at = _now_iso()
                return sr
            elif action == "snapshot":
                sr.detail = self._observe_dom(page)
                sr.end_at = _now_iso()
            elif action == "element-candidates":
                # Survey replay classes ride in on the step (additive field;
                # core dispatch reads the install key element.viewport-classes,
                # absent = default tier only). See driver.element_candidates.
                sr.detail = collect_element_candidates(
                    page, step.get(VIEWPORT_CLASSES_STEP_KEY) or ())
                sr.end_at = _now_iso()
            elif action == "http.request":
                sr.detail = self._http_request(step, sr.step_no)
                sr.end_at = _now_iso()
            elif action == "net.assert":
                sr.detail = self._net_assert(step, sr.step_no)
                sr.end_at = _now_iso()
            elif action == "console.assert":
                sr.detail = self._console_assert(step, sr.step_no)
                sr.end_at = _now_iso()
            elif action == "sql.assert":
                sr.detail = self._sql_assert(step)
                sr.end_at = _now_iso()
            elif action == "sql.exec":
                sr.detail = self._sql_exec(step)
                sr.end_at = _now_iso()
            elif action == "sql.query":
                sr.detail = self._sql_query(step)
                sr.end_at = _now_iso()
            elif action == "code.step":
                sr.detail = self._code_step(step)
                sr.end_at = _now_iso()
            elif action == "visual.assert":
                self._visual_assert(page, step, ctx, sr)
                sr.end_at = _now_iso()
                return sr
            else:
                raise ValueError(f"不支持的动作类型: {action}")
            sr.end_at = _now_iso()
        except PlaywrightTimeoutError as e:
            sr.status = "fail"
            sr.end_at = _now_iso()
            sr.detail = f"定位/等待超时: {e}"
        except Exception as e:
            sr.status = "fail"
            sr.end_at = _now_iso()
            sr.detail = f"{type(e).__name__}: {e}"
        if self._should_snapshot(action, sr.status):
            sr.screenshot_key = self._snapshot(page, sr.step_no, action)
        return sr

    def _exec_loop(self, page: Page, step: dict, sr: StepResult, ctx: _ExecCtx,
                   case_title: str, iter_scope: Optional[dict]) -> None:
        pass
        items = step.get("items")
        count = step.get("count")
        if items is None and count is None:
            raise ValueError("loop 缺少 items/count（04 契约 §2.1 双形态二选一）")
        if items is not None:
            if not isinstance(items, list):
                raise ValueError("loop items 必须是数组（04 契约 string[]）")
            values: list = list(items)
            default_var = "item"
        else:
            try:
                n = int(str(count).strip())
            except (TypeError, ValueError):
                raise ValueError(f"loop count 必须是整数，实际 {count!r}")
            if n < 0:
                raise ValueError(f"loop count 不能为负，实际 {n}")
            values = list(range(1, n + 1))
            default_var = "index"
        var = str(step.get("var") or default_var)
        children: list = []
        pre_total = len(ctx.failed)
        first_fail_iter: Optional[int] = None
        first_fail_detail: Optional[str] = None
        aborted = False
        for pos, value in enumerate(values, start=1):
            scope = dict(iter_scope or {})
            scope[var] = value
            scope["index"] = pos
            pre = len(ctx.failed)
            it_timeline: list = []
            self._exec_sequence(page, step.get("steps") or [], ctx, it_timeline,
                                case_title, iter_scope=scope)
            children.append({"iteration": pos, "steps": it_timeline})
            ctx.iterations += 1
            if first_fail_iter is None and len(ctx.failed) > pre:
                first_fail_iter = pos
                first_fail_detail = ctx.failed[pre][2]
            if self.limits.fail_policy != FAIL_POLICY_CONTINUE and len(ctx.failed) > pre:
                aborted = True
                break
        sr.children = children
        sr.sub_failures = len(ctx.failed) - pre_total
        if first_fail_iter is not None:
            sr.status = "fail"
            sr.detail = (f"迭代 {first_fail_iter} 失败: {first_fail_detail}"
                         + ("（fail-policy=2 已中止循环）" if aborted else ""))
        elif not children:
            sr.status = "ok"
            sr.detail = "0 次迭代（items 为空，合法跳过）"
        else:
            sr.status = "ok"
            sr.detail = f"{len(children)} 次迭代全部通过"

    def _exec_if(self, page: Page, step: dict, sr: StepResult, ctx: _ExecCtx,
                 case_title: str, iter_scope: Optional[dict]) -> None:
        pass
        try:
            take = evaluate_condition(step.get("condition"), dict(iter_scope or {}))
        except ConditionError as e:
            sr.status = "fail"
            sr.detail = str(e)
            sr.children = []
            return
        branch, branch_steps = (("then", step.get("steps") or []) if take
                                else ("else", step.get("elseSteps") or []))
        if not branch_steps:
            sr.status = "skipped"
            sr.children = []
            sr.detail = (f"if 条件为{'真' if take else '假'}，"
                         f"{branch} 分支无步骤，合法跳过")
            return
        pre = len(ctx.failed)
        tl: list = []
        self._exec_sequence(page, branch_steps, ctx, tl, case_title,
                            iter_scope=iter_scope)
        sr.children = [{"branch": branch, "steps": tl}]
        sr.sub_failures = len(ctx.failed) - pre
        if sr.sub_failures:
            sr.status = "fail"
            sr.detail = f"{branch} 分支失败: {ctx.failed[pre][2]}"
        else:
            sr.status = "ok"
            sr.detail = f"走 {branch} 分支（{len(tl)} 子步通过）"

    def _http_request(self, step: dict, step_no: int = 0) -> str:
        pass
        import requests
        url = step.get("url")
        if not url:
            raise ValueError("http.request 缺少 url（04 契约 §2.1.2）")
        url = self._resolve_url(str(url))
        policy = self._check_http_url_gates(url)
        method = str(step.get("method") or "GET").upper()
        timeout_s = self._http_timeout_seconds(step, policy)
        poll = step.get("poll") or {}
        interval_ms = int(poll.get("intervalMs") or POLL_MIN_INTERVAL_MS)
        if interval_ms < POLL_MIN_INTERVAL_MS:
            log.warning("http.request poll.intervalMs=%s 低于下限，已钳制为 %sms"
                        "（防打爆被测服务，04 §2.1.2）", interval_ms,
                        POLL_MIN_INTERVAL_MS)
            interval_ms = POLL_MIN_INTERVAL_MS
        budget_ms = int(poll.get("timeoutMs") or 0)
        expect = step.get("expect") or {}
        session = requests.Session()
        session.trust_env = False
        tls_kwargs = http_tls_kwargs(self.tls_policy, self._tls_material, url)
        tls_mode = self.tls_policy["mode"]
        deadline = time.monotonic() + budget_ms / 1000.0
        attempt = 0
        last_status = last_body = None
        exchange = None
        try:
            while True:
                attempt += 1
                try:
                    resp = session.request(
                        method, url,
                        headers=step.get("headers") or None,
                        json=step.get("body")
                        if isinstance(step.get("body"), (dict, list)) else None,
                        data=step.get("body")
                        if isinstance(step.get("body"), str) else None,
                        timeout=timeout_s, allow_redirects=False, **tls_kwargs)
                except requests.exceptions.SSLError:
                    raise HttpExecError(
                        f"TLS 证书校验失败：{url}（档位 {tls_mode}；"
                        "自签/内部 CA 环境请在档案配置 custom_ca 上传 CA 或 "
                        "insecure 跳过校验——insecure 将在报告诚实性标注）") from None
                except requests.exceptions.ConnectionError as e:
                    raise HttpExecError(
                        f"连接 {url} 失败（被测服务不可达或连接被拒绝）"
                        "——环境问题请检查网络/服务在线") from e
                except requests.exceptions.Timeout:
                    raise HttpExecError(
                        f"单请求超过 {timeout_s}s 未返回（exec.http.timeout-seconds 可配）"
                        "——环境问题") from None
                last_status, last_body = resp.status_code, resp.text
                # Duration observation = resp.elapsed (requests wall clock incl.
                # connect + transfer, same basis as netHits.durationMs evidence);
                # header names lowercased for case-insensitive expect.headers match.
                duration_ms = int(resp.elapsed.total_seconds() * 1000)
                resp_header_map = {k.lower(): v for k, v in resp.headers.items()}
                exchange = {
                    "request": {"method": method, "url": url,
                                "headers": step.get("headers") or {},
                                "body": step.get("body")},
                    "response": {"status": last_status,
                                 "url": str(resp.url),
                                 "headers": dict(resp.headers),
                                 "body": last_body},
                }
                try:
                    self._check_http_expect(expect, last_status, str(resp.url),
                                            last_body, method,
                                            duration_ms=duration_ms,
                                            response_headers=resp_header_map)
                except ExpectMatchError:
                    if not poll:
                        raise
                    if time.monotonic() >= deadline:
                        raise HttpExecError(
                            f"轮询 {attempt} 次（{budget_ms}ms）后期望仍不满足："
                            f"末次实际 status={last_status} body="
                            f"{self._brief_body(last_body)}")
                    time.sleep(interval_ms / 1000.0)
                    continue
                break
        finally:
            session.close()
            if exchange is not None:
                self._snapshots.append(build_step_snapshot(
                    step_no, "http.request", exchange["request"], exchange["response"],
                    _http_status_assertion(expect, last_status),
                    tls_mode=tls_mode))
        detail = f"{method} {url} → {last_status}（{len(last_body or '')} 字节）"
        save_as = step.get("saveAs")
        if save_as:
            payload = self._http_json_body(expect, last_body, url)
            leaves = scalar_leaves(payload, str(save_as))
            for name, value in leaves.items():
                self.var_store.set(name, value)
            detail += f"；saveAs {save_as} → {len(leaves)} 个标量变量"
        return detail

    def _check_http_url_gates(self, url: str) -> dict:
        pass
        return check_url_gates(self.exec_policy, self.env_base_url, url)

    def _http_timeout_seconds(self, step: dict, policy: dict) -> float:
        pass
        step_ms = step.get("timeoutMs")
        if step_ms and int(step_ms) > 0:
            return max(int(step_ms) / 1000.0, 0.1)
        base = policy.get("timeoutSeconds")
        return float(base) if base else 10.0

    # Declared expect keys (04 contract 2.1.2) — kept in sync with core
    # NonUiActionContract.EXPECT_KEYS; unknown keys fail closed at execution too
    # (save gate + executor double gate: a mistyped assertion key must never
    # silently skip judgement and let a broken case pass).
    EXPECT_KEYS = ("status", "body", "durationMs", "headers")

    def _check_http_expect(self, expect: dict, status: int, url: str,
                           text: str, method: str = "",
                           duration_ms: Optional[int] = None,
                           response_headers: Optional[dict] = None) -> None:
        """Judge expect{status, body[], durationMs{op,expect,bounds}, headers[{name,op,expect}]}.

        Assertion mismatch raises ExpectMatchError (error_type 1, 04 contract 2.1.2).
        Small signature = the net.assert observer reuses this single judge
        implementation (no second spec): http.request passes response values,
        net.assert passes observed-entry values. Adds duration_ms /
        response_headers keyword args defaulting to None so old two-arg call
        sites keep working untouched; duration bounds delegate to the expect_match
        range kernel and header rows to the shared matcher family.
        """
        unknown = [k for k in expect if k not in self.EXPECT_KEYS]
        if unknown:
            raise ExpectMatchError(
                f"expect 含未声明键「{'、'.join(sorted(unknown))}」（仅支持 "
                f"{' / '.join(self.EXPECT_KEYS)}——拼错的断言键会被静默忽略，"
                "等于断言从未生效，已拒）")
        want = expect.get("status")
        if want is not None and status != int(want):
            raise ExpectMatchError(
                f"断言不满足：实际 status={status} 期望 status={int(want)}"
                f"（{method} {url}）")
        for item in expect.get("body") or []:
            payload = self._http_json_body(expect, text, url)
            self._match_json_leaf(payload, item)
        duration = expect.get("durationMs")
        if duration:
            self._match_duration_expect(duration, duration_ms, method, url)
        rows = expect.get("headers") or []
        for row in rows:
            name = str(row.get("name") or "")
            actual = (response_headers or {}).get(name.lower())
            if actual is None:
                raise ExpectMatchError(
                    f"断言不满足：响应头「{name}」不存在（{method} {url}）")
            op = str(row.get("op") or "contains")
            from driver.expect_match import MATCH_TYPES as _FAMILY
            if op not in _FAMILY:
                raise ExpectMatchError(
                    f"op 非法值 {op!r}（白名单：{'、'.join(_FAMILY)}，禁自由表达式）")
            if op == "range":
                match_range(str(actual),
                            float(row["expect"][0]) if row.get("expect")
                            and row["expect"][0] is not None else None,
                            float(row["expect"][1]) if row.get("expect")
                            and len(row["expect"]) > 1 else None,
                            str(row.get("bounds") or "closed"))
            elif op == "regex":
                match_regex(str(actual), str(row.get("pattern")
                                               or row.get("expect")))
            else:
                match_text(str(actual), str(row.get("expect")),
                           op == "exact")

    @staticmethod
    def _match_duration_expect(duration: dict, duration_ms: Optional[int],
                               method: str, url: str) -> None:
        """Range judgement of expect.durationMs via the single expect_match kernel.

        No observed value = honest refusal (an environment/timing gap must never
        silently count as a passing assertion).
        """
        if duration_ms is None:
            raise ExpectMatchError(
                f"耗时断言无观测数据（{method} {url} 链路未提供 durationMs）"
                "——环境/时序问题，禁假装通过")
        pair = duration.get("expect") or []
        match_range(str(duration_ms),
                    float(pair[0]) if pair and pair[0] is not None else None,
                    float(pair[1]) if len(pair) > 1 and pair[1] is not None else None,
                    str(duration.get("bounds") or "closed"))

    @staticmethod
    def _http_json_body(expect: dict, body_text: str, url: str):
        pass
        import json as _json
        try:
            return _json.loads(body_text or "")
        except ValueError:
            raise HttpExecError(
                f"响应不是 JSON，无法按 JsonPath 判定/捕获：{url}"
                "（Content-Type 或端点返回形态问题=环境问题）") from None

    @staticmethod
    def _match_json_leaf(payload, item: dict) -> None:
        pass
        from driver.jsonpath_subset import evaluate_json_path
        path = item.get("path")
        raw = evaluate_json_path(payload, str(path))
        raw_text = str(raw)
        match_type = str(item.get("matchType") or "contains")
        if match_type == "range":
            match_range(raw_text,
                        float(item["min"]) if item.get("min") is not None else None,
                        float(item["max"]) if item.get("max") is not None else None,
                        str(item.get("bounds") or "closed"),
                        DEFAULT_STRIP_CHARS if item.get("strip") is None
                        else str(item["strip"]))
        elif match_type == "regex":
            match_regex(raw_text, str(item.get("pattern")))
        else:
            match_text(raw_text, str(item.get("text")), match_type == "exact")

    @staticmethod
    def _brief_body(body: Optional[str]) -> str:
        text = (body or "").strip().replace("\n", " ")
        return text[:200] + "…[截断]" if len(text) > 200 else (text or "（空）")

    def _sql_assert(self, step: dict) -> str:
        pass
        spec = self._require_conn(step)
        policy = self.exec_policy if isinstance(self.exec_policy, dict) else {}
        return self.db_pool.sql_assert(spec, step, policy)

    def _net_assert(self, step: dict, step_no: int) -> str:
        pass
        spec = validate_net_assert_step(step)
        observer = self.net_observer
        if observer is None:
            raise NetAssertError("net.assert 观测器未初始化（执行器内部错误）")
        policy = self.exec_policy if isinstance(self.exec_policy, dict) else {}
        try:
            enabled = int(str(policy.get("netAssertEnabled", 1))) == 1
        except (TypeError, ValueError):
            enabled = True
        found = observer.consume(spec, step_no)
        if not enabled:
            return (f"net.assert 已停用（exec.net-assert.enabled=0）：仅记录不判败，"
                    f"窗口命中 {len(found)} 次")
        return assert_net(spec, found, self._check_http_expect)

    def _console_assert(self, step: dict, step_no: int) -> str:
        pass
        spec = validate_console_assert_step(step)
        observer = self.console_observer
        if observer is None:
            raise ConsoleAssertError("console.assert 观测器未初始化（执行器内部错误）")
        policy = self.exec_policy if isinstance(self.exec_policy, dict) else {}
        mode = policy.get("consoleAssertMode")
        return assert_console(observer, spec, step_no,
                              str(mode) if mode is not None else None)

    def _evidence(self, console_obs: ConsoleObserver,
                  steps: list) -> dict:
        pass
        net_hits = [{"url": h["url"], "status": h["status"],
                     "durationMs": h.get("durationMs")}
                    for h in (self.net_observer.hits if self.net_observer else [])[:20]]
        db_digest = [s["detail"] for s in steps
                     if s.get("action") == "sql.assert" and s.get("detail")][:20]
        rejections = (collect_session_auth_rejections(
            self.net_observer.entries, self.env_base_url)
            if self._session_state_in_use and self.net_observer else [])
        return {"console_violations": console_obs.violations[:20],
                "net_hits": net_hits, "db_digest": db_digest,
                "session_auth_rejections": rejections,
                "protocol_snapshots": getattr(self, "_snapshots", [])}

    def _should_snapshot(self, action: str, status: str) -> bool:
        pass
        if status == "fail":
            return True
        mode = self.limits.screenshot_mode
        if mode == SCREENSHOT_EVERY_STEP:
            return True
        if mode == SCREENSHOT_KEY_STEP:
            return action == "expect"
        return mode == SCREENSHOT_FAIL_ONLY and status == "fail"

    def _goto(self, page: Page, step: dict) -> None:
        target = step.get("target") or {}
        url = step.get("url") or target.get("url") or target.get("href")
        if not url:
            raise ValueError("goto 缺少 url")
        resolved = self._resolve_url(str(url))
        page.goto(resolved, wait_until="domcontentloaded")

    def _click(self, page: Page, step: dict) -> None:
        locator = self._locator(page, step)
        locator.click(timeout=STEP_TIMEOUT_MS)

    def _input(self, page: Page, step: dict) -> None:
        locator = self._locator(page, step)
        value = str(step.get("value", ""))
        locator.fill(value, timeout=STEP_TIMEOUT_MS)

    def _select(self, page: Page, step: dict) -> None:
        locator = self._locator(page, step)
        value = step.get("value") or step.get("option")
        if value is None:
            raise ValueError("select 缺少 value/option")
        locator.select_option(str(value), timeout=STEP_TIMEOUT_MS)

    def _expect(self, page: Page, step: dict) -> None:
        target = step.get("target") or {}
        timeout = int(step.get("timeoutMs") or self.limits.assert_timeout_ms)
        state = step.get("state")
        if state is None:
            nested = step.get("expect")
            if isinstance(nested, dict):
                state = nested.get("state")
        state = str(state or "visible")
        if state not in _WAIT_STATES:
            raise ValueError(
                "expect.state 非法值 " + repr(state)
                + "（白名单：" + "、".join(_WAIT_STATES) + "，04 契约 §2.1）")
        match_type = step.get("matchType")
        if match_type is not None:
            match_type = str(match_type)
            if match_type not in MATCH_TYPES:
                raise ExpectMatchError(
                    "expect.matchType 非法值 " + repr(match_type)
                    + "（白名单：" + "、".join(MATCH_TYPES) + "，04 契约 §2.1.1）")
        if match_type in ("range", "regex"):
            raw = self._expect_matched_text(page, step, match_type, state, timeout)
            if step.get("capture"):
                self._capture_page_value(step, raw)
            return
        text = step.get("text") or target.get("text")
        if text:
            exact = bool(step.get("exact", target.get("exact", False))) \
                or match_type == "exact"
            locator = page.get_by_text(str(text), exact=exact).first
            locator.wait_for(state=state, timeout=timeout)  # type: ignore[arg-type]
        else:
            locator = self._locator(page, step).first
            locator.wait_for(state=state, timeout=timeout)  # type: ignore[arg-type]
        if step.get("capture"):
            self._capture_page_value(step, locator.inner_text())

    def _capture_page_value(self, step: dict, raw: str) -> None:
        pass
        self.var_store.set(str(step["capture"]), raw.strip())

    def _sql_exec(self, step: dict) -> str:
        pass
        spec = self._require_conn(step)
        policy = self.exec_policy if isinstance(self.exec_policy, dict) else {}
        return self.db_pool.sql_exec(spec, step, policy)

    def _visual_assert(self, page: Page, step: dict, ctx: _ExecCtx,
                       sr: StepResult) -> None:
        pass
        validate_visual_assert_step(step)
        if sr.step_key is None:
            raise VisualAssertViolation(
                "visual.assert 只能作顶层 setup/steps 步骤（if/loop 容器内子步无基线"
                "稳定键语义）——请移到顶层，两侧口径见 04 §2.1")
        entry = self.visual_baselines.get(sr.step_key)
        explicit_key = str(step.get("baselineKey") or "").strip()
        if not isinstance(entry, dict):
            if explicit_key:
                raise VisualAssertViolation(
                    f"visual.assert 显式指定的基线「{explicit_key}」未随任务下发"
                    f"（步骤稳定键 {sr.step_key} 无生效基线或键不一致）——"
                    "请核对基线是否已人工确认，或移除 baselineKey 走首建流程")
            self._capture_baseline_candidate(page, ctx, sr)
            return
        baseline_png = self._download_baseline(str(entry.get("baselineUrl") or ""),
                                               str(entry.get("baselineKey") or ""))
        current_png = page.screenshot()
        tolerance = step.get("tolerance")
        if tolerance is None:
            tolerance = entry.get("tolerance")
        tolerance = 0.0 if tolerance is None else float(tolerance)
        region = step.get("region")
        try:
            ratio = diff_ratio(baseline_png, current_png, region)
        except ValueError as e:
            raise VisualAssertViolation(f"视觉比对无法进行: {e}") from e
        pct = f"{ratio:.4%}"
        if ratio <= tolerance:
            sr.status = "ok"
            sr.detail = (f"视觉断言通过：差异像素占比 {pct} ≤ 容差 {tolerance:.4%}"
                         f"（基线 {entry.get('baselineKey')}）")
            return
        overlay = overlay_diff_png(baseline_png, current_png)
        path = self._write_visual_file(sr.step_no, "visual-diff", overlay)
        ctx.pending_artifacts.append(
            {"kind": "visual-diff", "stepKey": sr.step_key,
             "stepNo": sr.step_no, "fileName": os.path.basename(path),
             "localPath": path})
        sr.status = "fail"
        sr.detail = (f"视觉断言不满足：差异像素占比 {pct} 超出容差 {tolerance:.4%}"
                     f"（基线 {entry.get('baselineKey')}，对比图随产物回传）")

    def _capture_baseline_candidate(self, page: Page, ctx: _ExecCtx,
                                    sr: StepResult) -> None:
        pass
        path = self._write_visual_file(sr.step_no, "visual-baseline-candidate",
                                       page.screenshot())
        ctx.pending_artifacts.append(
            {"kind": "visual-baseline-candidate", "stepKey": sr.step_key,
             "stepNo": sr.step_no, "fileName": os.path.basename(path),
             "localPath": path})
        sr.status = "baseline_pending"
        sr.detail = ("已采集首图，等待人工确认为基线（稳定键 "
                     + sr.step_key + "）——确认前本步不计失败")

    def _download_baseline(self, url: str, baseline_key: str) -> bytes:
        pass
        import requests
        if not url:
            raise VisualAssertViolation(
                f"基线「{baseline_key}」下载地址缺失（下发异常）——无法比对")
        try:
            resp = requests.get(url, timeout=60,
                                **http_tls_kwargs(self.tls_policy,
                                                  self._tls_material, url))
        except Exception as e:  # noqa: BLE001
            raise VisualAssertViolation(
                f"基线「{baseline_key}」下载失败（网络/预签名过期）：{e}") from e
        if resp.status_code != 200:
            raise VisualAssertViolation(
                f"基线「{baseline_key}」下载失败（HTTP {resp.status_code}，"
                "预签名可能已过期）——请重新执行或确认基线在位")
        return resp.content

    def _write_visual_file(self, step_no: int, tag: str, data: bytes) -> str:
        pass
        base = self.screenshots_dir or tempfile.gettempdir()
        os.makedirs(base, exist_ok=True)
        path = os.path.join(base, f"step{step_no}_{tag}.png")
        with open(path, "wb") as f:
            f.write(data)
        return path

    def _code_step(self, step: dict) -> str:
        pass
        validate_code_step(step)
        res = run_sandboxed_script(str(step["code"]), "code_step.py",
                                   budget_ms=step.get("timeoutMs") or DEFAULT_TIMEOUT_MS)
        if res.timed_out:
            raise CodeStepViolation(
                f"自定义代码步骤超过 {int((step.get('timeoutMs') or DEFAULT_TIMEOUT_MS) / 1000)} 秒"
                "未退出，已限时终止（IT-B8-01 超时分支）")
        if res.returncode != 0:
            raise CodeStepViolation(
                f"自定义代码步骤执行失败（exit={res.returncode}）："
                + (res.stderr.strip() or "无 stderr 输出"))
        detail = f"code.step 执行成功（exit=0）"
        payload = res.stdout.strip()
        save_as = step.get("saveAs")
        if save_as:
            try:
                parsed = json.loads(payload)
            except Exception as e:
                raise CodeStepViolation(
                    f"自定义代码步骤带 saveAs 但 stdout 不是合法 JSON（{e}）——"
                    "请在脚本内 print 单层 JSON 对象（如 print(json.dumps({...}))）") from e
            leaves = scalar_leaves(parsed, str(save_as))
            for name, value in leaves.items():
                self.var_store.set(name, value)
            detail += f"；saveAs {save_as} → {len(leaves)} 个标量变量"
        elif payload:
            detail += f"；stdout: {payload[:500]}"
        if res.stderr.strip():
            detail += f"；stderr: {res.stderr.strip()[:200]}"
        return detail

    def _sql_query(self, step: dict) -> str:
        pass
        spec = self._require_conn(step)
        policy = self.exec_policy if isinstance(self.exec_policy, dict) else {}
        detail, leaves = self.db_pool.sql_query(spec, step, policy)
        for name, value in leaves.items():
            self.var_store.set(name, value)
        return detail

    def _require_conn(self, step: dict) -> dict:
        pass
        conn_name = str(step.get("conn") or "")
        spec = self.connections.get(conn_name)
        if spec is None:
            raise DbExecError(
                f"数据库连接「{conn_name or '(未指定)'}」未随任务下发（检查用例 conn "
                "字段与项目环境连接配置；连接须在管理页「数据库连接」建立并启用）")
        return spec

    def _expect_matched_text(self, page: Page, step: dict, match_type: str,
                             state: str, timeout: int) -> None:
        pass
        if match_type == "range":
            try:
                min_num = float(step["min"]) if step.get("min") is not None else None
                max_num = float(step["max"]) if step.get("max") is not None else None
            except (TypeError, ValueError):
                raise ExpectMatchError(
                    "expect range 的 min/max 必须是数值：min="
                    + repr(step.get("min")) + " max=" + repr(step.get("max"))
                    + "（04 契约 §2.1.1）") from None
            if min_num is None and max_num is None:
                raise ExpectMatchError(
                    "expect matchType=range 缺少 min/max（至少给定一端，04 契约 §2.1.1）")
        else:
            if step.get("pattern") is None:
                raise ExpectMatchError(
                    "expect matchType=regex 缺少 pattern（04 契约 §2.1.1）")
            min_num = max_num = None
        locator = self._locator(page, step).first
        locator.wait_for(state=state, timeout=timeout)  # type: ignore[arg-type]
        raw = locator.inner_text()
        if match_type == "range":
            bounds = str(step.get("bounds") or "closed")
            strip = step.get("strip")
            match_range(raw, min_num, max_num, bounds,
                        DEFAULT_STRIP_CHARS if strip is None else str(strip))
        else:
            match_regex(raw, str(step["pattern"]))
        return raw

    def _wait(self, page: Page, step: dict) -> None:
        ms = step.get("ms")
        if ms is not None:
            page.wait_for_timeout(int(ms))
            return
        target = step.get("target") or {}
        if target:
            state = str(step.get("state") or "visible")
            if state not in _WAIT_STATES:
                raise ValueError(
                    "wait.state 非法值 " + repr(state)
                    + "（白名单：" + "、".join(_WAIT_STATES) + "，04 契约 §2.1）")
            self._locator(page, step).first.wait_for(
                state=state, timeout=self.limits.assert_timeout_ms)  # type: ignore[arg-type]

    def _locator(self, page: Page, step: dict):
        pass
        target = step.get("target") or {}
        if not target:
            raise ValueError(f"动作 {step.get('action')} 缺少 target 定位器")
        if str(target.get("by") or "") == "element":
            return self._locator_by_element(
                page, str(target.get("name") or ""), step.get("action"))
        css = target.get("css") or step.get("css")
        if css:
            return page.locator(str(css))
        xpath = target.get("xpath") or step.get("xpath")
        if xpath:
            return page.locator(f"xpath={xpath}")
        text = target.get("text")
        if text:
            return page.get_by_text(str(text), exact=bool(target.get("exact", False)))
        role = target.get("role")
        if role:
            role_name, _, role_value = str(role).partition(":")
            return page.get_by_role(str(role_name), name=role_value or None)  # type: ignore[arg-type]
        raise ValueError(f"target 未包含可识别定位器（css/xpath/text/role）: {target}")

    # Envelope key literals mirrored 1:1 from the Java records they serialise
    # (ElementAnchorsClaimBinding / ElementSnapshot / WebElementAnchorInput).
    # The bilateral gate test re-reads the Java sources and fails on drift —
    # the gen-dialect lesson: key sets must not live in prose on either side.
    ENVELOPE_KEYS = ("allowCandidate", "elements", "reAnchorSimFloor")
    SNAPSHOT_KEYS = ("elementId", "pageKey", "state", "confidence", "anchors",
                     "variants", "revision")
    ANCHOR_KEYS = ("type", "value", "strength", "note")
 # WE (03 result trailing key elementAnchorHits): one row per anchor
 # attempt; key set bilaterally pinned with WebElementAnchorHitInput.java.
    ANCHOR_HIT_ROW_KEYS = ("elementId", "name", "viewport", "anchorType",
                           "hit", "tried")
    # Value domain of tp_web_element.anchors[].type (V544 COMMENT contract).
    ANCHOR_TYPES = ("css", "xpath", "text", "placeholder", "alt", "testid",
                    "rel", "role", "label")
    # Single source for the browser viewport: the context build and the
    # hit-report rows must state the same bucket — a second literal drifts
    # silently the moment one side changes.
    _VIEWPORT_SIZE = (1440, 900)
    _VIEWPORT_LABEL = f"{_VIEWPORT_SIZE[0]}x{_VIEWPORT_SIZE[1]}"
    # Per-run cap on hit/miss report rows (evidence-size discipline, same as
    # the ≤20 console/net evidence buckets). Overflow appends one
    # __truncated__ sentinel row so a clipped report is never silent.
    _ANCHOR_HIT_REPORT_MAX = 200

    def _locator_by_element(self, page: Page, name: str, action):
        """Reference target → ordered ledger anchors, first hit returned.

        Rows and anchors arrive pre-ordered by the server-side formula
        (confirmed first, strength x confidence); re-scoring here would be a
        second ordering source (red line 6), so this method only tries in the
        given order. Every honest failure names the element and what was
        tried — never a silent fallback to inline guessing.
        """
        binding = self._element_anchors
        if not isinstance(binding, dict):
            raise ValueError(
                f"动作 {action} 引用元素「{name}」，但派发信封未携带 "
                "elementAnchors 键（03 协议 2.3 尾追）——core 版本过旧或装配未命中")
        rows = (binding.get("elements") or {}).get(name)
        if not rows:
            raise ValueError(
                f"元素「{name}」在页面元素库未登记或当前不可派发（信封锚集为空）"
                "——请在「页面元素库」登记该元素后重跑，定位失败")
        tried: list[str] = []
        for row in rows:
            for anchor in row.get("anchors") or []:
                a_type = str(anchor.get("type") or "")
                value = str(anchor.get("value") or "")
                if a_type == "rel":
                    # Relative anchors ride on a confirmed neighbour locator
                    # (design §2.3); the resolver form lands with A3 self-heal —
                    # count it as an honest not-tried, never a silent pass.
                    tried.append(f"rel={value}(相对锚解析形态未落地，本档未尝试)")
                    self._record_anchor_hit(row, name, a_type,
                                            hit=False, tried=False)
                    continue
                if a_type not in self.ANCHOR_TYPES:
                    raise ValueError(
                        f"元素「{name}」锚类型 {a_type!r} 不在白名单 "
                        "（css/xpath/text/placeholder/alt/testid/rel，V544 契约）")
                loc = self._anchor_locator(page, a_type, value)
                if loc.count() > 0:
                    self._record_anchor_hit(row, name, a_type,
                                            hit=True, tried=True)
                    return loc
                self._record_anchor_hit(row, name, a_type,
                                        hit=False, tried=True)
                tried.append(f"{a_type}={value}")
        healed = self._try_equivalent_reanchor(page, name, rows)
        if healed is not None:
            return healed
        raise ValueError(
            f"元素「{name}」全部锚定位失手（尝试 {len(tried)} 个："
            + "；".join(tried) + "）——页面元素库锚已失效，等待修复建议单或人工重锚")

    # ---- WE equivalent re-anchor (design 8.1, two scales) ----
    # One candidate scan collects visible interactive nodes; identity
    # matching and the gate verdict ride on driver.re_anchor (pure rules,
    # L2 pinned) — this side only supplies page facts, never re-scores.
    _CANDIDATE_SCAN_JS = """() => {
      const out = [];
      for (const el of document.querySelectorAll(
          'button,a,input,[role],[data-testid]')) {
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) continue;
        out.push({
          testid: el.getAttribute('data-testid') || null,
          text: ((el.innerText || el.value || '') + '').trim() || null,
          role: (el.getAttribute('role') || el.tagName.toLowerCase()),
        });
        if (out.length >= 500) break;
      }
      return out;
    }"""

    def _try_equivalent_reanchor(self, page: Page, name: str, rows: list):
        """Total-miss gate: heal only when A (identity) and B (visual) both
        hold; an A-hit without a provable B files a WEB suggestion and the
        step still fails honestly (no silent pass, red line 1)."""
        binding = self._element_anchors or {}
        clues = re_anchor.identity_clues(
            [a for row in rows for a in (row.get("anchors") or [])])
        if not clues["testids"] and not clues["texts"]:
            return None
        try:
            candidates = page.evaluate(self._CANDIDATE_SCAN_JS)
        except Exception:
            return None
        if not isinstance(candidates, list):
            return None
        cand = re_anchor.match_candidate(clues, candidates)
        if cand is None:
            return None
        used = re_anchor.candidate_anchor(cand)
        if used is None:
            return None
        row = rows[0]
        from_anchor = (row.get("anchors") or [{}])[0]
        loc = self._healed_locator(page, used)
        try:
            reachable = loc.count() > 0
        except Exception:
            reachable = False
        if not reachable:
            # the scan saw it but the locator engine cannot reach it —
            # nothing to heal with; the proposal still serves the board.
            self._file_web_suggestion(row, from_anchor, used, None)
            return None
        similarity = self._visual_similarity(row, loc)
        verdict = re_anchor.decide(cand, similarity,
                                   binding.get("reAnchorSimFloor"))
        if verdict == "direct":
            self._heal_evidence.append(re_anchor.build_evidence(
                step_no=self._current_step_no, element_id=row.get("elementId"),
                from_anchor=from_anchor,
                used_anchor=used, viewport=self._VIEWPORT_LABEL,
                ledger_revision=row.get("revision"),
                similarity=similarity))
            return loc
        self._file_web_suggestion(row, from_anchor, used, similarity)
        return None

    def _healed_locator(self, page: Page, used: dict):
        """Engine-direct form of a matched candidate. The ledger probe rides
        the configured selector engines; the heal rides the raw attribute/
        normalized-text query the DOM scan actually observed — that dialect
        difference is what reaches a moved or whitespace-dirty element."""
        value = str(used["value"]).replace('"', '\\"')
        if used["type"] == "testid":
            return page.locator(f'[data-testid="{value}"]')
        return page.locator(
            f'xpath=*[normalize-space()="{value}"]')

    def _file_web_suggestion(self, row: dict, from_anchor: dict,
                             used: dict, similarity) -> None:
        self._suggestions.append(re_anchor.build_web_suggestion(
            task_id=getattr(self, "task_id", None),
            step_no=self._current_step_no,
            element_id=row.get("elementId"), from_anchor=from_anchor,
            used_anchor=used, similarity=similarity,
            viewport=self._VIEWPORT_LABEL))

    def _visual_similarity(self, row: dict, loc):
        """Scale B via the injected compare port (core baseline service is
        the single similarity source, B7 family). Port missing or capture
        failure = unprovable, which the gate reads as propose-not-direct."""
        port = getattr(self, "_visual_compare", None)
        if port is None:
            return None
        try:
            return port(row.get("elementId"), loc.first.screenshot())
        except Exception:
            return None

    def _record_anchor_hit(self, row: dict, name: str, a_type: str,
                           hit: bool, tried: bool) -> None:
        """One hit/miss ledger row per anchor attempt (WE .

 The core side recomputes confidence from these outcome rows
 (tp_web_element_anchor_stat, 3b) — the executor only records what
 happened, never re-scores (single ordering/scoring source, red
 line 6). Rows are capped per run; overflow appends exactly one
 __truncated__ sentinel so a clipped report stays honest.
 """
        rows = self._element_anchor_hits
        if len(rows) >= self._ANCHOR_HIT_REPORT_MAX:
            if rows and rows[-1].get("anchorType") == "__truncated__":
                return
            rows.append({"anchorType": "__truncated__",
                         "hit": False, "tried": False})
            return
        rows.append({"elementId": row.get("elementId"), "name": name,
                     "viewport": self._VIEWPORT_LABEL,
                     "anchorType": a_type, "hit": hit, "tried": tried})

    def _anchor_locator(self, page: Page, a_type: str, value: str):
        """Anchor type → Playwright locator (single mapping point, A2)."""
        if a_type == "css":
            return page.locator(value)
        if a_type == "xpath":
            return page.locator(f"xpath={value}")
        if a_type == "text":
            return page.get_by_text(value)
        if a_type == "placeholder":
            return page.get_by_placeholder(value)
        if a_type == "alt":
            return page.get_by_alt_text(value)
        if a_type == "role":
            # Same "role:accessible-name" form as the inline target dialect
            # (see _locator); name stays optional so a bare role still routes.
            role_name, _, role_value = value.partition(":")
            return page.get_by_role(role_name, name=role_value or None)  # type: ignore[arg-type]
        if a_type == "label":
            return page.get_by_label(value)
        return page.get_by_test_id(value)  # testid

    def _resolve_url(self, url: str) -> str:
        pass
        return resolve_url_template(url, self.env_vars, self.env_base_url)

    def _snapshot(self, page: Page, step_no: int, action: str) -> Optional[str]:
        pass
        if not self.screenshots_dir:
            return None
        import os
        os.makedirs(self.screenshots_dir, exist_ok=True)
        path = os.path.join(self.screenshots_dir, f"step{step_no}_{action}.png")
        try:
            page.screenshot(path=path)
            return path
        except Exception:
            return None

    def _observe_dom(self, page: Page) -> str:
        pass
        try:
            text = page.locator("body").inner_text()
        except Exception as e:
            return f"DOM 观测失败: {e}"
        if len(text) > SNAPSHOT_MAX_CHARS:
            return text[:SNAPSHOT_MAX_CHARS] + "…[截断]"
        return text

    def _screenshot(self, page: Page, step: dict) -> Optional[str]:
        import os
        name = step.get("name", "shot.png")
        name = os.path.basename(str(name)).strip() or "shot.png"
        if not os.path.splitext(name)[1]:
            name += ".png"
        if not self.screenshots_dir:
            return None
        os.makedirs(self.screenshots_dir, exist_ok=True)
        path = os.path.join(self.screenshots_dir, name)
        page.screenshot(path=path)
        return path

    def _map_failure(self, detail: str) -> int:
        pass
        if "断言不满足" in detail:
            return 1
        locator_hint = ("定位", "locator", "Timeout", "等待", "get_by", "Locator")
        return 2 if any(h in detail for h in locator_hint) else 3

    def _fail(self, error_type: int, detail: str) -> RunOutcome:
        return RunOutcome(result="fail", error_type=error_type, error_detail=detail,
                          duration_ms=0)


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def _http_status_assertion(expect: dict, actual_status) -> list:
    pass
    want = (expect or {}).get("status")
    if want is None or actual_status is None:
        return []
    try:
        wanted = int(want)
    except (TypeError, ValueError):
        return []
    return [{"kind": "expect.status", "expected": wanted,
             "actual": actual_status, "passed": wanted == actual_status}]


def run_action_json(action_json: str, env_base_url: Optional[str] = None,
                    headless: bool = True, screenshots_dir: Optional[str] = None,
                    env_vars: Optional[dict] = None,
                    script_limits: Optional[dict] = None,
                    var_store: Optional[VarStore] = None,
                    exec_policy: Optional[dict] = None,
                    connections: Optional[list] = None,
                    data_vars: Optional[dict] = None,
                    visual_baselines: Optional[dict] = None,
                    tls_policy: Optional[dict] = None,
                    case_id: int = 0) -> RunOutcome:
    pass
    ex = WebDriverExecutor(env_base_url=env_base_url, headless=headless,
                           screenshots_dir=screenshots_dir, env_vars=env_vars,
                           script_limits=script_limits, exec_policy=exec_policy,
                           connections=connections, data_vars=data_vars,
                           visual_baselines=visual_baselines,
                           tls_policy=tls_policy, case_id=case_id)
    try:
        ex.start()
        return ex.run(action_json, var_store=var_store)
    finally:
        ex.stop()
