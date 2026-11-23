
import re

from playwright.sync_api import expect

from conftest import assert_no_errors, inject_token, nav_to



def _mk_project(api, code, name):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "预期",
        "priority": 2, "confidence": 2, "endType": "web", "phase": 1, "source": 2, "status": 1,
        "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _goto_cases(page, proj_name):
    pass
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=proj_name).first.click()
    expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)


def _headers(page):
    return page.locator('[data-test="case-table"] thead th .cell')


def _col_header(page, label):
    pass
    return page.locator('[data-test="case-table"] thead th').filter(
        has=page.locator(f".cell:text-is(\"{label}\")"))


def _open_settings(page):
    page.locator('[data-test="column-settings-btn"]').click()
    pop = page.locator('[data-test="column-settings-pop"]:visible')
    expect(pop).to_be_visible(timeout=5000)
    return pop


def _reset(page):
    pop = _open_settings(page)
    pop.locator('[data-test="column-settings-reset"]').click()
    page.locator('[data-test="case-table"] thead th').first.wait_for(timeout=5000)


def test_o3_hide_column_persist(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O3A{uniq.upper()}", f"O3列配置项目_{uniq}")
    _mk_case(api_client, pid, f"O3列配置用例_{uniq}")
    inject_token(page)
    try:
        _goto_cases(page, f"O3列配置项目_{uniq}")
        expect(_col_header(page, "用例阶段")).to_have_count(1, timeout=5000)
        pop = _open_settings(page)
        pop.locator('label.el-checkbox[data-test="col-cfg-phase"]').click()
        expect(_col_header(page, "用例阶段")).to_have_count(0, timeout=5000)
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        page.locator("[data-test=project-ctx-select]").click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O3列配置项目_{uniq}").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        expect(_col_header(page, "用例阶段")).to_have_count(0, timeout=5000)
        _reset(page)
        expect(_col_header(page, "用例阶段")).to_have_count(1, timeout=5000)
        assert_no_errors(collectors, "O3 隐藏列持久化")
    finally:
        _reset_safe(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_o3_reorder_column_persist(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O3B{uniq.upper()}", f"O3调序项目_{uniq}")
    _mk_case(api_client, pid, f"O3调序用例_{uniq}")
    inject_token(page)
    try:
        _goto_cases(page, f"O3调序项目_{uniq}")

        def header_titles():
            return [h.inner_text().strip() for h in _headers(page).all() if h.inner_text().strip()]

        before = header_titles()
        assert before.index(next(x for x in before if "测试目标" in x)) < \
            before.index(next(x for x in before if "优先级" in x))
        pop = _open_settings(page)
        pop.locator('[data-test="col-cfg-priority-up"]').click()
        mid = header_titles()
        assert mid.index(next(x for x in mid if "优先级" in x)) < \
            mid.index(next(x for x in mid if "测试目标" in x)), f"上移后顺序未变: {mid}"
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        page.locator("[data-test=project-ctx-select]").click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O3调序项目_{uniq}").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        after = header_titles()
        assert after == mid, f"reload 后顺序丢失: {mid} -> {after}"
        assert_no_errors(collectors, "O3 列调序持久化")
    finally:
        _reset_safe(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_o3_width_drag_persist(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O3C{uniq.upper()}", f"O3列宽项目_{uniq}")
    _mk_case(api_client, pid, f"O3列宽用例_{uniq}")
    inject_token(page)
    try:
        _goto_cases(page, f"O3列宽项目_{uniq}")
        expect(page.locator(".el-select-dropdown:visible")).to_have_count(0, timeout=10000)
        th = page.locator('[data-test="case-table"] thead th').filter(has_text="编号").first
        box = th.bounding_box()
        assert box and box["width"] > 50
        start_w = box["width"]
        edge_x, mid_y = box["x"] + box["width"] - 4, box["y"] + box["height"] / 2
        new_w = start_w
        for _attempt in range(3):
            box = th.bounding_box()
            edge_x, mid_y = box["x"] + box["width"] - 4, box["y"] + box["height"] / 2
            page.mouse.move(edge_x - 10, mid_y)
            page.mouse.move(edge_x, mid_y)
            page.mouse.down()
            page.mouse.move(edge_x + 60, mid_y, steps=6)
            page.mouse.up()
            new_w = th.bounding_box()["width"]
            if new_w > start_w + 30:
                break
        assert new_w > start_w + 30, f"拖拽未生效: {start_w} -> {new_w}"
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        page.locator("[data-test=project-ctx-select]").click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O3列宽项目_{uniq}").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        th2 = page.locator('[data-test="case-table"] thead th').filter(has_text="编号").first
        w2 = th2.bounding_box()["width"]
        assert abs(w2 - new_w) < 10, f"reload 后列宽丢失: {new_w} -> {w2}"
        assert_no_errors(collectors, "O3 列宽拖拽持久化")
    finally:
        _reset_safe(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_o3_pin_and_density(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, f"O3D{uniq.upper()}", f"O3固定项目_{uniq}")
    _mk_case(api_client, pid, f"O3固定用例_{uniq}")
    inject_token(page)
    try:
        _goto_cases(page, f"O3固定项目_{uniq}")
        pop = _open_settings(page)
        pop.locator('[data-test="col-cfg-caseNo-pin"]').click()
        pop.locator("label.el-radio-button:has-text('紧凑')").click()
        th = page.locator('[data-test="case-table"] thead th').filter(has_text="编号").first
        expect(th).to_have_class(re.compile("fixed-column--left"), timeout=5000)
        expect(page.locator('[data-test="case-table"]')).to_have_class(re.compile("el-table--small"), timeout=5000)
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        page.locator("[data-test=project-ctx-select]").click()
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O3固定项目_{uniq}").first.click()
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        th2 = page.locator('[data-test="case-table"] thead th').filter(has_text="编号").first
        expect(th2).to_have_class(re.compile("fixed-column--left"), timeout=5000)
        expect(page.locator('[data-test="case-table"]')).to_have_class(re.compile("el-table--small"), timeout=5000)
        assert_no_errors(collectors, "O3 固定列+密度持久化")
    finally:
        _reset_safe(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


def _reset_safe(page):
    pass
    try:
        page.evaluate("() => { for (const k of Object.keys(localStorage)) if (k.startsWith('tp-colcfg-')) localStorage.removeItem(k) }")
    except Exception:
        pass

