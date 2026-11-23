
from __future__ import annotations

import os


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


class ExecutorConfig:
    def __init__(self) -> None:
        # Protocol entry (api service; executors reach core only through api).
        # Slot-aware: explicit EXECUTOR_API_URL wins, otherwise derive from the
        # chain slot's exported API_PORT so a worktree chain never silently
        # registers against another chain's ledger.
        self.api_base_url = _env(
            "EXECUTOR_API_URL",
            "http://localhost:" + _env("API_PORT", "8082"),
        )
        self.name = _env("EXECUTOR_NAME", "executor-local-01")
        self.group_ids = _env("EXECUTOR_GROUP_IDS", "")
        self.env_ids = _env("EXECUTOR_ENV_IDS", "")
        self.end_types = _env("EXECUTOR_END_TYPES", "web")
        self.browsers = _env("EXECUTOR_BROWSERS", "chromium")
        self.capacity = int(_env("EXECUTOR_CAPACITY", "2"))
        self.version = _env("EXECUTOR_VERSION", "1.0.0")
        self.heartbeat_interval = int(_env("EXECUTOR_HEARTBEAT_INTERVAL", "15"))
        self.proxy_audit_interval = int(_env("EXECUTOR_PROXY_AUDIT_INTERVAL", "300"))
        if self.proxy_audit_interval <= 0:
            self.proxy_audit_interval = 300
        self.monitor_heartbeat_interval = int(
            _env("EXECUTOR_MONITOR_HEARTBEAT_INTERVAL", "5"))
        if not 0 < self.monitor_heartbeat_interval < 30:
            self.monitor_heartbeat_interval = self.heartbeat_interval
        self.claim_interval = int(_env("EXECUTOR_CLAIM_INTERVAL", "2"))
        self.device_discovery_interval = int(_env("EXECUTOR_DEVICE_DISCOVERY_INTERVAL", "60"))
        if self.device_discovery_interval <= 0:
            self.device_discovery_interval = 60
        self.device_project_scope = _env("EXECUTOR_DEVICE_PROJECT_SCOPE", "")
        self.task_timeout_ms = int(_env("EXECUTOR_TASK_TIMEOUT_MS", "600000"))
        self.screenshots_dir = _env("EXECUTOR_SCREENSHOTS_DIR",
                                    os.path.join(os.path.dirname(__file__), "shots"))
        self.request_timeout = float(_env("EXECUTOR_REQUEST_TIMEOUT", "30"))

    @property
    def register_payload(self) -> dict:
        from capabilities import probe_capabilities
        return {
            "type": "register",
            "name": self.name,
            "groupIds": self.group_ids,
            "envIds": self.env_ids,
            "endTypes": self.end_types,
            "browsers": self.browsers,
            "capacity": self.capacity,
            "version": self.version,
            "runningTasks": 0,
            "capabilities": probe_capabilities(),
        }
