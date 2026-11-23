
from playwright.sync_api import expect

from conftest import pick_project_in_header, BASE_URL, assert_no_errors


def _mk_project(api_client, uniq, tag):
    r = api_client.post("/projects", json={"name": f"T127{tag}项目_{uniq}", "code": f"T127{uniq[:4].upper()}{tag}"})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _mk_case(api_client, pid, uniq, title):
    r = api_client.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "priority": 1,
        "confidence": 2,
        "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _mk_requirement(api_client, pid, title):
    r = api_client.post(f"/projects/{pid}/requirements", json={
        "title": title, "source": 3, "chapter": "7.1",
    })
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _link(api_client, req_id, case_id):
    r = api_client.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _set_status(api_client, pid, req_id, status):
    r = api_client.put(f"/projects/{pid}/requirements/{req_id}", json={"status": status})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]


def _safe(api_client, url):
    pass
    try:
        api_client.delete(url)
    except Exception:
        pass


def _pick_project(page, name):
    pass
    pick_project_in_header(page, name)


def test_t127_case_list_and_detail_offline_hint(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq, "H")
    case_id = _mk_case(api_client, pid, uniq, "下线提示用例")
    req_id = _mk_requirement(api_client, pid, f"下线目标_{uniq}")
    _link(api_client, req_id, case_id)

    try:
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(f'[data-test="case-no-{case_id}"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="case-offline-hint"]')).to_have_count(0)

        _set_status(api_client, pid, req_id, 3)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator(f'[data-test="case-no-{case_id}"]')).to_be_visible(timeout=15000)
        hint = page.locator('[data-test="case-offline-hint"]').first
        expect(hint).to_be_visible()
        expect(hint).to_contain_text("下线关联")

        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        alert = page.locator('[data-test="case-offline-hint"]')
        expect(alert).to_be_visible()
        expect(alert).to_contain_text("所属需求已下线（不计覆盖）")

        _set_status(api_client, pid, req_id, 1)
        page.goto(f"{BASE_URL}/#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator(f'[data-test="case-no-{case_id}"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="case-offline-hint"]')).to_have_count(0)

        assert_no_errors(collectors, "TC-REQ-20 offline hint")
    finally:
        _safe(api_client, f"/projects/{pid}/purge?force=true")


def test_t127_matrix_offline_denominator_recompute(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq, "M")
    name = f"T127M项目_{uniq}"
    req_a = _mk_requirement(api_client, pid, f"矩阵下线A_{uniq}")
    req_b = _mk_requirement(api_client, pid, f"矩阵对照B_{uniq}")
    _link(api_client, req_a, _mk_case(api_client, pid, uniq, "覆盖A用例"))
    _link(api_client, req_b, _mk_case(api_client, pid, uniq, "覆盖B用例"))

    try:
        page.goto(f"{BASE_URL}/#/coverage", wait_until="domcontentloaded")
        expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, name)
        expect(page.locator('[data-test="matrix-table"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="matrix-total"]')).to_have_text("2", timeout=15000)
        expect(page.locator('[data-test="matrix-covered"]')).to_have_text("2")
        expect(page.locator('[data-test="matrix-req-link"]')).to_have_count(2)

        _set_status(api_client, pid, req_a, 3)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, name)
        expect(page.locator('[data-test="matrix-total"]')).to_have_text("1", timeout=15000)
        expect(page.locator('[data-test="matrix-covered"]')).to_have_text("1")
        expect(page.locator('[data-test="matrix-req-link"]')).to_have_count(1)
        expect(page.locator('[data-test="matrix-req-link"]').first).to_contain_text("矩阵对照B")

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, name)
        expect(page.locator('[data-test="matrix-total"]')).to_have_text("1", timeout=15000)

        _set_status(api_client, pid, req_a, 1)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="coverage-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, name)
        expect(page.locator('[data-test="matrix-total"]')).to_have_text("2", timeout=15000)
        expect(page.locator('[data-test="matrix-covered"]')).to_have_text("2")
        expect(page.locator('[data-test="matrix-req-link"]')).to_have_count(2)
        expect(page.locator('[data-test="matrix-cell-check"]').first).to_be_visible()

        assert_no_errors(collectors, "TC-REQ-19 matrix recompute")
    finally:
        _safe(api_client, f"/projects/{pid}/purge?force=true")
