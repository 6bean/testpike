# -*- coding: utf-8 -*-
pass
import argparse
import hashlib
import io
import json
import os
import shutil
import sys

import instance_names

DEFAULT_TIER = "lightweight"

COMPONENTS = {
    "postgres": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 500, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("postgres"),
        "install": "docker compose up -d postgres",
        "rollback": "docker rm -f " + instance_names.name("postgres"),
    },
    "redis": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 100, "mem_mb": 128,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("redis"),
        "install": "docker compose up -d redis",
        "rollback": "docker rm -f " + instance_names.name("redis"),
    },
    "rabbitmq": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 200, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("rabbitmq"),
        "install": "docker compose up -d rabbitmq",
        "rollback": "docker rm -f " + instance_names.name("rabbitmq"),
    },
    "seaweedfs": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 300, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' " + instance_names.name("seaweedfs"),
        "install": "docker compose up -d seaweedfs",
        "rollback": "docker rm -f " + instance_names.name("seaweedfs"),
    },
    "backend-services": {
        "tiers": ["lightweight", "standard", "full"],
        "disk_mb": 1600, "mem_mb": 3072,
        "probe": "curl -sf http://localhost:8083/actuator/health",
        "install": "docker compose --profile app up -d core api admin ai-service",
        "rollback": "docker compose --profile app down core api admin ai-service",
    },
    "frontend-spa": {
        "tiers": ["standard", "full"],
        "disk_mb": 200, "mem_mb": 64,
        "probe": "docker inspect -f '{{.State.Running}}' testpike-nginx",
        "install": "docker compose --profile app up -d frontend",
        "rollback": "docker rm -f testpike-nginx",
    },
    "prometheus": {
        "tiers": ["full"],
        "disk_mb": 800, "mem_mb": 512,
        "probe": "docker inspect -f '{{.State.Running}}' tp-prometheus",
        "install": "docker compose --profile observability up -d tp-prometheus",
        "rollback": "docker rm -f tp-prometheus",
    },
    "grafana": {
        "tiers": ["full"],
        "disk_mb": 400, "mem_mb": 256,
        "probe": "docker inspect -f '{{.State.Running}}' tp-grafana",
        "install": "docker compose --profile observability up -d tp-grafana",
        "rollback": "docker rm -f tp-grafana",
    },
    "executor": {
        # Numbers are image-build measurements, not guesses: disk 487MB is
        # the unpacked footprint of testpike/executor (archive layer sum
        # 117,964,067 B, window anchor IMG-EXEC2-SIZE-BYTES); mem 64MB
        # covers the measured import-time peak RSS 47,528 KiB plus heartbeat
        # loop headroom (one-shot probe inside the built image).
        # Values pinned by tests/test_resource_tiers_installer_contract.py.
        "tiers": ["full"],
        "disk_mb": 487, "mem_mb": 64,
        "probe": "docker inspect -f '{{.State.Running}}' testpike-executor",
        "install": "docker compose --profile executor up -d tp-executor tp-fixtures",
        "rollback": "docker rm -f testpike-executor testpike-fixtures",
    },
}

TIERS = {
    "lightweight": {
        "label": "轻量体验",
        "blurb": "中间件+四后端服务，开箱即用 AI 生成与评审；用例执行由完整功能档的执行器承接（默认档）",
        "components": [c for c, m in COMPONENTS.items() if "lightweight" in m["tiers"]],
        "compose_profiles": [],
    },
    "standard": {
        "label": "标准上手",
        "blurb": "轻量档+应用镜像与前端站点，开箱即用完整界面",
        "components": [c for c, m in COMPONENTS.items() if c in [
            k for k, v in COMPONENTS.items() if "standard" in v["tiers"]]],
        "compose_profiles": ["app"],
    },
    "full": {
        "label": "完整功能",
        "blurb": "标准档+Prometheus/Grafana 观测栈+执行器与夹具靶，装完用例即可真跑红绿灯",
        "components": list(COMPONENTS),
        "compose_profiles": ["app", "observability", "executor"],
    },
}

_TIER_ALIASES = {}
for _key, _spec in TIERS.items():
    _TIER_ALIASES[_key] = _key
    _TIER_ALIASES[_spec["label"]] = _key
for _i, _key in enumerate(("lightweight", "standard", "full"), start=1):
    _TIER_ALIASES[str(_i)] = _key
_TIER_ALIASES.update({"轻量": "lightweight", "标准": "standard", "完整": "full"})


def _gb(mb):
    pass
    return "%.1fGB" % (mb / 1024.0)


def tier_requirements(tier):
    pass
    spec = TIERS[tier]
    disk = sum(COMPONENTS[c]["disk_mb"] for c in spec["components"])
    mem = sum(COMPONENTS[c]["mem_mb"] for c in spec["components"])
    return {"tier": tier, "disk_mb": disk, "mem_mb": mem}


def machine_capacity(path=None):
    pass
    usage = shutil.disk_usage(path or os.path.expanduser("~"))
    disk_gb = usage.free / (1024.0 ** 3)
    try:
        with open("/proc/meminfo", encoding="utf-8") as fh:
            mem_kb = int(fh.readline().split()[1])  # MemTotal
        mem_gb = mem_kb / (1024.0 ** 2)
    except OSError:
        mem_gb = (os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")) / (1024.0 ** 3)
    return {"disk_gb": disk_gb, "mem_gb": mem_gb}


def render_tiers_text():
    pass
    cap = machine_capacity()
    lines = ["资源三档（磁盘/内存需求由组件矩阵程序化生成，供选择参照）："]
    for i, key in enumerate(("lightweight", "standard", "full"), start=1):
        req = tier_requirements(key)
        mark = "（默认）" if key == DEFAULT_TIER else ""
        lines.append("  %d. %s%s —— %s；需磁盘 %s / 内存 %s（含基础占用）" % (
            i, TIERS[key]["label"], mark, TIERS[key]["blurb"],
            _gb(req["disk_mb"]), _gb(req["mem_mb"])))
    lines.append("  本机当前可用：磁盘约 %s / 内存约 %s" % (
        _gb(int(cap["disk_gb"] * 1024)), _gb(int(cap["mem_gb"] * 1024))))
    return "\n".join(lines)


def compose_profiles(tier):
    pass
    return list(TIERS.get(tier or DEFAULT_TIER, TIERS[DEFAULT_TIER])["compose_profiles"])


def ask(input_text=None, answers_path=None, tier=None, unattended=False):
    pass
    stream = io.StringIO(input_text if input_text is not None else "")
    if input_text is None and not unattended:
        stream = sys.stdin
    print(render_tiers_text())
    line = None
    if stream is not sys.stdin:
        raw = stream.readline()
        line = raw.strip() if raw else None
    else:
        try:
            raw = stream.readline()
            line = raw.strip() if raw else None
        except (EOFError, ValueError):
            line = None
    if tier:
        chosen = _TIER_ALIASES.get(str(tier).strip().lower()) or _TIER_ALIASES.get(str(tier).strip())
        if not chosen:
            raise ValueError("档位「%s」不在值域——必须 lightweight / standard / full 之一"
                             "（问答界面输入 1/2/3 亦可）" % tier)
    elif line is None or line == "":
        chosen = DEFAULT_TIER
    else:
        chosen = _TIER_ALIASES.get(line.lower()) or _TIER_ALIASES.get(line)
        if not chosen:
            print("  ↳「%s」不是可选项，已按默认「%s」继续（不影响后续步骤）"
                  % (line, TIERS[DEFAULT_TIER]["label"]))
            chosen = DEFAULT_TIER
    answers = {}
    if answers_path and os.path.exists(answers_path):
        try:
            with open(answers_path, encoding="utf-8") as fh:
                answers = json.load(fh)
            if not isinstance(answers, dict):
                answers = {}
        except (OSError, ValueError):
            answers = {}
    answers["tier"] = chosen
    if answers_path:
        with open(answers_path, "w", encoding="utf-8") as fh:
            json.dump(answers, fh, ensure_ascii=False, indent=2)
    print("  ↳ 资源档位=%s（%s）" % (chosen, TIERS[chosen]["label"]))
    return answers


def install_matrix(tier):
    pass
    if tier not in TIERS:
        raise ValueError("档位「%s」不在值域——可选 lightweight / standard / full" % tier)
    steps = []
    for name in TIERS[tier]["components"]:
        comp = COMPONENTS[name]
        steps.append({
            "id": name,
            "probe": comp["probe"],
            "install": comp["install"],
            "rollback": comp["rollback"],
            "artifact": comp.get("artifact"),
        })
    return steps


def plan_execution(tier, installed_probe=None):
    pass
    installed_probe = installed_probe or {}
    steps = install_matrix(tier)
    run, skip = [], []
    for step in steps:
        (skip if installed_probe.get(step["id"]) else run).append(step)
    return {"run": run, "skip": skip,
            "rollback_stack": [s["id"] for s in reversed(run)]}


def verify_sha256(path, expected):
    pass
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual.lower() != str(expected).strip().lower():
        raise ValueError(
            "工件校验和不一致：%s 实际=%s 期望=%s。请重新下载该组件"
            "（或改用内网镜像源后重跑同一命令；镜像源在系统设置→装机配置，"
            "参数键 installkit.mirror-base-url）"
            % (os.path.basename(path), actual, expected))
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(prog="resource_tiers.py",
                                     description="资源三档问答+组件装机矩阵单源（T293b）")
    sub = parser.add_subparsers(dest="command", required=True)
    p_ask = sub.add_parser("ask", help="一行档位问答（EOF=默认档），结果 merge 落 answers")
    p_ask.add_argument("--answers-file", required=True)
    p_ask.add_argument("--tier", default="", help="CLI 显式档位（优先于问答输入流）")
    p_ask.add_argument("--unattended", action="store_true", help="零追问=空输入流走默认")
    sub.add_parser("render", help="打印三档面板（程序化数字）")
    p_matrix = sub.add_parser("matrix", help="打印该档计划（run/skip/rollback_stack）")
    p_matrix.add_argument("--tier", default=DEFAULT_TIER)
    p_verify = sub.add_parser("verify", help="校验文件 sha256（不过=人话拒）")
    p_verify.add_argument("--file", required=True)
    p_verify.add_argument("--sha256", required=True)
    args = parser.parse_args(argv)
    if args.command == "ask":
        ask(answers_path=args.answers_file, tier=args.tier or None,
            unattended=args.unattended)
        return 0
    if args.command == "render":
        print(render_tiers_text())
        return 0
    if args.command == "matrix":
        plan = plan_execution(args.tier)
        print("将装：%s" % "、".join(s["id"] for s in plan["run"]))
        print("已装跳过：%s" % ("、".join(s["id"] for s in plan["skip"]) or "无"))
        print("失败回滚序：%s" % " ← ".join(plan["rollback_stack"]))
        return 0
    if args.command == "verify":
        verify_sha256(args.file, args.sha256)
        print("校验通过：%s" % os.path.basename(args.file))
        return 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(2)
