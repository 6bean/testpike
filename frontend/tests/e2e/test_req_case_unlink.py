
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, nav_to, assert_no_errors


def _mk_project(api, uniq, tag="A"):
    r = api.post("/projects", json={"name": f"T126项目{tag}_{uniq}", "code": f"T126{uniq[:4].upper()}{tag}"})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "priority": 1,
        "confidence": 2,
        "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={
        "title": title, "source": 3, "chapter": "3.1",
    })
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _link(api, req_id, case_id):
    r = api.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _safe(api, method, url):
    pass
    try:
        getattr(api, method)(url)
    except Exception:
        pass


def _open_case_detail(page, case_id):
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="case-req-block"]')).to_be_visible(timeout=15000)


def test_t126_case_detail_offline_tag_and_unlink(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, uniq, "闭环用例")
    req_active = _mk_requirement(api_client, pid, f"活跃需求_{uniq}")
    req_offline = _mk_requirement(api_client, pid, f"下线需求_{uniq}")
    _link(api_client, req_active["id"], case["id"])
    _link(api_client, req_offline["id"], case["id"])
    r = api_client.put(f"/projects/{pid}/requirements/{req_offline['id']}", json={"status": 3})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]

    try:
        _open_case_detail(page, case["id"])
        table = page.locator('[data-test="case-req-table"]')
        expect(table).to_be_visible()
        expect(page.locator(f'[data-test="req-status-{req_offline["id"]}"]')).to_contain_text("已下线")
        expect(page.locator(f'[data-test="req-status-{req_active["id"]}"]')).to_contain_text("活跃")

        page.locator(f'[data-test="req-unlink-{req_offline["id"]}"]').click()
        dialog = page.locator(".el-message-box:visible")
        expect(dialog).to_be_visible()
        expect(dialog).to_contain_text("取消")
        dialog.locator('button:has-text("确定取消")').click()
        expect(page.locator(".el-message--success:visible").last).to_contain_text("已取消关联")

        expect(page.locator(f'[data-test="req-unlink-{req_offline["id"]}"]')).to_have_count(0)
        expect(page.locator(f'[data-test="req-status-{req_active["id"]}"]')).to_be_visible()
        page.goto(f"{BASE_URL}/#/cases/{case['id']}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="req-status-{req_offline["id"]}"]')).to_have_count(0)
        expect(page.locator(f'[data-test="req-status-{req_active["id"]}"]')).to_be_visible()

        r = api_client.get(f"/requirements/{req_offline['id']}/cases")
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        assert [c["id"] for c in r.json()["data"]] == [], "需求点侧应同步不可见（同源）"

        assert_no_errors(collectors, "T126 用例侧取消关联闭环")
    finally:
        _safe(api_client, "delete", f"/requirements/{req_active['id']}/cases/{case['id']}")
        _safe(api_client, "delete", f"/requirements/{req_offline['id']}")
        _safe(api_client, "delete", f"/requirements/{req_active['id']}")
        _safe(api_client, "delete", f"/cases/{case['id']}")
        _safe(api_client, "delete", f"/cases/{case['id']}/purge")
        _safe(api_client, "delete", f"/projects/{pid}/purge?force=true")


def test_t126_unlink_endpoint_repeat_404(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq, "A")
    pid2 = _mk_project(api_client, uniq, "B")
    case = _mk_case(api_client, pid, uniq, "端点用例")
    req = _mk_requirement(api_client, pid, f"端点需求_{uniq}")
    req2 = _mk_requirement(api_client, pid2, f"外项目需求_{uniq}")
    _link(api_client, req["id"], case["id"])

    try:
        r = api_client.delete(f"/cases/{case['id']}/requirements/{req2['id']}")
        assert r.status_code == 404, f"未关联应 404: {r.status_code} {r.text[:200]}"
        assert "关联" in (r.json().get("msg") or ""), f"人话提示: {r.text[:200]}"

        r = api_client.delete(f"/cases/{case['id']}/requirements/{req['id']}")
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]

        r = api_client.delete(f"/cases/{case['id']}/requirements/{req['id']}")
        assert r.status_code == 404 and "关联" in (r.json().get("msg") or ""), \
            f"重复取消应 404 人话: {r.text[:200]}"

        assert_no_errors(collectors, "T126 取消关联端点重复 404")
    finally:
        api_client.delete(f"/requirements/{req['id']}")
        api_client.delete(f"/requirements/{req2['id']}")
        r = api_client.delete(f"/cases/{case['id']}")
        if r.status_code == 200 and r.json().get("code") == "0":
            entry = api_client.get(f"/cases/recycle-bin?projectId={pid}&page=1&size=10")
            if entry.status_code == 200 and entry.json().get("code") == "0":
                row = next((x for x in entry.json()["data"]["list"]
                            if x["id"] == case["id"]), None)
                if row is not None:
                    api_client.delete(f"/cases/{case['id']}/purge")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        api_client.delete(f"/projects/{pid2}/purge?force=true")
