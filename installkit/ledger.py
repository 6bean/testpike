#!/usr/bin/env python3
pass
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import messages
from doctor import (
    HOSTS,
    collect_recipes,
    default_host,
)


def now_stamp():
    return time.strftime("%Y-%m-%dT%H:%M:%S")

LEDGER_NAME = "installed.json"


def load_installed(state_dir):
    pass
    path = Path(state_dir) / LEDGER_NAME
    if not path.exists():
        return {}, None
    try:
        data = json.loads(path.read_text(encoding="utf-8") or "{}")
    except (ValueError, OSError):
        return {}, messages.t("ledger.corrupt")
    if not isinstance(data, dict) or not isinstance(data.get("entries"), dict):
        return {}, messages.t("ledger.corrupt")
    return data["entries"], None


def save_installed(state_dir, entries):
    pass
    state = Path(state_dir)
    state.mkdir(parents=True, exist_ok=True)
    doc = {"entries": entries, "updated_at": now_stamp()}
    tmp = state / (LEDGER_NAME + ".tmp")
    tmp.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(state / LEDGER_NAME)


def entry_key(engine, host):
    return f"{engine}|{host}"


def default_pin(state_dir, engine):
    pass
    for code, recipe, _ in collect_recipes(state_dir):
        if code == engine and isinstance(recipe, dict):
            return str(recipe.get("version_pin") or "")
    return ""


def _resolve_host(args):
    pass
    host = args.host or default_host()
    if host not in HOSTS:
        print(messages.t("host.not_in_domain", host=host,
                         supported=" / ".join(HOSTS)), file=sys.stderr)
        return None
    return host


def cmd_record(args):
    host = _resolve_host(args)
    if host is None:
        return 2
    entries, err = load_installed(args.state_dir)
    if err:
        print(err, file=sys.stderr)
        return 2
    if "|" in args.engine:
        print(messages.t("ledger.engine_code_invalid", engine=args.engine),
              file=sys.stderr)
        return 2
    pin = args.pin if args.pin is not None else default_pin(args.state_dir, args.engine)
    entries[f"{args.engine}|{host}"] = {
        "engine": args.engine, "host": host, "version_pin": pin,
        "recorded_at": now_stamp()}
    save_installed(args.state_dir, entries)
    print(f"已记账 {args.engine}|{host}（钉={pin or '未钉'}）")
    return 0


def cmd_remove(args):
    host = _resolve_host(args)
    if host is None:
        return 2
    entries, err = load_installed(args.state_dir)
    if err:
        print(err, file=sys.stderr)
        return 2
    key = entry_key(args.engine, host)
    if key in entries:
        del entries[key]
        save_installed(args.state_dir, entries)
        print(f"已销账 {key}")
    else:
        print(f"账上无 {key}——installkit 未在此宿主装过该引擎（账外件永不触碰）")
    return 0


def load_report(state_dir):
    pass
    path = Path(state_dir) / "doctor-report.json"
    if not path.exists():
        return None, messages.t("ledger.report_missing")
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return None, messages.t("ledger.report_corrupt")
    if not isinstance(report, dict) or not isinstance(report.get("engines"), list):
        return None, messages.t("ledger.report_corrupt")
    return report, None


def refill_plan(state_dir, host):
    pass
    report, err = load_report(state_dir)
    if err:
        raise ValueError(err)
    entries, err = load_installed(state_dir)
    if err:
        raise ValueError(err)
    rows = {e.get("engine"): e for e in (report or {}).get("engines", [])
            if isinstance(e, dict)}
    missing, drifted = [], []
    for code, recipe, _parse_error in collect_recipes(state_dir):
        row = rows.get(code)
        if row and row.get("status") == "missing":
            missing.append((code, f"缺件（{row.get('reason') or '体检探测不过'}）"))
            continue
        if isinstance(recipe, dict):
            recipe_pin = str(recipe.get("version_pin") or "")
            key = entry_key(code, host)
            if key in entries and recipe_pin:
                booked = str(entries[key].get("version_pin") or "")
                if booked and booked != recipe_pin:
                    drifted.append((code, f"钉漂移（账={booked} 配方={recipe_pin}）"))
    return missing + drifted


def cmd_refill_plan(args):
    host = _resolve_host(args)
    if host is None:
        return 2
    try:
        plan = refill_plan(args.state_dir, host)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    for code, reason in plan:
        print(f"{code}\t{reason}")
    return 0


def uninstall_plan(state_dir, host):
    pass
    entries, err = load_installed(state_dir)
    if err:
        raise ValueError(err)
    rows = [(e.get("engine"), str(e.get("version_pin") or ""))
            for e in entries.values() if e.get("host") == host and e.get("engine")]
    return sorted(rows)


def cmd_uninstall_plan(args):
    host = _resolve_host(args)
    if host is None:
        return 2
    try:
        rows = uninstall_plan(args.state_dir, host)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    for code, pin in rows:
        print(f"{code}\t{pin}")
    return 0


def cmd_list(args):
    entries, err = load_installed(args.state_dir)
    if err:
        print(err, file=sys.stderr)
        return 2
    rows = sorted(entries.values(),
                  key=lambda e: entry_key(e.get("engine", ""), e.get("host", "")))
    if args.json:
        json.dump(rows, sys.stdout, ensure_ascii=False)
        return 0
    if not rows:
        print("装机账为空——本机尚无 installkit 自装的引擎件")
        return 0
    for e in rows:
        print(f"{e.get('engine')}|{e.get('host')} 钉={e.get('version_pin') or '未钉'}"
              f" 记于 {e.get('recorded_at') or '?'}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="ledger.py",
                                     description="装机账（只删自装的记账单源件）")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_rec = sub.add_parser("record", help="装毕记账（(engine,host) 覆盖写）")
    p_rm = sub.add_parser("remove", help="卸载成功销账（账外件诚实声明）")
    for p in (p_rec, p_rm):
        p.add_argument("--state-dir", required=True)
        p.add_argument("--engine", required=True)
        p.add_argument("--host", default=None, help="宿主画像键（缺省单源自动判定）")
    p_rec.add_argument("--pin", default=None, help="钉版本（缺省取状态目录配方 version_pin）")
    p_list = sub.add_parser("list", help="列账（--json 结构化供脚本消费）")
    p_list.add_argument("--state-dir", required=True)
    p_list.add_argument("--json", action="store_true")
    p_plan = sub.add_parser("refill-plan", help="体检报告×装机账→补装需求 diff")
    p_plan.add_argument("--state-dir", required=True)
    p_plan.add_argument("--host", default=None, help="宿主画像键（缺省单源自动判定）")
    p_un = sub.add_parser("uninstall-plan",
                          help="本宿主自装引擎清单（卸载只删自装的判定单源）")
    p_un.add_argument("--state-dir", required=True)
    p_un.add_argument("--host", default=None, help="宿主画像键（缺省单源自动判定）")
    args = parser.parse_args(argv)
    return {"record": cmd_record, "remove": cmd_remove, "list": cmd_list,
            "refill-plan": cmd_refill_plan,
            "uninstall-plan": cmd_uninstall_plan}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
