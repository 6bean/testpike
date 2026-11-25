# -*- coding: utf-8 -*-
pass
import argparse
import json
import os
import sys
from pathlib import Path

import httpx

DEMO_PROJECT_NAME = "testPike 体验样例"
DEMO_PROJECT_CODE = "demo-first"
DEMO_CASE_TITLE = "样例_登录首页可达（演示 AI 生成与执行）"

DEMO_CLEAN_PREFIX = "tp_demo_"
DEMO_API_COLLECTION_NAME = DEMO_CLEAN_PREFIX + "样例接口集合"
DEMO_API_REQUEST_NAME = DEMO_CLEAN_PREFIX + "样例请求_登录页可达"
DEMO_APP_CASE_TITLE = DEMO_CLEAN_PREFIX + "样例_APP端冒烟（演示移动端目标）"
DEMO_DATA_FACTORY_NAME = DEMO_CLEAN_PREFIX + "样例造数池_订单号"
DEMO_PLAN_NAME = DEMO_CLEAN_PREFIX + "样例回归计划"
DEMO_ENV_NAME = DEMO_CLEAN_PREFIX + "样例夹具环境"
DEMO_REGRESSION_CASE_TITLE = DEMO_CLEAN_PREFIX + "样例_首页主链路回归（已通过评审，供计划执行）"
DEMO_FLOW_COLLECTION_NAME = DEMO_CLEAN_PREFIX + "样例编排流程_下单旅程"
DEMO_FLOW_ORDER_VAR = "ORDER_NO"
DEMO_FLOW_ORDER_NO = "DEMO-FLOW-0001"
DEMO_EXECUTOR_USERNAME = "demo_tester"

ENV_USER_KEY = "ADMIN_INIT_USER"
ENV_PASSWORD_KEY = "ADMIN_INIT_PASSWORD"


def env_val(key, env_file=None, default=None):
    pass
    value = os.environ.get(key)
    if value:
        return value.strip()
    path = Path(env_file) if env_file else Path(__file__).resolve().parents[1] / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if s.startswith(key + "="):
                return s.split("=", 1)[1].strip().strip('"').strip("'")
    if default is not None:
        return default
    raise SystemExit("Missing credential %s -- configure it in the project-root "
                     ".env file (or export an environment variable with the same "
                     "name), then re-run the seeder; the installer never fills "
                     "passwords on your behalf" % key)


def project_payload():
    return {"name": DEMO_PROJECT_NAME, "code": DEMO_PROJECT_CODE,
            "defaultLang": "zh-CN"}


def case_payload(project_id):
    return {"projectId": project_id, "title": DEMO_CASE_TITLE,
            "precondition": "平台前端与样例环境可访问",
            "expectedResult": "打开首页成功且无 JS 报错",
            "priority": 1, "confidence": 2, "endType": "web", "status": 1,
            "steps": [{"stepNo": 1, "description": "打开平台首页", "isAssert": 0},
                      {"stepNo": 2, "description": "检查页面标题渲染与无控制台报错", "isAssert": 1}]}



def api_collection_payload(project_id):
    pass
    return {"projectId": project_id, "parentId": 0,
            "name": DEMO_API_COLLECTION_NAME, "tags": "demo"}


def api_request_payload(collection_id):
    pass
    return {"collectionId": collection_id, "name": DEMO_API_REQUEST_NAME,
            "method": "GET", "urlPath": "/login",
            "headersJson": "[]", "bodyText": "", "authJson": "",
            "assertionsJson": json.dumps([{"type": "status", "op": "equals", "expect": 200}])}


def flow_demo_collection_payload(project_id):
    """Container for the factory demo journey : a dedicated
 collection holding the four orchestrated hops, seeded separately from the
 single-request sample so the flow pane shows something runnable on arrival."""
    return {"projectId": project_id, "parentId": 0,
            "name": DEMO_FLOW_COLLECTION_NAME, "tags": "demo,flow"}


def flow_demo_payloads(collection_id):
    """Factory demo journey, four hops: login -> create order -> poll -> paging.

    The precondition/loop column shapes are members of the shared both-ends
    fixture vocabulary (shared/orchestration_form_cases.json; save-gate
    authority = common ApiOrchestrationContract) — the journey must never
    invent a second orchestration syntax. Hop3's precondition consumes the
    ORDER_NO that hop2 extracts (an unsatisfied precondition skips honestly,
    which is the three-state demo face); hop3's until-stop loop carries a
    maxTimes guard, and hop4 pages for page 2 which the fixture site answers
    404 — the seeded assertion expects exactly that (honest no-more-data
    empty state).
    """
    def hop(name, method, url_path, body, assertions,
            precondition="", loop=""):
        return {"collectionId": collection_id, "name": name,
                "method": method, "urlPath": url_path,
                "headersJson": ('[{"name":"Content-Type","value":"application/json"}]'
                                if method == "POST" else "[]"),
                "bodyText": body, "authJson": "",
                "assertionsJson": assertions,
                "preconditionJson": precondition, "loopJson": loop}

    return [
        hop(DEMO_CLEAN_PREFIX + "剧本1_登录可达", "GET", "/login", "",
            json.dumps([{"type": "status", "op": "equals", "expect": 200}])),
        hop(DEMO_CLEAN_PREFIX + "剧本2_建单并提取单号", "POST", "/api/order",
            json.dumps({"orderNo": DEMO_FLOW_ORDER_NO, "userId": 42}),
            json.dumps([{"type": "status", "op": "equals", "expect": 200},
                        {"type": "extract", "path": "$.data.orderNo",
                         "saveAs": DEMO_FLOW_ORDER_VAR}])),
        hop(DEMO_CLEAN_PREFIX + "剧本3_轮询订单状态", "GET",
            "/api/order/{{VAR:%s}}" % DEMO_FLOW_ORDER_VAR, "",
            json.dumps([{"type": "status", "op": "equals", "expect": 200}]),
            precondition=json.dumps({"field": "var", "op": "exists",
                                     "name": DEMO_FLOW_ORDER_VAR}),
            loop=json.dumps({"mode": "until", "maxTimes": 3, "delayMs": 200,
                             "until": {"field": "status", "op": "eq", "expect": 200}})),
        hop(DEMO_CLEAN_PREFIX + "剧本4_分页拉取空态", "GET", "/api/orders/page/2", "",
            json.dumps([{"type": "status", "op": "equals", "expect": 404}]),
            loop=json.dumps({"mode": "count", "times": 2, "delayMs": 200})),
    ]


def app_case_payload(project_id):
    pass
    return {"projectId": project_id, "title": DEMO_APP_CASE_TITLE,
            "precondition": "移动端自动化引擎与设备池已就绪（演示展示用，未挂设备不影响建卡）",
            "expectedResult": "移动端冒烟场景通过",
            "priority": 1, "confidence": 2, "endType": "mobile", "status": 1,
            "automationTarget": "app", "appautoEngine": "MOBILEWRIGHT",
            "steps": [{"stepNo": 1, "description": "启动样例 App 进入首页", "isAssert": 0},
                      {"stepNo": 2, "description": "断言首页元素可见", "isAssert": 1}]}


def data_factory_payload(project_id, env_id):
    pass
    content = {"poolType": "订单号", "method": "POST", "url": fixture_base() + "/api/order",
               "headers": {"Content-Type": "application/json"},
               "body": {"orderNo": "DEMO-${gen.dateTime(format=yyyyMMddHHmmssSSS)}", "userId": 1},
               "extractExpr": "$.data.orderNo"}
    return {"projectId": project_id, "envId": env_id, "type": 1,
            "name": DEMO_DATA_FACTORY_NAME,
            "content": json.dumps(content, ensure_ascii=False), "paramsJson": ""}


def plan_payload(project_id, env_ids):
    pass
    return {"projectId": project_id, "name": DEMO_PLAN_NAME,
            "targetVersion": "demo", "envIds": list(env_ids),
            "caseScope": {"phases": [3], "priorities": [0, 1], "keyword": None, "tagIds": None},
            "entryCriteria": None,
            "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
            "planStart": None, "planEnd": None, "lockMode": 1}


def badge_url(badge_token, api_base="http://localhost:8082"):
    pass
    return "%s/open/badge/%s.svg" % (api_base.rstrip("/"), badge_token)


def fixture_base():
    """Built-in fixture site base address (single source for every seeded URL host).

    ``TP_FIXTURE_BASE`` overrides via .env / environment; the shipped default
    matches compose's published fixture port so a zero-config install keeps
    working. Container-pinned stacks (slot chains) re-publish the port and
    pass their own base — seeded cases must run where the executor runs.
    """
    return env_val("TP_FIXTURE_BASE", default="http://127.0.0.1:8899")


def env_payload():
    pass
    return {"name": DEMO_ENV_NAME, "envType": 2, "baseUrl": fixture_base(),
            "configJson": "{}", "endTypes": "web", "browsers": "chromium",
            "versionSnapshot": ""}


def regression_case_payload(project_id):
    pass
    return {"projectId": project_id, "title": DEMO_REGRESSION_CASE_TITLE,
            "precondition": "样例夹具环境可访问（本地夹具站）",
            "expectedResult": "回归主链路通过且无未关闭缺陷",
            "priority": 0, "confidence": 1, "endType": "web", "status": 3, "phase": 3,
            "steps": [{"stepNo": 1, "description": "打开平台首页并检索样例用例", "isAssert": 0},
                      {"stepNo": 2, "description": "断言列表可见且无 JS 报错", "isAssert": 1}]}


# one runnable API case per center aimed at the built-in fixture site.
# Project codes are bounded by the platform itself: core
# ProjectServiceImpl.create rejects a blank code or one longer than 20 chars
# (live slot4 evidence 2026-11-21: the 21-char "demo-inventory-center" got HTTP
# 400 40001, so the script kept the order center and silently lost the second
# one). The seed mirrors that bound instead of inventing a looser one.
PROJECT_CODE_MAX_LEN = 20
DEMO_CENTER_PROJECTS = (
    {"name": "订单中心", "code": "demo-order",
     "purpose": "中台缩微剧本：下单受理与按单号回读"},
    {"name": "库存中心", "code": "demo-inventory",
     "purpose": "中台缩微剧本：库存页面可达冒烟"},
)
ORDER_CENTER_CODE = DEMO_CENTER_PROJECTS[0]["code"]
# Retired code face kept on the clear surface: environments seeded before the
# rename still carry these codes and must stay reclaimable after it.
LEGACY_CENTER_CODES = ("demo-order-center", "demo-inventory-center")
DEMO_PROGRAM_NAME = DEMO_CLEAN_PREFIX + "中台样例项目集"
DEMO_CENTER_COLLECTION_NAME = DEMO_CLEAN_PREFIX + "中心可跑用例集"


def center_project_payload(spec):
    pass
    return {"name": spec["name"], "code": spec["code"], "defaultLang": "zh-CN"}


def program_payload():
    pass
    return {"name": DEMO_PROGRAM_NAME,
            "description": "中台缩微剧本：订单/库存两中心同集，演示中台项目的组织关系"}


def center_collection_payload(project_id):
    return {"projectId": project_id, "parentId": 0,
            "name": DEMO_CENTER_COLLECTION_NAME, "tags": "demo,center"}


def center_request_plan(spec):
    pass
    if spec["code"] == ORDER_CENTER_CODE:
        return {"name": DEMO_CLEAN_PREFIX + "中心请求_下单受理",
                "method": "POST", "urlPath": "/api/order",
                "headersJson": '[{"name":"Content-Type","value":"application/json"}]',
                "bodyText": json.dumps({"orderNo": "DEMO-CENTER-0001", "userId": 7})}
    return {"name": DEMO_CLEAN_PREFIX + "中心请求_页面可达",
            "method": "GET", "urlPath": "/login",
            "headersJson": "[]", "bodyText": ""}


def center_request_payload(collection_id, spec):
    pass
    plan = center_request_plan(spec)
    return {"collectionId": collection_id, "name": plan["name"],
            "method": plan["method"], "urlPath": plan["urlPath"],
            "headersJson": plan["headersJson"], "bodyText": plan["bodyText"],
            "authJson": "",
            "assertionsJson": json.dumps([{"type": "status", "op": "equals", "expect": 200}])}


DEMO_PERF_SCENARIO_NAME = DEMO_CLEAN_PREFIX + "演示压测场景_登录页恒定RPS"
PERF_FIXTURE_BASE_DEFAULT = "http://127.0.0.1:8899"


def perf_scenario_payload(project_id, env_id):
    pass
    base = fixture_base()
    return {"projectId": project_id, "name": DEMO_PERF_SCENARIO_NAME, "envId": env_id,
            "pressureModel": "CONSTANT_RPS",
            "pressureConfig": {"targetRps": 5, "durationSeconds": 10},
            "steps": [{"action": "http.request", "method": "GET",
                       "url": base.rstrip("/") + "/login"}],
            "slaJson": {"p95Ms": 800.0, "errorRate": 0.05}}


def _need(resp, what):
    j = resp.json()
    if j.get("code") != "0":
        raise SystemExit("%s failed: HTTP %s, code=%s, msg=%s"
                         % (what, resp.status_code,
                            j.get("code"), j.get("msg")))
    return j.get("data")


# Business code the platform answers when a deletion clashes with a keep-in-place
# gate: common BizErrorCode.RESOURCE_CONFLICT (request trace gate and the
# non-empty container gate share it), single source = the server, never relaxed.
CONFLICT_GATE_CODE = "40904"


def _delete_item(c, h, path, label, item_id, removed, skipped, params=None):
    """Delete one purge target with per-item fault tolerance.

    The one-click clear surface must finish its whole ledger even when a demo
    row was executed meanwhile: the platform keeps requests that carry run
    records (trace chain) and refuses containers that still hold children,
    both answering the RESOURCE_CONFLICT business code. Such refusals are
    named in the skip ledger together with the server's own repair sentence
    (entry path included) and the purge keeps going. Any other failure still
    stops the script loudly — the tolerance never widens past the conflict
    gate and the server-side trace gate itself stays untouched.
    Returns True when the item was deleted.
    """
    resp = c.delete(path, headers=h, params=params)
    j = resp.json()
    if j.get("code") == "0":
        removed.append("%s#%s" % (label, item_id))
        return True
    if str(j.get("code")) == CONFLICT_GATE_CODE:
        skipped.append("%s#%s: %s" % (label, item_id, j.get("msg")))
        return False
    raise SystemExit("Deleting %s failed: HTTP %s, code=%s, msg=%s"
                     % (label, resp.status_code,
                        j.get("code"), j.get("msg")))


def _rows(data):
    if isinstance(data, dict):
        return data.get("list") or data.get("records") or []
    return data or []


def _client(base_url):
    pass
    c = httpx.Client(base_url=base_url.rstrip("/") + "/api/v1", timeout=30)
    tok = _need(c.post("/auth/login", json={"username": env_val(ENV_USER_KEY, default="admin"),
                                            "password": env_val(ENV_PASSWORD_KEY)}),
                "登录")["accessToken"]
    return c, {"Authorization": "Bearer " + tok}


def _find_or_create(c, h, list_path, list_params, match_key, want, create_path, payload, what):
    pass
    for x in _rows(_need(c.get(list_path, headers=h, params=list_params), what + "列表")):
        if x.get(match_key) == want:
            return x["id"], False
    created = _need(c.post(create_path, headers=h, json=payload), "创建" + what)
    return (created["id"] if isinstance(created, dict) else created), True


def _one_id(data):
    return data["id"] if isinstance(data, dict) else data


def _find_or_restore_project(c, h, code, payload, what):
    """Project idempotency, single source for every seed face that owns a project.

    Order = active list by code -> archived list by code + the product's own
    recovery channel PUT /projects/{id}/restore -> create. Rationale: deleting a
    project archives it (status=2, hidden from the active list) while the code
    uniqueness gate keeps counting archived rows, so a purge/seed cycle that
    only listed active projects died live with HTTP 409 on the center codes.
    Restoring keeps the original row (history recoverable, product semantics)
    and avoids any second delete path.

    Returns (project_id, created); created is True only when a new row landed.
    """
    for x in _rows(_need(c.get("/projects", headers=h, params={"size": 100}), what + "列表")):
        if x.get("code") == code:
            return x["id"], False
    for x in _rows(_need(c.get("/projects/archived", headers=h,
                              params={"size": 100}), what + "归档列表")):
        if x.get("code") == code:
            restored = _need(c.put("/projects/%s/restore" % x["id"], headers=h),
                             "还原" + what)
            return _one_id(restored), False
    created = _need(c.post("/projects", headers=h, json=payload), "创建" + what)
    return _one_id(created), True


def ensure_sample(base_url):
    pass
    c, h = _client(base_url)

    pid, _ = _find_or_restore_project(c, h, DEMO_PROJECT_CODE, project_payload(), "样例项目")

    cid = None
    cases = _need(c.get("/cases", headers=h,
                        params={"projectId": pid, "keyword": DEMO_CASE_TITLE, "size": 10}),
                  "用例列表")
    for x in _rows(cases):
        if x.get("title") == DEMO_CASE_TITLE:
            cid = x["id"]
    if cid is None:
        created = _need(c.post("/cases", headers=h, json=case_payload(pid)), "创建样例用例")
        cid = _one_id(created)
    return pid, cid


def ensure_journey(base_url):
    pass
    c, h = _client(base_url)
    out = {"created": 0}

    pid, _ = ensure_sample(base_url)
    out["project"] = pid

    env_id, new_env = _find_or_create(
        c, h, "/projects/%s/envs" % pid, {}, "name", DEMO_ENV_NAME,
        "/projects/%s/envs" % pid, env_payload(), "样例环境")
    out["env"] = env_id
    out["created"] += int(new_env)

    coll_id, new_coll = _find_or_create(
        c, h, "/apitest/collections", {"projectId": pid}, "name", DEMO_API_COLLECTION_NAME,
        "/apitest/collections", api_collection_payload(pid), "接口集合")
    out["collection"] = coll_id
    out["created"] += int(new_coll)

    reqs = _rows(_need(c.get("/apitest/requests", headers=h,
                             params={"collectionId": coll_id}), "接口请求列表"))
    req_id = next((x["id"] for x in reqs if x.get("name") == DEMO_API_REQUEST_NAME), None)
    if req_id is None:
        req_id = _one_id(_need(c.post("/apitest/requests", headers=h,
                                      json=api_request_payload(coll_id)), "创建接口请求"))
        out["created"] += 1
    out["request"] = req_id

    # Factory demo journey : the four hops (login -> order ->
    # poll -> paging) live in their own collection so the flow pane shows a
    # runnable orchestration sample right after install. Same-name rows are
    # reused = idempotent replay with zero churn.
    flow_coll_id, new_flow_coll = _find_or_create(
        c, h, "/apitest/collections", {"projectId": pid}, "name", DEMO_FLOW_COLLECTION_NAME,
        "/apitest/collections", flow_demo_collection_payload(pid), "编排流程集合")
    out["flowCollection"] = flow_coll_id
    out["created"] += int(new_flow_coll)
    flow_reqs = _rows(_need(c.get("/apitest/requests", headers=h,
                                  params={"collectionId": flow_coll_id}), "编排请求列表"))
    flow_ids = []
    for payload in flow_demo_payloads(flow_coll_id):
        existing = next((x["id"] for x in flow_reqs
                         if x.get("name") == payload["name"]), None)
        if existing is None:
            existing = _one_id(_need(c.post("/apitest/requests", headers=h,
                                            json=payload), "创建编排请求"))
            out["created"] += 1
        flow_ids.append(existing)
    out["flowRequests"] = flow_ids

    app_cid, new_app = _find_or_create(
        c, h, "/cases", {"projectId": pid, "keyword": DEMO_APP_CASE_TITLE, "size": 10},
        "title", DEMO_APP_CASE_TITLE, "/cases", app_case_payload(pid), "APP 样例用例")
    out["appCase"] = app_cid
    out["created"] += int(new_app)

    reg_cid, new_reg = _find_or_create(
        c, h, "/cases", {"projectId": pid, "keyword": DEMO_REGRESSION_CASE_TITLE, "size": 10},
        "title", DEMO_REGRESSION_CASE_TITLE, "/cases", regression_case_payload(pid), "回归出厂用例")
    out["regressionCase"] = reg_cid
    out["created"] += int(new_reg)

    df_id, new_df = _find_or_create(
        c, h, "/data-factories", {"projectId": pid, "size": 50},
        "name", DEMO_DATA_FACTORY_NAME, "/data-factories",
        data_factory_payload(pid, env_id), "造数池")
    out["dataFactory"] = df_id
    out["created"] += int(new_df)

    df_view = _need(c.get("/data-factories/%s" % df_id, headers=h), "造数池详情")
    want_content = json.loads(data_factory_payload(pid, env_id)["content"])
    stored_content = json.loads(df_view.get("content") or "{}")
    if stored_content != want_content:
        upd = data_factory_payload(pid, env_id)
        _need(c.put("/data-factories/%s" % df_id, headers=h,
                    json={"name": DEMO_DATA_FACTORY_NAME, "content": upd["content"],
                          "status": None, "paramsJson": ""}), "修正造数池 content")

    tester = _rows(_need(c.get("/users", headers=h,
                               params={"keyword": DEMO_EXECUTOR_USERNAME, "size": 10}), "用户列表"))
    tester_id = next((u["id"] for u in tester if u.get("username") == DEMO_EXECUTOR_USERNAME), None)
    if tester_id is None:
        raise SystemExit("Factory demo account %s is missing -- the database was "
                         "created without the V220 migration or the account was "
                         "deleted; journey step 4 cannot assign an executor and "
                         "will not fall back silently" % DEMO_EXECUTOR_USERNAME)
    members = _rows(_need(c.get("/projects/%s/members" % pid, headers=h), "项目成员列表"))
    if not any(m.get("userId") == tester_id for m in members):
        _need(c.post("/projects/%s/members" % pid, headers=h,
                     json={"userId": tester_id, "role": 1}), "加入项目成员")

    plan_id, new_plan = _find_or_create(
        c, h, "/plans", {"projectId": pid, "size": 50},
        "name", DEMO_PLAN_NAME, "/plans", plan_payload(pid, [env_id]), "回归计划")
    out["plan"] = plan_id
    out["created"] += int(new_plan)

    plan_view = _need(c.get("/plans/%s" % plan_id, headers=h), "计划详情")
    if plan_view.get("status") == 1:
        _need(c.put("/plans/%s" % plan_id, headers=h,
                    json={**plan_payload(pid, [env_id]), "ownerId": None}), "更新计划范围")
        cases_in_plan = _rows(_need(c.get("/plans/%s/cases" % plan_id, headers=h), "计划用例列表"))
        unassigned = [x["caseId"] for x in cases_in_plan if x.get("assigneeId") is None]
        if unassigned:
            _need(c.post("/plans/%s/assign" % plan_id, headers=h,
                         json={"caseIds": unassigned, "assigneeId": tester_id}), "分配执行人")
        _need(c.post("/plans/%s/publish" % plan_id, headers=h), "发布回归计划")
    _need(c.post("/plans/%s/generate-batches" % plan_id, headers=h), "生成批次")

    batch = _rows(_need(c.get("/batches", headers=h,
                              params={"projectId": pid, "keyword": DEMO_PLAN_NAME, "size": 20}),
                        "批次列表"))
    out["batch"] = batch[0]["id"] if batch else None
    return out


def purge_pool_items(c, h, project_id, env_id=None, skipped=None):
    pass
    removed = []
    skipped = [] if skipped is None else skipped
    params = {"projectId": project_id, "size": 100}
    if env_id:
        params["envId"] = env_id
    pool = _rows(_need(c.get("/data-factories/pool-items", headers=h, params=params), "数据池列表"))
    for p in pool:
        if str(p.get("value") or "").startswith("DEMO-"):
            _delete_item(c, h, "/data-factories/pool-items/%s" % p["id"], "池件",
                         p["id"], removed, skipped, params={"projectId": project_id})
    return removed


def purge_journey(base_url):
    pass
    c, h = _client(base_url)
    pid, _ = ensure_sample(base_url)
    skipped = []
    removed = purge_pool_items(c, h, pid, skipped=skipped)

    def drop(path, params, match_key, want, label):
        rows = _rows(_need(c.get(path, headers=h, params=params), label + "列表"))
        for x in rows:
            if x.get(match_key) == want:
                _delete_item(c, h, "%s/%s" % (path, x["id"]), label, x["id"],
                             removed, skipped)

    def drop_container(container, child_path, child_key, child_want,
                       request_label, container_label):
        """Delete matching children first; the container follows only when it
        is really empty — kept children produce a named skip line instead of
        a doomed delete call whose refusal sentence would point at the wrong
        repair step."""
        kept = []
        for r in _rows(_need(c.get(child_path, headers=h,
                                   params={"collectionId": container["id"]}),
                             child_key + "列表")):
            if r.get("name") in child_want:
                if not _delete_item(c, h, "%s/%s" % (child_path, r["id"]),
                                    request_label, r["id"], removed, skipped):
                    kept.append(r["id"])
        if kept:
            skipped.append("%s#%s：保留——其内演示请求 #%s 的运行记录未清理，"
                           "先到「API 测试」页清理这些请求的运行记录，再重跑一次清除即可"
                           % (container_label, container["id"],
                              "、#".join(str(x) for x in kept)))
        else:
            _delete_item(c, h, "/apitest/collections/%s" % container["id"],
                         container_label, container["id"], removed, skipped)

    drop("/data-factories", {"projectId": pid, "size": 50}, "name", DEMO_DATA_FACTORY_NAME, "造数池")
    coll = next((x for x in _rows(_need(c.get("/apitest/collections", headers=h,
                                              params={"projectId": pid}), "接口集合列表"))
                 if x.get("name") == DEMO_API_COLLECTION_NAME), None)
    if coll:
        drop_container(coll, "/apitest/requests", "接口请求",
                       {DEMO_API_REQUEST_NAME}, "接口请求", "接口集合")
    # Factory demo journey collection : delete each tp_demo_-prefixed
    # hop by name, then the container itself.
    flow_coll = next((x for x in _rows(_need(c.get("/apitest/collections", headers=h,
                                                    params={"projectId": pid}),
                                             "接口集合列表"))
                      if x.get("name") == DEMO_FLOW_COLLECTION_NAME), None)
    if flow_coll:
        drop_container(flow_coll, "/apitest/requests", "编排请求",
                       {p["name"] for p in flow_demo_payloads(flow_coll["id"])},
                       "编排请求", "编排集合")
    drop("/cases", {"projectId": pid, "keyword": DEMO_APP_CASE_TITLE, "size": 10},
         "title", DEMO_APP_CASE_TITLE, "APP 样例用例")
    drop("/cases", {"projectId": pid, "keyword": DEMO_REGRESSION_CASE_TITLE, "size": 10},
         "title", DEMO_REGRESSION_CASE_TITLE, "回归出厂用例")
    for p in _rows(_need(c.get("/plans", headers=h,
                              params={"projectId": pid, "size": 50}), "回归计划列表")):
        if p.get("name") == DEMO_PLAN_NAME:
            if p.get("status") in (2, 3):
                _need(c.post("/plans/%s/cancel" % p["id"], headers=h), "取消回归计划")
            _delete_item(c, h, "/plans/%s" % p["id"], "回归计划", p["id"],
                         removed, skipped, params={"force": "true"})
    drop("/projects/%s/envs" % pid, {}, "name", DEMO_ENV_NAME, "样例环境")
    return removed, skipped


def ensure_centers(base_url):
    pass
    c, h = _client(base_url)
    out = {"created": 0, "centers": {}}
    for spec in DEMO_CENTER_PROJECTS:
        pid, new_p = _find_or_restore_project(
            c, h, spec["code"], center_project_payload(spec), "中心项目")
        out["created"] += int(new_p)
        env_id, new_e = _find_or_create(
            c, h, "/projects/%s/envs" % pid, {}, "name", DEMO_ENV_NAME,
            "/projects/%s/envs" % pid, env_payload(), "中心环境")
        out["created"] += int(new_e)
        coll_id, new_coll = _find_or_create(
            c, h, "/apitest/collections", {"projectId": pid}, "name",
            DEMO_CENTER_COLLECTION_NAME, "/apitest/collections",
            center_collection_payload(pid), "中心集合")
        out["created"] += int(new_coll)
        req_id, new_req = _find_or_create(
            c, h, "/apitest/requests", {"collectionId": coll_id}, "name",
            center_request_plan(spec)["name"], "/apitest/requests",
            center_request_payload(coll_id, spec), "中心请求")
        out["created"] += int(new_req)
        out["centers"][spec["code"]] = {"project": pid, "env": env_id,
                                        "collection": coll_id, "request": req_id}
    prog_id, new_prog = _find_or_create(
        c, h, "/programs", {}, "name", DEMO_PROGRAM_NAME,
        "/programs", program_payload(), "项目集")
    out["program"] = prog_id
    out["created"] += int(new_prog)
    attached = set(_need(c.get("/programs/%s/projects" % prog_id, headers=h),
                         "项目集挂载") or [])
    out["attach"] = 0
    for ids in out["centers"].values():
        if ids["project"] in attached:
            continue
        _need(c.post("/programs/%s/projects/%s" % (prog_id, ids["project"]), headers=h),
              "项目集挂项目")
        out["created"] += 1
        out["attach"] += 1
    order = out["centers"].get(ORDER_CENTER_CODE)
    out["perf"] = None
    if order:
        perf_id, new_perf = _find_or_create(
            c, h, "/perf/scenarios", {"projectId": order["project"]}, "name",
            DEMO_PERF_SCENARIO_NAME, "/perf/scenarios",
            perf_scenario_payload(order["project"], order["env"]), "性能场景")
        out["perf"] = perf_id
        out["created"] += int(new_perf)
    return out


def purge_centers(base_url):
    pass
    c, h = _client(base_url)
    removed = []
    skipped = []
    demo_codes = ({s["code"] for s in DEMO_CENTER_PROJECTS}
                  | set(LEGACY_CENTER_CODES) | {DEMO_PROJECT_CODE})
    for p in list(_rows(_need(c.get("/projects", headers=h, params={"size": 100}),
                              "项目列表"))):
        if p.get("code") not in demo_codes:
            continue
        for s in list(_rows(_need(c.get("/perf/scenarios", headers=h,
                                        params={"projectId": p["id"]}), "性能场景列表"))):
            if str(s.get("name") or "").startswith(DEMO_CLEAN_PREFIX):
                _delete_item(c, h, "/perf/scenarios/%s" % s["id"], "性能场景",
                             s["id"], removed, skipped, params={"projectId": p["id"]})
    for prog in list(_rows(_need(c.get("/programs", headers=h), "项目集列表"))):
        if prog.get("name") != DEMO_PROGRAM_NAME:
            continue
        for pid in _need(c.get("/programs/%s/projects" % prog["id"], headers=h),
                         "项目集挂载") or []:
            _delete_item(c, h, "/programs/%s/projects/%s" % (prog["id"], pid),
                         "项目集摘项目", pid, removed, skipped)
        _delete_item(c, h, "/programs/%s" % prog["id"], "项目集",
                     prog["id"], removed, skipped)
    center_codes = {s["code"] for s in DEMO_CENTER_PROJECTS} | set(LEGACY_CENTER_CODES)
    for p in list(_rows(_need(c.get("/projects", headers=h, params={"size": 100}), "项目列表"))):
        if p.get("code") not in center_codes:
            continue
        for e in list(_rows(_need(c.get("/projects/%s/envs" % p["id"], headers=h), "环境列表"))):
            if e.get("name") == DEMO_ENV_NAME:
                _delete_item(c, h, "/projects/%s/envs/%s" % (p["id"], e["id"]),
                             "中心环境", e["id"], removed, skipped)
        for coll in list(_rows(_need(c.get("/apitest/collections", headers=h,
                                      params={"projectId": p["id"]}), "集合列表"))):
            if coll.get("name") != DEMO_CENTER_COLLECTION_NAME:
                continue
            kept = []
            for r in list(_rows(_need(c.get("/apitest/requests", headers=h,
                                       params={"collectionId": coll["id"]}), "请求列表"))):
                if str(r.get("name") or "").startswith(DEMO_CLEAN_PREFIX):
                    if not _delete_item(c, h, "/apitest/requests/%s" % r["id"],
                                         "中心请求", r["id"], removed, skipped):
                        kept.append(r["id"])
            if kept:
                skipped.append("中心集合#%s：保留——其内演示请求 #%s 的运行记录未清理，"
                               "先到「API 测试」页清理这些请求的运行记录，"
                               "再重跑一次清除即可"
                               % (coll["id"], "、#".join(str(x) for x in kept)))
            else:
                _delete_item(c, h, "/apitest/collections/%s" % coll["id"],
                             "中心集合", coll["id"], removed, skipped)
        _delete_item(c, h, "/projects/%s" % p["id"], "中心项目",
                     p["id"], removed, skipped)
    return removed, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(prog="demo_seed.py",
                                     description="S1 样例种子：真实 API 造样例项目+用例+五分钟旅程四件（幂等）")
    parser.add_argument("--base-url", default="http://localhost:8081",
                        help="admin 服务地址（对外用户接口只走 admin/api，禁直连 core 库）")
    parser.add_argument("--api-base", default="http://localhost:8082",
                        help="api 服务地址（徽章公开面 /open/badge 所在，仅用于提示文案）")
    parser.add_argument("--journey", action="store_true",
                        help="造满五分钟旅程四件：API 用例/APP 用例/造数池/回归计划（含发布+生成批次）")
    parser.add_argument("--centers", action="store_true",
                        help="造中台缩微剧本：订单/库存两中心项目+同项目集+每中心一条可跑 API 用例（幂等）")
    parser.add_argument("--purge", action="store_true",
                        help="一键清除 tp_demo_ 前缀旅程件与中台剧本面（受保护样例面零误伤）")
    args = parser.parse_args(argv)

    if args.purge:
        j_removed, j_skipped = purge_journey(args.base_url)
        c_removed, c_skipped = purge_centers(args.base_url)
        removed = j_removed + c_removed
        skipped = j_skipped + c_skipped
        print("Purge done: %d item(s) deleted, %d kept (matches only the %s "
              "name prefix and demo- code names)"
              % (len(removed), len(skipped), DEMO_CLEAN_PREFIX))
        for item in removed:
            print("  - %s" % item)
        if skipped:
            print("Skipped items (their run records and other trace data were "
                  "not cleaned; remove those first, then re-run this command "
                  "to finish the purge):")
            for item in skipped:
                print("  ! %s" % item)
        return 0

    if args.journey:
        info = ensure_journey(args.base_url)
        print("Five-minute journey ready: project=%s env=%s api collection=%s "
              "request=%s flow collection=%s (4 hops=%s) app case=%s "
              "data factory=%s plan=%s batch=%s (%d item(s) created)"
              % (info["project"], info["env"], info["collection"], info["request"],
                 info["flowCollection"], len(info["flowRequests"]),
                 info["appCase"], info["dataFactory"], info["plan"], info["batch"],
                 info["created"]))
        print("Guided tour: under project 「%s」 open 「接口测试」 and send one "
              "request to see the assertion -> open the 「流程」 tab of "
              "「接口测试」 to watch the 4-hop flow (login -> create order -> "
              "poll status -> empty page fetch: precondition + loop coverage) -> "
              "check the APP/mobile sample in 「用例库」 -> run the pool once in "
              "「造数池」 -> publish a batch of 「测试计划」 「%s」 and view the CI "
              "badge on the batch detail page (public link looks like "
              "%s/open/badge/<token>.svg)."
              % (DEMO_PROJECT_NAME, DEMO_PLAN_NAME, args.api_base.rstrip("/")))
        return 0

    if args.centers:
        info = ensure_centers(args.base_url)
        detail = ", ".join("%s=project %s/case %s"
                           % (s["name"], info["centers"][s["code"]]["project"],
                              info["centers"][s["code"]]["request"])
                           for s in DEMO_CENTER_PROJECTS)
        print("Mini scenario packs ready (%d item(s) created): %s; program = "
              "「%s」" % (info["created"], detail, DEMO_PROGRAM_NAME))
        print("Guided tour: from the project list open 「订单中心」 or "
              "「库存中心」 -> pick the center's runnable case in 「接口测试」 "
              "and use debug run. Debug run and load start are carried by the "
              "executor, which ships with the 「Full」 tier; the default "
              "tier can still submit and queue the dispatch. Then start load "
              "for 「%s」 in 「性能测试」 (5 RPS x 10s against the fixture-site "
              "login page; the seed stores it, load never starts on its own) "
              "and view how both centers share one program in 「项目集」 「%s」."
              % (DEMO_PERF_SCENARIO_NAME, DEMO_PROGRAM_NAME))
        return 0

    pid, cid = ensure_sample(args.base_url)
    print("Sample ready: project id=%s case id=%s" % (pid, cid))
    print("Next (frontend): open project 「%s」 from the project list -> select "
          "the sample case -> click 「AI 生成脚本」 -> dry-run it -> view the "
          "report" % DEMO_PROJECT_NAME)
    print("To see the full capability set at once: run again with --journey "
          "(API case / APP case / data factory / regression plan, idempotent)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
