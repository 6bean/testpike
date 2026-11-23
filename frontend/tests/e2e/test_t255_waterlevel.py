
import re
import time

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
)
from playwright.sync_api import expect
from test_v11_process_cancel import _mk_case, _trial_run, _wait_status

pytestmark = pytest.mark.timeout(420)

KEY_MAX = "exec.parallel.max"
DANGER = re.compile("el-tag--danger")


def _sys_config(api, key):
    rows = api.get("/configs").json()["data"]
    if not isinstance(rows, list):
        rows = rows.get("list") or []
    return next(x for x in rows if x["configKey"] == key)


def _set_max(api, cfg, value):
    r = api.put(f"/configs/{cfg['id']}", json={
        "configKey": KEY_MAX, "configValue": str(value), "groupName": cfg["groupName"],
        "valueType": "int", "encryptFlag": 0,
        "description": cfg.get("description") or "I5 并行度上限"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"热更失败: {r.text[:200]}"


def test_t255_waterlevel_bar_full(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    t1 = None
    cfg = _sys_config(api_client, KEY_MAX)
    orig = cfg["configValue"]
    try:
        r = api_client.get("/executors/parallel-water-level")
        assert r.status_code == 200, f"水位端点异常: {r.status_code}"
        body = r.json()["data"]
        assert {"globalRunning", "globalMax", "perProjectMax", "projects"} <= set(body), \
            f"水位契约漂移: {body}"

        pid = api_client.post("/projects", json={
            "name": f"水位E2E{uniq}", "code": f"WL{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"水位环境{uniq}", "envType": 2,
            "baseUrl": "http://localhost:8899/"})
        case_id = _mk_case(api_client, pid, f"水位用例{uniq}")
        t1 = _trial_run(api_client, pid, case_id)
        _wait_status(api_client, t1, lambda s: s == 2, 120, "被活体 runner 领取执行中")

        _set_max(api_client, cfg, 1)

        inject_token(page)
        # merge: process view now rides the execution page tab.
        nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
        page.locator('[data-test="execution-tab-process"]').first.click()
        bar = page.locator('[data-test="process-waterlevel"]')
        expect(bar).to_be_visible(timeout=15000)
        g = page.locator('[data-test="water-global"]')
        expect(g).to_contain_text("上限 1", timeout=10000)
        expect(g).to_have_class(DANGER)
        proj_tag = page.locator('[data-test="water-project-item"]').filter(has_text=f"#{pid}")
        expect(proj_tag.first).to_be_visible(timeout=10000)

        _set_max(api_client, cfg, orig)
        page.reload(wait_until="domcontentloaded")
        expect(bar).to_be_visible(timeout=15000)
        expect(g).to_contain_text("不限", timeout=10000)
        expect(g).not_to_have_class(DANGER)

        assert_no_errors(collectors, "t255 waterlevel bar")
    finally:
        _set_max(api_client, cfg, orig)
        if t1:
            api_client.post(f"/executors/tasks/{t1}/cancel")
        if pid:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:
                pass
            for _ in range(5):
                rows = api_client.get("/executors/running?page=1&size=50").json()["data"]["list"]
                if not [x for x in rows if x["projectId"] == pid]:
                    break
                time.sleep(2)
