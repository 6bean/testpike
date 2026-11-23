
import re

import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query


def _create_project(api_client, uniq):
    pass
    resp = api_client.post("/projects", {"name": f"技能项目{uniq}", "code": f"SKL{uniq.upper()}"})
    body = resp.json()
    assert body["code"] == "0", f"创建项目失败: {body}"
    return body["data"]


def _create_published_skill(api_client, name):
    pass
    r = api_client.post("/skills", {
        "projectId": None, "name": name, "type": 3,
        "body": "## 用途\nS4 绑定链造数正文\n", "frontmatterJson": None, "submitNow": True,
    }).json()
    assert r["code"] == "0", f"创建 SKILL 失败: {r}"
    sid, ver = r["data"]["id"], r["data"]["version"]
    ar = api_client.post(f"/skills/{sid}/approve?expectedVersion={ver}").json()
    assert ar["code"] == "0" and ar["data"]["status"] == 3, f"审批失败: {ar}"
    return sid


def _cleanup_skill(api_client, sid):
    pass
    cur = api_client.get(f"/skills/{sid}").json()["data"]
    rr = api_client.post(f"/skills/{sid}/retire?expectedVersion={cur['version']}").json()
    assert rr["code"] == "0", f"retire 失败: {rr}"
    dr = api_client.delete(f"/skills/{sid}?expectedVersion={rr['data']['version']}").json()
    assert dr["code"] == "0", f"delete 失败: {dr}"


def _open_skill_tab(page, pid, reload=False):
    pass
    if reload:
        page.reload(wait_until="domcontentloaded")
    else:
        page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    page.wait_for_selector('[data-test="env-table"]', timeout=10000)
    page.click('.el-tabs__item:has-text("SKILL 配置")')
    page.wait_for_selector('[data-test="skill-bind-table"]', timeout=8000)


@pytest.fixture
def project_and_skill(api_client, uniq):
    pid = _create_project(api_client, uniq)
    name = f"e2e-s4-skill-{uniq}"
    sid = _create_published_skill(api_client, name)
    yield pid, sid, name
    cur = api_client.get(f"/projects/{pid}/skills").json()
    if cur["code"] == "0" and cur["data"]["list"]:
        pr = api_client.put(f"/projects/{pid}/skills", json={"skills": [], "expectedVersion": None}).json()
        assert pr["code"] == "0", f"清理解绑失败: {pr}"
    _cleanup_skill(api_client, sid)
    api_client.delete(f"/projects/{pid}")



def test_prj_skill_tab_smoke(logged_in, collectors, project_and_skill):
    page = logged_in
    pid, _sid, _name = project_and_skill
    _open_skill_tab(page, pid)
    expect(page.locator('[data-test="skill-add-select"]')).to_be_visible(timeout=5000)
    expect(page.locator('[data-test="skill-bind-save"]')).to_be_visible()
    expect(page.locator('[data-test="skill-bind-save"]')).to_be_disabled()
    expect(page.locator('[data-test="skill-bind-table"] tbody tr')).to_have_count(0, timeout=5000)
    assert_no_errors(collectors, "T161 S4 页签冒烟")



def test_prj_skill_bind_save_reload_unbind(logged_in, collectors, project_and_skill):
    page = logged_in
    pid, sid, name = project_and_skill
    _open_skill_tab(page, pid)

    page.click('[data-test="skill-add-select"]')
    page.fill('[data-test="skill-add-select"] input', name)
    option = page.locator(".tp-skill-add-popper .el-select-dropdown__item:visible", has_text=name).first
    option.wait_for(timeout=8000)
    option.click()
    ring = page.evaluate("""(() => {
      const inp = document.querySelector('[data-test="skill-add-select"] .el-select__input');
      const wrap = document.querySelector('[data-test="skill-add-select"] .el-select__wrapper');
      inp.focus();
      return {
        innerOutline: getComputedStyle(inp).outlineStyle,
        outerRing: getComputedStyle(wrap).boxShadow !== 'none',
      }
    })()""")
    assert ring["innerOutline"] == "none", f"内层框中框复发（outline 应单源压制）: {ring}"
    assert ring["outerRing"], f"外壳焦点环丢失（WCAG 2.4.7 降级）: {ring}"
    row = page.locator('[data-test="skill-bind-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=5000)

    page.click(f'[data-test="skill-role-{sid}"]')
    role_opt = page.locator(f".tp-skill-role-popper-{sid} .el-select-dropdown__item:visible", has_text="脚本开发").first
    role_opt.wait_for(timeout=5000)
    role_opt.click()
    page.fill(f'[data-test="skill-priority-{sid}"] input', "5")
    page.click(f'[data-test="skill-enabled-{sid}"]')
    expect(page.locator(f'[data-test="skill-enabled-{sid}"]')).not_to_have_class(
        re.compile(r"is-checked"), timeout=5000)

    with page.expect_response(
        lambda r: r.request.method == "PUT" and f"/api/v1/projects/{pid}/skills" in r.url
    ):
        page.click('[data-test="skill-bind-save"]')
    toast = page.locator(".el-message--success").first
    expect(toast).to_be_visible(timeout=5000)
    assert "已保存" in toast.inner_text()
    expect(page.locator('[data-test="skill-bind-save"]')).to_be_disabled(timeout=5000)
    expect(page.locator(f'[data-test="skill-role-{sid}"]')).to_contain_text("脚本开发", timeout=5000)

    _open_skill_tab(page, pid, reload=True)
    row = page.locator('[data-test="skill-bind-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    expect(page.locator(f'[data-test="skill-role-{sid}"]')).to_contain_text("脚本开发")
    assert page.locator(f'[data-test="skill-priority-{sid}"] input').input_value() == "5"
    expect(page.locator(f'[data-test="skill-enabled-{sid}"]')).not_to_have_class(
        re.compile(r"is-checked"), timeout=5000)
    assert "未保存" not in row.inner_text(), f"绑定时间未回显: {row.inner_text()}"

    page.click(f'[data-test="skill-view-{sid}"]')
    drawer = page.locator('[data-test="skill-detail-drawer"]')
    expect(drawer).to_be_visible(timeout=5000)
    body_cell = page.locator('[data-test="skill-detail-body"]').first
    expect(body_cell).to_be_visible(timeout=8000)
    assert "S4 绑定链造数正文" in body_cell.inner_text(), \
        f"详情正文非生效版本快照全量: {body_cell.inner_text()[:120]}"
    binding = page.locator('[data-test="skill-detail-binding"]').first
    assert "脚本开发" in binding.inner_text(), f"绑定段未回显注入角色: {binding.inner_text()}"
    assert "停用" in binding.inner_text() or "已停用" in binding.inner_text(), \
        f"绑定段未回显启停态: {binding.inner_text()}"
    page.keyboard.press("Escape")
    expect(drawer).to_be_hidden(timeout=5000)

    db = sql_query(f"SELECT role, priority, enabled FROM tp_project_skill WHERE project_id={pid} AND skill_id={sid}")
    assert db == "1|5|0", f"DB 落库值与页面编辑不一致: {db}"

    page.click(f'[data-test="skill-unbind-{sid}"]')
    expect(page.locator('[data-test="skill-bind-table"] tbody tr')).to_have_count(0, timeout=5000)
    with page.expect_response(
        lambda r: r.request.method == "PUT" and f"/api/v1/projects/{pid}/skills" in r.url
    ):
        page.click('[data-test="skill-bind-save"]')
    expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
    assert sql_query(
        f"SELECT count(*) FROM tp_project_skill WHERE project_id={pid}") == "0", "解绑保存后 DB 应零行"

    assert_no_errors(collectors, "T161 S4 绑定保存解绑全链")
