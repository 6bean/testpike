# -*- coding: utf-8 -*-
pass
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import recipe as rp  # noqa: E402
import runtime_bootstrap as rb  # noqa: E402

MIG_DIR = Path(__file__).resolve().parents[2] / "db" / "migration"
V214 = MIG_DIR / "V214__mobilewright_mobilecli_runtime_requires.sql"


def _v214_recipe():
    pass
    text = V214.read_text(encoding="utf-8")
    m = re.search(r"SET install_recipe = '(.*?)'::jsonb", text, re.DOTALL)
    assert m, "V214 必须含 install_recipe 整改 UPDATE 段"
    return json.loads(m.group(1))



def test_v214_file_exists_and_touches_only_mobilewright():
    assert V214.exists(), "V214 迁移文件必须存在"
    text = V214.read_text(encoding="utf-8")
    assert text.count("UPDATE tp_appauto_engine") == 1, \
        "V214 只改 MOBILEWRIGHT 一行——HYPIUM 空配方保持诚实缺省（真人工边界=随鸿蒙 SDK 分发非公共包）"
    assert "HYPIUM" not in re.sub(r"^--.*$", "", text, flags=re.MULTILINE), \
        "SQL 语句面不得触碰 HYPIUM（复核注释行除外）"


@pytest.mark.parametrize("host", ["linux", "macos", "windows"])
def test_mobilecli_promoted_into_install_probe_uninstall(host):
    rec = _v214_recipe()
    install = rp.commands_for(rec, "install", host=host, mirror_base_url="http://m.example.net")
    assert any("mobilewright@0.0.63" in c for c in install)
    assert any("mobilecli@1.0.18" in c for c in install), \
        "mobilecli 必须转正入 npm install 段（三宿主全覆盖，禁停留 manual_steps）"
    probe = rp.commands_for(rec, "probe", host=host)
    assert "mobilecli --version" in probe, \
        "probe 旗标=--version（实查：裸 version 子命令退出码 1=假缺件）"
    uninstall = rp.commands_for(rec, "uninstall", host=host)
    assert any("npm uninstall -g mobilecli" in c for c in uninstall), \
        "转正件必须可卸载（卸载边界=只删自装，装了什么就要能卸什么）"
    assert any(c.endswith("--registry=http://m.example.net/npm") for c in install), \
        "mobilecli 段同样吃镜像改道（内网零前置口径）"


def test_manual_steps_no_longer_defers_mobilecli():
    steps = " ".join(rp.manual_steps(_v214_recipe()))
    assert "mobilecli" not in steps, "自备条目已转正=必须删除（禁两本账）"
    assert "adb" in steps.lower() or "ADB" in steps, "设备侧真人工边界条目保留（如实）"


def test_runtime_requires_single_source_and_rollback_pin():
    rec = _v214_recipe()
    assert rec.get("runtime_requires", {}).get("node") == ">=22.12", \
        "运行时需求进配方（脚本侧禁第二套版本常量）"
    assert rec.get("version_pin") == "0.0.63", "引擎钉不变"
    cmds = rp.commands_for(rec, "upgrade", host="linux", target_pin="0.0.50")
    joined = " ".join(cmds)
    assert "mobilewright@0.0.50" in joined and "mobilecli@1.0.18" in joined, \
        "反向钉只改写引擎钉（mobilecli 自有钉不随回滚漂移）"



@pytest.mark.parametrize("text,expect", [
    ("v22.23.1", (22, 23, 1)),
    ("v18.0.0", (18, 0, 0)),
    ("", None),
    ("not-a-version", None),
])
def test_parse_version(text, expect):
    assert rb.parse_version(text) == expect


@pytest.mark.parametrize("ver,minimum,expect", [
    ((22, 12, 0), (22, 12), True),
    ((22, 11, 9), (22, 12), False),
    ((22, 23, 1), (22, 12), True),
    ((18, 0, 0), (22, 12), False),
    ((23, 0, 0), (22, 12), True),
])
def test_version_satisfies_minimum(ver, minimum, expect):
    assert rb.satisfies(ver, minimum) is expect


def test_requirement_from_recipes_is_max_over_state_dir(tmp_path):
    (tmp_path / "recipe-A.json").write_text(json.dumps(
        {"hosts": {"linux": {}}, "runtime_requires": {"node": ">=18.0"}}), encoding="utf-8")
    (tmp_path / "recipe-B.json").write_text(json.dumps(
        {"hosts": {"linux": {}}, "runtime_requires": {"node": ">=22.12"}}), encoding="utf-8")
    (tmp_path / "recipe-C.json").write_text(json.dumps({"hosts": {"linux": {}}}), encoding="utf-8")
    assert rb.requirement_from_recipes(tmp_path) == (22, 12)


def test_requirement_none_when_no_recipes(tmp_path):
    assert rb.requirement_from_recipes(tmp_path) is None


def test_apt_family_with_version_line_uses_nvm_not_apt_nodejs():
    pass
    plan = rb.bootstrap_plan("apt", minimum=(22, 12))
    joined = " ".join(plan)
    assert "nvm install 22" in joined, "apt 系版本兜底=nvm（单源口径，禁 apt 直装假称满足）"
    assert "apt-get install -y nodejs" not in joined, "版本线在位时 apt 直装=必不满足，禁入计划"


def test_apt_family_presence_only_reuses_prereq_single_source():
    pass
    import pkg_probe
    plan = rb.bootstrap_plan("apt", minimum=None)
    assert plan == [pkg_probe.install_command("apt", "node")]


def test_macos_and_windows_plans_use_prereq_single_source():
    import pkg_probe
    assert rb.bootstrap_plan("brew", minimum=(22, 12)) == \
        [pkg_probe.install_command("brew", "node")]
    assert rb.bootstrap_plan("winget", minimum=None) == \
        [pkg_probe.install_command("winget", "node")]
    assert "OpenJS.NodeJS.LTS" in rb.bootstrap_plan("winget", minimum=(22, 12))[0]


def test_windows_fallback_nvm_windows_behind_line():
    pass
    plan = rb.bootstrap_plan("winget", minimum=(22, 12), win_lts_behind=True)
    assert "CoreyButler.NVMforWindows" in plan[0]
    assert any("nvm install 22.12" in c for c in plan)


def test_dnf_family_presence_only_and_version_line():
    import pkg_probe
    assert rb.bootstrap_plan("dnf", minimum=None) == [pkg_probe.install_command("dnf", "node")]
    assert any("nvm install 22" in c for c in rb.bootstrap_plan("dnf", minimum=(22, 12)))


def test_unknown_pkg_manager_honest_refusal():
    with pytest.raises(ValueError):
        rb.bootstrap_plan("portage", minimum=None)


def test_plan_for_current_host_empty_when_satisfied():
    assert rb.plan_if_needed(True, (22, 23, 1), (22, 12), "apt") == []
    assert rb.plan_if_needed(True, (22, 12, 0), (22, 12), "apt") == []
    assert rb.plan_if_needed(True, (18, 0, 0), (22, 12), "apt"), "版本不足必须出计划"
    assert rb.plan_if_needed(False, None, None, "brew"), "缺件必须出计划"
    assert rb.plan_if_needed(True, (22, 0, 0), None, "brew") == [], "仅在位即够"


def test_cli_check_reports_human_without_sudo_execution(tmp_path, capsys):
    pass
    (tmp_path / "recipe-M.json").write_text(json.dumps(
        {"hosts": {"linux": {}}, "runtime_requires": {"node": ">=22.12"}}), encoding="utf-8")
    rc = rb.main(["check", "--state-dir", str(tmp_path)])
    assert rc == 0
    text = capsys.readouterr().out
    assert "node" in text, "报告必须点名运行时"
    assert "已满足" in text or "nvm" in text


def test_ensure_without_yes_flag_refuses_silent_install(tmp_path, monkeypatch):
    pass
    monkeypatch.setattr(rb, "current_node_version", lambda: None)
    called = []
    monkeypatch.setattr(rb.subprocess, "run", lambda *a, **k: called.append(a))
    rc = rb.main(["ensure", "--state-dir", str(tmp_path), "--assume-pm", "apt"])
    assert rc == 0
    assert called == [], "无 --yes 禁执行任何安装命令"


def test_ensure_with_yes_executes_plan(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(rb, "current_node_version", lambda: None)
    fake = []

    class _P:
        returncode = 0

    monkeypatch.setattr(rb.subprocess, "run", lambda *a, **k: fake.append(a) or _P())
    rc = rb.main(["ensure", "--state-dir", str(tmp_path), "--yes", "--assume-pm", "apt"])
    assert rc == 0, capsys.readouterr()
    assert fake, "--yes 必须按计划执行（无开发经验人员零前置闭环）"


def test_existing_module_doctor_and_ledger_untouched_by_import():
    pass
    import doctor  # noqa: F401
    import ledger  # noqa: F401
    assert callable(rb.bootstrap_plan)
