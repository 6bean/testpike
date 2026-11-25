# -*- coding: utf-8 -*-
"""T293c mid-platform miniature script contract: two center projects + program (project set)
+ one runnable API case per center aimed at the built-in fixture site + a demo perf scenario
hooked onto the order center (seed only — the script never launches a pressure run).

L2 posture = payload shape checked against the admin outward DTO contract, plus idempotent
assembly of ensure_centers/purge_centers driven by an in-memory recorder for the platform's
own CRUD surface. The recorder stands in for the *seed script's* transport only (this file
is installer tooling, not a product service chain); real API execution is proven live in
the L4 net-machine matrix (T293d). Case style follows the payload-contract precedent in
test_installkit.py. R37: seeding is insert-only idempotent — no TRUNCATE anywhere.

Stub fidelity note: the recorder mirrors the product delete semantics for projects —
DELETE /projects/{id} is an archive soft-delete (status=2, hidden from the active list)
while the code-uniqueness gate keeps counting archived rows — because the first-generation
stub removed rows outright, and that gap hid a real purge/re-seed clash (HTTP 409 on the
center codes, live slot4 evidence).
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest  # noqa: E402

import demo_seed  # noqa: E402

BASE = "http://svc:8081"


def test_center_project_specs_cover_order_and_inventory():
    specs = list(demo_seed.DEMO_CENTER_PROJECTS)
    names = [s["name"] for s in specs]
    assert "订单中心" in names and "库存中心" in names, "中台缩微剧本=两「中心」项目"
    assert all(s["code"].startswith("demo-") for s in specs), "center codes carry the demo- guard"


def test_center_project_payload_shape_matches_admin_contract():
    spec = demo_seed.DEMO_CENTER_PROJECTS[0]
    payload = demo_seed.center_project_payload(spec)
    assert payload["name"] == spec["name"] and payload["code"] == spec["code"]
    assert payload["defaultLang"] == "zh-CN", "same shape as project_payload()"


def test_program_surface_carries_clean_prefix():
    assert demo_seed.DEMO_PROGRAM_NAME.startswith(demo_seed.DEMO_CLEAN_PREFIX), \
        "项目集名必须入一键清除位的命中面"
    payload = demo_seed.program_payload()
    assert payload["name"] == demo_seed.DEMO_PROGRAM_NAME
    assert payload.get("description")


def test_program_payload_never_claims_an_unimplemented_gate():
    """The cross-project contract-reference gate has no implementation in the
    source surface (repo-wide scan, 2026-11-21), so neither the on-screen
    project-set description nor the docstring may promise a demo of it."""
    payload = demo_seed.program_payload()
    assert "契约引用" not in payload["description"], \
        "上屏描述禁声称未实装的跨项目契约引用能力（降级=两中心同集组织演示）"
    doc = demo_seed.program_payload.__doc__ or ""
    assert "即拒" not in doc, "注释同样禁保留虚构的闸门行为句"


def test_center_request_plans_aim_at_built_in_fixture_site():
    plan_order = demo_seed.center_request_plan(demo_seed.DEMO_CENTER_PROJECTS[0])
    assert plan_order["method"] == "POST" and plan_order["urlPath"] == "/api/order"
    assert json.loads(plan_order["bodyText"])["orderNo"], "下单请求必须带可回读的单号"
    plan_stock = demo_seed.center_request_plan(demo_seed.DEMO_CENTER_PROJECTS[1])
    assert plan_stock["method"] == "GET" and plan_stock["urlPath"] == "/login"
    for plan in (plan_order, plan_stock):
        assert not plan["urlPath"].startswith("http"), "SSRF 红线：urlPath 仅路径段"
    for spec in demo_seed.DEMO_CENTER_PROJECTS:
        req = demo_seed.center_request_payload(9, spec)
        assertions = json.loads(req["assertionsJson"])
        assert assertions[0] == {"type": "status", "op": "equals", "expect": 200}


def test_perf_scenario_payload_matches_admin_contract():
    """Shape = admin PerfScenarioSaveRequest field names; validation single
    source = core PerfScenarioServiceImpl (CONSTANT_RPS keys, step action
    whitelist, sla key subset with ratio keys inside [0, 1])."""
    payload = demo_seed.perf_scenario_payload(7, 9)
    assert payload["projectId"] == 7 and payload["envId"] == 9, "演示场景挂订单中心项目与其夹具环境"
    assert payload["pressureModel"] == "CONSTANT_RPS"
    assert payload["pressureConfig"] == {"targetRps": 5, "durationSeconds": 10}, \
        "core 校验单源：CONSTANT_RPS 必带 targetRps/durationSeconds 两正整数键"
    assert all(isinstance(v, int) and v > 0 for v in payload["pressureConfig"].values())
    for step in payload["steps"]:
        assert step["action"] == "http.request", "steps 动作白名单唯一 http.request"
        assert set(step) == {"action", "method", "url"}
        assert step["url"].startswith("http://") and step["url"].endswith("/login"), \
            "perf 步形态=绝对地址打内置夹具站（executor/serve_fixtures.py 单源靶）"
    sla = payload["slaJson"]
    assert set(sla) <= {"p95Ms", "errorRate", "minRps", "breakerErrorRate"}, "slaJson 四键子集"
    for key in ("errorRate", "minRps", "breakerErrorRate"):
        if key in sla:
            assert 0 <= sla[key] <= 1, "比率键校验域 [0,1]"
    assert payload["name"] == demo_seed.DEMO_PERF_SCENARIO_NAME
    assert demo_seed.DEMO_PERF_SCENARIO_NAME.startswith(demo_seed.DEMO_CLEAN_PREFIX), \
        "场景名入一键清除位命中面"
    assert "isolationMode" not in payload, "非生产场景 isolationMode 留空合法（种子不碰起压门）"


class _Resp:
    def __init__(self, data):
        self._data = data

    def json(self):
        return {"code": "0", "data": self._data, "msg": ""}

    @property
    def status_code(self):
        return 200


class _ConflictResp(_Resp):
    """Code uniqueness clash: HTTP 409 + business code 40904, same shape as core."""

    def __init__(self, code):
        super().__init__(None)
        self._code = code

    @property
    def status_code(self):
        return 409

    def json(self):
        return {"code": "40904", "data": None,
                "msg": "项目编码 %s 已存在，请在「新建项目」弹窗改成未被占用的编码（如 TP-001）后重试" % self._code}


class _TraceConflictResp(_Resp):
    """Request trace gate: DELETE /apitest/requests/{id} with run rows answers
    HTTP 409 + business code 40904, verbatim server sentence from
    core ApiRequestServiceImpl.delete (single source, do not reword here)."""

    def __init__(self, request_id):
        super().__init__(None)
        self._rid = request_id

    @property
    def status_code(self):
        return 409

    def json(self):
        return {"code": "40904", "data": None,
                "msg": "该请求已有运行记录，为保追溯链不允许直接删除：请在「API 测试」页先清理"
                       "该请求未升格的运行记录后再删除"}


class _StubPlatform:
    """In-memory recorder of the admin CRUD surface the seed script consumes."""

    def __init__(self):
        self.projects, self.collections, self.requests, self.programs = [], [], [], []
        self.perf = []
        self.cases = []
        self.archived = []  # product truth: project delete = archive (status=2)
        self.envs = {}
        self.attach = set()
        # Trace-gate fidelity: requests listed here keep run rows, so deleting
        # them clashes with the platform audit guard exactly like prod.
        self.executed = set()
        self.factories = []
        self.pool = []
        self.plans = []
        self._ids = iter(range(1000, 9999))

    def get(self, path, headers=None, params=None, **kw):
        params = params or {}
        if path == "/projects":
            return _Resp({"list": self.projects})
        if path == "/projects/archived":
            return _Resp({"list": self.archived})
        if path.endswith("/envs"):
            return _Resp({"list": self.envs.setdefault(path, [])})
        if path == "/cases":
            return _Resp({"list": self.cases})
        if path == "/data-factories/pool-items":
            return _Resp({"list": [x for x in self.pool
                                   if x["projectId"] == params.get("projectId")]})
        if path == "/data-factories":
            return _Resp({"list": [x for x in self.factories
                                   if x["projectId"] == params.get("projectId")]})
        if path == "/plans":
            return _Resp({"list": [x for x in self.plans
                                   if x.get("projectId") == params.get("projectId")]})
        if path == "/apitest/collections":
            return _Resp({"list": [x for x in self.collections
                                   if x["projectId"] == params.get("projectId")]})
        if path == "/apitest/requests":
            return _Resp({"list": [x for x in self.requests
                                   if x["collectionId"] == params.get("collectionId")]})
        if path == "/perf/scenarios":
            return _Resp([x for x in self.perf
                          if x["projectId"] == params.get("projectId")])
        if path == "/programs":
            return _Resp(self.programs)
        if path.startswith("/programs/") and path.endswith("/projects"):
            prog = int(path.split("/")[2])
            return _Resp([p for (pr, p) in self.attach if pr == prog])
        raise AssertionError("unplanned GET " + path)

    def post(self, path, json=None, headers=None, **kw):
        if path == "/projects":
            code = (json or {}).get("code")
            if any(x.get("code") == code for x in self.projects + self.archived):
                # core gate: the project code is globally unique and archived
                # rows keep counting, so a duplicate POST fails exactly like prod.
                return _ConflictResp(code)
            row = dict(json); row["id"] = next(self._ids); self.projects.append(row)
            return _Resp(row)
        if path == "/cases":
            row = dict(json); row["id"] = next(self._ids); self.cases.append(row)
            return _Resp(row)
        if path.endswith("/envs"):
            row = dict(json); row["id"] = next(self._ids)
            self.envs.setdefault(path, []).append(row)
            return _Resp(row)
        if path == "/apitest/collections":
            row = dict(json); row["id"] = next(self._ids); self.collections.append(row)
            return _Resp(row)
        if path == "/apitest/requests":
            row = dict(json); row["id"] = next(self._ids); self.requests.append(row)
            return _Resp(row)
        if path == "/perf/scenarios":
            row = dict(json); row["id"] = next(self._ids); self.perf.append(row)
            return _Resp(row["id"])
        if path == "/programs":
            row = dict(json); row["id"] = next(self._ids); self.programs.append(row)
            return _Resp(row["id"])
        if path.startswith("/programs/") and "/projects/" in path:
            parts = path.split("/")
            self.attach.add((int(parts[2]), int(parts[-1])))
            return _Resp(None)
        if path.startswith("/plans/") and path.endswith("/cancel"):
            return _Resp(None)
        raise AssertionError("unplanned POST " + path)

    def put(self, path, json=None, headers=None, **kw):
        if path.startswith("/projects/") and path.endswith("/restore"):
            pid = int(path.split("/")[2])
            row = next((x for x in self.archived if x["id"] == pid), None)
            if row is None:
                raise AssertionError("restore of a non-archived project %s" % pid)
            self.archived.remove(row)
            row["status"] = 1
            self.projects.append(row)
            return _Resp(row)
        raise AssertionError("unplanned PUT " + path)

    def delete(self, path, headers=None, params=None, **kw):
        if path.startswith("/perf/scenarios/"):
            sid = int(path.rsplit("/", 1)[1])
            self.perf[:] = [x for x in self.perf if x["id"] != sid]
            return _Resp(None)
        if path.startswith("/programs/") and "/projects/" in path:
            parts = path.split("/")
            self.attach.discard((int(parts[2]), int(parts[-1])))
            return _Resp(None)
        if path.startswith("/programs/"):
            prog = int(path.split("/")[2])
            self.programs[:] = [p for p in self.programs if p["id"] != prog]
            self.attach = {(pr, pid) for (pr, pid) in self.attach if pr != prog}
            return _Resp(None)
        if path.startswith("/apitest/requests/"):
            rid = int(path.rsplit("/", 1)[1])
            if rid in self.executed:
                # prod trace gate stays authoritative: run rows keep the request.
                return _TraceConflictResp(rid)
            self.requests[:] = [x for x in self.requests if x["id"] != rid]
            return _Resp(None)
        if path.startswith("/apitest/collections/"):
            cid = int(path.rsplit("/", 1)[1])
            self.collections[:] = [x for x in self.collections if x["id"] != cid]
            return _Resp(None)
        if path.startswith("/cases/"):
            cid = int(path.rsplit("/", 1)[1])
            self.cases[:] = [x for x in self.cases if x["id"] != cid]
            return _Resp(None)
        if path.startswith("/data-factories/pool-items/"):
            iid = int(path.rsplit("/", 1)[1])
            self.pool[:] = [x for x in self.pool if x["id"] != iid]
            return _Resp(None)
        if path.startswith("/data-factories/"):
            fid = int(path.rsplit("/", 1)[1])
            self.factories[:] = [x for x in self.factories if x["id"] != fid]
            return _Resp(None)
        if path.startswith("/plans/"):
            plid = int(path.rsplit("/", 1)[1])
            self.plans[:] = [x for x in self.plans if x["id"] != plid]
            return _Resp(None)
        if path.endswith("/envs") is False and "/envs/" in path:
            eid = int(path.rsplit("/", 1)[1])
            for key, rows in self.envs.items():
                self.envs[key] = [x for x in rows if x["id"] != eid]
            return _Resp(None)
        if path.startswith("/projects/"):
            pid = int(path.split("/")[2])
            row = next(x for x in self.projects if x["id"] == pid)
            self.projects.remove(row)
            # product truth: deleting a project archives it (status=2, out of
            # the active list, restorable by id) — the row is never erased.
            row["status"] = 2
            self.archived.append(row)
            self.envs.pop("/projects/%s/envs" % pid, None)
            return _Resp(None)
        raise AssertionError("unplanned DELETE " + path)


@pytest.fixture()
def stub(monkeypatch):
    fake = _StubPlatform()
    monkeypatch.setattr(demo_seed, "_client", lambda base: (fake, {}))
    return fake


def test_ensure_centers_seeds_full_surface_then_replays_with_zero_churn(stub):
    info = demo_seed.ensure_centers(BASE)
    assert {p["code"] for p in stub.projects} == \
        {s["code"] for s in demo_seed.DEMO_CENTER_PROJECTS}
    assert len(stub.programs) == 1 and len(stub.attach) == 2, "项目集挂载两中心"
    assert len(stub.collections) == 2 and len(stub.requests) == 2, "每中心一条可跑 API 用例"
    assert all(p["code"] in {s["code"] for s in demo_seed.DEMO_CENTER_PROJECTS}
               for p in stub.projects)
    assert info["created"] >= 9, "首跑=新建（项目×2+环境×2+集合×2+请求×2+项目集+挂载）"
    again = demo_seed.ensure_centers(BASE)
    assert again["created"] == 0, "R37 口径：重放幂等零新建"
    assert len(stub.projects) == 2 and len(stub.attach) == 2


def test_ensure_centers_hooks_perf_scenario_on_order_center(stub):
    info = demo_seed.ensure_centers(BASE)
    order_pid = next(p["id"] for p in stub.projects
                    if p["code"] == demo_seed.ORDER_CENTER_CODE)
    scenarios = [x for x in stub.perf if x["projectId"] == order_pid]
    assert len(scenarios) == 1, "演示压测场景挂订单中心"
    assert scenarios[0]["name"] == demo_seed.DEMO_PERF_SCENARIO_NAME
    assert info["perf"], "返回账透出场景 id"
    again = demo_seed.ensure_centers(BASE)
    assert again["created"] == 0 and len(stub.perf) == 1, "R37 重放幂等：场景零重建"


def test_purge_centers_removes_script_surface_and_spares_protected_faces(stub):
    demo_seed.ensure_centers(BASE)
    stub.projects.append({"id": 77, "name": demo_seed.DEMO_PROJECT_NAME,
                          "code": demo_seed.DEMO_PROJECT_CODE})
    removed, skipped = demo_seed.purge_centers(BASE)
    assert skipped == [], "net surface purges without skips"
    assert {p["code"] for p in stub.projects} == {demo_seed.DEMO_PROJECT_CODE}, \
        "只摘 demo- 码面，受保护样例项目零误伤"
    assert stub.programs == [] and not stub.attach
    assert stub.collections == [] and stub.requests == []
    assert removed


def test_purge_centers_removes_perf_scenarios_across_demo_projects(stub):
    demo_seed.ensure_centers(BASE)
    stub.projects.append({"id": 77, "name": demo_seed.DEMO_PROJECT_NAME,
                          "code": demo_seed.DEMO_PROJECT_CODE})
    stub.perf.append({"id": 555, "name": "用户自建压测场景", "projectId": 77})
    removed, skipped = demo_seed.purge_centers(BASE)
    assert [x["name"] for x in stub.perf] == ["用户自建压测场景"], \
        "只删 tp_demo_ 前缀场景，用户场景零误伤"
    assert any("性能场景" in item for item in removed), "清除账逐条可数"


def test_cli_exposes_centers_flag(capsys):
    with pytest.raises(SystemExit) as exc:
        demo_seed.main(["--help"])
    assert exc.value.code == 0
    assert "--centers" in capsys.readouterr().out


def test_center_codes_fit_the_platform_project_code_limit():
    """Seed payload must satisfy the platform's own create-project contract.

    Live slot4 evidence (2026-11-21, L4 window r3): creating the inventory
    center returned HTTP 400 40001 from core ProjectServiceImpl.create, whose
    guard is "code is blank or longer than 20 chars" - the code the seed sent,
    demo-inventory-center, is 21 chars. The order center (17 chars) passed, so
    the script shipped a half-working seed.
    """
    limit = demo_seed.PROJECT_CODE_MAX_LEN
    assert limit == 20, "上限锚=core 项目编码校验（>20 即拒），禁在脚本里放宽成第二套口径"
    for spec in demo_seed.DEMO_CENTER_PROJECTS:
        code = spec["code"]
        assert code.strip() == code and 0 < len(code) <= limit, \
            "中心项目编码必须落在平台契约内且无空白: %s(%d)" % (code, len(code))
    assert 0 < len(demo_seed.DEMO_PROJECT_CODE) <= limit


# ---- purge fault tolerance (demo data gets really executed -> trace gate bites)

def test_purge_journey_survives_trace_blocked_request_and_finishes_ledger(stub):
    """Purge must delete everything else even when one demo request keeps runs.

    Crash shape (live slot4 evidence): the request delete hit the trace gate,
    the whole purge aborted mid-way and left factories/plans behind — retry
    clashed at the same point. Tolerance = per-item skip ledger, full pass.
    """
    pid, _ = demo_seed.ensure_sample(BASE)
    stub.collections.append({"id": 401, "name": demo_seed.DEMO_API_COLLECTION_NAME,
                             "projectId": pid})
    stub.requests.append({"id": 402, "name": demo_seed.DEMO_API_REQUEST_NAME,
                          "collectionId": 401})
    stub.executed.add(402)
    stub.factories.append({"id": 404, "name": demo_seed.DEMO_DATA_FACTORY_NAME,
                           "projectId": pid})
    stub.plans.append({"id": 405, "name": demo_seed.DEMO_PLAN_NAME,
                       "projectId": pid, "status": 1})

    removed, skipped = demo_seed.purge_journey(BASE)

    assert any("402" in line for line in skipped), "被追溯闸保留的请求必须按账点名"
    assert any("401" in line for line in skipped), "其容器集合随留并进账（级联不假删）"
    assert 402 in {r["id"] for r in stub.requests}, "服务端闸不放行，件照留"
    assert 401 in {c["id"] for c in stub.collections}
    assert any("404" in item for item in removed) and 404 not in {f["id"] for f in stub.factories}, \
        "撞闸之后的造数池照常删完（旧形态在此中途退出）"
    assert any("405" in item for item in removed) and not stub.plans, "回归计划照常删完"


def test_purge_centers_keeps_blocked_center_and_finishes_the_rest(stub):
    """One executed center request must not strand the other center's teardown."""
    info = demo_seed.ensure_centers(BASE)
    order = info["centers"][demo_seed.ORDER_CENTER_CODE]
    stock = info["centers"][demo_seed.DEMO_CENTER_PROJECTS[1]["code"]]
    stub.executed.add(order["request"])

    removed, skipped = demo_seed.purge_centers(BASE)

    assert any(str(order["request"]) in line for line in skipped), "被闸件点名可复读"
    assert any(str(order["collection"]) in line for line in skipped), "容器集合随留"
    assert any(str(stock["request"]) in item for item in removed), "另一中心链路照常拆完"
    assert stock["request"] not in {r["id"] for r in stub.requests}
    assert stub.programs == [], "项目集拆链不受单件撞闸影响"


def test_purge_keeps_other_failures_loud(stub):
    """Fault tolerance is scoped to the conflict code the gate answers.

    Any other business code (or a malformed refusal) still aborts the purge —
    the ledger never swallows unrelated server failures.
    """
    class _ServerBoom:
        def delete(self, path, headers=None, params=None, **kw):
            class _R:
                status_code = 500

                def json(self):
                    return {"code": "50000", "msg": "内部错误"}
            return _R()

    removed, skipped = [], []
    with pytest.raises(SystemExit):
        demo_seed._delete_item(_ServerBoom(), {}, "/apitest/requests/1", "中心请求",
                               1, removed, skipped)
    assert removed == [] and skipped == [], "非冲突码不入账不吞错"


def test_cli_purge_prints_skip_ledger_with_repair_path(stub, capsys):
    """On-screen ledger duty: skips show the count and the server's entry path."""
    pid, _ = demo_seed.ensure_sample(BASE)
    stub.collections.append({"id": 411, "name": demo_seed.DEMO_API_COLLECTION_NAME,
                             "projectId": pid})
    stub.requests.append({"id": 412, "name": demo_seed.DEMO_API_REQUEST_NAME,
                          "collectionId": 411})
    stub.executed.add(412)

    rc = demo_seed.main(["--purge", "--base-url", BASE])
    out = capsys.readouterr().out

    assert rc == 0, "带跳过账的清除是完成态，不是失败态"
    assert "deleted, 2 kept" in out, "合计数账（删除/跳过分列）"
    assert "「API 测试」页" in out, "指路随跳过件上屏（原因+入口+后果三要素）"


def test_purge_centers_also_removes_the_legacy_overlong_code_face(stub):
    """Codes renamed inside the 20-char limit still purge the old face.

    Environments seeded before the rename carry the legacy code; the one-click
    clear surface must stay able to reclaim them, otherwise a code change turns
    already-seeded projects into unremovable residue.
    """
    demo_seed.ensure_centers(BASE)
    for legacy in demo_seed.LEGACY_CENTER_CODES:
        assert len(legacy) > demo_seed.PROJECT_CODE_MAX_LEN or legacy
    stub.projects.append({"id": 77, "name": demo_seed.DEMO_PROJECT_NAME,
                          "code": demo_seed.DEMO_PROJECT_CODE})
    stub.projects.append({"id": 88, "name": "历史播种残留中心",
                          "code": demo_seed.LEGACY_CENTER_CODES[0]})
    removed, skipped = demo_seed.purge_centers(BASE)
    codes = {p["code"] for p in stub.projects}
    assert demo_seed.LEGACY_CENTER_CODES[0] not in codes, \
        "旧码面必须仍入一键清除位，否则换码后环境留下不可回收的残项目"
    assert demo_seed.DEMO_PROJECT_CODE in codes, "受保护样例项目零误伤"
    assert "中心项目#88" in removed, "清除账逐条可数"


def test_centers_replay_after_purge_restores_archived_projects(stub):
    pass
    first = demo_seed.ensure_centers(BASE)
    demo_seed.purge_centers(BASE)
    replay = demo_seed.ensure_centers(BASE)
    assert {p["code"] for p in stub.projects} == \
        {s["code"] for s in demo_seed.DEMO_CENTER_PROJECTS}
    assert stub.archived == [], "两中心经还原通道归位，归档面无残留"
    for spec in demo_seed.DEMO_CENTER_PROJECTS:
        code = spec["code"]
        assert replay["centers"][code]["project"] == first["centers"][code]["project"], \
            "还原复用归档行原 id=产品归档语义（数据保留可恢复）"
    assert len(stub.programs) == 1 and len(stub.attach) == 2, "项目集与挂载随重播收敛"


def test_ensure_sample_after_purge_also_reuses_archived_row(stub):
    """Same restore channel must back the sample-project face (one helper, two consumers).

    ensure_sample used to hand-roll its own active-list lookup; after this batch
    both consumers share one project idempotency helper, so an archived sample
    project is restored and reused instead of clashing on its code.
    """
    pid, cid = demo_seed.ensure_sample(BASE)
    assert pid and cid
    stub.projects[:] = []
    stub.archived.append({"id": pid, "name": demo_seed.DEMO_PROJECT_NAME,
                          "code": demo_seed.DEMO_PROJECT_CODE, "status": 2})
    replay_pid, replay_cid = demo_seed.ensure_sample(BASE)
    assert replay_pid == pid, "样例项目面走同一还原通道复用归档行"
    assert stub.archived == []


def test_seed_source_never_truncates():
    src = Path(demo_seed.__file__).read_text(encoding="utf-8")
    assert "TRUNCATE" not in src.upper(), "R37 出厂基线口径：种子插-only，禁清表"
