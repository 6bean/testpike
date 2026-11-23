"""Project-memory ledger tab E2E .

Chain: fixture project + 32 API-seeded entries (server-paged worst-case scale)
-> deep link ?tab=memory survives reload -> paged window renders 20 of 33 ->
keyword and injection-state filters narrow to the target -> create dialog
echoes the header project read-only (ownership-echo iron rule) -> manual entry
lands effective and survives a hard reload -> disable/enable round trip flips
the state tag through the same consumption door -> delete hides the row, the
recycle-bin drawer lists it, restore brings it back -> soft-delete purge with
zero active residue (fixture rows cleaned by id, R37: no factory table touched).

Posture notes: a) run with `set -a && source .env` for ADMIN_INIT_PASSWORD;
b) every assertion is hard (iron rule 5d — absence is asserted as
to_have_count(0), never wrapped in a conditional); c) the browser walks the
real admin->core chain, no mocks; d) collectors enforce the three zero-
tolerance classes (4xx/5xx, console, Issues panel).
"""
import pytest
import project_memory_fixture as pmfx
from conftest import (
    ADMIN_PASSWORD,
    assert_no_errors,
    inject_user_token,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(420)

SKILLS_MEMORY_URL = "#/skills?tab=memory"


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API failure: {r.text[:300]}"
    return r.json()["data"]


def _table(page):
    return page.locator('[data-test="memory-table"]')


def _search(page, token):
    """Narrow the ledger to entries containing the token (server-side keyword)."""
    kw = page.locator('[data-test="memory-keyword"]')
    kw.fill(token)
    page.locator('[data-test="memory-search"]').click()
    return _table(page).locator("tbody tr")


def _pick_status(page, label):
    page.locator('[data-test="memory-status-select"]').click()
    page.locator(".tp-memory-status-popper .el-select-dropdown__item").filter(
        has_text=label).first.click()


def _clear_keyword(page):
    kw = page.locator('[data-test="memory-keyword"]')
    kw.fill("")
    page.locator('[data-test="memory-search"]').click()


def _confirm_box(page):
    page.locator(".el-message-box").get_by_role("button", name="确定").click()


def _toast(page, text):
    """The single ElMessage carrying this exact notice (stacking-safe)."""
    return page.locator(".el-message").filter(has_text=text)


def test_project_memory_tab(page, collectors, api_client, uniq):
    proj_name = f"PMem{uniq}"
    oid = _api_ok(api_client.post("/projects", json={"name": proj_name, "code": f"PM{uniq[:6]}"}))
    pin = f"PMEMPIN{uniq}"
    new_tok = f"PMEMNEW{uniq}"
    seeded = []
    try:
        # ---- seed: 31 fillers + 1 pin target (worst-scale: 32 rows > one page) ----
        for i in range(31):
            seeded.append(_api_ok(api_client.post(
                "/project-memory",
                json={"projectId": oid, "content": f"分页最坏规模填充第{i:02d}条：登录态复用约定 {uniq}"}))["id"])
        seeded.append(_api_ok(api_client.post(
            "/project-memory",
            json={"projectId": oid, "content": f"{pin} 项目约定：接口回归统一使用测试环境四套"}))["id"])

        # ---- deep link + reload keeps the tab (page-state URL iron rule) ----
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        base = page.url.split("#")[0]
        page.goto(f"{base}{SKILLS_MEMORY_URL}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-tab-memory"]')).to_be_visible(timeout=10000)
        pick_project_in_header(page, proj_name)
        panel = page.locator('[data-test="project-memory-panel"]')
        expect(panel).to_be_visible(timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(panel).to_be_visible(timeout=10000)
        assert "tab=memory" in page.url, f"deep link lost after reload: {page.url}"

        # ---- worst-scale window: server paged, total counts the segment ----
        expect(page.locator('[data-test="memory-pagination"] .el-pagination__total')).to_contain_text("32")
        expect(_table(page).locator("tbody tr")).to_have_count(20, timeout=10000)

        # ---- keyword filter narrows to exactly the pinned entry ----
        rows = _search(page, pin)
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first.locator('[data-test="memory-content-cell"]')).to_contain_text(pin)

        # ---- create dialog: read-only ownership echo = header project ----
        page.locator('[data-test="memory-create-btn"]').click()
        dialog = page.locator('[data-test="memory-form-dialog"]')
        expect(dialog).to_be_visible()
        expect(dialog.locator('[data-test="memory-owner-echo"]')).to_contain_text(proj_name)
        dialog.locator('[data-test="memory-form-content"]').fill(f"{new_tok} 手工固定注入：评审留痕须点名用例编号")
        dialog.locator('[data-test="memory-form-submit"]').click()
        # toast assertions match by message text: ElMessage stacks, so a bare
        # ".el-message" locator is strict-mode ambiguous while a previous
        # notice is still fading out.
        expect(_toast(page, "记忆已保存并开始生效")).to_be_visible(timeout=10000)

        # ---- new entry lands effective and survives a hard reload ----
        rows = _search(page, new_tok)
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first.locator('[data-test="memory-state-tag"]')).to_have_text("生效中")
        page.reload(wait_until="domcontentloaded")
        rows = _search(page, new_tok)
        expect(rows).to_have_count(1, timeout=10000)
        # the pager total is the filtered aggregate (server-side count), so the
        # scoped segment reads 1 here; the unfiltered 33 is pinned in the
        # state-filter leg below.
        expect(page.locator('[data-test="memory-pagination"] .el-pagination__total')).to_contain_text("共 1")

        # ---- disable -> evidence on the state tag, then a status-only filter ----
        rows = _search(page, pin)
        expect(rows).to_have_count(1, timeout=10000)
        rows.first.get_by_role("button", name="停用", exact=True).click()
        _confirm_box(page)
        expect(_toast(page, "已停用，该条记忆不再参与下一次装配")).to_be_visible(timeout=10000)
        _clear_keyword(page)
        _pick_status(page, "已停用")
        dis_rows = _table(page).locator("tbody tr")
        expect(dis_rows).to_have_count(1, timeout=10000)
        expect(dis_rows.first.locator('[data-test="memory-content-cell"]')).to_contain_text(pin)
        expect(dis_rows.first.locator('[data-test="memory-state-tag"]')).to_have_text("已停用")

        # ---- enable back: the row exits the disabled segment, flips into the active one ----
        dis_rows.first.get_by_role("button", name="启用", exact=True).click()
        _confirm_box(page)
        expect(_toast(page, "已启用，该条记忆恢复参与装配")).to_be_visible(timeout=10000)
        expect(_table(page).locator("tbody tr")).to_have_count(0, timeout=10000)
        _pick_status(page, "生效中")
        expect(_table(page).locator("tbody tr")).to_have_count(20, timeout=10000)
        expect(page.locator('[data-test="memory-pagination"] .el-pagination__total')).to_contain_text("33")
        rows = _search(page, pin)
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first.locator('[data-test="memory-state-tag"]')).to_have_text("生效中", timeout=10000)

        # ---- delete -> absent from the ledger -> recycle bin lists it ----
        rows.first.get_by_role("button", name="删除", exact=True).click()
        _confirm_box(page)
        expect(_toast(page, "已删除，可在回收站还原")).to_be_visible(timeout=10000)
        expect(_search(page, pin)).to_have_count(0, timeout=10000)
        page.locator('[data-test="memory-recycle-open"]').click()
        drawer = page.locator('[data-test="memory-recycle-drawer"]')
        expect(drawer).to_be_visible()
        expect(drawer.locator('[data-test="memory-recycle-table"]')).to_contain_text(pin)

        # ---- restore brings the row back to the active ledger ----
        bin_rows = _api_ok(api_client.get(f"/project-memory/recycle-bin?projectId={oid}&page=1&size=50"))["list"]
        target = [r for r in bin_rows if pin in r["content"]]
        assert len(target) == 1, f"recycle bin should hold exactly one pinned row: {bin_rows}"
        drawer.locator(f'[data-test="memory-recycle-restore-{target[0]["id"]}"]').click()
        _confirm_box(page)
        expect(_toast(page, "已还原")).to_be_visible(timeout=10000)
        expect(drawer.locator('[data-test="memory-recycle-table"]')).not_to_contain_text(pin, timeout=10000)
        page.keyboard.press("Escape")
        rows = _search(page, pin)
        expect(rows).to_have_count(1, timeout=10000)

        assert_no_errors(collectors, "project-memory ledger tab")
    finally:
        # ---- purge: soft-delete every fixture row by id, reconcile zero active ----
        for rid in seeded:
            api_client.delete(f"/project-memory/{rid}?projectId={oid}")
        leftovers = _api_ok(api_client.get(f"/project-memory?projectId={oid}&page=1&size=20"))["list"]
        for row in leftovers:
            if uniq in row["content"]:
                api_client.delete(f"/project-memory/{row['id']}?projectId={oid}")
        active = _api_ok(api_client.get(f"/project-memory?projectId={oid}&page=1&size=20"))
        assert active["total"] == 0, f"fixture residue left in the ledger: {active}"


def test_project_memory_four_state_confirmation_gate(page, collectors, api_client, uniq):
    """ B: distilled rows ride the shared draft confirmation door.

 Four-state ledger under one worst-scale roof (34 rows > one page): the
 state filter reaches every state (1 active / 0 disabled / 2 pending /
 3 rejected); a pending row anchored to an open card adopts through the
 shared ticket-then-apply protocol (row flips effective, draft flips
 applied — DB ground truth asserted), a pending row without a card shows
 a disabled adopt plus honest tag guidance to the AI Chat panel, and a
 rejected row keeps only the delete exit. Pending/rejected entries are
 minted by direct SQL because the distillation channel — not the ledger
 API — owns those states; the browser still walks the real
 admin->core chain with zero mocks (iron rule 5d: no conditional
 assertions).
 """
    fx = pmfx.seed(api_client, uniq)
    proj_name = fx["project"]
    oid = fx["oid"]
    tok_card = fx["tokens"]["card"]
    tok_nocard = fx["tokens"]["nocard"]
    tok_rejected = fx["tokens"]["rejected"]
    draft_ids = fx["draft_ids"]
    pending_card_id, pending_nocard_id = fx["pending_card_id"], fx["pending_nocard_id"]
    try:

        inject_user_token(page, "admin", ADMIN_PASSWORD)
        base = page.url.split("#")[0]
        page.goto(f"{base}{SKILLS_MEMORY_URL}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="skill-tab-memory"]')).to_be_visible(timeout=10000)
        pick_project_in_header(page, proj_name)
        panel = page.locator('[data-test="project-memory-panel"]')
        expect(panel).to_be_visible(timeout=10000)

        # ---- worst-scale: server paged, 34 rows across the pager ----
        expect(page.locator('[data-test="memory-pagination"] .el-pagination__total')).to_contain_text("34")
        expect(_table(page).locator("tbody tr")).to_have_count(20, timeout=10000)

        # ---- the state filter reaches all four states ----
        _pick_status(page, "已停用")
        dis_rows = _table(page).locator("tbody tr")
        expect(dis_rows).to_have_count(1, timeout=10000)
        expect(dis_rows.first.locator('[data-test="memory-content-cell"]')).to_contain_text(f"MEM4DIS{uniq}")
        _pick_status(page, "待确认")
        expect(_table(page).locator("tbody tr")).to_have_count(2, timeout=10000)
        _pick_status(page, "已驳回")
        rej_rows = _table(page).locator("tbody tr")
        expect(rej_rows).to_have_count(1, timeout=10000)
        expect(rej_rows.first.locator('[data-test="memory-content-cell"]')).to_contain_text(tok_rejected)
        # a rejected entry has no adopt/reject semantic — delete is its only exit
        expect(rej_rows.first.locator('[data-test="ra-adopt"]')).to_have_count(0)
        expect(rej_rows.first.locator('[data-test="ra-reject"]')).to_have_count(0)
        expect(rej_rows.first.locator('[data-test="ra-remove"]')).to_have_count(1)
        _pick_status(page, "生效中")
        expect(page.locator('[data-test="memory-pagination"] .el-pagination__total')).to_contain_text("30")
        page.reload(wait_until="domcontentloaded")
        expect(panel).to_be_visible(timeout=10000)

        # ---- pending without a card: disabled adopt + tag guidance ----
        rows = _search(page, tok_nocard)
        expect(rows).to_have_count(1, timeout=10000)
        expect(rows.first.locator('[data-test="memory-state-tag"]')).to_have_text("待确认")
        expect(rows.first.locator('[data-test="ra-adopt"]')).to_be_disabled()
        rows.first.locator('[data-test="memory-state-tag"]').hover()
        tip = page.locator(".el-popper").filter(has_text="暂无可确认的提案卡")
        expect(tip).to_be_visible(timeout=5000)
        expect(tip).to_contain_text("AI 对话")

        # ---- pending with an open card: adopt rides ticket-then-apply ----
        rows = _search(page, tok_card)
        expect(rows).to_have_count(1, timeout=10000)
        adopt = rows.first.locator('[data-test="ra-adopt"]')
        expect(adopt).to_be_enabled()
        adopt.click()
        _confirm_box(page)
        expect(_toast(page, "已采纳，该条记忆开始参与 AI 装配")).to_be_visible(timeout=10000)
        expect(rows.first.locator('[data-test="memory-state-tag"]')).to_have_text("生效中", timeout=10000)

        # ---- reject: lands the row on rejected, delete-only exit remains ----
        rows = _search(page, tok_nocard)
        rows.first.locator('[data-test="ra-reject"]').click()
        _confirm_box(page)
        expect(_toast(page, "已驳回，该条记忆不会被注入，仍可在列表中查看")).to_be_visible(timeout=10000)
        expect(rows.first.locator('[data-test="memory-state-tag"]')).to_have_text("已驳回", timeout=10000)
        expect(rows.first.locator('[data-test="ra-adopt"]')).to_have_count(0)
        _clear_keyword(page)
        _pick_status(page, "已驳回")
        expect(_table(page).locator("tbody tr")).to_have_count(2, timeout=10000)

        # ---- DB ground truth: the shared door did the write ----
        assert sql_query(
            f"SELECT inject_status FROM tp_ai_ctx_memory WHERE id = {pending_card_id}") == "1", \
            "adopt must promote the distilled row to effective"
        assert sql_query(
            f"SELECT status FROM tp_ai_chat_draft WHERE id = {draft_ids[0]}") == "2", \
            "adopt must mark the anchored card applied"
        assert sql_query(
            f"SELECT inject_status FROM tp_ai_ctx_memory WHERE id = {pending_nocard_id}") == "3", \
            "reject must land the pending row on rejected"

        assert_no_errors(collectors, "project-memory four-state confirmation gate")
    finally:
        pmfx.cleanup(api_client, fx)
