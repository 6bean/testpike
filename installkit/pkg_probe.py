#!/usr/bin/env python3
# -*- coding: utf-8 -*-
pass
import os
import platform
import shutil
import socket
import sys

PREREQS = ("docker", "node", "git", "python3")

PREREQ_CMD = {
    "apt": {
        "docker": "sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2",
        "node": "sudo apt-get install -y nodejs npm",
        "git": "sudo apt-get install -y git",
        "python3": "sudo apt-get install -y python3 python3-pip",
    },
    "dnf": {
        "docker": "sudo dnf install -y docker-ce docker-ce-cli containerd.io (or: dnf module install docker)",
        "node": "sudo dnf install -y nodejs npm",
        "git": "sudo dnf install -y git",
        "python3": "sudo dnf install -y python3 python3-pip",
    },
    "apk": {
        # Alpine (musl) line: apk is the root-native CLI, no sudo prefix.
        # py3-pip + py3-httpx ride the python3 line so a bare image reaches
        # both this repair path and the installer's own imports (r39b finding).
        "docker": "apk add --no-cache docker-cli docker-cli-compose",
        "node": "apk add --no-cache nodejs npm",
        "git": "apk add --no-cache git",
        "python3": "apk add --no-cache python3 py3-pip py3-httpx",
    },
    "brew": {
        "docker": "brew install --cask docker",
        "node": "brew install node",
        "git": "brew install git",
        "python3": "brew install python",
    },
    "winget": {
        "docker": "winget install -e --id Docker.DockerDesktop",
        "node": "winget install -e --id OpenJS.NodeJS.LTS",
        "git": "winget install -e --id Git.Git",
        "python3": "winget install -e --id Python.Python.3.12",
    },
    "choco": {
        "docker": "choco install -y docker-desktop",
        "node": "choco install -y nodejs-lts",
        "git": "choco install -y git",
        "python3": "choco install -y python312",
    },
}

DEBIAN_FAMILY = ("debian", "ubuntu", "linuxmint")
RPM_FAMILY = ("fedora", "rhel", "centos", "rocky", "alma", "amzn")
ALPINE_FAMILY = ("alpine", "postmarketos")


def host_os():
    pass
    raw = platform.system()
    return {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(raw, raw.lower())


def read_os_release():
    try:
        with open("/etc/os-release", encoding="utf-8") as f:
            return f.read()
    except OSError:
        return None


def _kv(text, key):
    for line in (text or "").splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip().strip('"')
    return ""


def pkg_manager_for(os_kind, os_release_text):
    pass
    if os_kind == "macos":
        return "brew"
    if os_kind == "windows":
        return "winget"
    if os_kind != "linux":
        return None
    ids = {_kv(os_release_text, "ID")}
    ids.discard("")
    ids.update(_kv(os_release_text, "ID_LIKE").split())
    if ids & set(DEBIAN_FAMILY):
        return "apt"
    if ids & set(RPM_FAMILY):
        return "dnf"
    if ids & set(ALPINE_FAMILY):
        return "apk"
    return None


def is_effective_root():
    pass
    try:
        return os.geteuid() == 0
    except AttributeError:
        return False


def apply_privilege(cmd, root=None):
    pass
    if not (is_effective_root() if root is None else root):
        return cmd
    segments = []
    for part in cmd.split("&&"):
        stripped = part.strip()
        segments.append(stripped[len("sudo "):].strip()
                        if stripped.startswith("sudo ") else stripped)
    return " && ".join(segments)


def install_command(pm, prereq):
    pass
    table = PREREQ_CMD.get(pm)
    if table is None:
        raise ValueError("Unregistered package-manager profile \"%s\" -- supported: %s; "
                         "on other distributions install the prerequisites manually per the "
                         "official docs, then re-run the preflight"
                         % (pm, " / ".join(PREREQ_CMD)))
    cmd = table.get(prereq)
    if cmd is None:
        raise ValueError("Prerequisite \"%s\" is not in the registry -- options: %s"
                         % (prereq, " / ".join(PREREQS)))
    return apply_privilege(cmd)


def port_occupied(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def preflight(require_items, ports, require_disk_mb=None, disk_root=None):
    pass
    os_kind = host_os()
    release = read_os_release() if os_kind == "linux" else None
    pretty = _kv(release, "PRETTY_NAME") if release else ""
    pm = pkg_manager_for(os_kind, release)
    pm_text = pm if pm else ("unknown (this distribution has no registered "
                             "package-manager profile; install the prerequisites "
                             "manually per the official docs)")
    print("Preflight report | OS=%s %s | package manager=%s" % (os_kind, pretty, pm_text))

    unknown = [r for r in require_items if r not in PREREQS]
    if unknown:
        print("Prerequisite \"%s\" is not in the registry -- options: %s"
              % (", ".join(unknown), " / ".join(PREREQS)),
              file=sys.stderr)
        return 2

    for req in require_items:
        if shutil.which(req):
            print("prerequisite %s: present" % req)
            continue
        print("missing prerequisite %s" % req)
        if pm:
            print("  install command for this profile: %s" % install_command(pm, req))
        else:
            print("  package-manager profile unknown -- install manually per the %s "
                  "official docs, then re-run the preflight" % (os_kind or "this host"))

    for port in ports:
        if port_occupied(port):
            print("port %d busy: a service is already listening on this machine -- "
                  "identify the owner: ss -ltnp | grep %d; if this machine is the "
                  "platform server, stop the old services first or change the port "
                  "keys in .env; executor-only machines can ignore this" % (port, port))
        else:
            print("port %d free" % port)

    if require_disk_mb is not None:
        root = disk_root or "/"
        free_mb = shutil.disk_usage(root).free // (1024 * 1024)
        print("disk: partition %s has %d MB free, this install profile needs %d MB"
              % (root, free_mb, require_disk_mb))
        if free_mb < require_disk_mb:
            import failure_guide
            print(failure_guide.teach("disk_short",
                                      required_mb=require_disk_mb,
                                      available_mb=free_mb),
                  file=sys.stderr)
            return 3
    return 0


def main(argv):
    if not argv or argv[0] not in ("preflight", "--help", "-h"):
        print("usage: pkg_probe.py preflight [--require=docker,node,git] "
              "[--ports=8081,8082] [--require-disk-mb=10240] [--disk-root=/]",
              file=sys.stderr)
        return 2
    if argv[0] in ("--help", "-h"):
        print("usage: pkg_probe.py preflight [--require=<prereq list>] [--ports=<port list>]\n"
              "Multi-profile package-manager adapter: apt/dnf/apk via /etc/os-release, "
              "brew on macOS, winget/choco on Windows.")
        return 0
    require, ports = [], []
    disk_mb, disk_root = None, None
    for arg in argv[1:]:
        if arg.startswith("--require="):
            require = [x for x in arg.split("=", 1)[1].split(",") if x]
        elif arg.startswith("--ports="):
            spec = arg.split("=", 1)[1]
            if spec.strip() == "auto":
                # Resolve effective host ports through the single source
                # (process env > .env > compose shipped), no literal list here.
                import host_ports
                root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
                ports = [int(value) for _, value in host_ports.effective_host_ports(root)]
            else:
                ports = [int(x) for x in spec.split(",") if x]
        elif arg.startswith("--require-disk-mb="):
            disk_mb = int(arg.split("=", 1)[1])
        elif arg.startswith("--disk-root="):
            disk_root = arg.split("=", 1)[1]
        else:
            print("unrecognized argument: %s" % arg, file=sys.stderr)
            return 2
    return preflight(require, ports, require_disk_mb=disk_mb,
                     disk_root=disk_root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
