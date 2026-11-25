#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Install ledger: records what this host self-installed, uninstalls only that."""
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
    state = Path(state_dir)
    state.mkdir(parents=True, exist_ok=True)
    doc = {"entries": entries, "updated_at": now_stamp()}
    tmp = state / (LEDGER_NAME + ".tmp")
    tmp.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(state / LEDGER_NAME)


def entry_key(engine, host):
    return f"{engine}|{host}"


def default_pin(state_dir, engine):
    for code, recipe, _ in collect_recipes(state_dir):
        if code == engine and isinstance(recipe, dict):
            return str(recipe.get("version_pin") or "")
    return ""


def _resolve_host(args):
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
    print(f"recorded {args.engine}|{host} (pin={pin or 'none'})")
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
        print(f"removed ledger entry {key}")
    else:
        print(f"no ledger entry for {key} — installkit never installed this "
              "engine on this host (things outside the ledger are never touched)")
    return 0


def load_report(state_dir):
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
            missing.append((code, f"missing (probe: {row.get('reason') or 'failed'})"))
            continue
        if isinstance(recipe, dict):
            recipe_pin = str(recipe.get("version_pin") or "")
            key = entry_key(code, host)
            if key in entries and recipe_pin:
                booked = str(entries[key].get("version_pin") or "")
                if booked and booked != recipe_pin:
                    drifted.append((code, f"pin drift (ledger={booked} recipe={recipe_pin})"))
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
        print("install ledger is empty — nothing has been self-installed on this host")
        return 0
    for e in rows:
        print(f"{e.get('engine')}|{e.get('host')} pin={e.get('version_pin') or 'none'}"
              f" recorded {e.get('recorded_at') or '?'}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="ledger.py",
                                     description="Install ledger (records only what this host "
                                                 "self-installed; uninstalls are bounded by it)")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_rec = sub.add_parser("record", help="book an engine after install ((engine,host) overwrite)")
    p_rm = sub.add_parser("remove", help="clear the entry after uninstall (honest note when absent)")
    for p in (p_rec, p_rm):
        p.add_argument("--state-dir", required=True)
        p.add_argument("--engine", required=True)
        p.add_argument("--host", default=None, help="host profile key (auto-detected by default)")
    p_rec.add_argument("--pin", default=None,
                       help="version pin (defaults to the recipe version_pin in the state dir)")
    p_list = sub.add_parser("list", help="list entries (--json for scripted consumers)")
    p_list.add_argument("--state-dir", required=True)
    p_list.add_argument("--json", action="store_true")
    p_plan = sub.add_parser("refill-plan",
                            help="doctor report x install ledger -> refill diff")
    p_plan.add_argument("--state-dir", required=True)
    p_plan.add_argument("--host", default=None, help="host profile key (auto-detected by default)")
    p_un = sub.add_parser("uninstall-plan",
                          help="engines self-installed on this host (uninstall removes ledger entries only)")
    p_un.add_argument("--state-dir", required=True)
    p_un.add_argument("--host", default=None, help="host profile key (auto-detected by default)")
    args = parser.parse_args(argv)
    return {"record": cmd_record, "remove": cmd_remove, "list": cmd_list,
            "refill-plan": cmd_refill_plan,
            "uninstall-plan": cmd_uninstall_plan}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
