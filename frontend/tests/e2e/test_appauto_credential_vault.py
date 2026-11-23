
import subprocess
import time
import uuid

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    BASE_URL,
    api_login,
    assert_no_errors,
    open_journey_leaf,
)
from playwright.sync_api import expect
from test_immediate_test_tails import _open_exchange_detail

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
    """: page-level selector is gone — the ?projectId deep link lands the segment.
 Bounce via the dashboard: the router reuses the component across query-only
 navigation, so the deep-link-consuming setup must be re-triggered by a route
 change (reload-persistence checks need the genuine remount too)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/credentials?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="credential-vault-page"]')).to_be_visible(timeout=15000)


def test_appauto_credential_vault_page(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    suffix = uuid.uuid4().hex[:6]
    proj_name = f"凭据E2E项目{suffix}"
    cred_name = f"商城测试账号{suffix}"
    cred_key = f"app_acct_{suffix}"
    secret = "E2eS" + suffix + "!x9"
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    pid = None
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": proj_name, "code": f"CVE{suffix}"}).json()["data"]

        open_journey_leaf(page, "menu-credential-vault", "dataAssets")
        expect(page.locator('[data-test="credential-vault-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="credential-usage-alert"]')).to_contain_text("CRED")

        create_btn = page.locator('[data-test="credential-create-btn"]')
        expect(create_btn).to_be_disabled(timeout=5000)
        _pick_project(page, pid)
        expect(create_btn).to_be_enabled(timeout=8000)
        create_btn.click()
        dlg = page.locator('[data-test="credential-dialog"]')
        expect(dlg).to_be_visible()
        page.locator('[data-test="credential-dialog-save"]').click()
        expect(dlg).to_be_visible(timeout=3000)
        page.locator('[data-test="credential-form-name"]').fill(cred_name)
        page.locator('[data-test="credential-form-key"]').fill(cred_key)
        secret_input = page.locator('[data-test="credential-form-secret"]')
        assert secret_input.get_attribute("type") == "password"
        secret_input.fill(secret)
        page.locator('[data-test="credential-dialog-save"]').click()
        expect(dlg).to_be_hidden(timeout=8000)
        row = sql(f"SELECT id FROM tp_credential WHERE project_id={pid} AND cred_key='{cred_key}'")
        assert row.isdigit(), f"凭据行未落库: {row}"
        cred_id = int(row)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="credential-vault-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, pid)
        table = page.locator('[data-test="credential-table"]')
        expect(table.locator("tbody tr").filter(has_text=cred_name)).to_have_count(1, timeout=10000)
        mask_cell = table.locator("tbody tr").filter(has_text=cred_name).first \
            .locator('[data-test="credential-mask"]')
        expect(mask_cell).to_have_text("******", timeout=8000)
        body_text = page.locator("body").inner_text()
        assert secret not in body_text, "明文口令出现在页面文本中（永不回显硬伤）"
        cipher = sql(f"SELECT secret_cipher FROM tp_credential WHERE id={cred_id}")
        assert cipher and cipher != secret and "******" not in cipher, "密文列形态异常"

        row_loc = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=cred_name).first
        row_loc.locator('[data-test="credential-edit-btn"]').click()
        dlg2 = page.locator('[data-test="credential-dialog"]')
        expect(dlg2).to_be_visible()
        expect(page.locator('[data-test="credential-form-name"]')).to_have_value(cred_name)
        expect(page.locator('[data-test="credential-form-secret"]')).to_have_value("******")
        key_input = page.locator('[data-test="credential-form-key"]')
        assert key_input.get_attribute("disabled") is not None, "编辑态引用键未锁死"
        renamed = f"{cred_name}改"
        page.locator('[data-test="credential-form-name"]').fill(renamed)
        page.locator('[data-test="credential-dialog-save"]').click()
        expect(dlg2).to_be_hidden(timeout=8000)
        assert sql(f"SELECT name FROM tp_credential WHERE id={cred_id}") == renamed
        assert sql(f"SELECT secret_cipher FROM tp_credential WHERE id={cred_id}") == cipher, \
            "打码串提交覆盖了原密文（未修改语义失效）"

        row_loc2 = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=renamed).first
        row_loc2.locator('[data-test="credential-enabled-switch"]').click()
        for _ in range(40):
            if sql(f"SELECT status FROM tp_credential WHERE id={cred_id}") == "0":
                break
            time.sleep(0.5)
        assert sql(f"SELECT status FROM tp_credential WHERE id={cred_id}") == "0", "禁用未落库"
        page.locator('[data-test="credential-refresh"]').click()
        expect(page.locator('[data-test="credential-table"] tbody tr').filter(has_text=renamed)) \
            .to_have_count(1, timeout=8000)

        page.locator('[data-test="credential-create-btn"]').click()
        dlg3 = page.locator('[data-test="credential-dialog"]')
        expect(dlg3).to_be_visible()
        page.locator('[data-test="credential-form-name"]').fill(f"撞键{suffix}")
        page.locator('[data-test="credential-form-key"]').fill(cred_key)
        page.locator('[data-test="credential-form-secret"]').fill(secret + "2")
        page.locator('[data-test="credential-dialog-save"]').click()
        expect(page.locator(".el-message").filter(has_text="同键")).to_be_visible(timeout=8000)
        page.locator('[data-test="credential-dialog-cancel"]').click()
        expect(dlg3).to_be_hidden(timeout=5000)

        page.locator('[data-test="credential-status-select"]').click()
        opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', has_text="启用中")
        expect(opt.first).to_be_visible(timeout=5000)
        opt.first.click()
        expect(page.locator('[data-test="credential-table"] tbody tr').filter(has_text=renamed)) \
            .to_have_count(0, timeout=8000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="credential-vault-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, pid)
        del_row = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=renamed).first
        del_row.locator('[data-test="credential-delete-btn"]').click()
        confirm = page.locator('.el-message-box')
        expect(confirm).to_be_visible(timeout=5000)
        confirm.locator('button', has_text="删除").click()
        expect(page.locator('[data-test="credential-table"] tbody tr').filter(has_text=renamed)) \
            .to_have_count(0, timeout=8000)
        assert sql(f"SELECT count(*) FROM tp_credential WHERE id={cred_id}") == "0"

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/api/v1/appauto/credentials" in e["url"] and e["status"] == 409)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 409" not in c]

        assert_no_errors(collectors, "appauto credential vault page")
    finally:
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_credential WHERE project_id={pid}") == "0"
        api.close()


def test_appauto_credential_test_door_grade_comparison(page, collectors):
    """Comparison spectrum for the vault test door: the same target address reads
    differently depending on whether the credential is actually referenced.
    A. "test with this credential" prefills the Bearer reference -> green badge
       (credential verified), the note names the injection count, the expanded
       exchange frame shows a masked authorization (first 8 chars + total length),
       and the real secret never leaves the server.
    B. Running without any reference -> grey badge (credential unverified) with a
       fix-it pointer telling the user to press "test with this credential".
    Both states propagate to the list verdict column and survive RELOAD (the
    evidence grade is persisted, not just dialog state).
    Real chain: admin 8081 -> core 8083 -> genuine HTTP against actuator/health,
    no substitutes.
    """
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    suffix = uuid.uuid4().hex[:6]
    cred_name = f"对照账号{suffix}"
    cred_key = f"cmp_acct_{suffix}"
    secret = "CmpS" + suffix + "!k7"
    target = "http://localhost:8083/actuator/health"
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    pid = None
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": f"凭据对照谱{suffix}", "code": f"CMP{suffix}"}).json()["data"]
        open_journey_leaf(page, "menu-credential-vault", "dataAssets")
        _pick_project(page, pid)
        page.locator('[data-test="credential-create-btn"]').click()
        dlg = page.locator('[data-test="credential-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        dlg.locator('[data-test="credential-form-name"]').fill(cred_name)
        dlg.locator('[data-test="credential-form-key"]').fill(cred_key)
        dlg.locator('[data-test="credential-form-secret"]').fill(secret)
        dlg.locator('[data-test="credential-dialog-save"]').click()
        expect(dlg).to_be_hidden(timeout=8000)

        row = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=cred_name).first
        # Never-tested rows carry no evidence-grade badge at all (contrast baseline)
        expect(row.locator('[data-test="credential-grade-badge"]')).to_have_count(0, timeout=8000)

        row.locator('[data-test="credential-test-btn"]').click()
        door = page.locator('[data-test="credential-test-dialog"]')
        expect(door).to_be_visible(timeout=10000)
        page.locator('[data-test="credential-test-url"]').fill(target)
        page.locator('[data-test="credential-test-method"]').click()
        page.locator(".el-select-dropdown__item").filter(has_text="GET").last.click()
        page.locator('[data-test="credential-test-prefill"]').click()
        bearer_item = page.locator(".el-dropdown-menu__item:visible").filter(has_text="Authorization")
        expect(bearer_item.first).to_be_visible(timeout=5000)
        bearer_item.first.click()
        value_input = page.locator('[data-test="credential-test-header-value"]')
        expect(value_input).to_have_count(1, timeout=5000)
        assert cred_key in value_input.input_value(), "预填行未携带本行引用键占位符"
        page.locator('[data-test="credential-test-run"]').click()
        badge = page.locator('[data-test="credential-test-grade"]')
        expect(badge).to_contain_text("已验证", timeout=30000)
        assert "el-tag--success" in (badge.get_attribute("class") or ""), "VERIFIED 必须绿徽标"
        expect(page.locator('[data-test="credential-test-note"]')).to_contain_text("引用 1 处")

        req_cursor = _open_exchange_detail(page, needle="请求头（")
        req_text = page.locator(f'[data-test="trail-detail-{req_cursor}"]').inner_text()
        assert "authorization" in req_text, "递交的请求头必须含 authorization 行"
        assert "（总长度 " in req_text, "凭据值必须以长度掩码形式打码"
        assert secret not in req_text, "请求头帧严禁凭据真值"
        assert secret not in page.locator('[data-test="task-tail-drawer"]').inner_text(), \
            "凭据真值严禁进入过程流"
        page.locator('[data-test="credential-test-close"]').click()
        expect(door).to_be_hidden(timeout=8000)

        row = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=cred_name).first
        expect(row.locator('[data-test="credential-grade-badge"]')).to_contain_text("已验证", timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="credential-vault-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, pid)
        row = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=cred_name).first
        expect(row.locator('[data-test="credential-grade-badge"]')).to_contain_text("已验证", timeout=10000)

        row.locator('[data-test="credential-test-btn"]').click()
        expect(door).to_be_visible(timeout=10000)
        page.locator('[data-test="credential-test-url"]').fill(target)
        page.locator('[data-test="credential-test-method"]').click()
        page.locator(".el-select-dropdown__item").filter(has_text="GET").last.click()
        # Under GET the body field is not rendered at all (v-if in
        # CredentialVaultView, protocol semantics), and the server does not
        # count bodyTemplate references for GET (proved by leg A asserting
        # "1 reference" while the body still carried a placeholder). No need
        # to clear the body here.
        page.locator('[data-test="credential-test-run"]').click()
        badge2 = page.locator('[data-test="credential-test-grade"]')
        expect(badge2).to_contain_text("未被检验", timeout=30000)
        assert "el-tag--success" not in (badge2.get_attribute("class") or ""), \
            "UNVERIFIED 禁绿徽标（与 VERIFIED 同形制=两态不可辨）"
        expect(page.locator('[data-test="credential-test-note"]')).to_contain_text("用此凭据")
        page.locator('[data-test="credential-test-close"]').click()
        expect(door).to_be_hidden(timeout=8000)

        row = page.locator('[data-test="credential-table"] tbody tr').filter(has_text=cred_name).first
        expect(row.locator('[data-test="credential-grade-badge"]')).to_contain_text("未被检验", timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="credential-vault-page"]')).to_be_visible(timeout=15000)
        _pick_project(page, pid)
        expect(page.locator('[data-test="credential-table"] tbody tr')
               .filter(has_text=cred_name).first
               .locator('[data-test="credential-grade-badge"]')).to_contain_text("未被检验", timeout=10000)

        assert_no_errors(collectors, "credential test door grade comparison")
    finally:
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_credential WHERE project_id={pid}") == "0"
        api.close()
