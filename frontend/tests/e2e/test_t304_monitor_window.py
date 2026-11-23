
import base64
import contextlib
import json
import os
import re
import subprocess
import time

import pytest
from conftest import (
    assert_no_errors,
    cleanup_monitor_frames,
    create_running_exec_task,
    inject_token,
    monitor_frame_payload,
    nav_to,
    open_exec_task_detail,
    push_monitor_frames,
    redis_cli,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(900)

# Real 8x8 JPEG verified by strict server-side ImageIO (the old hex lacked the
# SOS scan segment, so browsers tolerated it but the thumbnail decoder rejected it).
_JPEG_B64 = base64.b64encode(bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb00430003020203020203"
    "03030304030304050805050404050a070706080c0a0c0c0b0a0b0b0d0e12100d"
    "0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304"
    "0405040509050509140d0b0d1414141414141414141414141414141414141414"
    "141414141414141414141414141414141414141414141414141414141414ffc0"
    "0011080008000803012200021101031101ffc4001f0000010501010101010100"
    "000000000000000102030405060708090a0bffc400b510000201030302040305"
    "0504040000017d01020300041105122131410613516107227114328191a10823"
    "42b1c11552d1f02433627282090a161718191a25262728292a3435363738393a"
    "434445464748494a535455565758595a636465666768696a737475767778797a"
    "838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7"
    "b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1"
    "f2f3f4f5f6f7f8f9faffc4001f01000301010101010101010100000000000001"
    "02030405060708090a0bffc400b5110002010204040304070504040001027700"
    "0102031104052131061241510761711322328108144291a1b1c109233352f015"
    "6272d10a162434e125f11718191a262728292a35363738393a43444546474849"
    "4a535455565758595a636465666768696a737475767778797a82838485868788"
    "898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4"
    "c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9"
    "faffda000c03010002110311003f00e3e8a28af08fe543ffd9"
)).decode()

ADMIN = "http://127.0.0.1:8081"


# Frame-channel fixtures live in conftest (single source): these are thin
# forwarders that keep this spectrum's call sites readable with its own 8x8
# JPEG payload.
def _frame(task_id: int, step_no: int, sensitive: bool = False) -> str:
    return monitor_frame_payload(task_id, step_no, _JPEG_B64, sensitive)


def _push_frames(task_id: int, frames: list) -> None:
    push_monitor_frames(task_id, frames)


def _cleanup_frames(task_id: int) -> None:
    cleanup_monitor_frames(task_id)


def _mk_running_task(api_client, pid: int, uniq: str) -> int:
    return create_running_exec_task(api_client, pid, uniq, "监看用例")


def _open_task_detail(page, proj_name: str, tid: int):
    return open_exec_task_detail(page, proj_name, tid)


def _core_health() -> bool:
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                        "-m", "2", "http://127.0.0.1:8083/actuator/health"],
                       capture_output=True, text=True, check=False)
    return r.stdout.strip() == "200"


def _core_unit_name() -> str:
    """Resolve the user unit that actually serves port 8083 (this stack's core).

 Two stale assumptions in the old recipe (assertion-lag triage,
 red family): unit names are not "*-core.service" but
 "tp-svc-core-<port>.service" (the internal service launcher recipe tp_unit_name),
 and several cores run side by side under the AU slotting design
 (main slot 8083 + lane slot 8183), so "exactly one running core unit" is
 wrong by construction. This test restarts the core its own admin chain
 talks to, so the deterministic lookup is socket owner:
 listener on :8083 -> pid -> /proc/<pid>/cgroup -> unit tail. A bare java
 process outside a unit has no ".service" cgroup tail and fails here loudly
 (restart via systemctl would be impossible anyway) instead of guessing.
 """
    ss = subprocess.run(["ss", "-ltnp", "sport = :8083"],
                        capture_output=True, text=True, timeout=30, check=False)
    m = re.search(r"pid=(\d+)", ss.stdout)
    assert m, f":8083 has no listener with pid info (ss rc={ss.returncode}): {ss.stdout[:300]}"
    with open(f"/proc/{m.group(1)}/cgroup", encoding="utf-8") as fh:
        cg_lines = [ln for ln in fh.read().splitlines() if ln.startswith("0::")]
    assert cg_lines, f"unreadable cgroup for pid {m.group(1)}"
    unit = cg_lines[0].rsplit("/", 1)[-1]
    assert unit.endswith(".service") and "core" in unit, (
        f"owner of :8083 is not a core service unit (pid {m.group(1)}, tail {unit})")
    shown = subprocess.run(["systemctl", "--user", "show", unit,
                            "-p", "Id", "-p", "ActiveState"],
                           capture_output=True, text=True, timeout=30, check=False)
    fields = dict(ln.split("=", 1) for ln in shown.stdout.splitlines() if "=" in ln)
    assert fields.get("Id") == unit and fields.get("ActiveState") == "active", (
        f":8083 owner unit {unit} is not an active user unit: {fields}")
    return unit


def test_t304_monitor_window_live_chain(page, api_client, collectors, uniq):
    pass
    pid = None
    tid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"监看{uniq}", "code": f"MON{uniq.upper()}"}).json()["data"]
        api_client.post(f"/projects/{pid}/envs", json={
            "name": f"监看环境{uniq}", "envType": 2, "baseUrl": "http://localhost:8899/"})
        tid = _mk_running_task(api_client, pid, uniq)

        dlg = _open_task_detail(page, f"监看{uniq}", tid)
        win = dlg.locator('[data-test="exec-monitor-window"]')
        expect(win).to_be_visible(timeout=10000)
        expect(win.locator('[data-test="monitor-mode-badge"]')).to_be_visible()
        expect(win.locator('[data-test="monitor-waiting"]')).to_be_visible(timeout=10000)

        _push_frames(tid, [_frame(tid, 1), _frame(tid, 2)])
        img = win.locator('[data-test="monitor-frame"]')
        expect(img).to_be_visible(timeout=10000)
        expect(win.locator('[data-test="monitor-step-tag"]')).to_contain_text("2", timeout=10000)
        src_v1 = img.get_attribute("src") or ""
        assert src_v1.startswith("data:image/jpeg;base64,")
        expect(win.locator('[data-test="monitor-waiting"]')).to_have_count(0)

        _push_frames(tid, [_frame(tid, 3, sensitive=True)])
        expect(win.locator('[data-test="monitor-sensitive-mask"]')).to_be_visible(timeout=10000)
        assert (img.get_attribute("src") or "") == src_v1, "敏感帧不得换面（只暂停）"
        _push_frames(tid, [_frame(tid, 4)])
        expect(win.locator('[data-test="monitor-sensitive-mask"]')).to_have_count(0, timeout=10000)

        redis_cli("HSET", f"appauto-monitor:meta:{tid}", "dropped", "1")
        redis_cli("EXPIRE", f"appauto-monitor:meta:{tid}", "120")
        expect(win.locator('[data-test="monitor-degrade-notice"]')).to_be_visible(timeout=10000)
        redis_cli("HDEL", f"appauto-monitor:meta:{tid}", "dropped")
        expect(win.locator('[data-test="monitor-degrade-notice"]')).to_have_count(0, timeout=10000)

        _cleanup_frames(tid)
        _push_frames(tid, [_frame(tid, 5)])
        mark = {k: len(collectors[k]) for k in
                ("network_4xx", "network_errors", "console_errors")}
        unit = _core_unit_name()
        r = subprocess.run(["systemctl", "--user", "restart", unit],
                           capture_output=True, text=True, timeout=60, check=False)
        assert r.returncode == 0, f"core restart({unit}) 失败: {r.stderr[:200]}"
        time.sleep(6)
        expect(win.locator('[data-test="monitor-frame"]')).to_be_visible(timeout=5000)
        deadline = time.time() + 180
        while time.time() < deadline and not _core_health():
            time.sleep(3)
        assert _core_health(), "core 重启后未恢复健康"
        _push_frames(tid, [_frame(tid, 6)])
        expect(win.locator('[data-test="monitor-step-tag"]')).to_contain_text("6", timeout=30000)
        time.sleep(3)
        transient = (collectors["network_4xx"][mark["network_4xx"]:]
                     + collectors["network_errors"][mark["network_errors"]:])
        for e in transient:
            assert ("monitor-frames" in e["url"] or "/api/v1/notifications" in e["url"]), \
                f"重启窗内出现非 core 下游链轮询端点错误: {e}"
        console_win = collectors["console_errors"][mark["console_errors"]:]
        for c in console_win:
            assert "Failed to load resource" in c, f"重启窗内出现非读面 console 错误: {c}"
        del collectors["network_4xx"][mark["network_4xx"]:]
        del collectors["network_errors"][mark["network_errors"]:]
        del collectors["console_errors"][mark["console_errors"]:]

        assert_no_errors(collectors, "T304 监看窗真链 E2E")
    finally:
        if tid is not None:
            _cleanup_frames(tid)
            sql_query(f"DELETE FROM tp_exec_task WHERE id = {tid}")
        if pid is not None:
            with contextlib.suppress(Exception):
                api_client.delete(f"/projects/{pid}/purge?force=true")
