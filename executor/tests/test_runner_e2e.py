
from __future__ import annotations

import json
import os
import tempfile
import time
from urllib.parse import urlparse

import pytest
import requests

from driver.webdriver import run_action_json
from protocol import ExecutorProtocolClient

API_BASE = os.environ.get("EXECUTOR_API_URL", "http://localhost:8082")
CORE_BASE = os.environ.get("CORE_BASE_URL", "http://localhost:8083")
ADMIN_BASE = os.environ.get("ADMIN_BASE_URL", "http://localhost:8081/api/v1")
ADMIN_USERNAME = os.environ.get("TP_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_INIT_PASSWORD", "Admin@123")
DEMO_URL = "file://" + os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "fixtures", "demo_page.html"))

LOGIN_ACTION = {
    "version": "v2", "caseId": "TC-DEMO-L4", "title": "L4 登录链路",
    "endType": "web",
    "setup": [{"action": "goto", "target": {"url": DEMO_URL}}],
    "steps": [
        {"action": "input", "target": {"css": "#username"}, "value": "l4tester"},
        {"action": "input", "target": {"css": "#password"}, "value": "Pass@123"},
        {"action": "click", "target": {"css": "#login-btn"}},
        {"action": "expect", "target": {"text": "欢迎，l4tester！登录成功"}},
    ],
}

FAIL_ACTION = {
    "version": "v2", "caseId": "TC-DEMO-L4-FAIL", "title": "L4 定位失败",
    "steps": [
        {"action": "goto", "target": {"url": DEMO_URL}},
        {"action": "click", "target": {"css": "#missing-btn"}},
    ],
}

# Cleanup deletes only rows this suite created (projects named exec-l4-* and their
# dependents). A blanket TRUNCATE tp_project CASCADE used to wipe every table that
# references tp_project (factory tools/skills/cases included) — an R37 baseline violation.
# Target DB resolves from the chain env (DB_NAME) instead of the hard-coded main DB name.
_cleanup_statements = [
    "DELETE FROM tp_exec_task WHERE project_id IN (SELECT id FROM tp_project WHERE name LIKE 'exec-l4-%')",
    "DELETE FROM tp_project_env WHERE project_id IN (SELECT id FROM tp_project WHERE name LIKE 'exec-l4-%')",
    "DELETE FROM tp_user_project WHERE project_id IN (SELECT id FROM tp_project WHERE name LIKE 'exec-l4-%')",
    "DELETE FROM tp_executor WHERE name IN ('ex-l4-e2e', 'ex-l4-a', 'ex-l4-b')",
    "DELETE FROM tp_project WHERE name LIKE 'exec-l4-%'",
]


def _run_cleanup_sql() -> None:
    import subprocess
    db_name = os.environ.get("DB_NAME", "testpike")
    for stmt in _cleanup_statements:
        subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", db_name, "-c", stmt], check=False)


def _admin_login() -> str:
    pass
    resp = requests.post(ADMIN_BASE + "/auth/login",
                         json={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}, timeout=10)
    assert resp.status_code == 200, f"admin 登录失败: {resp.status_code} {resp.text[:200]}"
    return resp.json()["data"]["accessToken"]


def _provision_project_env() -> tuple[int, int]:
    pass
    token = _admin_login()
    headers = {"Authorization": f"Bearer {token}"}
    name = f"exec-l4-{int(time.time())}"
    resp = requests.post(ADMIN_BASE + "/projects",
                         json={"name": name, "code": name.upper().replace("-", "")[:20]},
                         headers=headers, timeout=10)
    assert resp.status_code == 200 and resp.json().get("code") == "0", \
        f"创建项目失败: {resp.status_code} {resp.text[:300]}"
    project_id = resp.json()["data"]
    resp = requests.post(ADMIN_BASE + f"/projects/{project_id}/envs",
                         json={"name": "开发", "envType": 1, "baseUrl": "http://demo.local",
                               "versionSnapshot": "v1.0.0"},
                         headers=headers, timeout=10)
    assert resp.status_code == 200 and resp.json().get("code") == "0", \
        f"配置环境失败: {resp.status_code} {resp.text[:300]}"
    env_id = resp.json()["data"]
    return project_id, env_id


@pytest.fixture(scope="module")
def project_env():
    assert requests.get(CORE_BASE + "/actuator/health", timeout=3).json()["status"] == "UP"
    assert requests.get(API_BASE + "/actuator/health", timeout=3).json()["status"] == "UP"
    yield _provision_project_env()


@pytest.fixture(scope="module")
def protocol_client():
    assert requests.get(CORE_BASE + "/actuator/health", timeout=3).json()["status"] == "UP"
    assert requests.get(API_BASE + "/actuator/health", timeout=3).json()["status"] == "UP"
    client = ExecutorProtocolClient(API_BASE)
    executor_id = client.register({
        "type": "register", "name": "ex-l4-e2e", "groupIds": "1",
        "envIds": "1", "endTypes": "web", "browsers": "chromium",
        "capacity": 2, "version": "1.0.0", "runningTasks": 0,
    })
    assert executor_id > 0
    yield client
    _run_cleanup_sql()


def _create_task(project_id: int, case_id: int, action: dict, env_base_url: str | None,
                 env_id: int | None = None) -> int:
    resp = requests.post(CORE_BASE + "/internal/executors/tasks", json={
        "projectId": project_id, "caseId": case_id, "schemaVersion": "v2",
        "actionJson": json.dumps(action, ensure_ascii=False), "envBaseUrl": env_base_url,
        "envId": env_id,
    }, timeout=5)
    assert resp.json()["code"] == "0", resp.text
    return resp.json()["data"]["taskId"]


def _fetch_task(task_id: int) -> dict:
    resp = requests.get(CORE_BASE + f"/internal/executors/tasks/{task_id}", timeout=5)
    return resp.json()["data"]


class TestExecutorFullChain:
    pass

    def test_注册心跳无任务claim(self, protocol_client):
        client = protocol_client
        client.heartbeat(0)
        time.sleep(1)

        task = client.claim()
        assert task is None

        resp = requests.get(CORE_BASE + "/internal/executors", timeout=5)
        executors = resp.json()["data"]
        assert any(e["name"] == "ex-l4-e2e" for e in executors), "注册表可见执行器"

    def test_全链路执行成功_pass_截图上传对象存储(self, protocol_client, project_env):
        client = protocol_client
        project_id, env_id = project_env
        task_id = _create_task(project_id, 1001, LOGIN_ACTION, None, env_id)

        claimed = client.claim()
        assert claimed is not None and claimed["taskId"] == task_id, "必须领取到刚创建的任务"
        assert claimed["claimToken"], "claim 响应必须带 claim_token（03 协议补充字段）"
        assert claimed["schemaVersion"] == "v2"

        task = _fetch_task(task_id)
        assert task["status"] == 2

        with tempfile.TemporaryDirectory() as shots:
            outcome = run_action_json(claimed["actionJson"], headless=True, screenshots_dir=shots)
            assert outcome.result == "pass", f"L4 执行应通过: {outcome.error_detail}"

            keys: list[str] = []
            for path in outcome.screenshots:
                if not path or not os.path.exists(path):
                    continue
                grant = client.grant_artifact(task_id, "screenshot", os.path.basename(path))
                assert grant["objectKey"].startswith(f"testpike/{task_id}/exec-artifact/")
                with open(path, "rb") as f:
                    up = requests.put(grant["uploadUrl"], data=f.read(), timeout=30)
                assert up.status_code == 200, f"预签名 PUT 应成功: {up.text[:200]}"
                keys.append(grant["objectKey"])

            resp = client.submit_result(task_id, claimed["claimToken"], outcome.result,
                                        steps_timeline=outcome.steps_timeline,
                                        screenshots=keys, duration_ms=outcome.duration_ms)
            assert resp["accepted"] is True

        task = _fetch_task(task_id)
        assert task["status"] == 3, f"任务应成功: {task}"
        timeline = json.loads(task["stepsTimeline"]) if task["stepsTimeline"] else []
        assert len(timeline) == 5, f"步骤时间戳应 5 条: {timeline}"
        assert all(s["status"] == "ok" for s in timeline)
        assert json.loads(task["screenshots"]), "截图 objectKey 必须落库"
        assert task["durationMs"] > 0
        assert task["finishedAt"]

    def test_双执行器原子领取_仅一个成功(self, protocol_client, project_env):
        project_id, env_id = project_env
        client_a = ExecutorProtocolClient(API_BASE)
        id_a = client_a.register({"type": "register", "name": "ex-l4-a", "capacity": 2,
                                  "endTypes": "web", "browsers": "chromium",
                                  "version": "1.0.0", "runningTasks": 0})
        client_b = ExecutorProtocolClient(API_BASE)
        id_b = client_b.register({"type": "register", "name": "ex-l4-b", "capacity": 2,
                                  "endTypes": "web", "browsers": "chromium",
                                  "version": "1.0.0", "runningTasks": 0})
        assert id_a != id_b

        task_id = _create_task(project_id, 1002, LOGIN_ACTION, None, env_id)
        first = client_a.claim()
        second = client_b.claim()

        assert first is not None and first["taskId"] == task_id
        assert second is None, "同一任务仅一个执行器能领取成功"
        client_a.submit_result(task_id, first["claimToken"], "pass", duration_ms=1)

    def test_结果回传claimToken不匹配_拒绝(self, protocol_client, project_env):
        project_id, env_id = project_env
        client = protocol_client
        task_id = _create_task(project_id, 1003, LOGIN_ACTION, None, env_id)
        claimed = client.claim()
        assert claimed["taskId"] == task_id

        resp = requests.post(API_BASE + "/internal/tasks/result", json={
            "taskId": task_id, "claimToken": "wrong-token", "result": "pass",
        }, timeout=5)
        body = resp.json()
        assert body["code"] not in ("0", 0), "错误令牌必须被拒绝"
        assert "令牌不匹配" in body["msg"]
        assert _fetch_task(task_id)["status"] == 2, "拒绝后任务仍执行中"

    def test_定位失败_fail归类错误(self, protocol_client, project_env):
        project_id, env_id = project_env
        client = protocol_client
        task_id = _create_task(project_id, 1004, FAIL_ACTION, None, env_id)
        claimed = client.claim()

        outcome = run_action_json(claimed["actionJson"], headless=True)
        assert outcome.result == "fail"
        client.submit_result(task_id, claimed["claimToken"], "fail",
                             error_type=outcome.error_type, error_detail=outcome.error_detail,
                             steps_timeline=outcome.steps_timeline, duration_ms=outcome.duration_ms)

        task = _fetch_task(task_id)
        assert task["status"] == 4
        assert task["errorType"] == 2, "定位失败必须归为 2=定位错误"
        assert task["errorDetail"] and "步骤 2" in task["errorDetail"]
        timeline = json.loads(task["stepsTimeline"]) if task["stepsTimeline"] else []
        assert timeline[-1]["status"] == "fail"
