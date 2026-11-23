
import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, logged_in, api_client, collectors, uniq  # noqa: F401
from script_seed import base_doc, mk_case, mk_project, open_detail, open_tree_dialog, save_doc

pytestmark = pytest.mark.timeout(180)


def test_v4_shell_insert_save_flowchart_roundtrip(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = mk_project(api_client, uniq)
        case_id = mk_case(api_client, pid, uniq)
        save_doc(case_id, base_doc(case_id, uniq))

        open_detail(page, case_id)
        dialog = open_tree_dialog(page)
        rows = dialog.locator('[data-test^="step-tree-row-"]')
        expect(rows).to_have_count(3)

        dialog.locator('[data-test="step-tree-insert-loop"]').click()
        dialog.locator('[data-test="step-tree-insert-if"]').click()
        expect(rows).to_have_count(5)
        chip = dialog.locator('[data-test^="step-tree-loopvar-"]')
        expect(chip).to_have_count(1)
        expect(chip).to_contain_text("item")

        dialog.locator('[data-test="step-tree-save"]').click()
        diff = page.locator('[data-test="step-tree-diff-dialog"]')
        expect(diff).to_be_visible()
        page.locator('[data-test="step-tree-diff-confirm"]').click()
        expect(page.locator(".el-message").filter(has_text="脚本已保存")).to_be_visible(timeout=8000)

        from conftest import sql_query
        import json
        doc = json.loads(sql_query(f"SELECT action_json FROM tp_script WHERE case_id={case_id}"))
        steps = doc["steps"]
        loop_shell = next(s for s in steps if s.get("action") == "loop")
        assert loop_shell.get("items") == [] and loop_shell.get("steps") == [] \
            and "var" not in loop_shell, f"loop 壳形态漂移: {loop_shell}"
        if_shell = next(s for s in steps if s.get("action") == "if")
        assert if_shell.get("steps") == [] and if_shell.get("elseSteps") == [] \
            and if_shell.get("condition") == "", f"if 壳形态漂移: {if_shell}"

        open_detail(page, case_id)
        svg = page.locator('[data-test="flowchart-svg"]')
        expect(svg).to_contain_text("循环（items 共 0 项）", timeout=15000)
        text = svg.inner_text()
        for frag in ("空循环体", "判定", "成立", "不成立", "汇聚"):
            assert frag in text, f"SVG 缺「{frag}」节点/边标签: {text[:400]}"
        expect(page.locator('[data-test="flowchart-step-count"]')).to_contain_text("5")

        dialog = open_tree_dialog(page)
        expect(dialog.locator('[data-test^="step-tree-row-"]')).to_have_count(5)
        expect(dialog.locator('[data-test^="step-tree-loopvar-"]')).to_contain_text("item")

        assert_no_errors(collectors, "V4 壳插入保存流程图往返")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")


def test_v4_loopvar_conflict_precheck(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = mk_project(api_client, uniq)
        case_id = mk_case(api_client, pid, uniq)
        save_doc(case_id, base_doc(case_id, uniq))

        open_detail(page, case_id)
        dialog = open_tree_dialog(page)
        rows = dialog.locator('[data-test^="step-tree-row-"]')
        dialog.locator('[data-test="step-tree-insert-loop"]').click()
        expect(rows).to_have_count(4)

        rows.nth(3).click()
        dialog.locator('[data-test="step-tree-insert-loop"]').click()
        toast = page.locator(".el-message--error").filter(has_text="冲突")
        expect(toast).to_be_visible(timeout=5000)
        expect(toast).to_contain_text('"item"')
        expect(rows).to_have_count(4)

        assert_no_errors(collectors, "V4 loopVar 冲突预检")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")
