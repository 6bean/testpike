
import json

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to, reload_boot_snapshot, sql_query


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"D2项目_{uniq}", "code": f"D2P{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uniq, title):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uniq}",
        "precondition": f"前置：已登录（{uniq}）",
        "expectedResult": f"预期：成功（{uniq}）",
        "priority": 1,
        "confidence": 2,
        "endType": "web",
        "status": 1,
        "steps": [{"stepNo": 1, "description": "打开 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _insert_sample(case_id, sample_type, payload, version):
    pass
    body = json.dumps(payload, ensure_ascii=False).replace("'", "''")
    sql_query(
        "INSERT INTO tp_training_sample (case_id, sample_type, sample_json, source_version) "
        f"VALUES ({case_id}, '{sample_type}', '{body}'::jsonb, {version})"
    )


def _open_type_filter(page, label):
    page.locator('[data-test="sample-type-select"]').click()
    page.locator(".tp-sample-type-popper .el-select-dropdown__item").filter(has_text=label).first.click()


def _purge(api, pid):
    api.delete(f"/projects/{pid}/purge?force=true")


def test_d2_sample_ledger_view_filter_persist(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    c1 = _mk_case(api_client, pid, uniq, "样本来源用例")
    sample_ids = []
    try:
        _insert_sample(c1["id"], "驳回",
                       {"reason": "步骤缺少断言", "masked_token": "******"}, 3)
        _insert_sample(c1["id"], "修改差异",
                       {"diff": "补充预期结果", "authorization": "***"}, 4)
        rows = sql_query(
            "SELECT id FROM tp_training_sample WHERE case_id = "
            f"{c1['id']} ORDER BY id").splitlines()
        sample_ids = [int(x) for x in rows]
        assert len(sample_ids) == 2, f"样本直插取证异常: {rows}"

        # Header project dropdown options come from the boot-time /users/me
        # snapshot; this fixture creates the project out-of-band through the
        # API client, so force a fresh document boot before the first
        # dropdown interaction (single source: conftest.reload_boot_snapshot).
        reload_boot_snapshot(page)

        nav_to(page, "训练样本台账", "/training-samples",
               '[data-test="sample-page"]', submenu="AI 能力")

        table = page.locator('[data-test="sample-table"]')
        expect(table).to_be_visible()
        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"D2项目_{uniq}").first.click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(2)
        expect(table.locator('[data-test="sample-type-tag"]').first).to_have_text("修改差异")

        _open_type_filter(page, "驳回")
        expect(table.locator("tbody tr.el-table__row")).to_have_count(1)
        expect(table.locator('[data-test="sample-type-tag"]').first).to_have_text("驳回")

        type_select = page.locator('[data-test="sample-type-select"]')
        type_select.hover()
        type_select.locator(".el-select__clear").click()
        page.locator('[data-test="sample-search"]').click()
        expect(table.locator("tbody tr.el-table__row")).to_have_count(2)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        nav_to(page, "训练样本台账", "/training-samples",
               '[data-test="sample-page"]', submenu="AI 能力")
        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"D2项目_{uniq}").first.click()
        expect(page.locator('[data-test="sample-table"] tbody tr.el-table__row')).to_have_count(2)

        page.locator('[data-test="sample-detail-open"]').first.click()
        drawer = page.locator('[data-test="sample-detail-drawer"]')
        expect(drawer).to_be_visible()
        content = drawer.locator('[data-test="sample-detail-json"]').inner_text()
        assert "diff" in content and len(content) > 10, f"抽屉 JSON 不完整: {content[:120]}"
        page.keyboard.press("Escape")

        assert_no_errors(collectors, "D2 样本台账观察链路")
    finally:
        if sample_ids:
            sql_query("DELETE FROM tp_training_sample WHERE id IN ("
                      + ",".join(str(i) for i in sample_ids) + ")")
        _purge(api_client, pid)
        residue = sql_query(
            "SELECT count(*) FROM tp_training_sample WHERE case_id NOT IN "
            "(SELECT id FROM tp_test_case)")
        assert int(residue) == 0, f"样本残留未清: {residue}"
