
import json
import os
import re
import subprocess
import time

import httpx
import pytest
from playwright.sync_api import TimeoutError as PwTimeoutError
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    executor_runner_control,
    probe_executor_runner_unit,
)

pytestmark = pytest.mark.timeout(600)


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    from conftest import ADMIN_PASSWORD, api_login
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台（/users/me 疑似网络抖动被踢回登录）")
    raise AssertionError("unreachable")


def create_console_dataset(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"I3控制台项目{uniq}", "code": f"I3C{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"I3控制台环境{uniq}", "envType": 2,
        "baseUrl": "http://localhost:8899", "endTypes": "web",
        "versionSnapshot": f"build-{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"]

    case_a_title = f"I3长跑用例A{uniq[:6]}"
    r = api.post("/cases", json={
        "projectId": pid, "title": case_a_title, "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "打开演示页并长跑等待", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_a_id = r.json()["data"]["id"]

    case_b_title = f"I3长跑用例B{uniq[:6]}"
    r = api.post("/cases", json={
        "projectId": pid, "title": case_b_title, "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "打开演示页并长跑等待B", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例B失败: {r.text[:300]}"
    case_b_id = r.json()["data"]["id"]

    action = {"steps": [
        {"action": "goto", "url": "http://localhost:8899/demo_page.html"},
        {"action": "wait", "ms": 120000},
        {"action": "expect", "text": "欢迎"}]}
    for cid in (case_a_id, case_b_id):
        sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
            f"VALUES ({cid}, 'v2', '{json.dumps(action, ensure_ascii=False)}', 1, 1)")

    r = api.post("/batches", json={
        "projectId": pid, "name": f"I3控制台批{uniq[:6]}", "envId": env_id,
        "caseIds": [case_a_id, case_b_id]})
    assert r.json().get("code") == "0", f"建批失败: {r.text[:300]}"
    bid = r.json()["data"]["id"]

    r = api.post(f"/batches/{bid}/start", json={})
    assert r.json().get("code") == "0", f"启动失败: {r.text[:300]}"
    return pid, bid, case_a_id, case_a_title, case_b_id


def wait_status(api, bid, want, timeout_sec=90):
    pass
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        r = api.get(f"/batches/{bid}")
        if r.json().get("data", {}).get("status") == want:
            return True
        time.sleep(2)
    return False


def cleanup(api, pid):
    pass
    try:
        sql(f"UPDATE tp_exec_batch SET status = 1 WHERE project_id = {pid}")
    except subprocess.CalledProcessError:
        pass
    api.delete(f"/projects/{pid}")
    api.delete(f"/projects/{pid}/purge?force=true")


def register_offline_executor(suffix):
    pass
    api_base = os.environ.get("TP_API_BASE", "http://localhost:8082")
    reg = httpx.post(f"{api_base}/internal/executors/register", timeout=15,
                     json={"name": f"i3crash-{suffix}", "endTypes": "web",
                           "browser": "chromium", "capacity": 1,
                           "executorVersion": "1.0.0", "osInfo": "e2e"}).json()
    return reg["data"]["executorId"]


def seed_crash_state(bid, eid):
    pass
    sql(f"UPDATE tp_exec_batch SET status = 4 WHERE id = {bid}")
    sql(f"UPDATE tp_exec_task SET status = 2, executor_id = {eid}, started_at = now() "
        f"WHERE batch_id = {bid} AND status = 1")
    sql(f"UPDATE tp_executor SET status = 3 WHERE id = {eid}")


def seed_intact(bid):
    pass
    return int(sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id = {bid} "
                   f"AND status = 2 AND executor_id IS NOT NULL"))


def test_i3_batch_crash_recovery(page, collectors, api_client, uniq):
    pass
    suffix = uniq[:6]
    runner_unit = probe_executor_runner_unit()

    def runner(action):
        executor_runner_control(runner_unit, action)

    r = api_client.post("/projects", json={"name": f"I3崩溃恢复项目{suffix}",
                                           "code": f"I3R{suffix}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    bid = eid = None
    try:
        runner("stop")
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": f"I3崩溃环境{suffix}", "envType": 2,
            "baseUrl": "http://localhost:8899", "endTypes": "web",
            "versionSnapshot": f"r-{suffix}"})
        env_id = r.json()["data"]
        case_ids = []
        for tno in ("甲", "乙"):
            r = api_client.post("/cases", json={
                "projectId": pid, "title": f"崩溃恢复用例{tno}{suffix}", "precondition": "无",
                "expectedResult": "符合预期", "priority": 1, "confidence": 2,
                "endType": "web", "status": 3, "phase": 2,
                "steps": [{"stepNo": 1, "description": "占位步骤", "isAssert": 1}]})
            assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
            cid = r.json()["data"]["id"]
            case_ids.append(cid)
            sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
                f"VALUES ({cid}, 'v2', '{{\"caseId\":{cid},\"steps\":[]}}', 2, 1) "
                f"ON CONFLICT (case_id) DO NOTHING")
        eid = register_offline_executor(suffix)
        r = api_client.post("/batches", json={
            "projectId": pid, "name": f"崩溃恢复批{suffix}", "envId": env_id,
            "caseIds": case_ids})
        bid = r.json()["data"]["id"]
        r = api_client.post(f"/batches/{bid}/start", json={})
        assert r.json().get("code") == "0", f"启动失败: {r.text[:300]}"
        seed_crash_state(bid, eid)
        assert seed_intact(bid) == 2, "造种失败：应有 2 个在跑任务挂离线执行器"

        inject_token(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("执行中")

        btn = page.locator('[data-test="batch-recover-btn"]')
        expect(btn).to_be_visible()
        if seed_intact(bid) == 0:
            seed_crash_state(bid, eid)
            assert seed_intact(bid) == 2, "重钉造种失败"
        recovered_expect = seed_intact(bid)
        btn.click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("确认对批次")
        expect(box).to_contain_text("执行器已离线名下")
        box.locator('.el-message-box__btns button.el-button--primary').click()

        # Reconciliation dialog: plain message + counts. V270 invariant: seeded count =
        # manual reclaim + auto-sweep reclaim, so wherever the tasks went the numbers audit.
        dlg = page.locator('[data-test="batch-recover-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        msg = page.locator('[data-test="batch-recover-message"]')
        expect(dlg).to_be_visible(timeout=10000)
        manual_n = int(sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id = {bid} "
                           f"AND status = 1 AND last_reclaim_source = 2"))
        swept_n = int(sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id = {bid} "
                          f"AND status = 1 AND last_reclaim_source = 1"))
        assert manual_n + swept_n == recovered_expect, \
            f"回收对账不变式破坏：本次手动 {manual_n}+巡检已回收 {swept_n}≠造种 {recovered_expect}"
        if manual_n > 0:
            expect(msg).to_contain_text(f"回收失联任务 {manual_n} 个已重新排队", timeout=10000)
        else:
            # Sweep won the race: the report must name the auto-sweep reclaim, no silent "nothing to reclaim"
            expect(msg).to_contain_text("已被自动巡检回收", timeout=10000)
        expect(msg).to_contain_text("已完成结果保留 0 个")
        expect(dlg).to_contain_text(re.compile(r"本次回收重排队\s*\n?\s*" + str(manual_n)))
        expect(dlg).to_contain_text(re.compile(r"自动巡检已回收（排队中）\s*\n?\s*" + str(swept_n)))
        expect(dlg).to_contain_text("待派发排队")
        assert sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id = {bid} "
                   f"AND status = 1 AND executor_id IS NULL") == str(recovered_expect), \
            "recover 后任务未回待派发队列（status=1 且执行器解绑）"
        assert sql(f"SELECT status FROM tp_exec_batch WHERE id = {bid}") == "4"

        # Second click = idempotent (no offline running tasks left). If the first round
        # was swept by the auto job, the second report must still name that count.
        page.locator('[data-test="batch-recover-close"]').click()
        expect(dlg).to_be_hidden(timeout=5000)
        page.locator('[data-test="batch-recover-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        box.locator('.el-message-box__btns button.el-button--primary').click()
        if swept_n > 0:
            expect(msg).to_contain_text("已被自动巡检回收", timeout=10000)
        else:
            expect(msg).to_contain_text("无需回收的失联任务", timeout=10000)
        page.locator('[data-test="batch-recover-close"]').click()

        detail_url = page.url
        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("执行中")
        expect(page.locator('[data-test="batch-recover-btn"]')).to_be_visible()

        st = api_client.get(f"/batches/{bid}").json()["data"]["status"]
        if st in (6, 7, 8):
            assert st == 7, f"批次终态异常（预期部分失败 7，实读 {st}）"
        else:
            r = api_client.post(f"/batches/{bid}/cancel", json={})
            assert r.json().get("code") == "0", f"取消失败: {r.text[:300]}"
        assert sql(f"SELECT count(*) FROM tp_exec_task WHERE batch_id = {bid} "
                   f"AND status = 1") == "0", "收尾后不应仍有待派发任务"

        assert_no_errors(collectors, "I3 崩溃恢复 按钮/确认/对账弹窗/幂等/reload")
    finally:
        runner("start")
        if bid:
            sql(f"UPDATE tp_exec_task SET status = 7 WHERE batch_id = {bid} AND status = 2")
        sql(f"DELETE FROM tp_executor WHERE name LIKE 'i3crash-{suffix}%'")
        cleanup(api_client, pid)
        assert sql(f"SELECT count(*) FROM tp_exec_batch WHERE project_id = {pid}") == "0"


def test_i3_batch_console_flow(page, collectors, api_client, uniq):
    pass
    pid, bid, case_a_id, case_a_title, case_b_id = create_console_dataset(api_client, uniq)
    try:
        assert wait_status(api_client, bid, 4, timeout_sec=240), \
            "240s 内执行器未领取任务（批次未推进执行中）——检查执行器活体/容量"

        inject_token(page)
        page.goto(f"{BASE_URL}/#/batches/{bid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        page.evaluate(
            """(path) => {
              window.__pollN = 0;
              const origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function (m, u, ...rest) {
                if (m === 'GET' && typeof u === 'string' &&
                    u.split('?', 1)[0].endsWith(path)) window.__pollN++;
                return origOpen.call(this, m, u, ...rest);
              };
            }""",
            f"/api/v1/batches/{bid}")

        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("执行中")
        expect(page.locator('[data-test="batch-meta"]')).to_contain_text(re.compile(r"第 \d+ 位"))
        expect(page.locator('[data-test="batch-pause-btn"]')).to_be_visible()
        expect(page.locator('[data-test="batch-cancel-btn"]')).to_be_visible()
        expect(page.locator('[data-test="batch-skip-case-btn"]')).to_be_visible()
        expect(page.locator('[data-test="batch-resume-btn"]')).to_have_count(0)
        expect(page.locator('[data-test="batch-rerun-failed-btn"]')).to_have_count(0)

        progress = page.locator('[data-test="batch-progress"]')
        expect(progress).to_have_text(re.compile(r"^\d+/\d+$"))
        expect(page.locator('[data-test="batch-elapsed"]')).to_have_text(re.compile(r"^\d+:\d{2}(:\d{2})?$"))
        try:
            page.wait_for_function("window.__pollN >= 2", timeout=20000)
        except PwTimeoutError as e:
            raise AssertionError(
                f"20s 窗口内 GET /batches/{{id}} 仅 {page.evaluate('window.__pollN')} 次"
                "——5s 静默轮询未真实发出") from e

        page.locator('[data-test="batch-pause-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("确认暂停批次")
        expect(box).to_contain_text("不强杀正在执行的用例")
        box.locator('.el-message-box__btns button.el-button--primary').click()
        expect(page.locator('.el-message--success:has-text("批次已暂停")').first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("已暂停", timeout=10000)
        expect(page.locator('[data-test="batch-resume-btn"]')).to_be_visible()
        expect(page.locator('[data-test="batch-pause-btn"]')).to_have_count(0)

        detail_url = page.url
        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("已暂停")
        expect(page.locator('[data-test="batch-resume-btn"]')).to_be_visible()

        assert sql(f"SELECT status FROM tp_exec_task WHERE batch_id = {bid} "
                   f"AND case_id = {case_a_id}") == "2", \
            "暂停后在跑任务应仍为执行中(2)——暂停不强杀语义被破坏"

        page.locator('[data-test="batch-resume-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("确认续跑")
        box.locator('.el-message-box__btns button.el-button--primary').click()
        expect(page.locator('.el-message--success:has-text("批次已续跑")').first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("执行中", timeout=10000)

        page.locator('[data-test="batch-skip-case-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("确认跳过当前正在执行的用例")
        box.locator('.el-message-box__btns button.el-button--primary').click()
        toast = page.locator('.el-message--success:has-text("已跳过用例")').first
        expect(toast).to_be_visible(timeout=10000)
        expect(toast).to_contain_text(case_a_title)
        row = page.locator('[data-test="batch-case-list"] tbody tr').filter(has_text=case_a_title).first
        expect(row.locator('[data-test="batch-case-result"]')).to_contain_text("跳过", timeout=10000)
        expect(page.locator('[data-test="batch-progress"]')).to_have_text("1/2", timeout=15000)
        assert sql(f"SELECT manual_skip FROM tp_batch_case WHERE batch_id = {bid} "
                   f"AND case_id = {case_a_id}") == "1", \
            "skip-case 未落 manual_skip=1"
        assert sql(f"SELECT status FROM tp_exec_task WHERE batch_id = {bid} "
                   f"AND case_id = {case_a_id}") == "6", \
            "skip-case 后任务未置已跳过(6)"
        assert sql(f"SELECT status FROM tp_exec_task WHERE batch_id = {bid} "
                   f"AND case_id = {case_b_id}") in ("1", "2"), \
            "skip A 后 B 任务应非终态（1 待派发/2 在跑）——skip 只终结最早 claim 的一条"
        assert sql(f"SELECT status FROM tp_exec_batch WHERE id = {bid}") == "4", \
            "B 未终态时批次不应提前 finalize（应保持执行中 4 而非跳 6/7）"
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("执行中")
        expect(page.locator('[data-test="batch-pause-btn"]')).to_be_visible()

        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-case-list"] tbody tr')
               .filter(has_text=case_a_title).first
               .locator('[data-test="batch-case-result"]')).to_contain_text("跳过", timeout=10000)

        page.locator('[data-test="batch-cancel-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("不可逆")
        box.locator('.el-message-box__btns button.el-button--primary').click()
        t2 = page.locator('.el-message--success:has-text("批次已取消")').first
        expect(t2).to_be_visible(timeout=10000)
        expect(t2).to_contain_text("已完成的执行结果保留")
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("已取消", timeout=10000)
        for sel in ("batch-pause-btn", "batch-resume-btn", "batch-cancel-btn",
                    "batch-skip-case-btn", "batch-rerun-failed-btn"):
            expect(page.locator(f'[data-test="{sel}"]')).to_have_count(0)

        page.goto(detail_url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-status"]')).to_contain_text("已取消")
        expect(page.locator('[data-test="batch-resume-btn"]')).to_have_count(0)
        assert sql(f"SELECT status FROM tp_exec_batch WHERE id = {bid}") == "8"
        assert sql(f"SELECT finished_at IS NOT NULL FROM tp_exec_batch WHERE id = {bid}") == "t"

        assert_no_errors(collectors, "I3 批次控制台 暂停/续跑/跳过/取消 全链")
    finally:
        cleanup(api_client, pid)
