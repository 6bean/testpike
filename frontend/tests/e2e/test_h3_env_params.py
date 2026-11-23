
import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, row_cmd, sql_query


MASK = "******"
PWD_V1 = "Sec@pwd-v1"
PWD_V2 = "Sec@pwd-v2"
USER_V1 = "login-user-a"


@pytest.fixture
def h3_env(api_client, uniq):
    pass
    name = f"H3参数{uniq}"
    code = f"H3E{uniq.upper()}"
    resp = api_client.post("/projects", json={"name": name, "code": code})
    assert resp.status_code == 200 and resp.json().get("code") == "0", f"建项目失败: {resp.text[:300]}"
    pid = resp.json()["data"]
    env_name = f"test-h3-{uniq}"
    r = api_client.post(f"/projects/{pid}/envs", json={
        "name": env_name, "envType": 2,
        "baseUrl": f"http://h3-{uniq}.example.com",
        "configJson": "{}", "endTypes": "web", "browsers": "chromium",
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    eid = r.json()["data"]
    yield pid, eid, env_name
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _params_db(eid: int, key: str) -> str:
    return sql_query(
        f"SELECT config_json->'params'->>'{key}' FROM tp_project_env WHERE id={eid}")


def _open_envs_tab(page, pid):
    page.goto(f"{BASE_URL}/#/projects/{pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=10000)


def _env_row(page, env_name):
    return page.locator('[data-test="env-table"] tbody tr').filter(has_text=env_name)


def _open_params_dialog(page, env_name):
    row = _env_row(page, env_name)
    expect(row).to_be_visible(timeout=8000)
    row_cmd(page, row, "params")
    dialog = page.locator('[data-test="env-params-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    return dialog


def _close_params_dialog(page):
    page.keyboard.press("Escape")
    expect(page.locator('[data-test="env-params-dialog"]')).to_be_hidden(timeout=5000)


def _save_params(page, pid, eid):
    pass
    with page.expect_response(
        lambda r: r.request.method == "PUT" and f"/projects/{pid}/envs/{eid}/params" in r.url
    ) as info:
        page.click('[data-test="env-params-dialog"] [data-test="env-params-save"]')
    return info.value


def _add_param_row(page, key, value):
    page.click('[data-test="env-params-dialog"] [data-test="env-params-add"]')
    rows = page.locator('[data-test="env-params-table"] tbody tr')
    last = rows.last
    last.locator('input[data-test="env-params-key"]').fill(key)
    last.locator('input[data-test="env-params-value"]').fill(value)


def _param_row_by_key(page, key):
    pass
    rows = page.locator('[data-test="env-params-table"] tbody tr')
    for i in range(rows.count()):
        cand = rows.nth(i)
        try:
            if cand.locator('input[data-test="env-params-key"]').input_value() == key:
                return cand
        except Exception:
            continue
    raise AssertionError(f"参数行「{key}」未找到（共 {rows.count()} 行）")


def test_h3_params_create_mask_overwrite_keep(logged_in, collectors, api_client, h3_env):
    pass
    page = logged_in
    pid, eid, env_name = h3_env

    _open_envs_tab(page, pid)
    dialog = _open_params_dialog(page, env_name)
    expect(dialog.locator('[data-test="env-params-empty"]')).to_be_visible(timeout=5000)
    expect(dialog.locator('[data-test="env-params-hint"]')).to_contain_text("{{ENV:")

    _add_param_row(page, "LOGIN_USER", USER_V1)
    _add_param_row(page, "LOGIN_PWD", PWD_V1)
    resp = _save_params(page, pid, eid)
    assert resp.status == 200, f"PUT params 应 200: status={resp.status} body={resp.text()[:300]}"
    toast1 = page.locator(".el-message--success").filter(has_text="环境参数已保存")
    expect(toast1).to_be_visible(timeout=8000)
    expect(toast1).to_be_hidden(timeout=8000)
    rows = dialog.locator('[data-test="env-params-table"] tbody tr')
    expect(rows).to_have_count(2)
    expect(rows.nth(0).locator('[data-test="env-params-masked"]')).to_have_text(MASK)
    expect(rows.nth(1).locator('[data-test="env-params-masked"]')).to_have_text(MASK)
    expect(dialog.locator('[data-test="env-params-encrypted-tag"]')).to_have_count(2)

    c1_user = _params_db(eid, "LOGIN_USER")
    c1_pwd = _params_db(eid, "LOGIN_PWD")
    assert c1_user.startswith("ENC:") and c1_pwd.startswith("ENC:"), \
        f"DB 参数值应 ENC: 前缀: user={c1_user[:20]} pwd={c1_pwd[:20]}"
    cfg = sql_query(f"SELECT config_json::text FROM tp_project_env WHERE id={eid}")
    assert PWD_V1 not in cfg and USER_V1 not in cfg, "DB config_json 不得含明文参数值"

    _close_params_dialog(page)
    _open_envs_tab(page, pid)
    dialog = _open_params_dialog(page, env_name)
    expect(dialog.locator('[data-test="env-params-masked"]')).to_have_count(2, timeout=5000)

    pwd_row = _param_row_by_key(page, "LOGIN_PWD")
    pwd_row.locator('[data-test="env-params-reveal-edit"]').click()
    pwd_input = pwd_row.locator('input[data-test="env-params-value"]')
    expect(pwd_input).to_be_visible(timeout=5000)
    pwd_input.fill(PWD_V2)
    resp2 = _save_params(page, pid, eid)
    assert resp2.status == 200, f"覆盖保存 PUT 应 200: {resp2.status} {resp2.text()[:300]}"
    expect(page.locator(".el-message--success").filter(has_text="环境参数已保存")).to_be_visible(timeout=8000)
    c2_pwd = _params_db(eid, "LOGIN_PWD")
    assert c2_pwd.startswith("ENC:") and c2_pwd != c1_pwd, \
        f"覆盖后密文应更新: old={c1_pwd[:16]} new={c2_pwd[:16]}"
    assert _params_db(eid, "LOGIN_USER") == c1_user, "未覆盖行密文应逐字不变"
    assert PWD_V2 not in sql_query(f"SELECT config_json::text FROM tp_project_env WHERE id={eid}"), \
        "新明文不得落 DB"

    _close_params_dialog(page)
    _open_envs_tab(page, pid)
    dialog = _open_params_dialog(page, env_name)
    resp3 = _save_params(page, pid, eid)
    assert resp3.status == 200, f"纯打码回传 PUT 应 200（无需加密放行边界）: {resp3.status}"
    assert _params_db(eid, "LOGIN_USER") == c1_user, "打码回传后 LOGIN_USER 密文应不变"
    assert _params_db(eid, "LOGIN_PWD") == c2_pwd, "打码回传后 LOGIN_PWD 密文应不变"

    assert_no_errors(collectors, "H3 环境参数 新增/打码/覆盖/保留")


def test_h3_env_form_edit_after_params_guard(logged_in, collectors, api_client, h3_env):
    pass
    page = logged_in
    pid, eid, env_name = h3_env

    r = api_client.put(f"/projects/{pid}/envs/{eid}/params",
                       json={"params": {"GUARD_KEY": "guard-v1"}})
    assert r.status_code == 200 and r.json().get("code") == "0", f"预置参数失败: {r.text[:300]}"

    _open_envs_tab(page, pid)
    row = _env_row(page, env_name)
    expect(row).to_be_visible(timeout=8000)
    row.get_by_role("button", name="编辑").click()
    dialog = page.locator('[data-test="env-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    new_name = f"{env_name}-renamed"
    page.fill('input[data-test="env-name-input"]', new_name)
    with page.expect_response(
        lambda r: r.request.method == "PUT" and r.url.split("?")[0].endswith(f"/envs/{eid}")
    ) as info:
        page.click('[data-test="env-dialog"] [data-test="env-submit"]')
    resp = info.value
    assert resp.status == 200, \
        f"参数变更后裸编辑必 200（守卫等值放行），实为 {resp.status}: {resp.text()[:300]}"
    expect(page.locator(".el-message--success").filter(has_text="环境更新成功")).to_be_visible(timeout=8000)

    _open_envs_tab(page, pid)
    expect(_env_row(page, new_name)).to_be_visible(timeout=8000)
    assert _params_db(eid, "GUARD_KEY").startswith("ENC:"), "编辑环境后参数密文应保留"

    assert_no_errors(collectors, "H3 编辑表单 守卫回归")


def test_h3_purge_zero_residue(api_client, h3_env, uniq):
    pass
    pid, eid, env_name = h3_env
    r = api_client.put(f"/projects/{pid}/envs/{eid}/params",
                       json={"params": {"RESIDUE_KEY": "res-v1"}})
    assert r.status_code == 200, f"预置参数失败: {r.text[:200]}"
    r = api_client.delete(f"/projects/{pid}/purge?force=true")
    assert r.status_code == 200, f"purge 失败: {r.text[:200]}"
    assert sql_query(f"SELECT count(*) FROM tp_project_env WHERE project_id={pid}") == "0"
    assert sql_query(f"SELECT count(*) FROM tp_project WHERE code LIKE 'H3E{uniq.upper()}%'") == "0"
