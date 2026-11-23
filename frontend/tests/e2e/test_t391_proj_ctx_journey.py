"""Header global project context journey (; re-anchored to the
 single-select contract — ruling doc 2026-10-15).

Contract change : the header picker is single-select. The old
multi-select posture (P+Q aggregate IN mode, '+N' summary popover) is
retired: picking a project in the header takes effect immediately and the
dropdown closes on the pick. The four asserts below exercise the surviving
semantics:
1. pick P in the header -> the summary shows the project name directly
 (no '+N'), #/tags lands on the P segment (Q's tag absent);
2. header switch to Q (the in-page project selector on the tags page was
 retired by T396b — the page is a pure consumer of the global context) ->
 the segment follows and the header echo shows Q;
3. reload persistence = localStorage tp_proj_ctx_<userId> holds [Q],
 the whole page reload needs no re-pick;
4. #/tags?projectId=P explicit param wins on entry and the deep link
 lands the global context on [P] (10-14 IA ruling posture kept).

Data created and purged through the real API only; uniq suffixes keep the
demo projects clean. 17 doc TC-V16-02 / 18 doc IT-PROJ-15.
"""
import json

from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    BASE_URL,
    assert_no_errors,
    inject_user_token,
)
from playwright.sync_api import expect

TAGS_URL = f"{BASE_URL}/#/tags"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_tag(api, pid, name):
    r = api.post("/tags", json={"projectId": pid, "name": name, "type": 4})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _header_summary(page):
    return page.locator('[data-test="project-ctx-trigger"]')


def _pick_in_header(page, project_name):
    """Single-select header pick: open the dropdown, click the option.
    A single-select el-select closes the dropdown on the pick and the
    change takes effect immediately (no second click to collapse)."""
    page.locator('[data-test="project-ctx-select"]').click()
    page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{project_name}"):visible').first.click()


def test_t391_proj_ctx_four_step_journey(logged_in, collectors, api_client, uniq):
    """Header single pick -> tags segment -> in-page switch echo -> reload persist -> deep link."""
    page = logged_in  # admin shared identity: read journey + create/purge projects only
    me = api_client.get("/users/me").json()["data"]
    uid = me["user"]["userId"]  # /users/me shape={user:{userId,...},projects,passwordExpired}
    ctx_key = f"tp_proj_ctx_{uid}"

    name_p = f"T391甲项目{uniq}"
    name_q = f"T391乙项目{uniq}"
    pid = _mk_project(api_client, name_p, f"T391P{uniq[:6].upper()}")
    qid = _mk_project(api_client, name_q, f"T391Q{uniq[:6].upper()}")
    tag_p = _mk_tag(api_client, pid, f"T391标签甲{uniq[:6]}")
    tag_q = _mk_tag(api_client, qid, f"T391标签乙{uniq[:6]}")
    try:
        # Full reload to align the identity snapshot, land on the dashboard so
        # the header is mounted and loadForUser has run.
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)

        # ── assert 1: header single pick of P takes effect immediately ──
        # The '+N' summary popover and the aggregate column are retired under
        # the single-select contract; the trigger shows the picked name only.
        _pick_in_header(page, name_p)
        expect(_header_summary(page)).to_contain_text(name_p, timeout=10000)
        expect(_header_summary(page)).not_to_contain_text("+1")

        page.goto(TAGS_URL, wait_until="domcontentloaded")
        expect(page.locator('[data-test="tag-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="tag-name-{tag_p}"]')).to_be_visible(timeout=15000)
        # P segment only: Q's tag never appears under a single-select context
        expect(page.locator(f'[data-test="tag-name-{tag_q}"]')).to_have_count(0)

        # ── assert 2: header switch to Q -> segment + echo follow (the in-page
        # project selector on the tags page was retired by T396b: this page is
        # a pure consumer of the global header context) ──
        _pick_in_header(page, name_q)
        expect(page.locator(f'[data-test="tag-name-{tag_q}"]')).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="tag-name-{tag_p}"]')).to_have_count(0)
        expect(_header_summary(page)).to_contain_text(name_q)
        expect(_header_summary(page)).not_to_contain_text(name_p)

        # ── assert 3: reload persistence (tp_proj_ctx_<uid> = current pick) ──
        stored = page.evaluate(f"() => localStorage.getItem('{ctx_key}')")
        assert json.loads(stored) == [qid], f"上下文未按用户键持久: {stored!r}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(f'[data-test="tag-name-{tag_q}"]')).to_be_visible(timeout=15000)
        expect(_header_summary(page)).to_contain_text(name_q)

        # ── assertion 4: explicit ?projectId=P wins on entry; the URL keeps P
        # and the deep link selects the project globally (store -> [P]).
        # Per the 10-14 IA ruling a deep link lands in the global context —
        # cross-page links no longer load a dead local state. Authority: the
        # mock information-architecture fix doc.
        page.goto(f"{TAGS_URL}?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(f'[data-test="tag-name-{tag_p}"]')).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="tag-name-{tag_q}"]')).to_have_count(0)
        # the URL is not rewritten by write-back (still P); the on-disk context
        # lands on [P] via the deep link (global-sync posture)
        assert f"projectId={pid}" in page.url, f"显式参数被改写: {page.url}"
        stored_after = page.evaluate(f"() => localStorage.getItem('{ctx_key}')")
        assert json.loads(stored_after) == [pid], f"深链未落全局上下文: {stored_after!r}"

        assert_no_errors(collectors, "T391 头部项目上下文四断言旅程（单选契约）")
    finally:
        for tid in (tag_p, tag_q):
            api_client.delete(f"/tags/{tid}")
        for p in (pid, qid):
            api_client.delete(f"/projects/{p}/purge")
