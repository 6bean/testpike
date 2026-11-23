
from __future__ import annotations

from actions_common import IR_VERBS


class StubEngineAdapter:
    pass

    SUPPORTED_PLATFORMS: tuple = ("ANDROID",)
    SUPPORTED_DEVICE_KINDS: tuple = ("EMULATOR",)
    CAPABILITIES: tuple = IR_VERBS

    def __init__(self, descriptor: dict | None = None):
        self.descriptor = descriptor or {}
        self.calls: list[str] = []

    def prepare(self, ctx: dict) -> None:
        self.calls.append("prepare")

    def execute(self, step: dict) -> dict:
        self.calls.append(f"execute:{step.get('action')}")
        return {"result": "pass"}

    def collect(self, artifact: dict):
        self.calls.append("collect")
        return None

    def teardown(self) -> None:
        self.calls.append("teardown")
