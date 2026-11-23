
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    BASE_URL,
    TESTER_PASSWORD,
    assert_no_errors,
    inject_user_token,
)
from playwright.sync_api import expect

TAGS_URL = f"{BASE_URL}/#/tags"


def _open_tags_as(page, username, password):
    pass
    inject_user_token(page, username, password)
    page.goto(TAGS_URL, wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="tag-list-page"]')).to_be_visible(timeout=15000)


def _pick_project(page, pid):
    """Land the tags page on a project segment via the global header context.

 Assertion re-anchor : the in-page project
 selector was retired by the IA ruling — the page is a pure consumer
 of the header context, and a deep link ?projectId=P lands it globally.
 """
    page.goto(f"{TAGS_URL}?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")


def _mk_user(api, username, role):
    r = api.post("/users", json={
        "username": username, "password": TESTER_PASSWORD,
        "displayName": f"标签门闸用户{username[-6:]}",
        "email": f"{username}@test.local", "phone": "", "role": role,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用户失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_project(api, name, uniq, prefix):
    r = api.post("/projects", json={"name": name, "code": f"T388{prefix}{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]



def test_t388_admin_visible_gate_and_tag_journey(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    proj_name = f"T388可见域项目{uniq}"
    tag_name = f"T388标签{uniq[:6]}"
    pid = None
    try:
        pid = _mk_project(api_client, proj_name, uniq, "a")
        r = api_client.post("/tags", json={"projectId": pid, "name": tag_name, "type": 4})
        assert r.status_code == 200 and r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
        tag_id = r.json()["data"]["id"]

        _open_tags_as(page, ADMIN_USERNAME, ADMIN_PASSWORD)

        expect(page.locator('[data-test="tag-empty-no-project"]')).to_have_count(0, timeout=10000)
        expect(page.locator('[data-test="tag-empty-select-project"]')).to_be_visible(timeout=10000)

        _pick_project(page, pid)
        expect(page.locator('[data-test="tag-tabs"]')).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="tag-name-{tag_id}"]')).to_be_visible(timeout=10000)

        ui_tag_name = f"T388新建{uniq[:6]}"
        page.locator('[data-test="tag-create-btn"]').click()
        expect(page.locator('[data-test="tag-create-name"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="tag-create-name"]').fill(ui_tag_name)
        page.locator('[data-test="tag-create-submit"]').click()
        expect(page.locator('[data-test="tag-table"]').get_by_text(ui_tag_name)).to_be_visible(timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="tag-table"]').get_by_text(ui_tag_name)).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="tag-table"]').get_by_text(tag_name)).to_be_visible(timeout=10000)

        row = page.locator('[data-test="tag-table"] tbody tr', has_text=ui_tag_name)
        row.locator('[data-test="tag-delete-btn"]').click()
        expect(page.locator('[data-test="tag-table"]').get_by_text(ui_tag_name)).to_have_count(0, timeout=10000)
        r = api_client.delete(f"/tags/{tag_id}")
        assert r.json().get("code") == "0", f"删标签失败: {r.text[:200]}"

        assert_no_errors(collectors, "T388 admin 可见域门闸 + 标签旅程")
    finally:
        if pid is not None:
            api_client.delete(f"/projects/{pid}/purge")



def test_t388_empty_visible_domain_honest_state(page, collectors, api_client, uniq):
    pass
    username = f"t388lonely{uniq}"
    uid = None
    try:
        uid = _mk_user(api_client, username, 1)
        _open_tags_as(page, username, TESTER_PASSWORD)

        empty = page.locator('[data-test="tag-empty-no-project"]')
        expect(empty).to_be_visible(timeout=10000)
        expect(empty).to_contain_text("暂无项目")
        expect(empty).to_contain_text("去项目页")

        assert_no_errors(collectors, "T388 空可见域诚实空态")
    finally:
        if uid is not None:
            api_client.delete(f"/users/{uid}")



def test_t388_member_sees_own_project_tags(page, collectors, api_client, uniq):
    pass
    username = f"t388mem{uniq}"
    proj_name = f"T388成员项目{uniq}"
    pid = None
    uid = None
    try:
        uid = _mk_user(api_client, username, 1)
        pid = _mk_project(api_client, proj_name, uniq, "m")
        r = api_client.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
        assert r.json().get("code") == "0", f"加成员失败: {r.text[:200]}"
        r = api_client.post("/tags", json={"projectId": pid, "name": f"T388成员标签{uniq[:6]}", "type": 4})
        assert r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
        tag_id = r.json()["data"]["id"]

        _open_tags_as(page, username, TESTER_PASSWORD)

        expect(page.locator('[data-test="tag-empty-no-project"]')).to_have_count(0, timeout=10000)
        expect(page.locator('[data-test="tag-empty-select-project"]')).to_be_visible(timeout=10000)

        _pick_project(page, pid)
        expect(page.locator(f'[data-test="tag-name-{tag_id}"]')).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "T388 成员项目标签可见")
    finally:
        if pid is not None:
            api_client.delete(f"/projects/{pid}/purge")
        if uid is not None:
            api_client.delete(f"/users/{uid}")
