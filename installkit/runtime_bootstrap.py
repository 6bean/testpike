#!/usr/bin/env python3
# -*- coding: utf-8 -*-
pass
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import doctor
import pkg_probe

MINIMUM_PAT = re.compile(r">?=?\s*(\d+)\.(\d+)")


def parse_version(raw):
    pass
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", raw or "")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def satisfies(version, minimum):
    pass
    if version is None:
        return False
    if minimum is None:
        return True
    return version[:len(minimum)] >= minimum


def current_node_version():
    pass
    if shutil.which("node") is None:
        return None
    try:
        proc = subprocess.run(["node", "--version"], capture_output=True, text=True,
                              timeout=15, check=False)
    except (subprocess.TimeoutExpired, OSError):
        return None
    return parse_version(proc.stdout)


def requirement_lines(state_dir):
    pass
    rows = []
    for _code, recipe, _err in doctor.collect_recipes(state_dir):
        if not isinstance(recipe, dict):
            continue
        line = (recipe.get("runtime_requires") or {}).get("node")
        m = MINIMUM_PAT.search(line or "")
        if m:
            rows.append(((int(m.group(1)), int(m.group(2))), line))
    return rows


def requirement_from_recipes(state_dir):
    pass
    pairs = [p for p, _line in requirement_lines(state_dir)]
    return max(pairs) if pairs else None


def requirement_line(state_dir):
    pass
    rows = requirement_lines(state_dir)
    if not rows:
        return None
    return max(rows, key=lambda x: x[0])[1]


def _nvm_plan(minimum):
    pass
    major = minimum[0] if minimum else 22
    return [
        "curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash",
        "bash -lc 'source ~/.nvm/nvm.sh && nvm install %d && nvm alias default %d'" % (major, major),
    ]


def _nvm_windows_plan(minimum):
    pass
    want = "%d.%d" % minimum if minimum else "22"
    return ["winget install -e --id CoreyButler.NVMforWindows",
            "nvm install %s && nvm use %s" % (want, want)]


def bootstrap_plan(pm, minimum, win_lts_behind=False):
    pass
    if pm not in pkg_probe.PREREQ_CMD:
        raise ValueError("未登记的包管理画像「%s」——支持 %s，其余发行版请按官方文档人工装 node 后重跑"
                         % (pm, " / ".join(pkg_probe.PREREQ_CMD)))
    if minimum is None:
        return [pkg_probe.install_command(pm, "node")]
    if pm in ("apt", "dnf"):
        return _nvm_plan(minimum)
    if pm == "winget" and win_lts_behind:
        return _nvm_windows_plan(minimum)
    return [pkg_probe.install_command(pm, "node")]


def plan_if_needed(present, version, minimum, pm):
    pass
    if present and satisfies(version, minimum):
        return []
    return bootstrap_plan(pm, minimum)


def _resolve_pm(args):
    return args.assume_pm or pkg_probe.pkg_manager_for(pkg_probe.host_os(), pkg_probe.read_os_release())


def cmd_needs(args):
    pass
    if doctor.collect_recipes(args.state_dir):
        return 0
    answers = {}
    try:
        answers = json.loads(Path(args.answers_file).read_text(encoding="utf-8") or "{}")
    except (OSError, ValueError):
        answers = {}
    if isinstance(answers, dict) and answers.get("engines"):
        return 0
    return 1


def _run_plan_cmd(cmd):
    pass
    override = os.environ.get("INSTALLKIT_RUNTIME_CMD_BIN", "").strip()
    return subprocess.run(override if override else cmd, shell=True)


def cmd_check(args):
    pass
    minimum = requirement_from_recipes(args.state_dir)
    version = current_node_version()
    need_text = "node>=%d.%d" % minimum if minimum else "node 在位即可"
    print("运行时需求：%s（来源=引擎配方 runtime_requires 声明）" % need_text)
    if version is None:
        print("缺运行时 node——本机 PATH 探测不到 node 命令")
    elif satisfies(version, minimum):
        print("运行时 node v%d.%d.%d 已满足" % version)
    else:
        print("运行时 node v%d.%d.%d 版本不足（需 >=%d.%d）" % (version + minimum))
    pm = _resolve_pm(args)
    if version is not None and satisfies(version, minimum):
        return 0
    if pm is None:
        print("包管理画像未知——请按本机发行版官方文档人工安装 node 后重检")
        return 0
    for cmd in bootstrap_plan(pm, minimum):
        print("  补齐命令：%s" % cmd)
    print("  补齐执行：installkit/install.sh 装机位点会自动带 --yes 跑；人工=逐条复制上方命令")
    return 0


def cmd_ensure(args):
    pass
    minimum = requirement_from_recipes(args.state_dir)
    version = current_node_version()
    if version is not None and satisfies(version, minimum):
        print("运行时已满足（node v%d.%d.%d），零补齐" % version)
        return 0
    pm = _resolve_pm(args)
    try:
        plan = bootstrap_plan(pm, minimum)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if not args.yes:
        print("运行时缺位或版本不足，将执行以下补齐命令（未带 --yes=只计划不代跑）：")
        for cmd in plan:
            print("  %s" % cmd)
        print("确认后重跑同一命令并加 --yes，或逐条复制上方命令人工执行")
        return 0
    for cmd in plan:
        print("执行补齐：%s" % cmd)
        proc = _run_plan_cmd(cmd)
        if proc.returncode != 0:
            print("补齐命令失败（退出码 %s）——原因见上方输出；修复后重跑同一命令即从本步续"
                  % proc.returncode, file=sys.stderr)
            return proc.returncode
    after = current_node_version()
    if after is None:
        print("补齐命令已跑完但 node 仍探测不到——请重开终端后重跑体检（PATH 需新会话生效）")
        return 0
    if satisfies(after, minimum):
        print("运行时补齐完成（node v%d.%d.%d）" % after)
    else:
        need_text = "node>=%d.%d" % minimum if minimum else "在位即可"
        print("补齐命令已跑完但本机 node 仍为 v%d.%d.%d（需求 %s）——可能需要重开 shell 使 PATH 生效，重开后再跑一次体检复核"
              % (after + (need_text,)))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="runtime_bootstrap.py",
                                     description="引擎运行时 bootstrap（需求=配方声明，装法=画像命令表）")
    sub = parser.add_subparsers(dest="command", required=True)
    p_check = sub.add_parser("check", help="只读报告：配方 runtime 需求 vs 本机探测")
    p_needs = sub.add_parser("needs", help="位点闸：本次装机/换装是否有运行时需求（S1/S4 消费）")
    p_ensure = sub.add_parser("ensure", help="缺位/不足时按画像补齐（无 --yes 只出计划）")
    for p in (p_check, p_ensure):
        p.add_argument("--state-dir", required=True, help="配方 recipe-*.json 所在状态目录")
        p.add_argument("--assume-pm", default=None,
                       help="显式包管理画像（apt/dnf/apk/brew/winget/choco；缺省自动判定）")
    p_ensure.add_argument("--yes", action="store_true", help="显式同意执行补齐命令")
    p_needs.add_argument("--state-dir", required=True)
    p_needs.add_argument("--answers-file", default="",
                         help="S1 问答答案 JSON（engines 非空=需要运行时位点）")
    args = parser.parse_args(argv)
    if args.command == "check":
        return cmd_check(args)
    if args.command == "needs":
        return cmd_needs(args)
    return cmd_ensure(args)


if __name__ == "__main__":
    sys.exit(main())
