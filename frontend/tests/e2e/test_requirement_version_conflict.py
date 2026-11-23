
from playwright.sync_api import expect

from conftest import nav_to, row_cmd, assert_no_errors, whitelist_expected_reject


def _mk_project(api, uniq):
    name = f"ALV项目_{uniq}"
    code = f"ALV{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"], name


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={
        "source": 3, "sourceId": f"SRC-{title}", "title": title,
        "acceptanceCriteria": f"验收标准：{title}",
    })
    assert r.status_code == 200, f"创建需求点失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建需求点业务失败: {body.get('msg')}"
    return body["data"]


def _select_project(page, project_name):
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(
        ".el-select-dropdown:visible .el-select-dropdown__item:visible"
    ).filter(has_text=project_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()


def _open_req_page(page):
    # Requirement ledger re-anchored: standalone /requirements page,
    # the menu leaf navigates straight to it.
    nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]', submenu="设计用例")
    expect(page.locator('[data-test="project-ctx-select"]')).to_be_visible(timeout=10000)


def test_stale_edit_names_versions_and_reloads(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    req = _mk_requirement(api_client, pid, f"版本冲突需求_{uniq}")
    rid = req["id"]
    assert req.get("version") == 1, f"创建响应基线版本应为 1，实际 {req.get('version')}"

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    row = page.locator(
        f'[data-test="req-table"] tbody tr.el-table__row:has-text("版本冲突需求_{uniq}")')
    row_cmd(page, row, "edit")
    expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()

    # Another actor updates the same row while this dialog is open (baseline v1).
    ext = api_client.put(f"/projects/{pid}/requirements/{rid}", json={
        "title": f"外部改动_{uniq}", "expectedVersion": 1})
    assert ext.status_code == 200, f"外部更新应成功: {ext.status_code} {ext.text[:200]}"
    assert ext.json()["data"]["version"] == 2, "外部更新后当前版本应为 v2"

    page.locator('[data-test="req-title"]').fill(f"页内改动_{uniq}")
    page.locator('[data-test="req-submit"]').click()

    box = page.locator(".el-message-box")
    expect(box).to_be_visible(timeout=5000)
    expect(box).to_contain_text("版本冲突")
    expect(box).to_contain_text("当前版本 v2")
    expect(box).to_contain_text("你提交的基线 v1")
    # W-group: irreversible confirmation must name the consequence — reload
    # discards the unsaved in-dialog edits, spelled out in the dialog body.
    expect(box).to_contain_text("未保存的修改会被丢弃")
    box.get_by_role("button", name="刷新重载").click()

    # The reload branch re-fetched the list under the dialog: the external
    # title is now what the table shows.
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"外部改动_{uniq}", timeout=8000)

    # The in-page stale submit is THIS spectrum's designed outcome: exactly one
    # HTTP 409 on the row endpoint whose body carries business code 40901 (the
    # human line naming v2 vs v1 is already pinned via the dialog above).
    # Ground the exemption on the observed response, then drop only this
    # endpoint's expected conflict from the zero-tolerance collectors.
    conflicts = [r for r in collectors["responses"]
                 if r["status"] == 409 and r["url"].endswith(f"/requirements/{rid}")]
    assert len(conflicts) == 1, f"应恰好 1 次设计内冲突 409，实际 {len(conflicts)}"
    assert "40901" in (conflicts[0]["body"] or ""), \
        f"冲突响应体应带业务码 40901：{conflicts[0]['body']}"
    assert whitelist_expected_reject(collectors, f"/requirements/{rid}") == 1, \
        "预期冲突应从 network_4xx 精确摘除 1 条"
    assert_no_errors(collectors, "requirement stale-edit conflict")


def test_normal_edit_persists_and_version_advances(logged_in, api_client, collectors, uniq):
    """Uncontested edit: dialog baseline matches the row -> update succeeds,
    revision advances, change survives a full reload."""
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    _mk_requirement(api_client, pid, f"正常编辑需求_{uniq}")

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    row = page.locator(
        f'[data-test="req-table"] tbody tr.el-table__row:has-text("正常编辑需求_{uniq}")')
    row_cmd(page, row, "edit")
    expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()
    page.locator('[data-test="req-title"]').fill(f"正常编辑需求改_{uniq}")
    page.locator('[data-test="req-submit"]').click()
    expect(page.locator(".el-message").filter(has_text="需求点更新成功").last).to_be_visible(timeout=5000)

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    expect(page.locator('[data-test="req-table"]')).to_contain_text(f"正常编辑需求改_{uniq}")

    listing = api_client.get(f"/projects/{pid}/requirements").json()["data"]
    row_view = [x for x in listing["list"] if x["title"] == f"正常编辑需求改_{uniq}"]
    assert len(row_view) == 1, f"列表应恰含改后标题一行，实际 {len(row_view)}"
    assert row_view[0]["version"] == 2, f"编辑后版本应递增为 2，实际 {row_view[0]['version']}"
    assert_no_errors(collectors, "requirement normal-edit persistence")


def test_submit_double_click_issues_one_put(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    req = _mk_requirement(api_client, pid, f"防双击需求_{uniq}")

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)
    row = page.locator(
        f'[data-test="req-table"] tbody tr.el-table__row:has-text("防双击需求_{uniq}")')
    row_cmd(page, row, "edit")
    expect(page.locator('[data-test="req-edit-dialog"]')).to_be_visible()
    page.locator('[data-test="req-title"]').fill(f"防双击需求改_{uniq}")

    puts = []
    page.on("request",
            lambda r: puts.append(r.url)
            if r.method == "PUT" and f"/requirements/{req['id']}" in r.url else None)
    submit = page.locator('[data-test="req-submit"]')
    submit.click()
    # Second click inside the guard window: the button is disabled or the
    # dialog already closed. The hard contract asserted below is the count.
    try:
        page.locator('[data-test="req-submit"]').click(timeout=800)
    except Exception:
        pass

    expect(page.locator(".el-message").filter(has_text="需求点更新成功").last).to_be_visible(timeout=8000)
    assert len(puts) == 1, f"双击窗口内应只发 1 次 PUT，实际 {len(puts)}: {puts}"
    assert_no_errors(collectors, "requirement submit double-click guard")


def test_worst_case_scale_pagination(logged_in, api_client, collectors, uniq):
    """35 rows fed through the real list: page size 10 honored, no inline
    full dump, flipping to page 2 changes the rendered content."""
    page = logged_in
    pid, pname = _mk_project(api_client, uniq)
    for i in range(1, 36):
        _mk_requirement(api_client, pid, f"规模{i:02d}_{uniq}")

    page.reload(wait_until="domcontentloaded")
    _open_req_page(page)
    _select_project(page, pname)

    rows = page.locator('[data-test="req-table"] tbody tr.el-table__row')
    expect(rows).to_have_count(10, timeout=15000)
    expect(page.locator('[data-test="req-pagination"]')).to_be_visible()
    first_page_anchor = page.locator('[data-test="req-table"] tbody tr.el-table__row').first.inner_text()

    page.locator('[data-test="req-pagination"] .el-pager li').filter(has_text="2").first.click()
    expect(rows).to_have_count(10, timeout=15000)
    second_page_anchor = page.locator('[data-test="req-table"] tbody tr.el-table__row').first.inner_text()
    assert second_page_anchor != first_page_anchor, "翻到第 2 页内容必须变化"

    # Server default sort is ORDER BY id DESC (RequirementServiceImpl SORT
    # whitelist tail). The 35 fixture rows are newest-first, so page 1 shows
    # the run-scoped suffix instead of a bare prefix: page 2 must contain the
    # 11th-newest row, must not repeat the page-1 head, must not bleed into
    # the page-3 head.
    table = page.locator('[data-test="req-table"]')
    expect(table).to_contain_text(f"规模25_{uniq}", timeout=8000)
    page2_text = table.inner_text()
    assert f"规模35_{uniq}" not in page2_text, "第 2 页不得重复第 1 页首行"
    assert f"规模15_{uniq}" not in page2_text, "第 2 页不得越界混入第 3 页行"
    assert_no_errors(collectors, "requirement worst-case scale pagination")
