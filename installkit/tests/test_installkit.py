# -*- coding: utf-8 -*-
pass
import json
import os
import re
import subprocess
import sys
import importlib
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

ROOT = Path(__file__).resolve().parents[1]
INSTALL_SH = ROOT / "install.sh"
CLIENT_PY = ROOT / "enroll_client.py"
RECIPE_PY = ROOT / "recipe.py"
PKG_PROBE_PY = ROOT / "pkg_probe.py"
DOCTOR_PY = ROOT / "doctor.py"
LEDGER_PY = ROOT / "ledger.py"
sys.path.insert(0, str(ROOT))

SECRET = "tk-unit-0001"

MW_RECIPE = {
    "version_pin": "0.0.63",
    "hosts": {
        "windows": {"probe": ["node --version", "mobilewright --version"],
                    "install": [{"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"}],
                    "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]},
        "macos": {"probe": ["node --version"],
                  "install": [{"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"}],
                  "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]},
        "linux": {"probe": ["node --version", "mobilewright --version"],
                  "install": [{"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"}],
                  "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]},
    },
    "manual_steps": ["mobilecli 二进制需自行安装并在 PATH（testPike 不随包分发）"],
}

MINIUM_RECIPE = {
    "version_pin": "1.6.1",
    "hosts": {
        "linux": {"probe": ["python3 -m pip show minium"],
                  "install": [{"cmd": "python3 -m pip install minium==1.6.1", "mirror_rewrite": "index_url"}],
                  "uninstall": [{"cmd": "python3 -m pip uninstall -y minium"}]},
    },
    "manual_steps": [
        "微信开发者工具无法静默安装：① 官方页下载并安装 ② 打开设置→安全→服务端口开关 ③ 扫码登录小程序账号",
    ],
}


class Stub(BaseHTTPRequestHandler):
    pass

    requests = []
    fail_enroll = False
    mirror_value = "http://mirror.example.net/m"
    recipe_engine = "mobilewright"
    commands: list = []

    def log_message(self, format, *args):
        pass

    def _send(self, payload):
        raw = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        parsed = urlparse(self.path)
        Stub.requests.append((parsed.path, {}))
        if parsed.path == "/internal/cluster/installkit-config":
            self._send({"code": "0", "msg": "",
                        "data": {"mirrorBaseUrl": Stub.mirror_value}})
        elif parsed.path == "/internal/cluster/engine-recipe":
            code = (parse_qs(parsed.query).get("code") or [""])[0]
            if code == Stub.recipe_engine:
                self._send({"code": "0", "msg": "",
                            "data": {"code": code, "installRecipe": MW_RECIPE}})
            else:
                self._send({"code": "40400", "msg": f"引擎「{code}」不存在——请先在引擎中心登记",
                            "data": None})
        else:
            self._send({"code": "40400", "msg": "接口不存在", "data": None})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        Stub.requests.append((self.path, body))
        if Stub.fail_enroll and self.path.endswith("/enroll"):
            payload = {"code": "41001", "msg": "入池令牌已过期——请在机群页重新生成一条命令后再试", "data": None}
        elif self.path == "/internal/cluster/enroll":
            payload = {"code": "0", "msg": "", "data": {"tokenId": 7, "role": "executor", "projectScope": None}}
        elif self.path == "/internal/cluster/enroll/heartbeat":
            payload = {"code": "0", "msg": "", "data": None}
        elif self.path == "/internal/cluster/commands/poll":
            payload = {"code": "0", "msg": "", "data": Stub.commands}
        elif self.path in ("/internal/cluster/commands/progress",
                           "/internal/cluster/commands/result"):
            payload = {"code": "0", "msg": "", "data": None}
        else:
            payload = {"code": "40400", "msg": "接口不存在", "data": None}
        self._send(payload)


@pytest.fixture()
def server():
    Stub.requests = []
    Stub.fail_enroll = False
    Stub.mirror_value = "http://mirror.example.net/m"
    Stub.recipe_engine = "mobilewright"
    Stub.commands = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield "http://127.0.0.1:%d" % srv.server_address[1]
    srv.shutdown()


@pytest.fixture()
def sandbox(tmp_path, monkeypatch):
    state = tmp_path / "state"
    logs = tmp_path / "logs"
    monkeypatch.setenv("INSTALLKIT_STATE_DIR", str(state))
    monkeypatch.setenv("INSTALLKIT_LOG_DIR", str(logs))
    monkeypatch.setenv("INSTALLKIT_SEED_BASE_URL", "http://127.0.0.1:59911")
    return state, logs


def run_sh(args, env=None):
    return subprocess.run(["bash", str(INSTALL_SH)] + args,
                          capture_output=True, text=True, timeout=60,
                          env=env if env is not None else os.environ.copy())


def out(proc):
    return proc.stdout + proc.stderr



def test_unknown_scenario_rejected_human(sandbox):
    proc = run_sh(["--scenario=nope"])
    assert proc.returncode == 2
    text = out(proc)
    assert "scenario" in text and "first" in text


def test_missing_scenario_prints_usage(sandbox):
    proc = run_sh([])
    assert proc.returncode == 2
    assert "--scenario" in out(proc)


def test_help_exits_zero(sandbox):
    proc = run_sh(["--help"])
    assert proc.returncode == 0
    text = out(proc)
    for word in ("first", "scale-out", "scale-in", "engines", "repair"):
        assert word in text


def test_s1_skeleton_routing_and_resume(sandbox, monkeypatch):
    state, logs = sandbox
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    proc = run_sh(["--scenario=first"])
    assert proc.returncode == 0
    files = list(state.glob("*.txt"))
    assert files, "位点文件未落盘"
    done = files[0].read_text()
    assert len(done.strip().splitlines()) >= 3, "S1 骨架步骤未全部记账"
    logs_files = list(logs.glob("*.log"))
    assert logs_files, "日志未落盘"
    text = logs_files[0].read_text()
    assert re.search(r"\d{2}:\d{2}:\d{2}", text), "日志缺时间戳"
    again = run_sh(["--scenario=first"])
    assert again.returncode == 0
    assert "already completed" in out(again)
    assert files[0].read_text() == done, "续跑重复追加了位点"


def test_scale_out_enroll_and_heartbeat_contract(server, sandbox):
    state, logs = sandbox
    proc = run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    paths = [p for p, _ in Stub.requests]
    assert paths == ["/internal/cluster/enroll/heartbeat",
                     "/internal/cluster/enroll",
                     "/internal/cluster/enroll/heartbeat"]
    enroll_body = dict(Stub.requests[1][1])
    assert set(enroll_body.keys()) == {"token", "fingerprint"}
    assert enroll_body["token"] == SECRET
    assert re.fullmatch(r"[0-9a-f]{64}", enroll_body["fingerprint"])
    text = "".join(f.read_text() for f in logs.glob("*.log"))
    assert SECRET not in text, "明文令牌落盘=红线 4 违规"
    assert "executor" in out(proc), "核销回包角色未透出"


def test_scale_out_rerun_zero_calls(server, sandbox):
    run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
            "--base-url=" + server])
    first = len(Stub.requests)
    Stub.requests = []
    proc = run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    assert len(Stub.requests) == 0, "位点续跑应零重复外呼（幂等可重跑）"
    assert first == 3


def test_restart_clears_checkpoints(server, sandbox):
    run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
            "--base-url=" + server])
    Stub.requests = []
    proc = run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server, "--restart"])
    assert proc.returncode == 0, out(proc)
    assert len(Stub.requests) == 3, "--restart 后必须从头跑"


def test_role_value_domain_guard(sandbox):
    proc = run_sh(["--scenario=scale-out", "--role=robot", "--token=x", "--base-url=http://x"])
    assert proc.returncode == 2
    text = out(proc)
    assert "executor" in text and "ai-node" in text and "platform" in text


def test_missing_token_human(sandbox):
    proc = run_sh(["--scenario=scale-out", "--role=executor"])
    assert proc.returncode == 2
    assert "--token" in out(proc)


def test_enroll_fail_passthrough(server, sandbox):
    state, _ = sandbox
    Stub.fail_enroll = True
    proc = run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode != 0
    assert "已过期" in out(proc), "服务端人话文案必须原样透传"
    files = list(state.glob("*.txt"))
    content = files[0].read_text() if files else ""
    assert "enroll-token" not in content, "失败步骤不得记账为已完成"
    paths = [p for p, _ in Stub.requests]
    assert paths.count("/internal/cluster/enroll/heartbeat") == 1, "失败后不得继续收尾打点"


def test_scale_in_uninstall_boundary(sandbox):
    proc = run_sh(["--scenario=scale-in"])
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "only what installkit itself installed" in text


def test_engines_repair_routing(sandbox):
    state, _ = sandbox
    proc = run_sh(["--scenario=repair"])
    assert proc.returncode == 0, out(proc)
    names = {f.name for f in state.glob("*.txt")}
    assert "state-repair.txt" in names
    proc = run_sh(["--scenario=engines"])
    assert proc.returncode == 2
    assert "--engine" in out(proc)


def test_scenario_alias_canonical(server, sandbox):
    state, _ = sandbox
    proc = run_sh(["--scenario=S2", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    assert (state / "state-scale-out.txt").exists(), "别名须归一 canonical 场景名"



def test_client_fingerprint_stable():
    a = subprocess.run([sys.executable, str(CLIENT_PY), "fingerprint"],
                       capture_output=True, text=True)
    b = subprocess.run([sys.executable, str(CLIENT_PY), "fingerprint"],
                       capture_output=True, text=True)
    assert a.returncode == 0 and b.returncode == 0
    fa, fb = a.stdout.strip(), b.stdout.strip()
    assert fa == fb and re.fullmatch(r"[0-9a-f]{64}", fa)


def test_client_unreachable_human():
    proc = subprocess.run([sys.executable, str(CLIENT_PY), "heartbeat",
                           "--base-url=http://127.0.0.1:1", "--token=x"],
                          capture_output=True, text=True, timeout=30)
    assert proc.returncode == 1
    assert "unreachable" in out(proc)



import recipe  # noqa: E402


def test_recipe_install_commands_and_version_pin():
    cmds = recipe.commands_for(MW_RECIPE, "install", host="linux")
    assert cmds == ["npm install -g mobilewright@0.0.63"]


def test_recipe_mirror_rewrite():
    cmds = recipe.commands_for(MW_RECIPE, "install", host="linux",
                               mirror_base_url="http://mirror.example.net/mirror")
    assert "--registry=http://mirror.example.net/mirror/npm" in cmds[0]
    pips = recipe.commands_for(MINIUM_RECIPE, "install", host="linux",
                               mirror_base_url="http://mirror.example.net/mirror")
    assert "-i http://mirror.example.net/mirror/simple" in pips[0]


def test_recipe_probe_and_uninstall():
    assert recipe.commands_for(MW_RECIPE, "probe", host="windows")[0] == "node --version"
    assert recipe.commands_for(MW_RECIPE, "uninstall", host="linux") == ["npm uninstall -g mobilewright"]


def test_recipe_empty_rejected_human():
    with pytest.raises(ValueError) as e:
        recipe.commands_for({}, "install", host="linux")
    assert "refusing" in str(e.value)


def test_recipe_unknown_host_honest():
    with pytest.raises(ValueError) as e:
        recipe.commands_for(MINIUM_RECIPE, "install", host="windows")
    assert "no profile for this host" in str(e.value)


def test_recipe_manual_steps_wd_guide():
    steps = recipe.manual_steps(MINIUM_RECIPE)
    assert any("服务端口" in s for s in steps), "微信开发者工具三步引导必须内置透传"


def test_recipe_cli_install_preview(tmp_path):
    f = tmp_path / "r.json"
    f.write_text(json.dumps(MW_RECIPE))
    proc = subprocess.run([sys.executable, str(RECIPE_PY), "install",
                           "--recipe-file=" + str(f), "--host=linux"],
                          capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, out(proc)
    assert "npm install -g mobilewright@0.0.63" in proc.stdout



MIG_DIR = ROOT.parent / "db" / "migration"
V173_SQL = MIG_DIR / "V173__appauto_engine_recipes.sql"
SEEDED_CODES = ["APPIUM", "XCUITEST", "MAESTRO", "HMNEXTAUTO", "MINIPROGRAM_AUTOMATOR"]


def _load_v173_recipes():
    pass
    text = V173_SQL.read_text(encoding="utf-8")
    blocks = re.findall(r"UPDATE tp_appauto_engine\s+SET install_recipe = '(.*?)'::jsonb(.*?);",
                        text, re.DOTALL)
    out_map = {}
    for raw, tail in blocks:
        m = re.search(r"WHERE\s+code\s*=\s*'([A-Z_]+)'", tail)
        assert m, "种子 UPDATE 段缺 WHERE code 定位"
        out_map[m.group(1)] = json.loads(raw)
    return out_map


def test_v173_seed_covers_five_engines_and_hypium_stays_honest_empty():
    assert V173_SQL.exists(), "V173 六引擎配方种子文件必须存在"
    seeds = _load_v173_recipes()
    assert set(seeds) == set(SEEDED_CODES), "种子必须恰覆盖五引擎（HYPIUM 下架不给配方）"
    proc = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike",
         "-tAc", "SELECT install_recipe='{}'::jsonb FROM tp_appauto_engine WHERE code='HYPIUM';"],
        capture_output=True, text=True, timeout=30, check=False)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.strip() == "t", "HYPIUM 下架=空配方诚实缺省，禁给配方"


def test_v173_recipes_consumable_by_recipe_engine():
    pass
    seeds = _load_v173_recipes()
    for code, rec in seeds.items():
        pin = rec.get("version_pin")
        assert pin, f"{code} 缺版本钉"
        hosts = rec["hosts"]
        assert hosts, f"{code} 缺宿主画像"
        assert rec.get("manual_steps"), f"{code} 缺人工步骤清单"
        for host, spec in hosts.items():
            assert spec.get("probe"), f"{code}/{host} 缺探测段"
            assert spec.get("install"), f"{code}/{host} 缺安装段"
            assert spec.get("uninstall"), f"{code}/{host} 缺卸载段"
            cmds = recipe.commands_for(rec, "install", host=host,
                                       mirror_base_url="http://mirror.example.net/m")
            assert cmds, f"{code}/{host} 安装命令消费为空"
            assert pin in " ".join(cmds), f"{code}/{host} 安装链未钉版 {pin}"


def test_v173_appium_and_xcuitest_driver_split():
    seeds = _load_v173_recipes()
    ap = seeds["APPIUM"]
    cmds = recipe.commands_for(ap, "install", host="linux")
    assert any("appium@3.8.0" in c for c in cmds), "Appium 必须按钉 3.8.0 装"
    assert recipe.commands_for(ap, "probe", host="linux")[0] == "node --version"
    x = seeds["XCUITEST"]
    assert set(x["hosts"]) == {"macos"}, "XCUITEST 仅 macOS 宿主（§9.3 限定，禁凑别的宿主）"
    with pytest.raises(ValueError) as e:
        recipe.commands_for(x, "install", host="linux")
    assert "macos" in str(e.value), "linux 宿主必须人话点名仅覆盖 macos"
    xcmds = recipe.commands_for(x, "install", host="macos")
    assert any("xcuitest" in c for c in xcmds), "XCUITEST 配方必须含 driver 安装段"
    assert any("appium driver remove" in c for c in
               recipe.commands_for(x, "uninstall", host="macos")), "卸载必须先摘 driver"


def test_v173_maestro_jdk_gate_and_pinned_download():
    seeds = _load_v173_recipes()
    m = seeds["MAESTRO"]
    assert "java -version" in recipe.commands_for(m, "probe", host="linux"), "jdk>=17 前置闸"
    cmds = recipe.commands_for(m, "install", host="linux")
    assert any("cli-2.11.0/maestro.zip" in c and "curl" in c for c in cmds), \
        "Maestro=钉版 curl 下载（cli-2.11.0 GitHub Release 实证产物 maestro.zip）"
    assert any("unzip" in c for c in cmds), "下载包解包段必须在安装链内"
    assert any("外网" in s or "不可达" in s for s in m["manual_steps"]), \
        "内网不可达 GitHub=人工步骤诚实声明"


def test_v173_hmnextauto_hdc_and_miniprogram_wd_guide():
    seeds = _load_v173_recipes()
    h = seeds["HMNEXTAUTO"]
    probes = recipe.commands_for(h, "probe", host="linux")
    assert any("hdc -v" in p for p in probes), "hdc 探测必须用 -v 旗标"
    assert any("hmdriver2" in p for p in probes), "python 导入探针必须在"
    icmds = recipe.commands_for(h, "install", host="linux")
    assert any("hmdriver2==1.4.4" in c for c in icmds), "pip hmdriver2 按钉 1.4.4"
    assert any("index_url" == (e.get("mirror_rewrite") or "") and "hdc" not in (e.get("cmd") or "")
               for e in h["hosts"]["linux"]["install"]), "仅 pip 段挂镜像改道"
    mp = seeds["MINIPROGRAM_AUTOMATOR"]
    steps = " ".join(mp["manual_steps"])
    assert "微信开发者工具" in steps and "登录" in steps, "三步引导必须内置"
    assert "9420" in steps, "9420 连通探针口径必须内置"
    cmds = recipe.commands_for(mp, "install", host="windows")
    assert any("miniprogram-automator@0.12.1" in c for c in cmds)



import pkg_probe  # noqa: E402


def test_pkg_probe_os_release_profiles():
    assert pkg_probe.pkg_manager_for("linux", "ID=ubuntu\nID_LIKE=debian") == "apt"
    assert pkg_probe.pkg_manager_for("linux", "ID=fedora") == "dnf"
    assert pkg_probe.pkg_manager_for("linux", "ID=rhel\nID_LIKE=\"centos fedora\"") == "dnf"
    assert pkg_probe.pkg_manager_for("macos", None) == "brew"
    assert pkg_probe.pkg_manager_for("windows", None) == "winget"


def test_pkg_probe_unknown_linux_distro_honest_none():
    assert pkg_probe.pkg_manager_for("linux", "ID=someos") is None
    assert pkg_probe.pkg_manager_for("linux", "") is None


def test_pkg_probe_three_profile_install_commands():
    for pm in ("apt", "dnf", "brew", "winget", "choco"):
        for prereq in ("docker", "node", "git"):
            cmd = pkg_probe.install_command(pm, prereq)
            assert isinstance(cmd, str) and cmd.strip(), pm + "/" + prereq
    assert "apt-get install" in pkg_probe.install_command("apt", "docker")
    assert "brew install" in pkg_probe.install_command("brew", "node")
    assert "winget install" in pkg_probe.install_command("winget", "git")


def test_pkg_probe_unknown_rejected_human():
    with pytest.raises(ValueError) as e:
        pkg_probe.install_command("portage", "docker")
    assert "package-manager profile" in str(e.value)
    with pytest.raises(ValueError) as e2:
        pkg_probe.install_command("apt", "nope")
    assert "Prerequisite" in str(e2.value)


def test_pkg_probe_preflight_missing_prereq_human_with_hint(tmp_path):
    env = dict(os.environ, PATH=str(tmp_path))
    proc = subprocess.run([sys.executable, str(PKG_PROBE_PY), "preflight", "--require=node,git"],
                          capture_output=True, text=True, timeout=30, env=env)
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "node" in text and "missing" in text
    assert "install" in text, "缺件报告须附本画像安装命令"


def test_pkg_probe_preflight_ports_and_os_report():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    s.listen(1)
    port = s.getsockname()[1]
    proc = subprocess.run([sys.executable, str(PKG_PROBE_PY), "preflight",
                           "--require=", "--ports=" + str(port)],
                          capture_output=True, text=True, timeout=30)
    s.close()
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "OS" in text and "package manager" in text
    assert str(port) in text and "busy" in text


def test_pkg_probe_preflight_unknown_requirement_rejected_human():
    proc = subprocess.run([sys.executable, str(PKG_PROBE_PY), "preflight", "--require=nope"],
                          capture_output=True, text=True, timeout=30)
    assert proc.returncode == 2
    assert "Prerequisite" in out(proc)



import s1_wizard  # noqa: E402
import s1_bootstrap  # noqa: E402
import demo_seed  # noqa: E402


def test_s1_wizard_questions_at_most_five_all_with_defaults():
    qs = s1_wizard.QUESTIONS
    assert 1 <= len(qs) <= 5, "冻结 §9.2 上限=5 题"
    for q in qs:
        assert "default" in q.prompt, "每题必须带默认值（可一路回车）"
    joined = " ".join(q.prompt for q in qs).lower()
    assert "token" not in joined and "password" not in joined, \
        "红线 4：问答零敏感值采集（令牌只走 S2 场景请求体）"


def test_s1_wizard_reads_pipe_answers_and_saves(tmp_path):
    answers = s1_wizard.run(input_text="2\nmobilewright\nhttp://mirror.example.net/m\n/opt/tp\ny\n",
                            answers_path=tmp_path / "a.json")
    assert answers["role"] == "executor"
    assert answers["engines"] == ["mobilewright"]
    assert answers["mirror_base_url"] == "http://mirror.example.net/m"
    assert answers["install_dir"] == "/opt/tp"
    assert answers["autostart"] is True
    assert json.loads((tmp_path / "a.json").read_text(encoding="utf-8")) == answers


def test_s1_wizard_eof_takes_all_defaults(tmp_path):
    answers = s1_wizard.run(input_text="", answers_path=tmp_path / "a.json",
                            mirror_default="http://mirror.example.net/base")
    assert answers["role"] == "platform", "题1 默认=平台服务器（5 分钟首跑价值锚主场景）"
    assert "mobilewright" in answers["engines"], "题2 默认=已实装配方引擎"
    assert answers["mirror_base_url"] == "http://mirror.example.net/base", "题3 默认=CLI/平台键透传值"
    assert answers["autostart"] is False


def test_s1_wizard_junk_answer_falls_back_human(tmp_path):
    answers = s1_wizard.run(input_text="9\n\n\n\n\n", answers_path=tmp_path / "a.json")
    assert answers["role"] == "platform", "越界选项回退默认+人话提示，装机不炸"


def test_s1_role_question_realwired_not_skeleton(sandbox, monkeypatch):
    state, _ = sandbox
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=first"],
                          input="", capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    content = (state / "state-first.txt").read_text()
    assert "step:role-question" in content, "问答位点未记账"
    assert "placeholder" not in text.lower(), "role-question 仍是骨架占位=未实装"
    assert "role" in text.lower() and "default" in text.lower(), "问答须透出角色与默认值语义"
    assert (state / "answers-S1.json").exists(), "答案未落盘供后续位点消费"



import s1_bootstrap  # noqa: E402
import demo_seed  # noqa: E402


def test_s1_bootstrap_compose_plan_platform_vs_executor(tmp_path):
    answers = tmp_path / "a.json"
    answers.write_text(json.dumps({"role": "platform", "engines": ["mobilewright"]}),
                       encoding="utf-8")
    plan = s1_bootstrap.compose_plan(str(answers), repo_root="/repo")
    assert plan and plan[0][:2] == ["docker", "compose"], "平台角色必须拉起中间件 compose"
    assert "docker-compose.yml" in " ".join(plan[0])
    answers.write_text(json.dumps({"role": "executor"}), encoding="utf-8")
    assert s1_bootstrap.compose_plan(str(answers), repo_root="/repo") == [], \
        "纯执行器机不起 compose（§9.2 题1 分支语义）"


def test_s1_bootstrap_missing_answers_honest_default(tmp_path):
    plan = s1_bootstrap.compose_plan(str(tmp_path / "nope.json"), repo_root="/repo")
    assert plan and plan[0][:2] == ["docker", "compose"], "答案缺失=按默认角色 platform 走"


def test_s1_bootstrap_four_services_guide_never_force_stops():
    guide = " ".join(s1_bootstrap.four_services_guide())
    assert "four services" in guide and ("java -jar" in guide or "systemd" in guide)
    banned = ["systemctl" + " stop", "kill" + " -9", "docker" + " stop"]
    for word in banned:
        assert word not in guide, "禁脚本强停他人服务（本机常驻栈冲突的诚实姿势）"


def test_s1_bootstrap_seed_demo_command_zero_credentials():
    cmd = s1_bootstrap.seed_demo_command("http://10.0.0.9:8081")
    assert "seed-demo" in cmd and "http://10.0.0.9:8081" in cmd
    joined = cmd.lower()
    for banned in ("password", "token", "密码", "令牌"):
        assert banned not in joined, "红线 4：播种命令零内联凭据（口令从 .env/环境变量读）"


def test_demo_seed_payloads_are_seeded_sample_shape():
    proj = demo_seed.project_payload()
    assert proj["name"] == demo_seed.DEMO_PROJECT_NAME
    assert proj["code"] == demo_seed.DEMO_PROJECT_CODE
    case = demo_seed.case_payload(42)
    assert case["projectId"] == 42 and case["endType"] == "web"
    assert case["title"] == demo_seed.DEMO_CASE_TITLE, "样例=一条 Web 用例（§9.5-3 价值锚）"


def test_demo_seed_api_case_payloads_match_admin_contract():
    pass
    coll = demo_seed.api_collection_payload(42)
    assert coll["projectId"] == 42 and coll["parentId"] == 0
    assert coll["name"] == demo_seed.DEMO_API_COLLECTION_NAME
    req = demo_seed.api_request_payload(7)
    assert req["collectionId"] == 7
    assert req["method"] == "GET"
    assert req["urlPath"].startswith("/") and "://" not in req["urlPath"], \
        "SSRF 红线：urlPath 仅路径段，host 只从环境档案 baseUrl 来"
    assert json.loads(req["assertionsJson"]) == [{"type": "status", "op": "equals", "expect": 200}], \
        "断言=状态码 200（沿 l3-w1 姿势）"


def test_demo_seed_flow_demo_payloads_match_orchestration_vocabulary():
    """Factory demo journey : login -> order -> poll ->
 paging, four hops. The precondition/loop columns must be members of the
 shared both-ends fixture vocabulary (save-gate authority = common
 ApiOrchestrationContract) — the journey never carries a second syntax.
 SSRF rule same as the single-request sample: urlPath is a path segment."""
    flows = demo_seed.flow_demo_payloads(7)
    assert len(flows) == 4, "四跳=登录/建单/轮询/分页拉取（16 §53 T414⑤ 逐字）"
    cases = json.loads((ROOT.parent / "shared" / "orchestration_form_cases.json")
                       .read_text(encoding="utf-8"))
    pre_domain = {(p["row"] or {}).get("field", "") + "/" + (p["row"] or {}).get("op", "")
                  for p in cases["precondition"]["positives"]}
    loop_modes = {(l["row"] or {}).get("mode", "") for l in cases["loop"]["positives"]}
    for f in flows:
        assert f["collectionId"] == 7
        assert f["name"].startswith(demo_seed.DEMO_CLEAN_PREFIX)
        assert f["urlPath"].startswith("/") and "://" not in f["urlPath"], \
            "SSRF 红线：urlPath 仅路径段，host 只从环境档案 baseUrl 来"
        if f["preconditionJson"]:
            row = json.loads(f["preconditionJson"])
            assert row["field"] in ("status", "var") and \
                (row["field"] + "/" + row["op"]) in pre_domain, \
                "前置条件 (field,op) 组合必须命中两端同源夹具正向词表"
        if f["loopJson"]:
            row = json.loads(f["loopJson"])
            assert row["mode"] in loop_modes, "循环 mode 必须命中同源夹具正向词表"
            if row["mode"] == "until":
                assert isinstance(row.get("maxTimes"), int) and 1 <= row["maxTimes"] <= 100, \
                    "until 必带保护上限（保存闸同谱，失控即停）"
    m = [(f["method"], f["urlPath"]) for f in flows]
    assert m[0] == ("GET", "/login"), "跳1=登录可达"
    assert m[1] == ("POST", "/api/order"), "跳2=建单"
    assert m[2] == ("GET", "/api/order/{{VAR:ORDER_NO}}"), \
        "跳3=轮询订单（消费跳2 saveAs 的集合变量池）"
    assert m[3] == ("GET", "/api/orders/page/2"), "跳4=分页拉取第二页（夹具空态页）"
    hop2 = json.loads(flows[1]["assertionsJson"])
    assert any(a.get("saveAs") == "ORDER_NO" and a.get("type") == "extract"
               for a in hop2), "跳2 必须 extract 出 ORDER_NO 供跳3 消费"
    hop3_pre = json.loads(flows[2]["preconditionJson"])
    assert hop3_pre == {"field": "var", "op": "exists", "name": "ORDER_NO"}, \
        "跳3 前置=变量存在（建单未成即诚实跳过，禁裸打）"
    hop3_loop = json.loads(flows[2]["loopJson"])
    assert hop3_loop["mode"] == "until" and hop3_loop["maxTimes"] <= 5, \
        "轮询=条件停止带保护上限（失控即停）"
    hop4_loop = json.loads(flows[3]["loopJson"])
    assert hop4_loop["mode"] == "count" and hop4_loop["times"] >= 2, \
        "分页拉取=固定多轮"


def test_demo_seed_flow_demo_names_hit_cleanup_prefix():
    """One-click purge coverage: every journey hop name carries the tp_demo_
    prefix (zero-residue discipline), and so does the container name."""
    for f in demo_seed.flow_demo_payloads(1):
        assert f["name"].startswith(demo_seed.DEMO_CLEAN_PREFIX)
    assert demo_seed.DEMO_FLOW_COLLECTION_NAME.startswith(demo_seed.DEMO_CLEAN_PREFIX)


def test_demo_seed_app_case_payload_uses_real_target_fields():
    pass
    case = demo_seed.app_case_payload(42)
    assert case["projectId"] == 42 and case["endType"] == "mobile"
    assert case["automationTarget"] == "app" and case["appautoEngine"] == "MOBILEWRIGHT"
    assert case["title"] == demo_seed.DEMO_APP_CASE_TITLE


def test_demo_seed_data_factory_payload_matches_dto():
    pass
    df = demo_seed.data_factory_payload(42, 9)
    assert df["projectId"] == 42 and df["envId"] == 9 and df["type"] == 1
    assert df["name"] == demo_seed.DEMO_DATA_FACTORY_NAME
    content = json.loads(df["content"])
    assert set(content) == {"poolType", "method", "url", "headers", "body", "extractExpr"}
    assert content["url"].startswith("http://"), \
        "造数池 url=绝对地址（实证 core DataFactoryServiceImpl.executeApi URI.create 直消费，" \
        "与 apitest 路径段 SSRF 口径两域两谱，勿混——L4 活体 2026-10-11 勘正）"


def test_demo_seed_plan_payload_uses_current_exit_criteria_fields():
    pass
    plan = demo_seed.plan_payload(42, [9])
    assert plan["projectId"] == 42 and plan["envIds"] == [9]
    assert plan["name"] == demo_seed.DEMO_PLAN_NAME
    assert plan["exitCriteria"]["minPassRate"] == 0.95
    assert "passRate" not in plan["exitCriteria"], "历史 passRate 键勿照抄"


def test_demo_seed_badge_url_uses_existing_open_endpoint():
    pass
    url = demo_seed.badge_url("5f0d9c0a-1234-4abc-8def-0123456789ab",
                              api_base="http://localhost:8082")
    assert url == "http://localhost:8082/open/badge/5f0d9c0a-1234-4abc-8def-0123456789ab.svg"


def test_demo_seed_cleanup_prefix_hits_only_demo_rows():
    pass
    assert demo_seed.DEMO_CLEAN_PREFIX == "tp_demo_"
    names = [demo_seed.api_collection_payload(1)["name"],
             demo_seed.app_case_payload(1)["title"],
             demo_seed.data_factory_payload(1, 1)["name"],
             demo_seed.plan_payload(1, [1])["name"]]
    for n in names:
        assert n.startswith(demo_seed.DEMO_CLEAN_PREFIX), "%s 不带前缀=清除位扫不到或误伤" % n
    assert demo_seed.DEMO_PROJECT_NAME not in " ".join(names)
    assert "回归演示项目" not in " ".join(names)


def test_demo_seed_reads_credentials_from_env_not_inline(tmp_path, monkeypatch):
    monkeypatch.delenv("ADMIN_INIT_USER", raising=False)
    monkeypatch.delenv("ADMIN_INIT_PASSWORD", raising=False)
    envf = tmp_path / ".env"
    envf.write_text("ADMIN_INIT_USER=admin\nADMIN_INIT_PASSWORD=" + "Sup3r#pw\n",
                    encoding="utf-8")
    assert demo_seed.env_val("ADMIN_INIT_PASSWORD", env_file=envf) == "Sup3r#pw"
    src = (ROOT / "demo_seed.py").read_text(encoding="utf-8")
    assert "Sup3r#pw" not in src, "红线 4：源码禁内联凭据"


def test_s1_compose_seed_and_seed_demo_realwired_not_skeleton(sandbox, monkeypatch):
    state, _ = sandbox
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    monkeypatch.setenv("INSTALLKIT_SEED_BASE_URL", "http://127.0.0.1:59911")
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=first",
                           "--base-url=http://127.0.0.1:59911"],
                          input="", capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    content = (state / "state-first.txt").read_text()
    assert "step:compose-seed" in content and "step:seed-demo" in content
    assert "（骨架，T316 实装）" not in text, "compose/seed 位点仍是骨架占位=未实装"
    assert "four services" in text, "platform role must surface the four-services startup guide"
    assert "seed-demo" in text and "idempotent" in text, \
        "the seed stage must be real auto-seeding; with admin unreachable in this sandbox it has to degrade honestly to a re-runnable command and never hit the local 8081 slot0 service"


def test_s1_executor_role_branch_skips_compose(sandbox, monkeypatch):
    state, _ = sandbox
    Path(str(state)).mkdir(parents=True, exist_ok=True)
    flag = Path(str(state)) / "compose-ran.flag"
    stub = Path(str(state)) / "composestub.sh"
    stub.write_text("#!/bin/sh\ntouch %s\nexit 0\n" % flag, encoding="utf-8")
    stub.chmod(0o755)
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", str(stub))
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=first"],
                          input="2\n\n\n\n\n", capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, out(proc)
    assert not flag.exists(), "纯执行器机不得拉起 compose"
    assert "executor" in out(proc).lower()


def test_s1_compose_unreachable_fails_human_with_hint(sandbox, monkeypatch):
    state, _ = sandbox
    Path(str(state)).mkdir(parents=True, exist_ok=True)
    stub = Path(str(state)) / "composefail.sh"
    stub.write_text("#!/bin/sh\necho 'docker daemon 未启动' >&2\nexit 1\n", encoding="utf-8")
    stub.chmod(0o755)
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", str(stub))
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=first"],
                          input="", capture_output=True, text=True, timeout=120)
    assert proc.returncode != 0
    text = out(proc)
    assert "resume from the checkpoint" in text, "compose failure must name the cause and the resumable rerun (run_step semantics)"
    content = (state / "state-first.txt").read_text()
    assert "compose-seed" not in content, "失败步骤不得记账"


def test_s1_preflight_realwired_not_skeleton(sandbox, monkeypatch):
    state, _ = sandbox
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    proc = run_sh(["--scenario=first"])
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    files = list(state.glob("*.txt"))
    assert files[0].read_text().count("step:") >= 3, "S1 步骤记账断言（144 行同族）不得破坏"
    assert "step:preflight" in files[0].read_text()
    assert "Preflight report" in text and "package manager" in text
    assert "（骨架位点，S1 实装随 T316）" not in text, "预检仍是骨架占位=未实装"



def sh_first(extra):
    return ["bash", str(INSTALL_SH), "--scenario=first"] + extra
def test_enroll_client_fetch_mirror_prints_value_only(server):
    proc = subprocess.run([sys.executable, str(CLIENT_PY), "fetch-mirror",
                           "--base-url", server], capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert proc.stdout.strip() == "http://mirror.example.net/m", "stdout 只印键值（供 shell 命令替换消费）"
    assert [p for p, _ in Stub.requests] == ["/internal/cluster/installkit-config"]


def test_enroll_client_fetch_mirror_official_source_empty(server):
    Stub.mirror_value = ""
    proc = subprocess.run([sys.executable, str(CLIENT_PY), "fetch-mirror",
                           "--base-url", server], capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0
    assert proc.stdout.strip() == "", "键留空=官方源=印空串不印噪音"


def test_enroll_client_fetch_mirror_unreachable_human_nonzero(tmp_path):
    proc = subprocess.run([sys.executable, str(CLIENT_PY), "fetch-mirror",
                           "--base-url", "http://127.0.0.1:9"], capture_output=True, text=True, timeout=30)
    assert proc.returncode != 0
    assert "official source" in (proc.stdout + proc.stderr), "拉取失败须人话说明降级官方源"


def test_s1_first_fetches_mirror_key_before_question(server, sandbox, monkeypatch):
    state, _ = sandbox
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    proc = subprocess.run(sh_first(["--base-url=" + server]),
                          input="", capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, out(proc)
    paths = [p for p, _ in Stub.requests]
    assert paths[0] == "/internal/cluster/installkit-config", "问答前须先拉平台镜像键"
    assert "http://mirror.example.net/m" in out(proc), "题3 默认值=平台键透传值"
    assert "step:role-question" in (state / "state-first.txt").read_text()


def test_s1_cli_mirror_supersedes_platform_key(server, sandbox, monkeypatch):
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    proc = subprocess.run(sh_first(["--base-url=" + server,
                                    "--mirror-base-url=http://cli.example.net/x"]),
                          input="", capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, out(proc)
    assert [p for p, _ in Stub.requests] == [], "CLI 显式指定=不再拉键（零多余外呼）"
    assert "http://cli.example.net/x" in out(proc)


def test_s1_mirror_fetch_failure_degrades_not_blocks(sandbox, monkeypatch):
    state, _ = sandbox
    monkeypatch.setenv("INSTALLKIT_COMPOSE_BIN", "true")
    proc = subprocess.run(sh_first(["--base-url=http://127.0.0.1:9"]),
                          input="", capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, "拉键失败=人话降级官方源，S1 不阻断"
    text = out(proc)
    assert "official source" in text
    assert "step:role-question" in (state / "state-first.txt").read_text(), "降级后问答照常记账"




def test_ps1_s1_steps_consume_python_single_source():
    pass
    src = (ROOT / "install.ps1").read_text(encoding="utf-8-sig")
    assert "s1_wizard.py" in src and "s1_bootstrap.py" in src, \
        "ps1 S1 位点未接 python 单源件（二套 PS 问答=违 §9.0 单源承诺）"
    assert "（骨架，T316 实装）" not in src, "ps1 S1 位点仍是骨架占位=未实装"
    assert "--mirror-default" in src and "answers-S1.json" in src, \
        "题3 平台键透传与答案落盘契约须与 install.sh 同构"
    assert "Invoke-Py" in src, "python 件调用须走统一失败分型（throw=Run-Step 不记账可续跑）"
    assert "fetch-mirror" in src, \
        "镜像键拉取接线须与 install.sh 同构（§9.0 单源承诺=Win 侧复用同一 enroll_client 子命令）"


def test_ps1_old_skeleton_would_fail_this_assertion():
    pass
    proc = subprocess.run(["git", "-C", str(ROOT.parents[0]), "show",
                           "8110e50:installkit/install.ps1"],
                          capture_output=True, text=True, timeout=30)
    old = proc.stdout
    assert "（骨架，T316 实装）" in old or "s1_bootstrap.py" not in old, \
        "旧版对照组失效（本用例语义须能区分骨架与实装两态）"



def run_client(args):
    return subprocess.run([sys.executable, str(CLIENT_PY)] + args,
                          capture_output=True, text=True, timeout=30, check=False)


def test_client_heartbeat_with_step_keys(server):
    proc = run_client(["heartbeat", "--base-url=" + server, "--token=" + SECRET,
                       "--step=download", "--step-status=running", "--message=开始下载"])
    assert proc.returncode == 0, out(proc)
    hb_body = dict(Stub.requests[0][1])
    assert set(hb_body.keys()) == {"token", "fingerprint", "step", "stepStatus", "message"}
    assert hb_body["step"] == "download" and hb_body["stepStatus"] == "running"
    assert hb_body["message"] == "开始下载"


def test_client_heartbeat_default_done_status(server):
    proc = run_client(["heartbeat", "--base-url=" + server, "--token=" + SECRET,
                       "--step=healthcheck"])
    assert proc.returncode == 0, out(proc)
    hb_body = dict(Stub.requests[0][1])
    assert hb_body["step"] == "healthcheck"
    assert "stepStatus" not in hb_body, "缺省 done 由 core 判定，脚本侧禁自造注入"
    assert "message" not in hb_body


def test_client_heartbeat_without_step_old_contract(server):
    pass
    proc = run_client(["heartbeat", "--base-url=" + server, "--token=" + SECRET])
    assert proc.returncode == 0, out(proc)
    assert set(dict(Stub.requests[0][1]).keys()) == {"token", "fingerprint"}


def test_scale_out_wires_step_keys(server, sandbox):
    pass
    proc = run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    paths = [p for p, _ in Stub.requests]
    assert paths == ["/internal/cluster/enroll/heartbeat",
                     "/internal/cluster/enroll",
                     "/internal/cluster/enroll/heartbeat"]
    first = dict(Stub.requests[0][1])
    assert first.get("step") == "download" and first.get("stepStatus") == "running"
    last = dict(Stub.requests[2][1])
    assert last.get("step") == "healthcheck"
    text = "".join(f.read_text() for f in (sandbox[1]).glob("*.log"))
    assert SECRET not in text, "打点消息不得带出令牌明文"


# ----------  pack D: platform profile registers the service-agent unit ----------

def _systemctl_stub(tmp_path):
    """PATH-free systemctl test double (same replace-the-entrypoint pattern as
    INSTALLKIT_CMD_BIN): records every argv line it was called with."""
    stub = tmp_path / "systemctl-stub.sh"
    calls = tmp_path / "systemctl-calls.txt"
    stub.write_text("#!/bin/sh\necho \"$*\" >> '%s'\nexit 0\n" % calls)
    os.chmod(stub, 0o755)
    return str(stub), calls


def _host_facts(tmp_path, monkeypatch, pid1="systemd", linger=None):
    """Distro preflight double channel (same source as the bare trio tests):
    INSTALLKIT_PID1_COMM points the module at a fake PID1 comm file, and
    LOGINCTL_BIN replaces the linger entry point (SYSTEMCTL_BIN idiom).
    Pinning keeps the suite deterministic on any real host."""
    pid_file = tmp_path / "pid1-comm"
    pid_file.write_text(pid1 + "\n")
    monkeypatch.setenv("INSTALLKIT_PID1_COMM", str(pid_file))
    if linger is not None:
        ctl = tmp_path / "loginctl-stub.sh"
        ctl.write_text("#!/bin/sh\necho \"Linger=%s\"\nexit 0\n" % linger)
        os.chmod(ctl, 0o755)
        monkeypatch.setenv("LOGINCTL_BIN", str(ctl))


def _platform_env(tmp_path, monkeypatch):
    stub, calls = _systemctl_stub(tmp_path)
    units = tmp_path / "units"
    monkeypatch.setenv("INSTALLKIT_SYSTEMD_DIR", str(units))
    monkeypatch.setenv("SYSTEMCTL_BIN", stub)
    # Unit registration now runs the distro preflight first: pin a host that
    # can actually run the unit so the happy-path suite answers the wiring,
    # not the machine it happens to execute on.
    _host_facts(tmp_path, monkeypatch, linger="yes")
    return units, calls


def _exec_start_line(unit_text):
    """The real ExecStart= line (template comments mention the keyword too —
    assertion must target the directive, never prose)."""
    for line in unit_text.splitlines():
        if line.startswith("ExecStart="):
            return line
    raise AssertionError("unit file carries no ExecStart= directive")


def test_scale_out_platform_role_registers_agent_unit(server, sandbox, tmp_path, monkeypatch):
    """role=platform scale-out must install tp-service-agent.service:
    placeholders substituted, credentials ONLY in a 600 env file (red line 4),
    user-domain WantedBy adaptation, systemctl wired, log token-free."""
    units, calls = _platform_env(tmp_path, monkeypatch)
    proc = run_sh(["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    unit = (units / "tp-service-agent.service").read_text()
    assert SECRET not in unit, "unit file must carry zero token plaintext"
    for ph in ("__PYTHON__", "__AGENT_ENTRY__", "__ENV_FILE__"):
        assert ph not in unit, "placeholder %s must be substituted" % ph
    assert "WantedBy=default.target" in unit, "user domain has no multi-user.target"
    exec_line = _exec_start_line(unit)
    assert exec_line.endswith(" run"), "unit must launch the agent in resident mode"
    envf = sandbox[0] / "service-agent.env"
    assert envf.exists()
    assert SECRET in envf.read_text()
    assert oct(envf.stat().st_mode & 0o777) == "0o600", "env file must be 600"
    text = calls.read_text()
    assert "daemon-reload" in text and "enable tp-service-agent.service" in text \
        and "restart tp-service-agent.service" in text
    logtext = "".join(f.read_text() for f in sandbox[1].glob("*.log"))
    assert SECRET not in logtext, "registration must not log the token"


def test_scale_out_executor_role_no_agent_unit(server, sandbox, tmp_path, monkeypatch):
    """Only the platform profile owns the agent unit — executor/ai-node scale-out
    must not install it nor write a token env file."""
    units, calls = _platform_env(tmp_path, monkeypatch)
    proc = run_sh(["--scenario=scale-out", "--role=executor", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    assert not (units / "tp-service-agent.service").exists()
    assert not (sandbox[0] / "service-agent.env").exists()
    assert not calls.exists(), "executor scale-out must not touch systemctl"


def test_agent_unit_execstart_prefers_staged_package(server, sandbox, tmp_path, monkeypatch):
    """After an agent-upgrade landed service_agent.next, ExecStart must point at
    the staged copy (systemd relaunch is the upgrade mechanism)."""
    units, calls = _platform_env(tmp_path, monkeypatch)
    sandbox[0].mkdir(parents=True, exist_ok=True)
    staged = sandbox[0] / "service_agent.next"
    staged.write_text("# staged agent package\n")
    proc = run_sh(["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    exec_line = _exec_start_line((units / "tp-service-agent.service").read_text())
    assert str(staged) in exec_line, "ExecStart must prefer the staged package"


def test_agent_unit_registration_rerun_idempotent(server, sandbox, tmp_path, monkeypatch):
    """Second scale-out run: resume-point skip, zero extra HTTP calls and zero
    extra systemctl invocations (position semantics, same as every other step)."""
    units, calls = _platform_env(tmp_path, monkeypatch)
    args = ["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
            "--base-url=" + server]
    assert run_sh(args).returncode == 0
    first_calls = len(Stub.requests)
    first_sysd = calls.read_text()
    proc = run_sh(args)
    assert proc.returncode == 0, out(proc)
    assert len(Stub.requests) == first_calls, "rerun must not re-enroll"
    assert calls.read_text() == first_sysd, "rerun must not re-register the unit"


def test_release_script_family_map_matches_agent_catalogue():
    """R-1 drift gate: the CI release builder ships exactly the families the
    host agent catalogues (plus nothing silently forgotten). Adding a platform
    service means adding a MANAGED_SERVICES row AND a FAMILY_BY_FILE row — this
    test fails if either side moves alone, so the upgrade center can never
    quietly miss one process."""
    sa = importlib.import_module("service_agent")
    agent_families = {r.family for r in sa.MANAGED_SERVICES} | {sa.FAMILY_SERVICE_AGENT}
    text = (ROOT.parent / "tools" / "ops" / "release.sh").read_text(encoding="utf-8")
    block = text.split("FAMILY_BY_FILE = {", 1)
    assert len(block) == 2, "release.sh must keep the family map literal"
    mapped = set(re.findall(r'"([^"]+)":\s*"([a-z-]+)"', block[1].split("}", 1)[0]))
    shipped = {fam for _file, fam in mapped}
    assert shipped == agent_families, (
        "release.sh 族映射与 agent MANAGED_SERVICES 漂移：%s" %
        (shipped ^ agent_families))



def test_client_fetch_recipe_writes_file(server, tmp_path):
    out_file = tmp_path / "recipe.json"
    proc = run_client(["fetch-recipe", "--base-url=" + server,
                       "--engine=mobilewright", "--out=" + str(out_file)])
    assert proc.returncode == 0, out(proc)
    assert ("engine-recipe" in "".join(p for p, _ in Stub.requests)), \
        "必须经 api /internal/cluster/engine-recipe 口拉取（禁直连 core）"
    data = json.loads(out_file.read_text())
    assert data["version_pin"] == "0.0.63"
    assert "install" in data["hosts"]["linux"], "落盘=installRecipe 本体（交 recipe.py 消费）"


def test_client_fetch_recipe_unknown_engine_human(server, tmp_path):
    proc = run_client(["fetch-recipe", "--base-url=" + server,
                       "--engine=ghost", "--out=" + str(tmp_path / "x.json")])
    assert proc.returncode != 0
    assert "不存在" in out(proc), "服务端人话文案原样透传"
    assert not (tmp_path / "x.json").exists(), "拉取失败不得留半成品配方文件"


def test_client_fetch_recipe_unreachable_human(tmp_path):
    proc = run_client(["fetch-recipe", "--base-url=http://127.0.0.1:1",
                       "--engine=mobilewright", "--out=" + str(tmp_path / "x.json")])
    assert proc.returncode != 0
    assert "unreachable" in out(proc)



def test_client_poll_token_form_contract(server):
    Stub.commands = [{"id": 9, "commandType": "scale-in", "claimedBy": 3, "payload": {}}]
    proc = run_client(["poll", "--base-url=" + server, "--token=" + SECRET])
    assert proc.returncode == 0, out(proc)
    poll_body = dict(Stub.requests[0][1])
    assert set(poll_body.keys()) == {"token", "fingerprint", "limit"}
    printed = json.loads(proc.stdout)
    assert printed[0]["id"] == 9
    paths = [p for p, _ in Stub.requests]
    assert paths == ["/internal/cluster/commands/poll"], "不带 --ack=只领取不回执"


def test_client_poll_ack_receipt(server):
    pass
    Stub.commands = [{"id": 9, "commandType": "scale-in", "claimedBy": 3, "payload": {}}]
    proc = run_client(["poll", "--base-url=" + server, "--token=" + SECRET, "--ack"])
    assert proc.returncode == 0, out(proc)
    paths = [p for p, _ in Stub.requests]
    assert paths == ["/internal/cluster/commands/poll",
                     "/internal/cluster/commands/progress",
                     "/internal/cluster/commands/result"]
    progress_body = dict(Stub.requests[1][1])
    assert progress_body["commandId"] == 9 and progress_body["claimerId"] == 3
    result_body = dict(Stub.requests[2][1])
    assert result_body["commandId"] == 9 and result_body["success"] is True


def test_client_poll_empty_is_success(server):
    Stub.commands = []
    proc = run_client(["poll", "--base-url=" + server, "--token=" + SECRET, "--ack"])
    assert proc.returncode == 0, out(proc)
    assert [p for p, _ in Stub.requests] == ["/internal/cluster/commands/poll"], \
        "无命令=成功空转，禁空回执"


def test_scale_in_scenario_wires_poll_ack(server, sandbox):
    pass
    Stub.commands = [{"id": 9, "commandType": "scale-in", "claimedBy": 3, "payload": {}}]
    state, _ = sandbox
    proc = run_sh(["--scenario=scale-in", "--token=" + SECRET, "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    paths = [p for p, _ in Stub.requests]
    assert "/internal/cluster/commands/poll" in paths
    assert "/internal/cluster/commands/result" in paths
    content = (state / "state-scale-in.txt").read_text()
    assert "step:command-ack" in content
    receipt_bodies = [b for p, b in Stub.requests
                      if p.endswith(("/progress", "/result"))]
    # The receipt is the identity form: the api bridge folds the plaintext
    # token into its digest and core reconciles digest + bound fingerprint
    # before any write (the server rejection text names this exact client as
    # one of the two sanctioned identity-carrying shapes), so both receipts
    # must present the enrollment token the poll was made with.
    for body in receipt_bodies:
        assert body.get("token") == SECRET, \
            "progress/result 回执体携带领取令牌（身份形态，服务端 CAS 前核对摘要+指纹）"



def test_engines_missing_base_url_human(sandbox):
    proc = run_sh(["--scenario=engines", "--engine=mobilewright"])
    assert proc.returncode == 2
    assert "base-url" in out(proc)


def test_engines_realwired_install(server, sandbox, monkeypatch):
    pass
    state, _ = sandbox
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                           "--engine=mobilewright", "--base-url=" + server,
                           "--mirror-base-url=http://mirror.example.net/m"],
                          capture_output=True, text=True, timeout=120, check=False,
                          env=dict(os.environ, INSTALLKIT_CMD_BIN="true"))
    assert proc.returncode == 0, out(proc)
    paths = [p for p, _ in Stub.requests]
    assert "/internal/cluster/engine-recipe" in paths
    assert "step:fetch-recipe:mobilewright" in (state / "state-engines.txt").read_text()
    assert (state / "recipe-mobilewright.json").exists()


def test_engines_uninstall_action_preview(server, sandbox):
    pass
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                           "--engine=mobilewright", "--base-url=" + server,
                           "--action=uninstall", "--dry-run"],
                          capture_output=True, text=True, timeout=120, check=False)
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "npm uninstall -g mobilewright" in text, "dry-run 必须打印将执行的配方命令"
    assert "npm install" not in text


def test_engines_recipe_fetch_failure_blocks_human(server, sandbox):
    Stub.recipe_engine = "other"
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                           "--engine=mobilewright", "--base-url=" + server],
                          capture_output=True, text=True, timeout=120, check=False,
                          env=dict(os.environ, INSTALLKIT_CMD_BIN="true"))
    assert proc.returncode != 0
    assert "不存在" in out(proc), "拉配方失败=人话透传阻断，禁静默继续装"
    content = (sandbox[0] / "state-engines.txt").read_text() \
        if (sandbox[0] / "state-engines.txt").exists() else ""
    assert "step:fetch-recipe" not in content, "失败步骤不得记账"



def run_doctor(args):
    return subprocess.run([sys.executable, str(DOCTOR_PY)] + args,
                          capture_output=True, text=True, timeout=60, check=False)


def _write_recipe(state, code, recipe):
    (state / f"recipe-{code}.json").write_text(json.dumps(recipe, ensure_ascii=False),
                                               encoding="utf-8")


def test_doctor_probe_ok_and_missing(tmp_path):
    pass
    _write_recipe(tmp_path, "mobilewright", {
        "version_pin": "0.0.63",
        "hosts": {"linux": {"probe": ["true", "this-tool-is-not-here-xyz --version"]}},
    })
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert proc.returncode == 0, out(proc)
    report = json.loads((tmp_path / "doctor-report.json").read_text(encoding="utf-8"))
    eng = {e["engine"]: e for e in report["engines"]}
    probes = {p["cmd"]: p for p in eng["mobilewright"]["probes"]}
    assert probes["true"]["ok"] is True
    assert probes["this-tool-is-not-here-xyz --version"]["ok"] is False
    assert eng["mobilewright"]["status"] == "missing"
    assert [m["cmd"] for m in report["missing"]] == ["this-tool-is-not-here-xyz --version"]
    assert "component(s) missing" in out(proc), "the summary must name the missing components"


def test_doctor_empty_state_honest_no_recipe(tmp_path):
    pass
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert proc.returncode == 0, out(proc)
    report = json.loads((tmp_path / "doctor-report.json").read_text(encoding="utf-8"))
    assert report["engines"] == []
    assert report["host"] == "linux"
    text = out(proc)
    assert "no engine recipe" in text and "scenario" in text, \
        "an empty state must point at the next concrete action"


def test_doctor_broken_recipe_honest_row(tmp_path):
    pass
    (tmp_path / "recipe-broken.json").write_text("{not-json", encoding="utf-8")
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert proc.returncode == 0, out(proc)
    report = json.loads((tmp_path / "doctor-report.json").read_text(encoding="utf-8"))
    assert [(e["engine"], e["status"]) for e in report["engines"]] == [("broken", "recipe-invalid")]
    assert "人话" not in "".join(e.get("reason", "") for e in report["engines"]) \
        and report["engines"][0]["reason"], "损坏行必须带原因"


def test_doctor_host_not_covered_honest(tmp_path):
    pass
    _write_recipe(tmp_path, "xcuitest", {"version_pin": "1.0",
                                         "hosts": {"macos": {"probe": ["true"]}}})
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert proc.returncode == 0, out(proc)
    report = json.loads((tmp_path / "doctor-report.json").read_text(encoding="utf-8"))
    assert report["engines"][0]["status"] == "host-uncovered"


def test_doctor_unknown_host_rejected_human(tmp_path):
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "solaris"])
    assert proc.returncode == 2
    text = out(proc)
    assert "linux" in text and "windows" in text, "宿主值域拒绝必须人话列出支持面"


def test_doctor_report_overwrite_not_append(tmp_path):
    pass
    _write_recipe(tmp_path, "m", {"version_pin": "1", "hosts": {"linux": {"probe": ["true"]}}})
    assert run_doctor(["--state-dir", str(tmp_path), "--host", "linux"]).returncode == 0
    second = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert second.returncode == 0, out(second)
    text = (tmp_path / "doctor-report.json").read_text(encoding="utf-8")
    report = json.loads(text)
    assert len(report["engines"]) == 1


def test_repair_wires_doctor_report(server, sandbox):
    pass
    state, _ = sandbox
    pre = subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                          "--engine=mobilewright", "--base-url=" + server],
                         capture_output=True, text=True, timeout=120, check=False,
                         env=dict(os.environ, INSTALLKIT_CMD_BIN="true"))
    assert pre.returncode == 0, out(pre)
    proc = run_sh(["--scenario=repair"])
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "Health report" in text and "mobilewright" in text
    assert "骨架位点，实内容随 T318" not in text.split("复检")[0], "doctor 位点仍是骨架=未实装"
    assert (state / "doctor-report.json").exists(), "结构化报告必落状态目录（env-doctor/补装件消费位）"
    repair_state = (state / "state-repair.txt").read_text() if (state / "state-repair.txt").exists() else ""
    assert "step:doctor" not in repair_state, "探测只读零副作用=每次真跑，禁位点记账（重跑必重探）"


def test_ps1_repair_consumes_doctor_single_source():
    pass
    src = (ROOT / "install.ps1").read_text(encoding="utf-8-sig")
    assert "doctor.py" in src and "--state-dir" in src, \
        "ps1 repair 位点未接 doctor.py 单源件（二套 PS 探测=违 §9.0 单源承诺）"
    assert "ledger.py" in src and "refill" in src, \
        "ps1 repair 补段位点未与 install.sh 同构接 ledger 单源件（禁 PS 二套需求 diff）"



def run_ledger(args):
    return subprocess.run([sys.executable, str(LEDGER_PY)] + args,
                          capture_output=True, text=True, timeout=60, check=False)


def _write_report(state, engines):
    state.mkdir(parents=True, exist_ok=True)
    (state / "doctor-report.json").write_text(
        json.dumps({"host": "linux", "engines": engines, "missing": []},
                   ensure_ascii=False), encoding="utf-8")


def test_ledger_record_overwrites_not_appends(tmp_path):
    pass
    r = run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mw",
                    "--host", "linux", "--pin", "0.0.60"])
    assert r.returncode == 0, out(r)
    r = run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mw",
                    "--host", "linux", "--pin", "0.0.63"])
    assert r.returncode == 0, out(r)
    j = run_ledger(["list", "--state-dir", str(tmp_path), "--json"])
    assert j.returncode == 0, out(j)
    entries = json.loads(j.stdout)
    assert len(entries) == 1, "同 (engine,host) 二次 record 必须覆盖非追加"
    assert entries[0]["version_pin"] == "0.0.63"
    assert entries[0]["engine"] == "mw" and entries[0]["host"] == "linux"


def test_ledger_record_pin_defaults_from_recipe(tmp_path):
    pass
    _write_recipe(tmp_path, "mobilewright", MW_RECIPE)
    r = run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mobilewright",
                    "--host", "linux"])
    assert r.returncode == 0, out(r)
    entries = json.loads(run_ledger(["list", "--state-dir", str(tmp_path),
                                     "--json"]).stdout)
    assert entries[0]["version_pin"] == "0.0.63"


def test_ledger_list_empty_is_honest_zero(tmp_path):
    pass
    r = run_ledger(["list", "--state-dir", str(tmp_path), "--json"])
    assert r.returncode == 0, out(r)
    assert json.loads(r.stdout) == []


def test_ledger_remove_idempotent(tmp_path):
    pass
    run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mi",
                "--host", "linux", "--pin", "1.6.1"])
    r = run_ledger(["remove", "--state-dir", str(tmp_path), "--engine", "mi",
                    "--host", "linux"])
    assert r.returncode == 0, out(r)
    assert json.loads(run_ledger(["list", "--state-dir", str(tmp_path),
                                  "--json"]).stdout) == []
    again = run_ledger(["remove", "--state-dir", str(tmp_path), "--engine", "mi",
                        "--host", "linux"])
    assert again.returncode == 0, out(again)


def test_ledger_corrupt_honest_reject(tmp_path):
    pass
    (tmp_path / "installed.json").write_text("{not-json", encoding="utf-8")
    r = run_ledger(["list", "--state-dir", str(tmp_path), "--json"])
    assert r.returncode == 2
    text = out(r)
    assert "installed.json" in text and "health check" in text, \
        "a damaged ledger must name the file and the way out"


def test_refill_plan_reports_missing_and_pin_drift(tmp_path):
    pass
    _write_recipe(tmp_path, "mw", {"version_pin": "0.0.63",
                                   "hosts": {"linux": {"probe": ["nope"],
                                                       "install": [{"cmd": "npm i -g mw"}]}}})
    _write_recipe(tmp_path, "mi", {"version_pin": "1.6.1",
                                   "hosts": {"linux": {"probe": ["true"],
                                                       "install": [{"cmd": "pip i mi"}]}}})
    _write_recipe(tmp_path, "hand", {"version_pin": "9.9",
                                     "hosts": {"linux": {"probe": ["true"],
                                                         "install": [{"cmd": "x"}]}}})
    _write_report(tmp_path, [
        {"engine": "mw", "status": "missing", "probes": [{"cmd": "nope", "ok": False}],
         "reason": "probe failed: nope"},
        {"engine": "mi", "status": "ok", "probes": [], "reason": None},
        {"engine": "hand", "status": "ok", "probes": [], "reason": None},
    ])
    assert run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mi",
                       "--host", "linux", "--pin", "1.6.0"]).returncode == 0
    r = run_ledger(["refill-plan", "--state-dir", str(tmp_path), "--host", "linux"])
    assert r.returncode == 0, out(r)
    lines = [ln.split("\t") for ln in r.stdout.splitlines() if ln.strip()]
    codes = [c for c, *_ in lines]
    assert codes == ["mw", "mi"], "missing + pin drift are named; ok manual entries stay out"
    assert "missing" in lines[0][1]
    assert "pin drift" in lines[1][1] and "1.6.0" in lines[1][1] and "1.6.1" in lines[1][1]


def test_refill_plan_pinned_ok_engine_not_listed(tmp_path):
    pass
    _write_recipe(tmp_path, "mi", {"version_pin": "1.6.1",
                                   "hosts": {"linux": {"probe": ["true"],
                                                       "install": [{"cmd": "x"}]}}})
    _write_report(tmp_path, [{"engine": "mi", "status": "ok", "probes": [], "reason": None}])
    assert run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mi",
                       "--host", "linux", "--pin", "1.6.1"]).returncode == 0
    r = run_ledger(["refill-plan", "--state-dir", str(tmp_path), "--host", "linux"])
    assert r.returncode == 0, out(r)
    assert r.stdout.strip() == ""


def test_refill_plan_missing_report_honest_reject(tmp_path):
    pass
    r = run_ledger(["refill-plan", "--state-dir", str(tmp_path), "--host", "linux"])
    assert r.returncode == 2
    assert "doctor-report.json" in out(r)


def _calls(counter):
    pass
    return counter.read_text().strip() if counter.exists() else ""


def _counter_cmd_bin(tmp_path):
    pass
    counter = tmp_path / "cmd-counter.log"
    script = tmp_path / "fake-cmd.sh"
    script.write_text(f'#!/usr/bin/env bash\necho "$*" >> "{counter}"\nexit 0\n',
                      encoding="utf-8")
    os.chmod(script, 0o755)
    return str(script), counter


def test_engines_install_records_ledger(server, sandbox):
    pass
    state, _ = sandbox
    script, _counter = _counter_cmd_bin(sandbox[0].parent)
    proc = run_sh(["--scenario=engines", "--engine=mobilewright",
                   "--base-url=" + server],
                  env=dict(os.environ, INSTALLKIT_CMD_BIN=script))
    assert proc.returncode == 0, out(proc)
    entries = json.loads(run_ledger(["list", "--state-dir", str(state), "--json"]).stdout)
    assert [e["engine"] for e in entries] == ["mobilewright"]
    assert entries[0]["version_pin"] == "0.0.63", "钉=配方 version_pin（坑 C 对账基础）"


def test_engines_dry_run_does_not_record_ledger(server, sandbox):
    pass
    state, _ = sandbox
    proc = subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                           "--engine=mobilewright", "--base-url=" + server, "--dry-run"],
                          capture_output=True, text=True, timeout=120, check=False,
                          env=dict(os.environ, INSTALLKIT_CMD_BIN="true"))
    assert proc.returncode == 0, out(proc)
    assert not (state / "installed.json").exists()


def test_engines_uninstall_clears_ledger(server, sandbox):
    pass
    state, _ = sandbox
    env = dict(os.environ, INSTALLKIT_CMD_BIN="true")
    assert subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                           "--engine=mobilewright", "--base-url=" + server],
                          capture_output=True, text=True, timeout=120,
                          check=False, env=env).returncode == 0
    assert subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                           "--engine=mobilewright", "--base-url=" + server,
                           "--action=uninstall"],
                          capture_output=True, text=True, timeout=120,
                          check=False, env=env).returncode == 0
    assert json.loads(run_ledger(["list", "--state-dir", str(state), "--json"]).stdout) == []


def test_repair_refill_installs_missing_then_idempotent(server, sandbox):
    pass
    state, _ = sandbox
    script, counter = _counter_cmd_bin(sandbox[0].parent)
    env = dict(os.environ, INSTALLKIT_CMD_BIN=script)
    pre = subprocess.run(["bash", str(INSTALL_SH), "--scenario=engines",
                          "--engine=mobilewright", "--base-url=" + server, "--dry-run"],
                         capture_output=True, text=True, timeout=120, check=False, env=env)
    assert pre.returncode == 0, out(pre)
    recipe = json.loads((state / "recipe-mobilewright.json").read_text(encoding="utf-8"))
    recipe["hosts"]["linux"]["probe"] = ["this-tool-is-not-here-xyz --version"]
    (state / "recipe-mobilewright.json").write_text(json.dumps(recipe), encoding="utf-8")
    counter.write_text("")
    proc = run_sh(["--scenario=repair"], env=env)
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "refill" in text, "repair must really run the refill steps (old skeleton say-lines are gone)"
    calls = counter.read_text().splitlines()
    assert any("mobilewright@0.0.63" in c for c in calls), \
        "缺件引擎的配方 install 命令必须被执行（复用 recipe.py 单源）"
    entries = json.loads(run_ledger(["list", "--state-dir", str(state), "--json"]).stdout)
    assert [e["engine"] for e in entries] == ["mobilewright"], "补装成功即记账"
    assert "step:refill" in (state / "state-repair.txt").read_text()
    before = len(counter.read_text().splitlines())
    proc2 = run_sh(["--scenario=repair"], env=env)
    assert proc2.returncode == 0, out(proc2)
    assert "already completed" in out(proc2)
    assert len(counter.read_text().splitlines()) == before, "二次 repair 补装段必须零外呼"


def test_repair_refill_no_demand_honest_empty(server, sandbox):
    pass
    state, _ = sandbox
    _write_report(state, [])
    proc = run_sh(["--scenario=repair"], env=dict(os.environ, INSTALLKIT_CMD_BIN="true"))
    assert proc.returncode == 0, out(proc)
    assert "Nothing to refill" in out(proc)



def test_ledger_uninstall_plan_lists_only_self_installed_on_host(tmp_path):
    pass
    run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "mobilewright",
                "--host", "linux", "--pin", "0.0.63"])
    run_ledger(["record", "--state-dir", str(tmp_path), "--engine", "minium",
                "--host", "macos", "--pin", "1.6.1"])
    r = run_ledger(["uninstall-plan", "--state-dir", str(tmp_path), "--host", "linux"])
    assert r.returncode == 0, out(r)
    codes = [ln.split("\t")[0] for ln in r.stdout.splitlines() if ln.strip()]
    assert codes == ["mobilewright"], f"只列本宿主自装件，实际={codes}"


def test_ledger_uninstall_plan_empty_and_corrupt(tmp_path):
    pass
    r = run_ledger(["uninstall-plan", "--state-dir", str(tmp_path), "--host", "linux"])
    assert r.returncode == 0 and not r.stdout.strip()
    (tmp_path / "installed.json").write_text("{broken", encoding="utf-8")
    r = run_ledger(["uninstall-plan", "--state-dir", str(tmp_path), "--host", "linux"])
    assert r.returncode != 0
    assert "self-installed" in out(r) or "by hand" in out(r)


def test_engines_uninstall_refuses_off_ledger_engine(server, sandbox):
    pass
    script, counter = _counter_cmd_bin(sandbox[0].parent)
    proc = run_sh(["--scenario=engines", "--engine=mobilewright",
                   "--base-url=" + server, "--action=uninstall"],
                  env=dict(os.environ, INSTALLKIT_CMD_BIN=script))
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "not in the install ledger" in text and "never touches" in text, f"missing the off-ledger refusal wording: {text}"
    assert not _calls(counter), "账外件禁执行任何卸载命令（误删面=零）"


def test_engines_uninstall_dry_run_previews_despite_empty_ledger(server, sandbox):
    pass
    proc = run_sh(["--scenario=engines", "--engine=mobilewright",
                   "--base-url=" + server, "--action=uninstall", "--dry-run"],
                  env=dict(os.environ, INSTALLKIT_CMD_BIN="true"))
    assert proc.returncode == 0, out(proc)
    assert "npm uninstall -g mobilewright" in out(proc)


def test_scale_in_uninstalls_self_installed_then_ledger_empty(server, sandbox):
    pass
    state, _ = sandbox
    script, counter = _counter_cmd_bin(sandbox[0].parent)
    env = dict(os.environ, INSTALLKIT_CMD_BIN=script)
    assert run_sh(["--scenario=engines", "--engine=mobilewright",
                   "--base-url=" + server], env=env).returncode == 0
    counter.write_text("")
    proc = run_sh(["--scenario=scale-in"], env=env)
    assert proc.returncode == 0, out(proc)
    calls = counter.read_text()
    assert "uninstall -g mobilewright" in calls, "自装件退池必须真跑配方卸载命令"
    assert "install -g mobilewright@" not in calls, "退池禁触发安装命令"
    assert json.loads(run_ledger(["list", "--state-dir", str(state),
                                  "--json"]).stdout) == [], "卸毕销账"
    assert "step:uninstall-engine:mobilewright" in (state / "state-scale-in.txt").read_text(), \
        "位点键含引擎（坑 B：禁全局同 id 静默跳过）"


def test_scale_in_no_self_installed_honest_empty(sandbox):
    pass
    state, _ = sandbox
    script, counter = _counter_cmd_bin(state.parent)
    proc = run_sh(["--scenario=scale-in"], env=dict(os.environ, INSTALLKIT_CMD_BIN=script))
    assert proc.returncode == 0, out(proc)
    assert "No installkit self-installed" in out(proc)
    assert not _calls(counter)


def test_scale_in_missing_recipe_skips_honest_keeps_ledger(sandbox):
    pass
    state, _ = sandbox
    state.mkdir(parents=True, exist_ok=True)
    run_ledger(["record", "--state-dir", str(state), "--engine", "mobilewright",
                "--host", "linux", "--pin", "0.0.63"])
    script, counter = _counter_cmd_bin(state.parent)
    proc = run_sh(["--scenario=scale-in"], env=dict(os.environ, INSTALLKIT_CMD_BIN=script))
    assert proc.returncode == 0, out(proc)
    assert "no recipe on disk" in out(proc)
    assert not _calls(counter), "无配方命令可跑=禁凭空猜卸载命令"
    entries = json.loads(run_ledger(["list", "--state-dir", str(state),
                                     "--json"]).stdout)
    assert [e["engine"] for e in entries] == ["mobilewright"], "跳过不得销账"


def test_ps1_uninstall_boundary_consumes_ledger_single_source():
    pass
    src = (ROOT / "install.ps1").read_text(encoding="utf-8-sig")
    seg = src.split('"scale-in" {')[1].split('"engines" {')[0]
    assert "uninstall-plan" in seg and "recipe.py" in seg, \
        "ps1 scale-in 未接 ledger uninstall-plan×recipe.py 单源（二套卸载判定=违 §9.0）"
    eng = src.split('"engines" {')[1].split('"repair" {')[0]
    assert "uninstall-plan" in eng, "ps1 engines 卸载缺账外件闸（账外件可被误卸）"



import messages  # noqa: E402


def _no_lang_env():
    return {k: v for k, v in os.environ.items() if k != "INSTALLKIT_LANG"}


def test_messages_every_key_is_nonempty_english():
    """Install surface ships English only: no per-key language table, no CJK."""
    for key, text in messages.MESSAGES.items():
        assert text and text.strip(), f"message key {key} is empty"
        assert not any("\u4e00" <= ch <= "\u9fff" for ch in text), \
            f"message key {key} still carries CJK on the English-only install surface"


def test_messages_placeholders_render():
    text = messages.t("host.not_in_domain", host="plan9",
                      supported="linux / macos / windows")
    assert "plan9" in text and "linux" in text and "windows" in text, \
        "format placeholders must render (information complete, nothing truncated)"
    assert messages.t("recipe.empty_recipe").strip().startswith("This engine")


def test_host_range_message_shared_single_source():
    pass
    dsrc = (ROOT / "doctor.py").read_text(encoding="utf-8")
    lsrc = (ROOT / "ledger.py").read_text(encoding="utf-8")
    assert "host.not_in_domain" in dsrc and "host.not_in_domain" in lsrc, \
        "宿主值域句未收编公共键（两处各写一套=红线 6）"
    assert "不在值域" not in dsrc and "不在值域" not in lsrc, \
        "值域句字面不得留在件内（单源在文案库）"


def test_doctor_host_error_english(tmp_path):
    proc = subprocess.run([sys.executable, str(DOCTOR_PY), "--state-dir", str(tmp_path),
                           "--host", "solaris"],
                          capture_output=True, text=True, timeout=60, check=False,
                          env=dict(os.environ, INSTALLKIT_LANG="en"))
    assert proc.returncode == 2
    text = proc.stdout + proc.stderr
    assert "linux" in text and "windows" in text, "en 轨仍须列支持面（信息完整不截断）"
    assert "不在值域" not in text, "en 轨不得混中文文案（双轨闸）"


def test_recipe_empty_english_default(tmp_path):
    """Empty-recipe rejection renders English without any language switch."""
    empty = tmp_path / "r.json"
    empty.write_text("{}", encoding="utf-8")
    base = [sys.executable, str(RECIPE_PY), "install", "--recipe-file", str(empty),
            "--host", "linux"]
    run = subprocess.run(base, capture_output=True, text=True, timeout=60, check=False,
                         env=_no_lang_env())
    text = run.stdout + run.stderr
    assert run.returncode == 2 and "guess" in text.lower()
    assert not any("\u4e00" <= ch <= "\u9fff" for ch in text), \
        "the install surface must not emit CJK operator text"


def test_ledger_missing_report_error_english(tmp_path):
    proc = subprocess.run([sys.executable, str(LEDGER_PY), "refill-plan",
                           "--state-dir", str(tmp_path), "--host", "linux"],
                          capture_output=True, text=True, timeout=60, check=False,
                          env=dict(os.environ, INSTALLKIT_LANG="en"))
    assert proc.returncode == 2
    text = proc.stdout + proc.stderr
    assert "doctor-report.json" in text, "en 轨仍须点名文件与出路"
    assert "凭空猜" not in text and "损坏" not in text, "en 轨不得混中文文案（双轨闸）"



def test_doctor_report_masks_embedded_secrets(tmp_path):
    pass
    _write_recipe(tmp_path, "mw", {"version_pin": "1", "hosts": {"linux": {
        "probe": ["false  # token=tk-live-abc123",
                  "false  # Authorization: Bearer tk-dead9999",
                  "true"],
    }}})
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert proc.returncode == 0, out(proc)
    text = (tmp_path / "doctor-report.json").read_text(encoding="utf-8")
    assert "tk-live-abc123" not in text and "tk-dead9999" not in text, \
        "报告落盘前必做脱敏自检，明文凭据禁随报告外流"
    assert "***MASKED***" in text
    report = json.loads(text)
    eng = report["engines"][0]
    assert eng["status"] == "missing", "打码不得改变探测事实"
    oks = {p["cmd"]: p["ok"] for p in eng["probes"]}
    assert any("MASKED" in c and ok is False for c, ok in oks.items()), \
        "被遮的探测行仍如实记缺件"
    assert oks["true"] is True
    assert all("tk-" not in m["cmd"] for m in report["missing"])


def test_doctor_missing_summary_points_to_env_doctor_entry(tmp_path):
    pass
    _write_recipe(tmp_path, "mw", {"version_pin": "1",
                                   "hosts": {"linux": {"probe": ["false"]}}})
    proc = run_doctor(["--state-dir", str(tmp_path), "--host", "linux"])
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "Copy" in text and "doctor-report.json" in text, \
        "missing components must point at the report file and the way out"
    assert "env-doctor" in text, "route into the Engine Center health panel (A1 env-doctor)"
    assert "administrator" in text


def test_doctor_missing_hint_english_track(tmp_path):
    pass
    _write_recipe(tmp_path, "mw", {"version_pin": "1",
                                   "hosts": {"linux": {"probe": ["false"]}}})
    proc = subprocess.run([sys.executable, str(DOCTOR_PY), "--state-dir", str(tmp_path),
                           "--host", "linux"],
                          capture_output=True, text=True, timeout=60, check=False,
                          env=dict(os.environ, INSTALLKIT_LANG="en"))
    assert proc.returncode == 0, out(proc)
    text = out(proc)
    assert "doctor-report.json" in text, "en 轨仍须点名报告文件"
    assert "admin" in text.lower() or "health" in text.lower(), "en 轨仍须给出路"
    assert "复制" not in text and "体检" not in text, "en 轨不得混中文文案（双轨闸）"


# ---------- T294b micro: install.sh consumes distro_profile (R40 single source) ----------

def test_platform_unit_refuses_host_without_systemd(server, sandbox, tmp_path, monkeypatch):
    """A host whose PID1 is not systemd must be refused before anything lands:
    zero unit written, the refusal names the real fix path, and install.sh adds
    its own knob line INSTALLKIT_UNIT_DOMAIN (R63 three-element guidance)."""
    units, _calls = _platform_env(tmp_path, monkeypatch)
    _host_facts(tmp_path, monkeypatch, pid1="runit")
    proc = run_sh(["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode != 0, "a non-systemd host must not get a dead unit file"
    assert not (units / "tp-service-agent.service").exists()
    text = out(proc).lower()
    assert "systemd" in text, "the refusal must name the missing systemd"
    assert "INSTALLKIT_UNIT_DOMAIN" in out(proc), "point at this script's own domain knob"


def test_platform_unit_refuses_user_domain_without_linger(server, sandbox, tmp_path, monkeypatch):
    """User domain with linger off means the agent dies after logout: refuse
    on the spot and name 'loginctl enable-linger' as the fix (the preflight
    module's own honest sentence, passed through)."""
    units, _calls = _platform_env(tmp_path, monkeypatch)
    _host_facts(tmp_path, monkeypatch, linger="no")
    proc = run_sh(["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode != 0, "a linger-off user unit silently dies after logout"
    assert not (units / "tp-service-agent.service").exists()
    assert "enable-linger" in out(proc), "the fix path must name the real command"


def test_platform_unit_system_domain_renders_multi_user_target(server, sandbox, tmp_path, monkeypatch):
    """INSTALLKIT_UNIT_DOMAIN=system (R48 key with shipped default user): the
    renderer single-source module emits multi-user.target and systemctl runs
    WITHOUT --user — behaviour the old inline sed copy could not express,
    which is exactly why the ledger booked this single-source wiring."""
    units, calls = _platform_env(tmp_path, monkeypatch)
    _host_facts(tmp_path, monkeypatch)
    monkeypatch.setenv("INSTALLKIT_UNIT_DOMAIN", "system")
    proc = run_sh(["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode == 0, out(proc)
    unit = (units / "tp-service-agent.service").read_text()
    assert "WantedBy=multi-user.target" in unit
    assert "WantedBy=default.target" not in unit
    assert "--user" not in calls.read_text(), "system domain must not enable with --user"


def test_platform_unit_refuses_unknown_domain_value(server, sandbox, tmp_path, monkeypatch):
    """Domain value-domain gate at the entry (same idiom as the role gate):
    a bad value is refused with the legal set named, before any side effect."""
    _platform_env(tmp_path, monkeypatch)
    _host_facts(tmp_path, monkeypatch)
    monkeypatch.setenv("INSTALLKIT_UNIT_DOMAIN", "solar")
    proc = run_sh(["--scenario=scale-out", "--role=platform", "--token=" + SECRET,
                   "--base-url=" + server])
    assert proc.returncode != 0, out(proc)
    text = out(proc)
    assert "user" in text and "system" in text, "refusal must name the legal values"
