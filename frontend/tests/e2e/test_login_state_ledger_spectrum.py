
import base64
import json
import re
import time

import pytest

from conftest import (
    BASE_URL,
    assert_no_errors,
    pick_enum_option,
    pick_project_in_header,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

KIND_LABEL = "类型"
CRED_KEY_LABEL = "引用键"
SECRET_LABEL = "凭据值"


def _jwt(exp_epoch: int) -> str:
    """Unsigned JWT-shaped token: SessionStateSupport.parseJwtExp only
    base64url-decodes the payload segment and reads the numeric exp claim."""
    def seg(obj):
        raw = json.dumps(obj, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    return f"{seg({'alg': 'none', 'typ': 'JWT'})}.{seg({'exp': exp_epoch})}.ledger-sig"


def dialog_field(page, label_text: str):
    return (
        page.locator('[data-test="login-state-create-dialog"] .el-form-item')
        .filter(has_text=label_text)
        .locator("input")
        .first
    )


def row_of(page, name: str):
    row = page.locator('[data-test="login-state-table"] .el-table__row').filter(has_text=name)
    expect(row).to_have_count(1, timeout=15000)
    return row.first


def register(page, name: str, cred_key: str, secret: str):
    page.locator('[data-test="login-state-create-btn"]').click()
    expect(page.locator('[data-test="login-state-create-dialog"]')).to_be_visible(timeout=10000)
    page.fill('[data-test="login-state-create-name"]', name)
    pick_enum_option(page, "login-state-create-kind", "token")
    dialog_field(page, CRED_KEY_LABEL).fill(cred_key)
    dialog_field(page, SECRET_LABEL).fill(secret)
    page.locator('[data-test="login-state-create-submit"]').click()
    expect_success_toast(page, "已登记")


def expect_success_toast(page, text: str):
    """Assert a visible success toast carrying `text`.

    Element Plus stacks messages and (observed on this page) replays them, so
    stale `.el-message--success` nodes can stay attached after their fade.
    Matching only visible nodes by text keeps the strict-mode assertion stable
    while row light + API read-back remain the authoritative state checks.
    """
    toasts = page.locator(".el-message--success:visible").filter(has_text=text)
    expect(toasts.last).to_be_visible(timeout=15000)


def confirm_box(page, expected_fragment: str):
    box = page.locator(".el-message-box")
    expect(box).to_be_visible(timeout=10000)
    expect(box).to_contain_text(expected_fragment, timeout=10000)
    box.locator(".el-message-box__btns button.el-button--primary").click()


def ledger_rows(api_client, pid):
    r = api_client.get(f"/session-states?projectId={pid}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"], r.text


def test_login_state_ledger_crud_renewal(logged_in, collectors, api_client, uniq):
    page = logged_in
    project_name = f"登录态账本谱{uniq}"
    pid = seed_project(api_client, uniq, "LSE", display_name=project_name)
    name = f"ledger-spec-{uniq}"
    cred_key = f"lsecred{uniq}"
    past_token = _jwt(int(time.time()) - 120)
    fresh_token = _jwt(int(time.time()) + 7 * 86400)
    try:
        # --- 1. menu walk: header context + one group + one leaf = 3 clicks total
        pick_project_in_header(page, project_name)
        menu_clicks = 0
        group = page.locator(".el-sub-menu__title").filter(has_text="造数与资产").first
        expect(group).to_be_visible(timeout=15000)
        group.click()
        menu_clicks += 1
        leaf = page.locator('[data-test="menu-login-states"]').first
        expect(leaf).to_be_visible(timeout=10000)
        leaf.click()
        menu_clicks += 1
        assert menu_clicks <= 5, f"menu-to-ledger took {menu_clicks} clicks"
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)
        # R30: the header context is written back into the query, so the URL
        # carries ?projectId=<pid> after landing — assert the route, not an
        # exact string.
        expect(page).to_have_url(re.compile(r"/#/login-states(\?|$)"), timeout=10000)

        register(page, name, cred_key, past_token)
        row = row_of(page, name)
        expect(row.locator('[data-test="login-state-light"]')).to_have_text("已过期", timeout=10000)
        rows, raw = ledger_rows(api_client, pid)
        mine = [x for x in rows if x["stateName"] == name]
        assert len(mine) == 1, f"ledger should hold exactly one row for {name}: {raw[:200]}"
        assert mine[0]["kind"] == "token" and mine[0]["credKey"] == cred_key
        assert past_token not in raw, "vault plaintext leaked into the ledger read-back"
        assert past_token not in page.content(), "vault plaintext echoed into the DOM"

        # --- 3. renewal lane: same name through the same dialog upserts in place
        register(page, name, cred_key, fresh_token)
        row = row_of(page, name)
        expect(row.locator('[data-test="login-state-light"]')).to_have_text("有效", timeout=10000)
        rows, raw = ledger_rows(api_client, pid)
        mine = [x for x in rows if x["stateName"] == name]
        assert len(mine) == 1, "renewal must update the row, never duplicate it"
        assert mine[0]["expiresAt"], "renewed row lost its parsed expiry"
        assert fresh_token not in raw and past_token not in raw

        # --- 4. manage: disable names the object, the light follows the server
        row.locator('[data-test="login-state-toggle-btn"]').click()
        confirm_box(page, name)
        expect_success_toast(page, "已停用")
        row = row_of(page, name)
        expect(row.locator('[data-test="login-state-light"]')).to_have_text("已停用", timeout=10000)
        rows, _ = ledger_rows(api_client, pid)
        assert [x for x in rows if x["stateName"] == name][0]["status"] == 0
        row.locator('[data-test="login-state-toggle-btn"]').click()
        confirm_box(page, name)
        expect_success_toast(page, "已启用")
        rows, _ = ledger_rows(api_client, pid)
        assert [x for x in rows if x["stateName"] == name][0]["status"] == 1

        # --- 5. delete: the confirm states the consequence; DOM claim verified
        # server-side by the read-back being empty for that name.
        row_of(page, name).locator('[data-test="login-state-delete-btn"]').click()
        confirm_box(page, name)
        expect_success_toast(page, "已删除")
        page.locator('[data-test="login-state-table"] .el-table__row').filter(
            has_text=name
        ).wait_for(state="detached", timeout=15000)
        rows, _ = ledger_rows(api_client, pid)
        assert not [x for x in rows if x["stateName"] == name], "row survived on the server"

        assert_no_errors(collectors, "login_state_ledger")
    finally:
        purge_project(api_client, pid)


def test_login_state_ledger_paging_worst_size(logged_in, collectors, api_client, uniq):
    """S-group worst-size face : a 36-row ledger behind the paged door.

 Drives, through the real admin->core chain with zero mocks:
 1. default page shows one pageful (20 of 36) and the pager carries the
 true total; next page lands the remaining 16 and page=2 rides the URL;
 2. a reload restores the same segment from the URL (F1 page-state face);
 3. the keyword filter matches the account label server-side (6 rows) and
 the enabled-state filter isolates the 3 disabled rows;
 4. header sort on a whitelisted column orders the WHOLE ledger, not the
 visible page — the first row flips between paged-00 and paged-35, and
 sortBy/sortDir ride the URL;
 5. the derived light / vault key / site-scope columns honestly refuse the
 sort affordance (R25: no fake global order);
 6. pixel follow-up: every ledger column renders with a usable
 width, and every row action button shares one corner radius and one
 baseline (getComputedStyle, no naked screenshot claims).
 """
    page = logged_in
    project_name = f"登录态分页谱{uniq}"
    pid = seed_project(api_client, uniq, "LSP", display_name=project_name)
    rows_sel = '[data-test="login-state-table"] .el-table__row'
    created: list[int] = []
    try:
        for i in range(36):
            body = {
                "projectId": pid,
                "stateName": f"paged-{i:02d}-{uniq}",
                "kind": "token",
                "credKey": f"lsp_key_{i:02d}",
                "secret": _jwt(int(time.time()) + 3 * 86400 + i),
                "accountLabel": (f"acct-zeta-{uniq}" if i % 6 == 0 else f"label-{i:02d}"),
            }
            r = api_client.post("/session-states", json=body)
            assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
            created.append(r.json()["data"])
        for row_id in created[:3]:
            r = api_client.put(f"/session-states/{row_id}/enabled?enabled=false")
            assert r.status_code == 200, r.text[:200]

        page.goto(f"{BASE_URL}/#/login-states?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)

        # --- 1. pageful + pager next + URL page=2
        expect(page.locator(rows_sel)).to_have_count(20, timeout=15000)
        expect(page.locator(".el-pagination__total")).to_contain_text("共 36")
        page.locator('[data-test="login-state-pager"] button.btn-next').click()
        expect(page.locator(rows_sel)).to_have_count(16, timeout=15000)
        expect(page).to_have_url(re.compile(r"[?&]page=2(&|$)"), timeout=10000)

        # --- 2. reload restores the same segment straight from the URL
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="login-state-page"]')).to_be_visible(timeout=15000)
        expect(page.locator(rows_sel)).to_have_count(16, timeout=15000)

        # --- 3a. keyword hits the account label (ILIKE, server-side)
        page.goto(f"{BASE_URL}/#/login-states?projectId={pid}", wait_until="domcontentloaded")
        page.fill('[data-test="login-state-keyword-input"]', f"acct-zeta-{uniq}")
        page.locator('[data-test="login-state-query-btn"]').click()
        expect(page.locator(rows_sel)).to_have_count(6, timeout=15000)
        expect(page.locator(".el-pagination__total")).to_contain_text("共 6")
        expect(page).to_have_url(re.compile(r"[?&]keyword=acct-zeta"), timeout=10000)

        # --- 3b. enabled-state filter isolates the disabled rows
        page.goto(f"{BASE_URL}/#/login-states?projectId={pid}", wait_until="domcontentloaded")
        # EP2 contract: the toggle lives on .el-select__wrapper; the inner
        # readonly input sits under the placeholder and intercepts clicks.
        page.locator('[data-test="login-state-status-select"] .el-select__wrapper').click()
        page.locator(".el-select-dropdown__item").filter(has_text="停用").first.click()
        page.locator('[data-test="login-state-query-btn"]').click()
        expect(page.locator(rows_sel)).to_have_count(3, timeout=15000)
        expect(page).to_have_url(re.compile(r"[?&]status=0(&|$)"), timeout=10000)

        # --- 4. server whitelisted sort orders the WHOLE ledger
        page.goto(f"{BASE_URL}/#/login-states?projectId={pid}", wait_until="domcontentloaded")
        name_th = page.locator("th.el-table__cell.is-sortable").filter(has_text="登录态名称")
        expect(name_th).to_have_count(1, timeout=10000)
        name_th.click()
        expect(page.locator(rows_sel).first).to_contain_text("paged-00-", timeout=15000)
        expect(page).to_have_url(re.compile(r"[?&]sortBy=stateName"), timeout=10000)
        name_th.click()
        expect(page.locator(rows_sel).first).to_contain_text("paged-35-", timeout=15000)
        expect(page).to_have_url(re.compile(r"[?&]sortDir=descending"), timeout=10000)

        # --- 5. honest absence: derived columns refuse the affordance (R25)
        for label in ("状态灯", "引用键", "站点范围"):
            assert page.locator("th.el-table__cell.is-sortable").filter(
                has_text=label
            ).count() == 0, f"{label} must not offer a fake global sort"

        # --- 6. three-2: column widths + action-button radius/baseline pixels
        widths = page.evaluate(
            """() => [...document.querySelectorAll(
                '[data-test="login-state-table"] th.el-table__cell')]
                .map((e) => Math.round(e.getBoundingClientRect().width))"""
        )
        assert widths and all(w >= 40 for w in widths), f"crushed ledger column: {widths}"
        geom = page.locator(rows_sel).first.locator(".el-button--small").evaluate_all(
            """els => els.map((e) => {
                const r = e.getBoundingClientRect();
                const cs = getComputedStyle(e);
                return {radius: cs.borderTopLeftRadius, center: r.top + r.height / 2};
            })"""
        )
        # Shape note: the immediate-test probe door joined the ledger row on
        # 2026-11-16 (commit 8d2014f3c), so a row now carries enable/disable +
        # probe + delete. The assertion was written for the two-button era; the
        # contract is unchanged in spirit - every row button shares one radius
        # counts four buttons on one baseline instead of three.
        assert len(geom) == 4, f"row actions changed shape: {geom}"
        assert {g["radius"] for g in geom} == {geom[0]["radius"]}, f"row-button radius split: {geom}"
        centers = [g["center"] for g in geom]
        assert max(centers) - min(centers) < 3, f"row-button baselines split: {geom}"

        assert_no_errors(collectors, "login_state_paging")
    finally:
        purge_project(api_client, pid)
