#!/usr/bin/env python3
"""Distro-aware hosting layer for the testPike systemd units (T294b gap 3).

A bare (non-Kubernetes) node is not one machine type: WSL boots systemd only
when the operator enabled it, a headless server kills user-domain services at
logout unless lingering is on, and the RHEL/Debian families carry different
package ecosystems. Before this module those facts were smeared over by two
copies of inline ``sed`` renders (install.sh and bare-lib.sh); they are
consolidated here as the single source:

  detect     print the host profile (family, distro id, systemd presence);
  render     substitute the unit template, choosing WantedBy by unit domain;
  preflight  stop a bare install on a host that cannot host the unit, naming
             the real fix path (wsl.conf / k8s form / enable-linger).

Every host fact is injectable: CLI flag, then environment test double
(INSTALLKIT_OS_RELEASE, INSTALLKIT_PROC_VERSION, INSTALLKIT_PID1_COMM,
INSTALLKIT_DISTRO_FAMILY), then the real file. Install-time values stay in
the chain environment through the same keys the trio already uses (R48).
"""
import argparse
import os
import subprocess
import sys
import tempfile

# Families whose units we host. The name is the decision key the callers
# branch on; per-distro package specifics stay out of this layer.
FAMILIES = ("wsl", "rhel", "debian", "other")

RHEL_IDS = {"centos", "rhel", "rocky", "almalinux", "fedora", "amzn"}
DEBIAN_IDS = {"ubuntu", "debian", "deepin", "linuxmint"}

OS_RELEASE = "/etc/os-release"
PROC_VERSION = "/proc/version"
PID1_COMM = "/proc/1/comm"


def read_text(path):
    """Host fact files may be absent (containers, sandboxes); empty is fine."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def parse_os_release(text):
    """Minimal KEY=value reader for /etc/os-release (quotes and lists)."""
    fields = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        fields[key.strip()] = value.strip().strip('"').strip("'")
    return fields


def detect_family(os_release_text, proc_version_text):
    """Map host facts to one family; WSL wins because its kernel line proves
    the guest boots under Windows, which changes what systemd can do."""
    if "microsoft" in proc_version_text.lower():
        return "wsl"
    fields = parse_os_release(os_release_text)
    ident = fields.get("ID", "").lower()
    likes = set(fields.get("ID_LIKE", "").lower().split())
    if ident in RHEL_IDS or likes & RHEL_IDS:
        return "rhel"
    if ident in DEBIAN_IDS or likes & DEBIAN_IDS:
        return "debian"
    return "other"


def _resolve(flag_value, env_key, real_path):
    if flag_value:
        return flag_value
    if os.environ.get(env_key):
        return os.environ[env_key]
    return real_path


def build_facts(args):
    os_release = read_text(_resolve(args.os_release, "INSTALLKIT_OS_RELEASE",
                                    OS_RELEASE))
    proc_version = read_text(_resolve(args.proc_version, "INSTALLKIT_PROC_VERSION",
                                      PROC_VERSION))
    pid1 = read_text(_resolve(args.pid1_comm, "INSTALLKIT_PID1_COMM",
                              PID1_COMM)).strip()
    family = os.environ.get("INSTALLKIT_DISTRO_FAMILY") or \
        detect_family(os_release, proc_version)
    if family not in FAMILIES:
        family = "other"
    distro_id = parse_os_release(os_release).get("ID", "")
    return {"family": family, "distro_id": distro_id,
            "systemd_pid1": "true" if pid1 == "systemd" else "false",
            "pid1_comm": pid1}


def cmd_detect(args):
    facts = build_facts(args)
    for key in ("family", "distro_id", "systemd_pid1"):
        print("%s=%s" % (key, facts[key]))
    return 0


def render_unit(template_text, domain, substitutions):
    """Template + domain -> final unit bytes. The domain is the only knob
    that changes them: user-domain units pull under default.target because
    multi-user.target is never reached inside a user manager."""
    if domain == "user":
        template_text = template_text.replace("WantedBy=multi-user.target",
                                              "WantedBy=default.target")
    for token, value in substitutions.items():
        template_text = template_text.replace("__%s__" % token, value)
    return template_text


def cmd_render(args):
    if args.domain not in ("user", "system"):
        sys.stderr.write(
            "unknown unit domain %r — must be 'user' (run as your login "
            "account, units under ~/.config/systemd/user) or 'system' "
            "(host-wide, units under /etc/systemd/system)\n" % args.domain)
        return 2
    try:
        with open(args.template, "r", encoding="utf-8") as fh:
            template_text = fh.read()
    except OSError as exc:
        sys.stderr.write("cannot read unit template %s: %s — point --template "
                         "at installkit/tp-service-agent.service in the "
                         "checkout or the unpacked payload\n"
                         % (args.template, exc))
        return 1
    text = render_unit(template_text, args.domain, {
        "PYTHON": args.python, "AGENT_ENTRY": args.agent_entry,
        "ENV_FILE": args.env_file})
    left = [token for token in ("__PYTHON__", "__AGENT_ENTRY__", "__ENV_FILE__")
            if token in text]
    if left:
        # Never land a unit systemd would load with a placeholder inside.
        sys.stderr.write("template still holds %s — the substitution failed\n"
                         % ", ".join(left))
        return 1
    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    # Atomic replace: a crash must not leave a half-written unit loaded.
    fd, tmp = tempfile.mkstemp(dir=out_dir, prefix=".unit-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        os.replace(tmp, args.out)
    except OSError as exc:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        sys.stderr.write("cannot write unit %s: %s — is the directory "
                         "writable without sudo? (this trio never elevates)\n"
                         % (args.out, exc))
        return 1
    return 0


def _linger_state(loginctl_bin, user):
    """Ask loginctl whether user services survive logout. Any failure to
    reach it (missing binary, non-zero exit) is None = unknown, never a
    silent pass."""
    try:
        proc = subprocess.run([loginctl_bin, "show-user", user,
                               "--property=Linger"], capture_output=True,
                              text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    for line in (proc.stdout or "").splitlines():
        if line.startswith("Linger="):
            return line.split("=", 1)[1].strip()
    return None


def cmd_preflight(args):
    facts = build_facts(args)
    if facts["systemd_pid1"] != "true":
        if facts["family"] == "wsl":
            sys.stderr.write(
                "this WSL guest is not running systemd (PID1 is %r). Bare "
                "node hosting needs it: set '[systemd]' section with "
                "systemd=true in /etc/wsl.conf, run 'wsl --shutdown' from "
                "Windows, re-enter the distro, and re-run this install once "
                "'systemctl is-system-running' answers running or degraded.\n"
                % facts["pid1_comm"])
        else:
            sys.stderr.write(
                "PID1 on this host is %r, not systemd — the bare trio cannot "
                "host a unit here. Use the Kubernetes form under "
                "deploy/fleet/k8s instead, or move this node to a "
                "systemd-based distribution (Ubuntu 20.04+ / CentOS 8+).\n"
                % facts["pid1_comm"])
        return 1
    if args.domain == "user":
        user = os.environ.get("USER") or os.environ.get("LOGNAME") or ""
        state = _linger_state(args.loginctl_bin, user)
        if state is None:
            sys.stderr.write(
                "user-domain units need a lingering session: 'loginctl "
                "show-user %s --property=Linger' did not answer. Either "
                "install/enable loginctl, run 'loginctl enable-linger %s', "
                "or reinstall with EXECUTOR_NODE_UNIT_DOMAIN=system.\n"
                % (user or "$USER", user or "$USER"))
            return 1
        if state != "yes":
            sys.stderr.write(
                "linger is off for %r: user-domain units stop when the login "
                "session ends, so the agent dies after logout. Run "
                "'loginctl enable-linger %s' and re-run this install, or "
                "switch to EXECUTOR_NODE_UNIT_DOMAIN=system.\n"
                % (user, user))
            return 1
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    def add_facts(target):
        target.add_argument("--os-release", default="")
        target.add_argument("--proc-version", default="")
        target.add_argument("--pid1-comm", default="")

    p_detect = sub.add_parser("detect", help="print the host profile")
    add_facts(p_detect)
    p_detect.set_defaults(func=cmd_detect)

    p_render = sub.add_parser("render", help="render the unit template")
    p_render.add_argument("--template", required=True)
    p_render.add_argument("--out", required=True)
    p_render.add_argument("--domain", required=True)
    p_render.add_argument("--python", required=True)
    p_render.add_argument("--agent-entry", required=True)
    p_render.add_argument("--env-file", required=True)
    p_render.set_defaults(func=cmd_render)

    p_pre = sub.add_parser("preflight", help="can this host run the unit?")
    add_facts(p_pre)
    p_pre.add_argument("--domain", default="system")
    p_pre.add_argument("--loginctl-bin",
                       default=os.environ.get("LOGINCTL_BIN", "loginctl"))
    p_pre.set_defaults(func=cmd_preflight)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
