
import json
import subprocess

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_project_in_header,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)


def _sql(q):
    r = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc", q],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"sql failed: {r.stderr[:200]}"
    return r.stdout.strip()


def _seed(api_client, uniq):
    r = api_client.post("/projects", json={"name": f"T412默认值项目{uniq}", "code": f"T412{uniq[:6].upper()}", "tags": ""})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    r = api_client.post("/apitest/collections",
                        json={"projectId": pid, "parentId": 0, "name": f"默认值接口集{uniq}", "tags": "keep-me"})
    assert r.json().get("code") == "0", f"建接口集失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]
    return pid, cid


def _open_defaults_dialog(page, coll_text):
    card = page.locator('[data-test^="apitest-collection-card-"]').filter(has_text=coll_text).first
    expect(card).to_be_visible(timeout=10000)
    card.click()
    # trigger lives on the card's more-button (el-dropdown root click would miss it)
    card.locator('.ap-more-btn').click()
    # data-test on EP dropdown items is unreliable across poppers — text anchor is the
    # house pattern (test_t166 precedent)
    page.locator('.el-dropdown-menu__item:visible', has_text="默认值设置").first.click()
    expect(page.locator('[data-test="apitest-defaults-dialog"]')).to_be_visible()


def test_t412_collection_defaults_persist(page, collectors, api_client, uniq):
    pass
    pid, cid = _seed(api_client, uniq)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, f"T412默认值项目{uniq}")

        # 2-click reach + fill one header row
        _open_defaults_dialog(page, f"默认值接口集{uniq}")
        page.locator('[data-test="apitest-defaults-header-add"]').click()
        row = page.locator('[data-test="apitest-defaults-header-row-0"]')
        row.locator("input").nth(0).fill("X-T412-E2E")
        row.locator("input").nth(1).fill("persist-check")
        page.locator('[data-test="apitest-defaults-save-btn"]').click()
        expect(page.locator(".el-message--success")).to_be_visible(timeout=8000)

        # reload persistence: reopen the dialog, the stored row must be there
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, f"T412默认值项目{uniq}")
        _open_defaults_dialog(page, f"默认值接口集{uniq}")
        name_box = page.locator('[data-test="apitest-defaults-header-row-0"] input').nth(0)
        expect(name_box).to_have_value("X-T412-E2E", timeout=8000)
        expect(page.locator('[data-test="apitest-defaults-header-row-0"] input').nth(1)).to_have_value("persist-check")

        # structural DB assertion (jsonb text spacing is not stable — parse it)
        raw = _sql(f"SELECT headers_json::text FROM tp_apitest_collection WHERE id = {cid}")
        stored = json.loads(raw)
        assert {"name": "X-T412-E2E", "value": "persist-check"} in stored, f"DB 行缺该头: {raw[:200]}"
        tags = _sql(f"SELECT tags FROM tp_apitest_collection WHERE id = {cid}")
        assert tags == "keep-me", f"defaults 保存擦掉了 tags: {tags!r}"

        assert_no_errors(collectors, "T412 collection defaults persist")
    finally:
        # purge cascades collections/requests/project in one real-API call
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code == 200, f"清理项目失败: {r.text[:200]}"
