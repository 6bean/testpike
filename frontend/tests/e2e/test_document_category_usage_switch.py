
import os
import tempfile
import time

import httpx
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    API_BASE,
    BASE_URL,
    assert_no_errors,
    inject_user_token,
)
from issues_monitor import assert_no_browser_issues

DOC_PAGE = f"{BASE_URL}/#/extraction?tab=documents"


def upload_doc(api_token, project_id, name, body, category=None):
    """Upload one markdown document through the real multipart endpoint."""
    data = {"projectId": str(project_id)}
    if category:
        data["category"] = category
    r = httpx.post(
        f"{API_BASE}/ai/documents",
        headers={"Authorization": f"Bearer {api_token}"},
        files={"file": (name, body.encode("utf-8"), "text/markdown")},
        data=data,
        timeout=60,
    )
    assert r.status_code == 200, f"upload {name} -> {r.status_code}: {r.text[:200]}"
    payload = r.json()
    assert payload.get("code") == "0", f"upload biz error: {payload}"
    return payload["data"]


def open_select(page, sel):
    """Open an Element Plus select and wait until its options are readable.

    The toggle lives on .el-select__wrapper in the EP2 DOM; clicking the
    component root raced the teleported dropdown and read zero items, so the
    wrapper is preferred whenever it exists.
    """
    wrapper = sel.locator(".el-select__wrapper")
    if wrapper.count():
        wrapper.first.click()
    else:
        sel.first.click()
    items = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
    expect(items.first).to_be_visible(timeout=5000)
    return items


def open_filter_select(page, test_id):
    return open_select(page, page.locator(f'[data-test="{test_id}"]').first)


def close_select(page):
    page.keyboard.press("Escape")


def search(page):
    page.locator('[data-test="doc-search-btn"]').click()


def keyword_rows(page, token):
    """Filter by keyword so exactly one fixture row is on screen."""
    kw = page.locator('[data-test="doc-filter-keyword"]')
    kw.fill("")
    kw.fill(token)
    search(page)


def select_option_by_label(page, items, label):
    for i in range(items.count()):
        item = items.nth(i)
        # Match the display-name line; options may carry a second
        # description line that closed selects never show.
        if item.inner_text().strip().split("\n")[0].strip() == label:
            item.click()
            return
    raise AssertionError(f"option {label!r} not found among {items.count()} items")


def category_labels(page):
    """Read the four category option names from the filter dropdown.

    Options render a second description line for categories that carry one;
    the display name used by closed selects and table cells is the first
    line only. The description line is asserted here so a dropped effect
    sentence fails the spectrum (no conditionally-wrapped rendering checks).
    """
    items = open_filter_select(page, "doc-filter-category")
    texts = [items.nth(i).inner_text().strip() for i in range(items.count())]
    labels = [t.split("\n")[0].strip() for t in texts]
    # Requirement docs and reference docs both carry an effect sentence; the
    # sentence must survive in the open dropdown or the spectrum fails.
    assert len(labels[1]) < len(texts[1]), f"requirement-doc option lost its effect line: {texts[1]!r}"
    assert len(labels[3]) < len(texts[3]), f"reference option lost its effect line: {texts[3]!r}"
    close_select(page)
    # DOC_CATEGORIES order is fixed on both sides: TEST_SPEC, REQUIREMENT_DOC,
    # DDL_DICT, REFERENCE (core DocumentServiceImpl.CATEGORIES is the value source)
    assert len(labels) == 4, f"category option set drifted: {labels}"
    return {
        "TEST_SPEC": labels[0],
        "REQUIREMENT_DOC": labels[1],
        "DDL_DICT": labels[2],
        "REFERENCE": labels[3],
    }


def row_by_keyword(page):
    return page.locator('[data-test="doc-table"] tbody tr')


def test_document_category_usage_switch(page, collectors, api_client, uniq):
    """Worst-scale rows + filter + inline category edit + switch toggle + upload class."""
    pid = None
    try:
        # -- fixture: throwaway project, documents across all four categories
        r = api_client.post(
            "/projects",
            json={"name": f"E2E用途开关{uniq}", "code": f"EUS{uniq[:8]}"},
        )
        assert r.json().get("code") == "0", f"create project failed: {r.text[:300]}"
        pid = r.json()["data"]

        from conftest import api_login

        token = api_login("admin", ADMIN_PASSWORD)
        plans = (
            [(f"e2e-ref-{i}-{uniq}.md", "REFERENCE") for i in range(15)]
            + [(f"e2e-spec-{i}-{uniq}.md", "TEST_SPEC") for i in range(14)]
            + [(f"e2e-ddl-{i}-{uniq}.md", "DDL_DICT") for i in range(2)]
            # requirement docs are the source documents that drive requirement
            # points and cases downstream; two of them keep the segment small
            # enough to assert the exact filtered row count
            + [(f"e2e-req-{i}-{uniq}.md", "REQUIREMENT_DOC") for i in range(2)]
        )
        for name, category in plans:
            body = (
                f"# {name}\n\n"
                f"Unique token {name}-{uniq}. Section one: ordering service "
                "checkout flow with a 15 minute payment timeout.\n\n"
                "## Section two\n\nRefund within 7 days is allowed.\n"
            )
            upload_doc(token, pid, name, body, category)

        # -- deep link into the documents tab of /extraction, fresh app init
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        page.goto(f"{DOC_PAGE}&projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)

        labels = category_labels(page)

        # 1) worst scale: every fixture row exists behind pagination, not one flat wall
        pagination = page.locator('[data-test="doc-pagination"]')
        expect(pagination).to_be_visible()
        expect(pagination).to_contain_text(str(len(plans)))
        rendered = row_by_keyword(page)
        assert rendered.count() > 0, "no rows rendered at worst scale"

        # 2) category filter: REQUIREMENT_DOC keeps only the source documents
        items = open_filter_select(page, "doc-filter-category")
        select_option_by_label(page, items, labels["REQUIREMENT_DOC"])
        search(page)
        req_rows = row_by_keyword(page)
        expect(req_rows).to_have_count(2)
        req_cells = page.locator('[data-test="doc-table"] [data-test="doc-category-edit"]')
        expect(req_cells).to_have_count(2)
        for i in range(req_cells.count()):
            text = req_cells.nth(i).inner_text().strip()
            assert text == labels["REQUIREMENT_DOC"], f"requirement row {i} shows {text!r}"

        # 2b) category filter: REFERENCE keeps only reference rows
        items = open_filter_select(page, "doc-filter-category")
        select_option_by_label(page, items, labels["REFERENCE"])
        search(page)
        expect(page.locator('[data-test="doc-status-tag"]').first).to_be_visible()
        ref_rows = row_by_keyword(page)
        # 15 REFERENCE docs, none beyond the default page size
        expect(ref_rows).to_have_count(15)
        cells = page.locator('[data-test="doc-table"] [data-test="doc-category-edit"]')
        expect(cells).to_have_count(15)
        for i in range(cells.count()):
            text = cells.nth(i).inner_text().strip()
            assert text == labels["REFERENCE"], f"filtered row {i} shows {text!r}"

        # 3) inline usage-switch toggle on the first filtered row + reload persistence
        usage_input = row_by_keyword(page).first.locator(
            '[data-test="doc-usage-grounding"] input'
        )
        was_checked = usage_input.is_checked()
        row_by_keyword(page).first.locator('[data-test="doc-usage-grounding"]').click()
        # the PATCH + list refresh drives the DOM state: assert the flipped value
        # with a web-first wait instead of a fixed sleep
        if was_checked:
            expect(usage_input).not_to_be_checked(timeout=10000)
        else:
            expect(usage_input).to_be_checked(timeout=10000)
        now_checked = usage_input.is_checked()
        assert now_checked != was_checked, "usage switch did not change on click"

        page.goto(f"{DOC_PAGE}&projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        items = open_filter_select(page, "doc-filter-category")
        select_option_by_label(page, items, labels["REFERENCE"])
        search(page)
        persisted = row_by_keyword(page).first.locator(
            '[data-test="doc-usage-grounding"] input'
        ).is_checked()
        assert persisted == now_checked, "switch state lost after reload (not persisted)"

        # 4) inline category edit: move a TEST_SPEC fixture doc to DDL_DICT
        # fresh app init first: the REFERENCE segment left active by step 3 would
        # otherwise filter the TEST_SPEC target row out of view (0 rows red)
        page.goto(f"{DOC_PAGE}&projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        edited_name = plans[15][0].removesuffix(".md")  # tp_document.name drops the extension (fileType carries it), so a ".md" keyword can never match
        keyword_rows(page, edited_name)
        edit = row_by_keyword(page).first.locator('[data-test="doc-category-edit"]')
        expect(row_by_keyword(page)).to_have_count(1)
        items = open_select(page, edit)
        select_option_by_label(page, items, labels["DDL_DICT"])
        expect(edit).to_contain_text(labels["DDL_DICT"], timeout=10000)

        page.goto(f"{DOC_PAGE}&projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        keyword_rows(page, edited_name)
        moved = row_by_keyword(page).first.locator('[data-test="doc-category-edit"]').inner_text().strip()
        assert moved == labels["DDL_DICT"], f"category edit not persisted: {moved!r}"

        # 5) upload dialog carries the category pick (reference class end to end)
        with tempfile.TemporaryDirectory() as tmp:
            up_name = f"e2e-up-{uniq}.md"
            path = os.path.join(tmp, up_name)
            with open(path, "w", encoding="utf-8") as f:
                f.write(f"# 上传选类夹具 {uniq}\n\n正文：支付超时 15 分钟。\n")
            page.locator('[data-test="doc-upload-btn"]').first.click()
            dialog = page.locator('[data-test="file-upload-dialog"]')
            expect(dialog).to_be_visible(timeout=5000)
            items = open_select(page, dialog.locator('[data-test="doc-category-select"]'))
            select_option_by_label(page, items, labels["REFERENCE"])
            dialog.locator(".el-upload__input").set_input_files(path)
            dialog.locator('[data-test="file-upload-submit"]').click()
            expect(page.locator('[data-test="file-upload-dialog"]')).to_be_hidden(timeout=30000)
        keyword_rows(page, f"e2e-up-{uniq}")
        up_cell = row_by_keyword(page).first.locator('[data-test="doc-category-edit"]').inner_text().strip()
        assert up_cell == labels["REFERENCE"], f"uploaded doc category wrong: {up_cell!r}"

        # -- three zero-tolerance monitors over the whole journey
        assert_no_errors(collectors, "document category usage switch")
        assert_no_browser_issues(collectors["browser_issues"], "document category usage switch")
    finally:
        if pid:
            # The project purge does not cascade tp_document rows: drop every
            # fixture document through the real API first (chunk rows follow via
            # FK CASCADE), then purge the throwaway project -- the teardown
            # posture pinned by the test_i6_documents precedent.
            r = api_client.get(f"/ai/documents?projectId={pid}&page=1&size=200")
            for d in (r.json().get("data") or {}).get("list", []):
                api_client.delete(f"/ai/documents/{d['id']}")
            api_client.delete(f"/projects/{pid}/purge?force=true")
            # purge settles asynchronously after the DELETE response (
            # read 31 stale rows in the same second, project gone minutes
            # later): poll to zero instead of asserting on one cold read.
            total = None
            for _ in range(20):
                r = api_client.get(f"/ai/documents?projectId={pid}&page=1&size=5")
                total = (r.json().get("data") or {}).get("total")
                if total == 0:
                    break
                time.sleep(0.5)
            assert total == 0, f"documents survived the project purge: total={total}"
