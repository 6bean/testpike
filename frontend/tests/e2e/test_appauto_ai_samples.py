
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _pick_project(page, pid):
    """: page-level project selector removed — the ?projectId deep link lands
 the global header context. Dashboard bounce is required because vue-router
 reuses the mounted component across query-only navigation, so a bare second
 goto would never re-run the deep-link setup."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/samples?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="ai-sample-board-page"]')).to_be_visible(timeout=15000)


def _pick_combo(page, test_id, text):
    sel = page.locator(f'[data-test="{test_id}"] input').first
    sel.click()
    sel.fill(text)
    opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible', has_text=text)
    expect(opt.first).to_be_visible(timeout=8000)
    opt.first.click()


def _mk_project_case(api, uniq):
    pid = api.post("/api/v1/projects",
                   json={"name": f"样本看板E2E项目{uniq}", "code": f"AS{uniq[:6].upper()}"}).json()["data"]
    cid = api.post("/api/v1/cases", json={
        "projectId": pid,
        "title": f"看板目标用例_{uniq}",
        "precondition": f"前置：设备已连接（{uniq}）",
        "expectedResult": f"预期：App 启动成功（{uniq}）",
        "priority": 0, "confidence": 1, "endType": "mobile", "status": 1,
        "steps": [{"stepNo": 1, "description": f"启动应用（{uniq}）", "isAssert": 0},
                  {"stepNo": 2, "description": f"登录入口可见（{uniq}）", "isAssert": 1}],
    }).json()["data"]["id"]
    return pid, cid


def _mk_sample(pid, cid, step_no, anchor, hit_source, review_result, x=None, y=None):
    pass
    coord = f"{x}, {y}" if x is not None else "NULL, NULL"
    srow = sql("INSERT INTO tp_appauto_ai_sample (project_id, case_id, device_id, step_no, anchor,"
               " hit_source, review_result, x, y)"
               f" VALUES ({pid}, {cid}, NULL, {step_no}, '{anchor}', '{hit_source}',"
               f" '{review_result}', {coord}) RETURNING id")
    return int(srow.splitlines()[0])


def test_appauto_ai_samples_board(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    pid = None
    try:
        pid, cid = _mk_project_case(api, uniq := uuid.uuid4().hex[:6])
        _mk_sample(pid, cid, 1, f"旧锚点A{uniq}", "vision", "confirmed", 540, 1200)
        _mk_sample(pid, cid, 2, f"锚点B{uniq}", "a11y", "confirmed")
        _mk_sample(pid, cid, 3, f"锚点C{uniq}", "cache", "rejected")
        s4 = _mk_sample(pid, cid, 4, f"锚点D{uniq}", "manual", "rejected")
        _mk_sample(pid, cid, 5, f"锚点E{uniq}", "vision", "confirmed", 80, 60)

        open_journey_leaf(page, "menu-ai-samples", "appauto")
        expect(page.locator('[data-test="ai-sample-board-page"]')).to_be_visible(timeout=10000)

        # ── 2. deep-link the project + pick the case → five rows with plain-language
        # source/review labels and the coordinate column ──
        # combo filter text must be the full name: a bare uniq suffix also matches the
        # case popper that is mid-collapse (still in the DOM/animation)
        _pick_project(page, pid)
        _pick_combo(page, "ai-samples-case-select", f"看板目标用例_{uniq}")
        table = page.locator('[data-test="ai-samples-table"]')
        expect(table.locator("tbody tr")).to_have_count(5, timeout=10000)
        sources = table.locator('[data-test="ai-samples-hit-source"]')
        got = {sources.nth(i).inner_text() for i in range(5)}
        assert got == {"视觉定位", "无障碍树", "定位缓存", "人工标注"}, f"出处人话集合不符: {got}"
        tags = table.locator('[data-test="ai-samples-review-tag"]')
        tagtxt = [tags.nth(i).inner_text() for i in range(5)]
        assert tagtxt.count("已确认") == 3 and tagtxt.count("已驳回") == 2
        coords = table.locator('[data-test="ai-samples-coord"]')
        assert coords.count() == 2, "仅带坐标样本呈现坐标列"
        expect(coords.first).to_contain_text("80")

        stats = page.locator('[data-test="ai-samples-stats"]')
        expect(stats.locator('[data-test="ai-samples-stat-total"]')).to_contain_text("5")
        expect(stats.locator('[data-test="ai-samples-stat-confirmed"]')).to_contain_text("3")
        expect(stats.locator('[data-test="ai-samples-stat-rejected"]')).to_contain_text("2")
        expect(stats.locator('[data-test="ai-samples-stat-rate"]')).to_contain_text("60%")
        r = api.get("/api/v1/appauto/ai-samples/hit-rate", params={"projectId": pid, "caseId": cid})
        assert r.status_code == 200
        body = r.json()["data"]
        assert body["total"] == 5 and body["confirmed"] == 3 and body["rejected"] == 2
        assert abs(body["confirmRate"] - 0.6) < 1e-9, f"core 实算率异常: {body}"

        cid2 = api.post("/api/v1/cases", json={
            "projectId": pid, "title": f"零样本用例_{uniq}",
            "precondition": "p", "expectedResult": "e",
            "priority": 0, "confidence": 1, "endType": "mobile", "status": 1,
            "steps": [{"stepNo": 1, "description": "s", "isAssert": 0}],
        }).json()["data"]["id"]
        assert cid2
        # true reload (page.goto on the same hash URL does not remount the SPA = old
        # pitfall): the bounce deep link remounts and re-fetches options including cid2
        _pick_project(page, pid)
        # readiness wait: once the segment lands, listCases loads async — filling too
        # early matches "no data" against an empty list and reads as a false red
        caseSel = page.locator('[data-test="ai-samples-case-select"] input').first
        caseSel.click()
        expect(page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible').first).to_be_visible(timeout=15000)
        page.keyboard.press("Escape")
        _pick_combo(page, "ai-samples-case-select", f"零样本用例_{uniq}")
        empty = page.locator('[data-test="ai-samples-table"]')
        expect(empty.locator("tbody tr")).to_have_count(0, timeout=10000)
        expect(empty.locator('.el-table__empty-block')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="ai-samples-stat-total"]')).to_contain_text("0")
        expect(page.locator('[data-test="ai-samples-stat-rate"]')).to_contain_text("0%")

        _pick_project(page, pid)
        _pick_combo(page, "ai-samples-case-select", f"看板目标用例_{uniq}")
        expect(page.locator('[data-test="ai-samples-table"] tbody tr')).to_have_count(5, timeout=10000)
        expect(page.locator('[data-test="ai-samples-stat-rate"]')).to_contain_text("60%")
        assert sql(f"SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id={pid}") == "5"

        assert_no_errors(collectors, "appauto ai samples board")
    finally:
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id={pid}") == "0"
