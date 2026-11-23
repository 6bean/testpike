# -*- coding: utf-8 -*-
pass
import argparse
import json
import sys

import messages


def _hosts_view(recipe):
    hosts = (recipe or {}).get("hosts") or {}
    if not isinstance(hosts, dict):
        raise ValueError(messages.t("recipe.hosts_invalid"))
    return hosts


def commands_for(recipe, action, host, mirror_base_url=None, target_pin=None):
    pass
    if not recipe or not (recipe.get("hosts") or recipe.get("version_pin")):
        raise ValueError(messages.t("recipe.empty_recipe"))
    hosts = _hosts_view(recipe)
    if host not in hosts:
        raise ValueError(messages.t("recipe.host_not_covered", host=host,
                                    covered="、".join(sorted(hosts)) or "无"))
    spec = hosts[host] or {}
    if action == "probe":
        return list(spec.get("probe") or [])
    if target_pin:
        pin = str((recipe or {}).get("version_pin") or "").strip()
        if not pin:
            raise ValueError(messages.t("recipe.pin_missing"))
    entries = spec.get(action) or []
    if action == "upgrade" and not entries:
        entries = spec.get("install") or []
    cmds = []
    for e in entries:
        cmd = (e.get("cmd") or "").strip()
        if not cmd:
            continue
        if target_pin:
            cmd = cmd.replace(pin, target_pin)
        rewrite = e.get("mirror_rewrite")
        if mirror_base_url and rewrite == "registry":
            cmd += " --registry=%s/npm" % mirror_base_url.rstrip("/")
        elif mirror_base_url and rewrite == "index_url":
            cmd += " -i %s/simple" % mirror_base_url.rstrip("/")
        cmds.append(cmd)
    return cmds


def manual_steps(recipe):
    pass
    return list((recipe or {}).get("manual_steps") or [])


def main(argv=None):
    parser = argparse.ArgumentParser(prog="recipe.py", description="配方消费只读 CLI（体检/装/卸命令预览）")
    parser.add_argument("action", choices=["probe", "install", "uninstall", "upgrade", "manual"])
    parser.add_argument("--recipe-file", required=True, help="api /internal 口拉回并落盘的配方 JSON")
    parser.add_argument("--host", default="linux")
    parser.add_argument("--mirror-base-url", default=None)
    parser.add_argument("--target-pin", default=None,
                        help="反向钉（回滚=把配方版本钉整体替换为该目标版本）")
    args = parser.parse_args(argv)
    recipe = json.loads(open(args.recipe_file, encoding="utf-8").read() or "{}")
    if args.action == "manual":
        for s in manual_steps(recipe):
            print(s)
        return 0
    for c in commands_for(recipe, args.action, host=args.host,
                          mirror_base_url=args.mirror_base_url,
                          target_pin=args.target_pin):
        print(c)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(2)
