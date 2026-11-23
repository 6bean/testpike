
import pytest
from conftest import BASE_URL, assert_no_errors, inject_token, nav_to
from playwright.sync_api import expect

pytestmark = pytest.mark.xdist_group("h7_template_wizard")

TAG_NAMES = ("冒烟", "回归")
ENV_NAME = "示例测试环境"
ROLE_HINT = "建议配置：测试负责人 1 名 + 测试工程师 2 名"


def _mk_template(api, uniq):
    pass
    payload = {
        "name": f"H7零售模板_{uniq}",
        "industry": "零售",
        "content": {
            "skillIds": [],
            "tags": [{"name": n, "type": 1} for n in TAG_NAMES],
            "envs": [{"name": ENV_NAME, "envType": 2, "baseUrl": "http://127.0.0.1:8899/login"}],
            "roleHints": [ROLE_HINT],
        },
    }
    r = api.post("/project-templates", json=payload)
    assert r.status_code == 200 and r.json().get("code") == "0", f"造模板失败: {r.text[:300]}"
    data = r.json()["data"]
    tid = data if isinstance(data, int) else data["id"]
    return tid, payload["name"]


def _echo_project_id(api, name):
    r = api.get(f"/projects?page=1&size=20&keyword={name}")
    rows = [p for p in (r.json()["data"]["list"] if r.json().get("code") == "0" else [])
            if p["name"] == name]
    return rows[0]["id"] if rows else None


def _open_create_dialog(page):
    nav_to(page, "项目管理", "/projects", '[data-test="project-table"]', submenu="系统管理")
    page.click('button[data-test="project-create"]')
    expect(page.locator('[data-test="project-dialog"]')).to_be_visible(timeout=5000)


def _dialog_select_template(page, tpl_name):
    page.click('[data-test="project-template-select"]')
    popper = page.locator(".tp-template-popper")
    opt = popper.locator(f".el-select-dropdown__item:has-text('{tpl_name}')").first
    expect(opt).to_be_visible(timeout=5000)
    opt.click()


def _submit_create(page, name, code):
    page.fill('[data-test="project-name-input"]', name)
    page.fill('[data-test="project-code-input"]', code)
    page.click('button[data-test="project-submit"]')
    expect(page.locator(".el-message--success").first).to_be_visible(timeout=8000)


def test_h7_wizard_apply_template_prelude(page, collectors, api_client, uniq):
    pass
    tpl_id, tpl_name = _mk_template(api_client, uniq)
    pid = None
    try:
        name = f"H7套模板_{uniq}"
        code = f"H7T{uniq[:6].upper()}"
        inject_token(page)
        _open_create_dialog(page)
        _dialog_select_template(page, tpl_name)
        preview = page.locator('[data-test="template-preview"]')
        expect(preview).to_be_visible(timeout=5000)
        expect(preview.locator('[data-test="template-preview-tags"]')).to_contain_text(TAG_NAMES[0])
        expect(preview.locator('[data-test="template-preview-envs"]')).to_contain_text(ENV_NAME)
        expect(preview.locator('[data-test="template-preview-roles"]')).to_contain_text("测试负责人")
        _submit_create(page, name, code)
        pid = _echo_project_id(api_client, name)
        assert pid is not None, f"项目 {name} 未出现在列表（创建链断）"
        tags = api_client.get(f"/tags?projectId={pid}").json().get("data") or []
        tag_names = {t["name"] for t in tags}
        assert set(TAG_NAMES) <= tag_names, f"模板标签未预置: {tag_names}"
        envs = api_client.get(f"/projects/{pid}/envs").json().get("data") or []
        hit = [e for e in envs if e["name"] == ENV_NAME]
        assert hit, f"模板示例环境未预置: {[e['name'] for e in envs]}"
        assert hit[0]["envType"] == 2, f"环境类型应为测试(2): {hit[0]}"
        assert_no_errors(collectors, "H7 向导选模板创建四件套预置")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        api_client.delete(f"/project-templates/{tpl_id}")


def test_h7_wizard_no_template_zero_drift(page, collectors, api_client, uniq):
    pass
    tpl_id, _ = _mk_template(api_client, uniq)
    pid = None
    try:
        name = f"H7空白_{uniq}"
        code = f"H7B{uniq[:6].upper()}"
        inject_token(page)
        _open_create_dialog(page)
        expect(page.locator('[data-test="project-template-select"]')).to_be_visible(timeout=5000)
        assert page.locator('[data-test="template-preview"]').count() == 0, "未选模板不应出现预览卡"
        _submit_create(page, name, code)
        pid = _echo_project_id(api_client, name)
        assert pid is not None, f"项目 {name} 未出现在列表"
        tags = api_client.get(f"/tags?projectId={pid}").json().get("data") or []
        envs = api_client.get(f"/projects/{pid}/envs").json().get("data") or []
        assert not tags, f"不选模板不应写入标签: {tags}"
        assert not envs, f"不选模板不应写入环境: {envs}"
        assert_no_errors(collectors, "H7 不选模板零漂移")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        api_client.delete(f"/project-templates/{tpl_id}")
