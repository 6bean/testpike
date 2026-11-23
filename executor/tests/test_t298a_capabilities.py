
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from capabilities import (host_os_name, probe_capabilities,
                          kvm_usable, _version_ge, ENGINE_REQUIREMENTS)


def _fake_probe(tools=None, versions=None, host_os="linux", exists=()):
    pass
    tools = set(tools or [])
    versions = versions or {}
    present = set(exists)

    def which(name):
        return f"/usr/bin/{name}" if name in tools else None

    def run_version(exe):
        return versions.get(exe)

    import capabilities as cap
    real_exists = cap.os.path.exists
    cap.os.path.exists = lambda p: p in present or real_exists(p)
    try:
        return probe_capabilities(which=which, run_version=run_version,
                                  host_os=host_os)
    finally:
        cap.os.path.exists = real_exists



def test_缺工具_引擎不进上报面():
    pass
    caps = _fake_probe(tools=["node"], versions={"node": "v22.23.1\n"})
    assert "MOBILEWRIGHT" not in caps["engines"]
    assert caps["tools"]["mobilecli"] is False


def test_版本不达标_引擎拒报():
    pass
    low = _fake_probe(tools=["node", "mobilecli"], versions={"node": "v20.11.0"})
    assert "MOBILEWRIGHT" not in low["engines"]
    high = _fake_probe(tools=["node", "mobilecli"], versions={"node": "v22.23.1"})
    assert "MOBILEWRIGHT" in high["engines"]


def test_探测输出脏_按缺席():
    pass
    caps = _fake_probe(tools=["node", "mobilecli"], versions={"node": ""})
    assert caps["runtimes"]["node"] is None
    assert "MOBILEWRIGHT" not in caps["engines"]


def test_macos专属引擎_他宿主不报():
    pass
    caps = _fake_probe(tools=["node", "appium", "xcrun"],
                       versions={"node": "v22.0.0"}, host_os="linux")
    assert "XCUITEST" not in caps["engines"]
    mac = _fake_probe(tools=["node", "appium", "xcrun"],
                      versions={"node": "v22.0.0"}, host_os="macos")
    assert "XCUITEST" in mac["engines"]
    assert "IOS" in mac["platforms"]


def test_候补停用引擎永不在装载前提面():
    pass
    assert "HYPIUM" not in ENGINE_REQUIREMENTS


def test_开发者工具缺位_不上报小程序平台():
    pass
    os.environ.pop("WX_DEVTOOLS_CLI_PATH", None)
    caps = _fake_probe(tools=["node"], versions={"node": "v22.0.0"})
    assert caps["tools"]["wx_devtools_cli"] is False
    assert "MP" not in caps["platforms"]



def test_本机活体_node版本真实非空():
    caps = probe_capabilities()
    assert caps["hostOs"] == host_os_name()
    assert caps["runtimes"]["node"] is not None
    assert caps["hostOs"] in ("linux", "macos", "windows")


def test_本机活体_缺件诚实缺席():
    pass
    caps = probe_capabilities()
    if caps["tools"]["mobilecli"] is False:
        assert "MOBILEWRIGHT" not in caps["engines"]
    if caps["tools"]["maestro"] is False:
        assert "MAESTRO" not in caps["engines"]
    if caps["hostOs"] != "macos":
        assert "XCUITEST" not in caps["engines"]



def test_register载荷尾追capabilities键():
    pass
    from config import ExecutorConfig
    payload = ExecutorConfig().register_payload
    assert "capabilities" in payload
    caps = payload["capabilities"]
    assert caps["hostOs"] == host_os_name()
    assert set(caps.keys()) >= {"hostOs", "tools", "runtimes", "engines",
                                "platforms", "deviceKinds"}
    assert {"name", "endTypes", "capacity", "version"} <= set(payload.keys())



def test_版本比较_位数不齐补零():
    assert _version_ge("22.23", "22.12.0") is True
    assert _version_ge("22", "22.0.1") is False
    assert _version_ge(None, "17") is False
    assert _version_ge("17.0.2", "17") is True


# ── ④ KVM probe: existence is not accessibility ─────────────────────────
# Real-file fixtures on the live filesystem (no mocks): a node the current
# user cannot read/write must report unavailable, otherwise the scheduler
# keeps dispatching emulator work to a host that cannot boot one.

def test_kvm探測_存在但不可读写判伪(tmp_path):
    # mode 000 = node exists but open() would hit EACCES for a non-member
    node = tmp_path / "kvm-no-access"
    node.write_text("")
    os.chmod(node, 0o000)
    assert kvm_usable(str(node)) is False


def test_kvm探測_可读写判真(tmp_path):
    # current user owns the file and mode grants read+write = usable
    node = tmp_path / "kvm-rw"
    node.write_text("")
    os.chmod(node, 0o644)
    assert kvm_usable(str(node)) is True


def test_kvm探測_节点缺席判伪(tmp_path):
    assert kvm_usable(str(tmp_path / "no-such-node")) is False


def test_本机活体_kvm申报与真实访问权一致():
    """Live-machine honesty: reported kvm flag and EMULATOR declaration
    must both track the access-based ground truth, never bare existence."""
    caps = probe_capabilities()
    expected = os.path.exists("/dev/kvm") and os.access(
        "/dev/kvm", os.R_OK | os.W_OK)
    assert caps["kvm"] == expected
    expect_emulator = caps["tools"].get("adb") is True or expected
    assert ("EMULATOR" in caps["deviceKinds"]) == expect_emulator
