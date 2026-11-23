
import io
import time
import uuid

import httpx
import openpyxl
import pytest

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, api_login, assert_no_errors, inject_token, nav_to

from playwright.sync_api import expect


CASE_HEADERS = ["测试目标", "前置条件", "预期结果", "优先级", "置信度", "终端类型", "步骤"]


def _import_1000(client, pid, prefix):
    pass
    wb = openpyxl.Workbook()
    ws = wb.active if wb.active is not None else wb.create_sheet()
    ws.append(CASE_HEADERS)
    for i in range(1000):
        ws.append([f"{prefix}用例_{i}", "前置", "预期", i % 3, (i % 3) + 1, "web", "打开=>展示"])
    buf = io.BytesIO()
    wb.save(buf)
    buf.seek(0)
    r = client.post(
        f"{API_BASE}/import/cases",
        params={"projectId": pid, "targetStatus": 1},
        files={"file": ("o4.xlsx", buf, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
        timeout=300,
    )
    assert r.status_code == 200, f"导入失败: {r.status_code} {r.text[:200]}"
    d = r.json().get("data") or {}
    assert d.get("successCount") == 1000, f"导入未满 1000: {d}"


@pytest.fixture(scope="module")
def o4_env():
    pass
    uniq = "o4" + uuid.uuid4().hex[:8]
    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    client = httpx.Client(base_url=API_BASE, headers={"Authorization": f"Bearer {token}"}, timeout=60)
    r = client.post("/projects", json={"name": f"O4虚拟滚动_{uniq}", "code": f"O4{uniq[2:].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:200]}"
    pid = r.json()["data"]
    _import_1000(client, pid, f"O4{uniq}")
    yield pid, uniq
    client.delete(f"/projects/{pid}")
    client.close()


def _goto_virtual_1000(page, uniq):
    pass
    inject_token(page)
    nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
    page.locator('[data-test="project-ctx-select"]').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=f"O4虚拟滚动_{uniq}").first.click()
    first = page.locator('[data-test="case-table"] tbody tr').first
    expect(first).to_be_visible(timeout=15000)
    page.locator('[data-test="case-pagination"] .el-pagination__sizes .el-select').click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text="1000").first.click()
    expect(page.locator('[data-test="case-table"] tbody tr.virtual-spacer-row').first).to_be_visible(timeout=20000)


def _rows(page):
    return page.locator('[data-test="case-table"] tbody tr')


def test_o4_virtual_window_render(logged_in, collectors, o4_env):
    pass
    page = logged_in
    _, uniq = o4_env
    _goto_virtual_1000(page, uniq)
    expect(page.locator('[data-test="case-pagination"] .el-pagination__total')).to_contain_text("1000")
    n = _rows(page).count()
    assert 2 < n < 150, f"虚拟滚动未生效：DOM 行数={n}（期望窗口 ~视口+缓冲 << 1000）"
    expect(page.locator('[data-test^="case-no-"]').first).to_be_visible()
    assert_no_errors(collectors, "O4 窗口化渲染")


def test_o4_scroll_to_last(logged_in, collectors, o4_env):
    pass
    page = logged_in
    pid, uniq = o4_env
    _goto_virtual_1000(page, uniq)

    def first_row_text():
        return page.evaluate(
            """() => {
                const tr = Array.from(document.querySelectorAll('[data-test="case-table"] tbody tr'))
                    .find(r => !r.classList.contains('virtual-spacer-row'))
                return tr ? tr.innerText.slice(0, 60) : ''
            }"""
        )

    token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
    with httpx.Client(base_url=API_BASE, headers={"Authorization": f"Bearer {token}"}, timeout=60) as c:
        lst = c.get("/cases", params={"projectId": pid, "page": 1, "size": 1000}).json()["data"]["list"]
    first_title, last_title = lst[0]["title"], lst[-1]["title"]

    top_text = first_row_text()
    assert first_title in top_text.replace("\n", ""), f"首窗首行与服务端顺序不符: {top_text!r} vs {first_title!r}"
    page.evaluate("() => { const el = document.querySelector('.layout-main'); el.scrollTop = el.scrollHeight }")
    expect(page.locator('[data-test="case-table"]').get_by_text(last_title, exact=False).first).to_be_visible(timeout=5000)
    bottom_text = first_row_text()
    assert bottom_text != top_text, "滚到底窗口未重算（首行未变化）"
    t0 = time.time()
    page.evaluate("() => { document.querySelector('.layout-main').scrollTop = 0 }")
    page.wait_for_function(
        "(t) => { const tr = Array.from(document.querySelectorAll('[data-test=\"case-table\"] tbody tr'))"
        ".find(r => !r.classList.contains('virtual-spacer-row')); return !!tr && tr.innerText.includes(t) }",
        arg=first_title,
        timeout=5000)
    assert time.time() - t0 < 3.0, "滚动回顶后窗口重算超 3s（卡顿）"
    assert_no_errors(collectors, "O4 滚动到底")


def test_o4_scroll_preserved_reload(logged_in, collectors, o4_env):
    pass
    page = logged_in
    _, uniq = o4_env
    _goto_virtual_1000(page, uniq)
    page.evaluate("() => { const el = document.querySelector('.layout-main'); el.scrollTop = 8000 }")
    expect(page.locator('[data-test="case-table"] tbody tr.virtual-spacer-row').first).to_be_visible(timeout=5000)
    before = page.evaluate("() => document.querySelector('.layout-main').scrollTop")
    assert before > 1000, f"滚动未生效 scrollTop={before}"
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=20000)
    after = page.evaluate("() => document.querySelector('.layout-main').scrollTop")
    assert abs(after - before) < 200, f"reload 后滚动位置丢失: before={before} after={after}"
    assert_no_errors(collectors, "O4 滚动位置 reload 保留")
