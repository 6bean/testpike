#!/usr/bin/env python3
pass
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

import messages

HOSTS = ("linux", "macos", "windows")

MASKED = "***MASKED***"
_SECRET_PATTERNS = (
    re.compile(r"(?i)(token|api[-_]?key|secret|password|passwd|pwd)\s*[=:]\s*\S+"),
    re.compile(r"(?i)bearer\s+\S+"),
    re.compile(r"\btk-[A-Za-z0-9_-]{6,}\b"),
)


def mask(text):
    pass
    if not text:
        return text
    masked = _SECRET_PATTERNS[0].sub(lambda m: f"{m.group(1)}={MASKED}", text)
    masked = _SECRET_PATTERNS[1].sub(f"Bearer {MASKED}", masked)
    masked = _SECRET_PATTERNS[2].sub(MASKED, masked)
    return masked


def default_host():
    pass
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import pkg_probe
    return pkg_probe.host_os()


def collect_recipes(state_dir):
    pass
    rows = []
    for path in sorted(Path(state_dir).glob("recipe-*.json")):
        code = path.name[len("recipe-"):-len(".json")]
        try:
            recipe = json.loads(path.read_text(encoding="utf-8") or "{}")
        except (ValueError, OSError) as exc:
            rows.append((code, None, f"配方文件解析失败：{exc}"))
            continue
        if not isinstance(recipe, dict):
            rows.append((code, None, "配方根节点不是对象"))
        else:
            rows.append((code, recipe, None))
    return rows


def run_probe(cmd):
    pass
    try:
        proc = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                              timeout=30, check=False)
        return proc.returncode == 0
    except (subprocess.TimeoutExpired, OSError):
        return False


def examine(recipe, host):
    pass
    if not recipe or not (recipe.get("hosts") or recipe.get("version_pin")):
        return "recipe-invalid", [], "配方为空或结构非法（缺 hosts/version_pin 段）"
    hosts = recipe.get("hosts")
    if not isinstance(hosts, dict):
        return "recipe-invalid", [], "配方 hosts 段结构非法（应为宿主画像字典）"
    if host not in hosts:
        covered = "、".join(sorted(h for h in hosts if isinstance(hosts.get(h), dict)))
        return "host-uncovered", [], f"配方未提供当前宿主画像（当前宿主：{host}；已覆盖：{covered or '无'}）"
    spec = hosts[host] if isinstance(hosts[host], dict) else {}
    probes = [{"cmd": mask(c), "ok": run_probe(c)} for c in (spec.get("probe") or [])]
    missing = [p["cmd"] for p in probes if not p["ok"]]
    status = "missing" if missing else "ok"
    reason = f"缺件探测不过：{'、'.join(missing)}" if missing else None
    return status, probes, reason


# ---- MCP transport handshake probe --------------------------
# Two-state verdict on the api service /mcp door, read-only: (1) an
# unauthenticated request must be honestly refused with 401/403 before any
# JSON-RPC is touched (deny shape), (2) with a bearer token the transport must
# answer a JSON-RPC envelope (authed shape). No token available = authed phase
# reported as "skipped" — never faked. A refused connection lands as
# "unreachable" (fact over guess). The token is only ever sent, never echoed
# into the report (credential rule: values masked, keys named).
MCP_INITIALIZE = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                  "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                             "clientInfo": {"name": "installkit-doctor",
                                            "version": "1"}}}
MCP_ACCEPT = "application/json, text/event-stream"

MCP_SUMMARY_KEYS = {"ok": "doctor.mcp_ok", "shape-gap": "doctor.mcp_shape_gap",
                    "auth-gap": "doctor.mcp_auth_gap", "unreachable": "doctor.mcp_unreachable"}


def mcp_base_url(flag):
    """Resolve the /mcp endpoint: explicit flag > TP_MCP_BASE > API_PORT > shipped 8082."""
    base = flag or os.environ.get("TP_MCP_BASE") or \
        "http://localhost:%s" % (os.environ.get("API_PORT") or "8082")
    base = base.rstrip("/")
    return base if base.endswith("/mcp") else base + "/mcp"


def _post_mcp(url, token, timeout):
    """One JSON-RPC initialize POST; returns (status_code|None, body_text)."""
    req = urllib.request.Request(url, data=json.dumps(MCP_INITIALIZE).encode(),
                                 method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Accept": MCP_ACCEPT})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError):
        return None, ""


def mcp_handshake(base, token=None, timeout=5):
    """Two-state MCP door probe -> {base, deny, authed, status, detail} (token never echoed)."""
    url = base.rstrip("/")
    url = url if url.endswith("/mcp") else url + "/mcp"
    status, body = _post_mcp(url, None, timeout)
    out = {"base": mask(url), "deny": "unreachable", "authed": "skipped", "detail": ""}
    if status is None:
        out["status"] = "unreachable"
        out["detail"] = "connection refused or timed out"
        return out
    if status in (401, 403):
        out["deny"] = "ok"
    elif 200 <= status < 300 and "jsonrpc" in body:
        out["deny"] = "missing"   # answered unauthenticated = no honest refusal gate
    else:
        out["deny"] = "missing"
    if out["deny"] == "missing":
        out["status"] = "shape-gap"
        out["detail"] = "the /mcp door answered an unauthenticated request (HTTP %s) — auth gate absent" % status
        return out
    if not token:
        out["status"] = "ok"
        return out
    a_status, a_body = _post_mcp(url, token, timeout)
    if a_status == 200 and "jsonrpc" in a_body:
        out["authed"] = "ok"
        out["status"] = "ok"
    else:
        out["authed"] = "fail"
        out["status"] = "auth-gap"
        out["detail"] = "bearer token rejected on the JSON-RPC door (HTTP %s)" % a_status
    return out


def runtime_gap(state_dir):
    pass
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import runtime_bootstrap as rb
    minimum = rb.requirement_from_recipes(state_dir)
    version = rb.current_node_version()
    if version is not None and rb.satisfies(version, minimum):
        return None
    return {
        "status": "missing" if version is None else "below",
        "current": ".".join(str(x) for x in version) if version else None,
        "required": rb.requirement_line(state_dir),
    }


def build_report(state_dir, host, mcp_probe=None):
    pass
    engines, missing = [], []
    for code, recipe, parse_error in collect_recipes(state_dir):
        if parse_error is not None:
            engines.append({"engine": code, "status": "recipe-invalid",
                            "probes": [], "reason": parse_error})
            continue
        status, probes, reason = examine(recipe, host)
        engines.append({"engine": code, "status": status, "probes": probes, "reason": reason})
        missing.extend({"engine": code, "cmd": p["cmd"]} for p in probes if not p["ok"])
    report = {"host": host, "engines": engines, "missing": missing,
              "missing_runtime": runtime_gap(state_dir)}
    if mcp_probe is not None:
        report["mcp_handshake"] = mcp_probe
    return report


def print_summary(report, empty_state_hint):
    pass
    gap = report.get("missing_runtime")
    if gap:
        if gap["status"] == "missing":
            print(messages.t("doctor.runtime_missing",
                             required=gap["required"] or "在位即可"))
        else:
            print(messages.t("doctor.runtime_below",
                             current=gap["current"] or "?",
                             required=gap["required"] or "在位即可"))
    probe = report.get("mcp_handshake")
    if probe:
        print(messages.t(MCP_SUMMARY_KEYS.get(probe.get("status"), "doctor.mcp_unreachable"),
                         base=probe.get("base") or "?",
                         detail=probe.get("detail") or "—"))
    if not report["engines"]:
        print(empty_state_hint)
        return
    for e in report["engines"]:
        line = f"引擎 {e['engine']}：{e['status']}"
        if e.get("reason"):
            line += f"（{e['reason']}）"
        print(line)
    if report["missing"]:
        print(messages.t("doctor.missing_line", count=len(report["missing"])))
        print(messages.t("doctor.copy_hint"))
    else:
        print(messages.t("doctor.all_ok"))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="doctor.py",
                                     description="S5 体检：配方 probe 清单×本机探测→结构化报告")
    parser.add_argument("--state-dir", required=True,
                        help="状态目录（recipe-*.json 所在，报告同目录覆盖写）")
    parser.add_argument("--host", default=None, help="宿主画像键 linux/macos/windows（缺省自动判定）")
    parser.add_argument("--mcp-probe", action="store_true",
                        help="附带 MCP 传输握手探测（只读，不落凭据）")
    parser.add_argument("--mcp-base", default=None,
                        help="MCP 端点址（缺省 TP_MCP_BASE > API_PORT > 出厂 8082）")
    args = parser.parse_args(argv)
    host = args.host or default_host()
    if host not in HOSTS:
        print(messages.t("host.not_in_domain", host=host,
                         supported=" / ".join(HOSTS)), file=sys.stderr)
        return 2
    state = Path(args.state_dir)
    probe = None
    if args.mcp_probe or args.mcp_base:
        # token only from the environment (credential rule: never a CLI flag value on disk)
        probe = mcp_handshake(mcp_base_url(args.mcp_base),
                              token=os.environ.get("TP_MCP_TOKEN") or None)
    report = build_report(state, host, mcp_probe=probe)
    report_path = state / "doctor-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)
    hint = ("本机状态目录尚无引擎配方文件——请先用 engines 场景加装引擎"
            "（配方经 api 只读口拉回落盘）后再体检，本次无可探测项")
    print_summary(report, hint)
    print(f"结构化报告已落盘：{report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
