# -*- coding: utf-8 -*-
pass
import argparse
import importlib
import sys
from pathlib import Path

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

s1_bootstrap = importlib.import_module("s1_bootstrap")
demo_seed = importlib.import_module("demo_seed")
INSTALL_SH = ROOT / "install.sh"
INSTALL_PS1 = ROOT / "install.ps1"

DEAD_URL = "http://127.0.0.1:59911"


def _args(base_url=DEAD_URL):
    return argparse.Namespace(base_url=base_url)


def _hermetic_root(monkeypatch, tmp_path):
    pass
    for key in ("BASE_URL", "INSTALLKIT_SEED_BASE_URL"):
        monkeypatch.delenv(key, raising=False)
    for key in s1_bootstrap.host_ports.HOST_PORT_KEYS:
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr(s1_bootstrap, "REPO_ROOT", tmp_path)
    return tmp_path


def _stub_ensures(monkeypatch, seen, centers=None):
    def stub_sample(url):
        seen.append("sample")
        return 7, 9

    def stub_journey(url):
        seen.append("journey")
        return {"created": 5, "project": "p"}

    def stub_centers(url):
        seen.append("centers")
        return centers or {"created": 4}

    monkeypatch.setattr(demo_seed, "ensure_sample", stub_sample)
    monkeypatch.setattr(demo_seed, "ensure_journey", stub_journey)
    monkeypatch.setattr(demo_seed, "ensure_centers", stub_centers)


def test_seed_demo_command_shape_and_zero_credentials(monkeypatch, tmp_path):
    cmd = s1_bootstrap.seed_demo_command("http://10.0.0.9:8081")
    assert "s1_bootstrap.py" in cmd and "seed-demo" in cmd and "http://10.0.0.9:8081" in cmd
    joined = cmd.lower()
    for banned in ("password", "token", "密码", "令牌"):
        assert banned not in joined, "红线 4：播种命令零内联凭据（口令从 .env/环境变量读）"
    _hermetic_root(monkeypatch, tmp_path)
    assert "localhost:8081" in s1_bootstrap.seed_demo_command(), \
        "进程环境无带宽键且树无 .env = admin 出厂口（缺省语义 hermetic 前置）"


def test_seed_demo_not_ready_degrades_honestly(monkeypatch, capsys):
    calls = []
    monkeypatch.setattr(s1_bootstrap, "admin_ready", lambda url, **kw: False)
    for name in ("ensure_sample", "ensure_journey", "ensure_centers"):
        def _rec(_n=name, *a, **k):
            calls.append(_n)
            return {"created": 0}
        monkeypatch.setattr(demo_seed, name, _rec)
    rc = s1_bootstrap.cmd_seed_demo(_args())
    out = capsys.readouterr().out
    assert rc == 0, "不就绪=诚实降级 rc0，禁拦装机流程"
    assert calls == [], "不就绪时禁真发起播种调用"
    assert "不可达" in out and "就绪" in out
    assert "python3 installkit/s1_bootstrap.py seed-demo" in out, "降级必须给可复读重跑命令（R63 假路即缺陷）"
    assert "幂等" in out, "透出重跑安全语义"


def test_seed_demo_consumes_three_seed_entries_in_order(monkeypatch, capsys):
    seen = []
    monkeypatch.setattr(s1_bootstrap, "admin_ready", lambda url, **kw: True)
    _stub_ensures(monkeypatch, seen)
    rc = s1_bootstrap.cmd_seed_demo(_args(base_url="http://10.0.0.9:8081"))
    out = capsys.readouterr().out
    assert rc == 0
    assert seen == ["sample", "journey", "centers"], "消费序=样例→旅程→剧本 各恰一次（禁重复/漏段）"
    assert "播种完成" in out and "体验动线" in out, "就绪态透出结果与页面入口动线"


def test_seed_demo_mid_failure_degrades_not_blocks(monkeypatch, capsys):
    monkeypatch.setattr(s1_bootstrap, "admin_ready", lambda url, **kw: True)

    def boom(url):
        raise RuntimeError("登录已失效 401")

    monkeypatch.setattr(demo_seed, "ensure_sample", lambda url: (7, 9))
    monkeypatch.setattr(demo_seed, "ensure_journey", boom)
    monkeypatch.setattr(demo_seed, "ensure_centers", lambda url: {"created": 0})
    rc = s1_bootstrap.cmd_seed_demo(_args())
    err = capsys.readouterr().err
    assert rc == 0, "演示数据非装机前置：中途失败降级续跑，禁阻断整个装机"
    assert "播种未完成" in err and "登录已失效" in err, "原因点名透传（R63 要素一）"
    assert "seed-demo" in err and "重跑" in err and "幂等" in err, "修法给重跑命令且明示幂等续播（要素二）"


def test_seed_demo_systemexit_degrades_not_blocks(monkeypatch, capsys):
    pass
    monkeypatch.setattr(s1_bootstrap, "admin_ready", lambda url, **kw: True)
    monkeypatch.setattr(demo_seed, "ensure_sample", lambda url: (7, 9))
    monkeypatch.setattr(demo_seed, "ensure_journey", lambda url: {"created": 1})

    def boom(url):
        raise SystemExit("上游直接退出")

    monkeypatch.setattr(demo_seed, "ensure_centers", boom)
    rc = s1_bootstrap.cmd_seed_demo(_args())
    err = capsys.readouterr().err
    assert rc == 0, "SystemExit 同样诚实降级，禁阻断装机"
    assert "播种未完成" in err and "上游直接退出" in err, "原因点名上屏（R63 要素一）"
    assert "seed-demo" in err and "重跑" in err, "重跑命令上屏（要素二）"


def test_probe_is_login_identity_not_port_answer(monkeypatch):
    captured = {}

    class _Resp:
        status_code = 200

        def __init__(self, payload):
            self._payload = payload

        def raise_for_status(self):
            if self._payload is None:
                raise RuntimeError("login refused 401")

        def json(self):
            return self._payload

    def fake_post(url, json=None, **kw):
        captured["url"] = url
        captured["body"] = json
        return _Resp({"data": {"accessToken": "opaque"}})

    monkeypatch.setattr(httpx, "post", fake_post)
    assert s1_bootstrap.admin_ready("http://10.1.2.3:8081/") is True
    assert captured["url"].startswith("http://10.1.2.3:8081/"), "探测与播种同 host:port，禁换目标冒充就绪"
    assert "username" in captured["body"] and "password" in captured["body"], "探测必须是真登录形态（凭据可用性并入判据）"

    monkeypatch.setattr(httpx, "post", lambda url, **kw: _Resp(None))
    assert s1_bootstrap.admin_ready("http://10.1.2.3:8081") is False, "端口有应答但登录被拒=目标不可信（别的服务占端口的形态）"

    def refuse(url, **kw):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "post", refuse)
    assert s1_bootstrap.admin_ready("http://10.1.2.3:8081") is False


def test_seed_channel_resolves_band_key_from_process_env(monkeypatch, tmp_path, capsys):
    pass
    _hermetic_root(monkeypatch, tmp_path)
    monkeypatch.setenv("APP_ADMIN_HOST_PORT", "25411")
    probed = {}

    def fake_ready(url, **kw):
        probed["url"] = url
        return False

    monkeypatch.setattr(s1_bootstrap, "admin_ready", fake_ready)
    assert s1_bootstrap.cmd_seed_demo(_args(base_url="")) == 0
    assert probed["url"] == "http://localhost:25411", "装机带宽键必须被播种通道消费（与 compose 解析同源）"
    out = capsys.readouterr().out
    assert "25411" in out, "可复读重跑命令回显装机口（屏显命令=照做能跑的命令）"

    s1_bootstrap.cmd_seed_demo(_args(base_url=DEAD_URL))
    assert probed["url"] == DEAD_URL, "显式 --base-url 最高优先"
    monkeypatch.setenv("INSTALLKIT_SEED_BASE_URL", "http://127.0.0.1:59912")
    s1_bootstrap.cmd_seed_demo(_args(base_url=""))
    assert probed["url"] == "http://127.0.0.1:59912", "显式覆盖通道 INSTALLKIT_SEED_BASE_URL 高于带宽键"


def test_seed_channel_resolves_band_key_from_tree_dotenv(monkeypatch, tmp_path):
    root = _hermetic_root(monkeypatch, tmp_path)
    (root / ".env").write_text("APP_ADMIN_HOST_PORT=25412\n", encoding="utf-8")
    probed = {}

    def fake_ready(url, **kw):
        probed["url"] = url
        return False

    monkeypatch.setattr(s1_bootstrap, "admin_ready", fake_ready)
    s1_bootstrap.cmd_seed_demo(_args(base_url=""))
    assert probed["url"] == "http://localhost:25412", "树 .env 带宽键=第二解析源"
    monkeypatch.setenv("APP_ADMIN_HOST_PORT", "25411")
    s1_bootstrap.cmd_seed_demo(_args(base_url=""))
    assert probed["url"] == "http://localhost:25411", "进程环境优先于树 .env（compose 同语义）"


def test_install_sh_seed_demo_position_has_no_fallback_literal():
    pass
    text = INSTALL_SH.read_text(encoding="utf-8")
    lines = text.splitlines()
    idx = [i for i, l in enumerate(lines) if "run_step seed-demo" in l]
    assert idx, "首装位点=seed-demo（自动播种，禁回退只打印命令）"
    block = "\n".join(lines[max(0, idx[0] - 2):idx[0] + 2])
    assert "8081" not in block, "回退字面摘除：壳侧禁猜地址"
    assert "INSTALLKIT_SEED_BASE_URL" in block, "仅显式覆盖才传 --base-url"
    assert '--base-url "${INSTALLKIT_SEED_BASE_URL:-' not in text, "禁 :- 回退链（装机键单源在 s1 侧）"
    assert "trial-run" not in text, "trial-run 位点退役零残留（R31）"


def test_install_ps1_seed_demo_same_retirement_standard():
    ps1 = INSTALL_PS1.read_text(encoding="utf-8-sig")
    assert "http://localhost:8081" not in ps1, "windows 镜像同口径：壳侧回退字面摘除（单源承诺）"
    assert 'Run-Step "seed-demo"' in ps1


def test_trial_run_fully_retired_in_python_sources():
    seed_src = (ROOT / "s1_bootstrap.py").read_text(encoding="utf-8")
    assert 'add_parser("seed-demo"' in seed_src
    for name in ("s1_bootstrap.py", "demo_seed.py", "s1_wizard.py"):
        src = (ROOT / name).read_text(encoding="utf-8")
        assert "trial" not in src.lower(), "%s 仍残留退役面（R31 随批清扫）" % name
