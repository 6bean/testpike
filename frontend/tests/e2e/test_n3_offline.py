
import os
import time
import uuid

import httpx
import psycopg2
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, nav_to



def inject_token(page):
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
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def db_conn():
    """Connection to THIS chain's database, resolved through the single source.

    The previous version defaulted to the main chain's database when DB_URL was
    missing, so a slot chain that forgot its slot env wrote fixtures into another
    chain's environment. conftest.db_connect() refuses instead.
    """
    from conftest import db_connect
    return db_connect()


def test_n3_offline_alert(page, collectors):
    pass
    uniq = uuid.uuid4().hex[:8]
    ex_name = f"E2E执行器{uniq}"
    api_base = os.environ.get("TP_API_BASE", "http://localhost:8082")
    conn = db_conn()
    cur = conn.cursor()
    executor_id = None
    try:
        inject_token(page)

        cur.execute("""INSERT INTO tp_notification_pref (user_id, event_type, enabled)
                       SELECT id, 'EXECUTOR_ONLINE', 1 FROM tp_user WHERE username='admin'
                       ON CONFLICT (user_id, event_type)
                       DO UPDATE SET enabled=1, updated_at=now()""")
        conn.commit()

        r = httpx.post(f"{api_base}/internal/executors/register", json={
            "name": ex_name, "groupIds": "1", "envIds": "10", "endTypes": "web",
            "browsers": "chromium", "capacity": 2, "version": "1.0.0", "runningTasks": 0,
        }, timeout=15)
        assert r.json().get("code") == "0", f"注册失败: {r.text[:300]}"
        executor_id = r.json()["data"]["executorId"]
        r = httpx.post(f"{api_base}/internal/executors/heartbeat?executorId={executor_id}",
                       json={"runningTasks": 0}, timeout=15)
        assert r.status_code == 200, f"心跳失败: {r.text[:300]}"

        cur.execute("UPDATE tp_executor SET last_heartbeat = now() - interval '40 seconds' WHERE id = %s",
                    (executor_id,))
        conn.commit()
        offline = False
        for _ in range(10):
            cur.execute("SELECT status FROM tp_executor WHERE id = %s", (executor_id,))
            if cur.fetchone()[0] == 3:
                offline = True
                break
            time.sleep(5)
        assert offline, "执行器未被定时检测置离线（30s 超时 + 15s 周期内应完成）"

        nav_to(page, "工作台", "/dashboard", '[data-test="my-project-table"]', submenu="工作台")
        page.locator("[data-test=notif-bell]").click()
        notif_items = page.locator("[data-test=notif-item]")
        expect(notif_items.first).to_be_visible(timeout=10000)
        found_offline = False
        for _ in range(12):
            page.locator("[data-test=notif-bell]").click()
            texts = page.locator("[data-test=notif-panel]").inner_text()
            if "执行器离线告警" in texts and ex_name in texts:
                found_offline = True
                break
            page.keyboard.press("Escape")
            time.sleep(2)
        assert found_offline, f"铃铛未出现离线告警（执行器 {ex_name}）"

        r = httpx.post(f"{api_base}/internal/executors/heartbeat?executorId={executor_id}",
                       json={"runningTasks": 0}, timeout=15)
        assert r.status_code == 200
        found_online = False
        for _ in range(6):
            page.locator("[data-test=notif-bell]").click()
            texts = page.locator("[data-test=notif-panel]").inner_text()
            if "执行器已恢复" in texts and ex_name in texts:
                found_online = True
                break
            page.keyboard.press("Escape")
            time.sleep(2)
        assert found_online, "铃铛未出现恢复通知"

        page.reload(wait_until="domcontentloaded")
        page.locator("[data-test=notif-bell]").click()
        expect(page.locator("[data-test=notif-panel]")).to_contain_text("执行器离线告警", timeout=10000)

        assert_no_errors(collectors, "N3 执行器离线告警列表")
    finally:
        cur.execute("DELETE FROM tp_notification_pref WHERE event_type='EXECUTOR_ONLINE'")
        conn.commit()
        if executor_id:
            cur.execute("DELETE FROM tp_notification WHERE content LIKE %s", (f"%{ex_name}%",))
            cur.execute("DELETE FROM tp_executor WHERE id = %s", (executor_id,))
            conn.commit()
        cur.close()
        conn.close()
