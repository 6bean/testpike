
import httpx
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, API_BASE, BASE_URL, assert_no_errors, inject_user_token


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"AL血缘{uniq}", "code": f"ALX{uniq[:6].upper()}"})
    assert r.status_code == 200, f"创建项目失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"创建项目业务失败: {body.get('msg')}"
    return body["data"]


def _upload(api, pid, title, content: bytes):
    r = httpx.post(f"{API_BASE}/ai/documents", headers=api.headers,
                   files={"file": (f"{title}.md", content, "text/markdown")},
                   data={"projectId": str(pid)}, timeout=60)
    assert r.status_code == 200, f"上传文档失败: {r.status_code} {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"上传文档业务失败: {str(body)[:200]}"
    assert body["data"]["status"] == 1, f"解析未成功: {str(body['data'])[:200]}"
    return body["data"]["id"]


def _md(title, marker):
    return (
        f"# {title}\n\n本文件为血缘列端到端夹具（{marker}）。\n\n"
        f"## 章节一\n\n下单流程说明，夹具编号 {marker}，内容随版本变化以通过查重门。\n"
    ).encode("utf-8")


def test_lineage_column_scale_and_group_ordinal(page, collectors, api_client, uniq):
    pass
    pid = _mk_project(api_client, uniq)

    # 30 single-version docs first, then the two versions of one logical doc:
    # the list sorts id DESC, so the lineage pair lands on the first two rows.
    for i in range(30):
        _upload(api_client, pid, f"血缘夹具文档{i:02d}{uniq}", _md(f"血缘夹具文档{i:02d}{uniq}", f"u{i:02d}"))
    twin = f"血缘双版文档{uniq}"
    _upload(api_client, pid, twin, _md(twin, "v1"))
    _upload(api_client, pid, twin, _md(twin, "v2 改版：新增风控前置"))

    # Deep link (documents leaf retired into the /extraction host tab with
    # query preserved; reload gives a clean initialization, i6/f3 pattern).
    inject_user_token(page, "admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)

    rows = page.locator('[data-test="doc-table"] tbody tr')
    # Worst-case scale: exactly one full page of 20 on page 1 (hard count).
    expect(rows).to_have_count(20, timeout=10000)

    # Lineage ordinal rendered from the server-computed fields (no client
    # re-derivation): newest twin row = 2nd of 2, previous = 1st of 2.
    expect(rows.nth(0)).to_contain_text("第 2 版（共 2 版）")
    expect(rows.nth(1)).to_contain_text("第 1 版（共 2 版）")
    # The remaining 18 rows on page 1 are untouched single-version docs.
    expect(page.locator('[data-test="doc-table"] .el-tag:has-text("首个版本")')).to_have_count(18)

    # Pagination contract: 32 total -> 2 pages; flip to the tail page.
    pager = page.locator('[data-test="doc-pagination"]')
    expect(pager).to_be_visible()
    expect(pager.locator(".el-pager li")).to_have_count(2)
    pager.locator(".el-pager li", has_text="2").first.click()
    expect(rows).to_have_count(12, timeout=10000)
    # The lineage pair left page 1: zero occurrences is itself the assertion.
    expect(page.locator('[data-test="doc-table"] .el-tag:has-text("版（共")')).to_have_count(0)
    expect(page.locator('[data-test="doc-table"] .el-tag:has-text("首个版本")')).to_have_count(12)

    # Persistence: a fresh reload recomputes from the database, page 1 is back
    page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(rows).to_have_count(20, timeout=10000)
    expect(rows.nth(0)).to_contain_text("第 2 版（共 2 版）")

    assert_no_errors(collectors, "document lineage column scale + ordinal")
