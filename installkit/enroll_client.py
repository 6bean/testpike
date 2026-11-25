# -*- coding: utf-8 -*-
pass
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def machine_fingerprint():
    pass
    material = None
    for path in ("/etc/machine-id", "/var/lib/dbus/machine-id"):
        try:
            with open(path, encoding="utf-8") as fh:
                material = fh.read().strip()
            if material:
                break
        except OSError:
            continue
    if not material:
        import socket
        parts = [socket.gethostname()]
        try:
            with open("/proc/sys/kernel/random/boot_id", encoding="utf-8") as fh:
                parts.append(fh.read().strip())
        except OSError:
            pass
        material = "|".join(parts)
    return hashlib.sha256(("testpike-installkit:" + material).encode()).hexdigest()


def _get(base_url, path, timeout=15):
    req = urllib.request.Request(base_url.rstrip("/") + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as err:
        try:
            payload = json.loads(err.read().decode() or "{}")
        except ValueError:
            return None
        return payload if payload.get("msg") else None
    except (urllib.error.URLError, OSError, TimeoutError):
        return None


def _post_json(base_url, path, body, timeout=15):
    req = urllib.request.Request(base_url.rstrip("/") + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as err:
        try:
            payload = json.loads(err.read().decode() or "{}")
        except ValueError:
            payload = {}
        if not payload.get("msg"):
            print("Platform answered abnormally (HTTP %d) — confirm the api service is "
                  "running, then retry" % err.code, file=sys.stderr)
            return None
        return payload
    except (urllib.error.URLError, OSError, TimeoutError):
        print("Platform unreachable: %s — check the base-url and the network, then rerun "
              "the same command (checkpoint resume never duplicates the enrollment)"
              % base_url, file=sys.stderr)
        return None


def _post(base_url, path, token, fingerprint, timeout=15, extra=None):
    body = {"token": token, "fingerprint": fingerprint}
    for key, value in (extra or {}).items():
        if value is not None and str(value).strip() != "":
            body[key] = value
    return _post_json(base_url, path, body, timeout)


def _fail(payload):
    if payload is None:
        return 1
    if str(payload.get("code")) == "0":
        return 0
    msg = payload.get("msg") or "The platform rejected this call (no reason returned)"
    print(msg, file=sys.stderr)
    return 1


def cmd_enroll(args):
    payload = _post(args.base_url, "/internal/cluster/enroll", args.token,
                    machine_fingerprint())
    rc = _fail(payload)
    if rc == 0:
        data = (payload or {}).get("data") or {}
        print("Enrolled: role=%s scope=%s" % (data.get("role"), data.get("projectScope") or "all projects"))
    return rc


def cmd_fetch_mirror(args):
    pass
    payload = _get(args.base_url, "/internal/cluster/installkit-config")
    if payload is None or str(payload.get("code")) != "0":
        reason = (payload or {}).get("msg") or "The platform is unreachable or the config endpoint answered abnormally"
        print("%s — mirror key fetch failed; the wizard continues with the official "
              "source (pass --mirror-base-url to override explicitly)"
              % reason, file=sys.stderr)
        return 1
    data = payload.get("data") or {}
    print(data.get("mirrorBaseUrl") or "")
    return 0


def cmd_heartbeat(args):
    payload = _post(args.base_url, "/internal/cluster/enroll/heartbeat", args.token,
                    machine_fingerprint(),
                    extra={"step": args.step, "stepStatus": args.step_status,
                           "message": args.message})
    if payload is not None and str(payload.get("code")) == "0":
        print("Heartbeat step reported")
        return 0
    return _fail(payload)


def cmd_fetch_recipe(args):
    pass
    quoted = urllib.parse.quote(args.engine)
    payload = _get(args.base_url, "/internal/cluster/engine-recipe?code=" + quoted)
    if payload is None:
        print(f"Platform unreachable or answered abnormally: {args.base_url} — confirm the "
              "api service is running, then retry (a missing recipe must never be guessed "
              "into an install command)", file=sys.stderr)
        return 1
    if str(payload.get("code")) != "0":
        return _fail(payload)
    data = payload.get("data") or {}
    recipe = data.get("installRecipe") or {}
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(recipe, fh, ensure_ascii=False)
    os.replace(tmp, args.out)
    print(args.out)
    return 0


def cmd_poll(args):
    pass
    fingerprint = machine_fingerprint()
    payload = _post(args.base_url, "/internal/cluster/commands/poll", args.token,
                    fingerprint, extra={"limit": args.limit})
    if payload is None:
        return _fail(payload)
    if str(payload.get("code")) != "0":
        return _fail(payload)
    commands = payload.get("data") or []
    print(json.dumps(commands, ensure_ascii=False))
    if not args.ack:
        return 0
    rc = 0
    for cmd in commands:
        ctype = str(cmd.get("commandType") or "")
        if ctype not in ("scale-in", "node-remove"):
            print(f"Command {cmd.get('id')} (type={ctype or 'unknown'}) requires real "
                  "execution — the installer never fakes an acknowledgement; it stays "
                  "queued for the executor or a later round", file=sys.stderr)
            continue
        claimer = cmd.get("claimedBy")
        if claimer is None:
            continue
        _post(args.base_url, "/internal/cluster/commands/progress", args.token,
              fingerprint, extra={"commandId": cmd.get("id"), "claimerId": claimer,
                                  "step": "ack", "message": "The installer acknowledged the command"})
        ack = _post(args.base_url, "/internal/cluster/commands/result", args.token,
                    fingerprint, extra={"commandId": cmd.get("id"), "claimerId": claimer,
                                        "success": True,
                                        "resultDetail": "Installer acknowledgement recorded (semantic judgement runs on the platform side)"})
        if ack is None or str(ack.get("code")) != "0":
            rc = _fail(ack)
    return rc


def main(argv=None):
    parser = argparse.ArgumentParser(prog="enroll_client.py",
                                     description="Installer enrollment / heartbeat reporting / recipe fetch / fleet command client (via the api /internal endpoints)")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fingerprint", help="Print this machine's fingerprint (stable sha256, for troubleshooting cross-checks)")
    p = sub.add_parser("enroll")
    p.add_argument("--base-url", required=True)
    p.add_argument("--token", required=True)
    p = sub.add_parser("heartbeat")
    p.add_argument("--base-url", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--step", default=None)
    p.add_argument("--step-status", dest="step_status", default=None)
    p.add_argument("--message", default=None)
    p = sub.add_parser("fetch-mirror", help="Fetch the platform mirror-source key default (stdout prints the value only; on failure print a notice and fall back to the official source)")
    p.add_argument("--base-url", required=True)
    p = sub.add_parser("fetch-recipe", help="Fetch the engine install recipe to disk (stdout prints the path only; on failure print the reason and exit non-zero without a partial file)")
    p.add_argument("--base-url", required=True)
    p.add_argument("--engine", required=True)
    p.add_argument("--out", required=True)
    p = sub.add_parser("poll", help="Claim fleet commands (token form; --ack records a receipt for pure-confirmation commands only, execution-required types are never faked)")
    p.add_argument("--base-url", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--limit", type=int, default=5)
    p.add_argument("--ack", action="store_true")
    args = parser.parse_args(argv)
    if args.cmd == "fingerprint":
        print(machine_fingerprint())
        return 0
    if args.cmd == "enroll":
        return cmd_enroll(args)
    if args.cmd == "fetch-mirror":
        return cmd_fetch_mirror(args)
    if args.cmd == "fetch-recipe":
        return cmd_fetch_recipe(args)
    if args.cmd == "poll":
        return cmd_poll(args)
    return cmd_heartbeat(args)


if __name__ == "__main__":
    sys.exit(main())
