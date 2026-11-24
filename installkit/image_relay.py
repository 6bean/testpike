#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Keep image delivery working on hosts that cannot reach the official registry.

A bring-up that dies on ``docker compose up`` because ``registry-1.docker.io``
is black-holed (DNS poisoning, egress filter, corporate proxy) used to surface
as a socket timeout with no way forward. This module turns that single failure
into an automatic detour, in this order:

1. images already present locally are never touched (idempotent rerun),
2. if this process may write the docker daemon config, merge accelerator
   ``registry-mirrors`` into it (back up first, never overwrite, only add)
   and restart the engine only when no foreign container is running,
3. otherwise pull each missing image through a mirror prefix and re-tag it to
   the canonical name compose asks for,
4. if nothing works, print the offline ferry recipe with the exact image list.

Nothing here elevates (no sudo, ever), nothing here deletes a user image, and
every candidate mirror / timeout is an env key with a shipped default so a
locked-down intranet can retune it without a code change.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

# Verified reachable 2026-11-24 from the shipping network (real pulls landed).
# The list is a shipped default, not a constant: TP_DOCKER_MIRRORS overrides it.
SHIPPED_MIRRORS = ("docker.m.daocloud.io", "docker.1ms.run", "docker.1panel.live")
HUB_HOST = "registry-1.docker.io"

MIRRORS_KEY = "TP_DOCKER_MIRRORS"
RELAY_KEY = "TP_DOCKER_RELAY"
ACCELERATE_KEY = "TP_DOCKER_ACCELERATE"
PROBE_TIMEOUT_KEY = "TP_DOCKER_PROBE_TIMEOUT_S"
DIRECT_TIMEOUT_KEY = "TP_DOCKER_DIRECT_TIMEOUT_S"
RELAY_TIMEOUT_KEY = "TP_DOCKER_RELAY_TIMEOUT_S"
DAEMON_JSON = "/etc/docker/daemon.json"
CONTAINER_PREFIX_KEY = "TP_CONTAINER_PREFIX"
SHIPPED_CONTAINER_PREFIX = "testpike"

_ENV_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_COMPOSE_INTERP = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")


# ------------------------------------------------------------------ knobs
def env_value(key, default, environ=None):
    """Read one knob: process env wins, blank/absent keeps the shipped default."""
    environ = os.environ if environ is None else environ
    raw = (environ.get(key) or "").strip()
    return raw or default


def mirrors(environ=None):
    """Accelerator candidate hosts, in probe order (config key + shipped default)."""
    raw = env_value(MIRRORS_KEY, ",".join(SHIPPED_MIRRORS), environ)
    return tuple(_strip_scheme(m.strip().rstrip("/")) for m in raw.split(",") if m.strip())


def _flag(key, environ):
    return env_value(key, "on", environ).lower() not in ("off", "0", "false", "no")


def relay_enabled(environ=None):
    """Master switch for the whole detour (TP_DOCKER_RELAY=off disables it)."""
    return _flag(RELAY_KEY, environ)


def accelerate_enabled(environ=None):
    """Switch for the daemon-config branch (still needs a writable daemon.json)."""
    return _flag(ACCELERATE_KEY, environ)


def _seconds(key, default, environ):
    raw = env_value(key, str(default), environ)
    try:
        value = float(raw)
    except ValueError:
        value = float(default)
    return value if value > 0 else float(default)


def _strip_scheme(host):
    return re.sub(r"^https?://", "", host).rstrip("/")


# ---------------------------------------------------------- image reference
def split_image(image):
    """Return (registry, repository, tag) for a compose image reference.

    ``nginx:1.27-alpine`` -> ("docker.io", "nginx", "1.27-alpine");
    ``quay.io/org/img:1`` -> ("quay.io", "org/img", "1"); an untagged reference
    keeps ``latest`` the way docker itself resolves it.
    """
    first, sep, rest = image.partition("/")
    if sep and ("." in first or ":" in first):
        registry, remainder = first, rest
    else:
        registry, remainder = "docker.io", image
    if ":" in remainder.rsplit("/", 1)[-1]:
        repo, tag = remainder.rsplit(":", 1)
    else:
        repo, tag = remainder, "latest"
    return registry, repo, tag


def on_docker_hub(image):
    """Only docker.io images can be relayed; a third-party registry is already direct."""
    return split_image(image)[0] == "docker.io"


def relay_ref(image, mirror):
    """Mirror-qualified reference for one docker.io image.

    Official images live under ``library/`` on every mirror that clones the
    hub namespace; namespaced images keep their own path.
    """
    _, repo, tag = split_image(image)
    prefix = "library/" if "/" not in repo else ""
    return "%s/%s%s:%s" % (_strip_scheme(mirror), prefix, repo, tag)


def resolve_interpolation(value, environ=None):
    """Apply compose ``${KEY:-fallback}`` semantics to a single scalar."""
    environ = os.environ if environ is None else environ

    def repl(match):
        key, fallback = match.group(1), match.group(2)
        return (environ.get(key) or "").strip() or (fallback or "")

    return _COMPOSE_INTERP.sub(repl, value).strip().strip("\"'")


def pullable_images(compose_path, profiles=(), environ=None):
    """Images the middleware bring-up must fetch, deduplicated, in file order.

    Parsed from docker-compose.yml directly (the same idiom host_ports.py uses
    for the port band) so the answer needs no running daemon: services that
    declare ``build:`` are excluded because compose builds those locally and a
    relay attempt against them can only ever fail.
    """
    wanted = set(profiles)
    out, seen = [], set()
    service, service_build, service_image, service_profiles = None, False, None, set()

    def flush():
        if service is None:
            return
        if wanted != service_profiles:
            return
        if service_build or not service_image:
            return
        if not on_docker_hub(service_image):
            return
        if service_image not in seen:
            seen.add(service_image)
            out.append(service_image)

    indent_of = None
    with open(compose_path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if re.match(r"^services:\s*$", line):
                flush()
                service = None
                indent_of = None
                continue
            if not line.startswith(" ") and not line.startswith("#"):
                flush()
                service = None
                indent_of = None
                continue
            top = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(.*)$", line)
            if top and not line.startswith("    "):
                flush()
                service = top.group(1)
                service_build, service_image = False, None
                service_profiles = set()
                indent_of = None
                continue
            if service is None:
                continue
            key = re.match(r"^    ([A-Za-z0-9_.-]+):\s*(.*)$", line)
            if not key:
                continue
            name, value = key.group(1), key.group(2).strip()
            if name == "build":
                service_build = True
            elif name == "image":
                service_image = resolve_interpolation(value, environ)
            elif name == "profiles":
                service_profiles = set(re.findall(r"\"?([A-Za-z0-9_.-]+)\"?", value))
    flush()
    return out


# ------------------------------------------------------------ local/docker
def docker_available():
    return shutil.which("docker") is not None


def _docker(args, timeout=None):
    cmd = ["docker"] + list(args)
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                              check=False)
    except (subprocess.TimeoutExpired, OSError) as exc:
        return 124, "", "%s" % exc
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def local_image_tags():
    """Every ``repository:tag`` the engine has, in one call (None when unreadable).

    ``docker image inspect --type image`` is rejected by the 29.x CLI, which
    made every locally present image look missing and re-pulled the whole
    stack on an idempotent rerun; the tag list is both cheaper and stable.
    """
    rc, out, _err = _docker(["images", "--format", "{{.Repository}}:{{.Tag}}"], timeout=60)
    if rc != 0:
        return None
    return {line.strip() for line in out.splitlines() if line.strip()}


def present_locally(image, known=None):
    if known is not None:
        return image in known
    rc, _out, _err = _docker(["image", "inspect", image], timeout=30)
    return rc == 0


def running_container_names():
    rc, out, _err = _docker(["ps", "--format", "{{.Names}}"], timeout=30)
    if rc != 0:
        return None
    return [n.strip() for n in out.splitlines() if n.strip()]


def configured_mirrors():
    """Accelerators the daemon already claims (empty list = none configured)."""
    rc, out, _err = _docker(["info", "--format", "{{json .RegistryConfig.Mirrors}}"],
                           timeout=30)
    if rc != 0:
        return None
    try:
        value = json.loads(out.strip() or "[]")
    except ValueError:
        return None
    return [str(v) for v in value] if isinstance(value, list) else None


def reachable(url, timeout):
    """Any HTTP answer (401 token challenge included) proves the endpoint is up."""
    try:
        import httpx
    except ImportError:
        return None
    try:
        resp = httpx.get(url, timeout=timeout, follow_redirects=True)
    except Exception:
        return False
    return resp.status_code < 500


def hub_reachable(environ=None):
    return reachable("https://%s/v2/" % HUB_HOST, _seconds(PROBE_TIMEOUT_KEY, 6, environ))


def mirror_reachable(mirror, environ=None):
    return reachable("https://%s/v2/" % _strip_scheme(mirror),
                     _seconds(PROBE_TIMEOUT_KEY, 6, environ))


# ------------------------------------------------- daemon config self-healing
def can_write_daemon(path=DAEMON_JSON, environ=None):
    """True only when this process may edit the daemon file without elevating.

    A missing file counts as writable when its directory is: installs on a
    fresh host legitimately have no daemon.json yet.
    """
    if not accelerate_enabled(environ):
        return False
    target = path if os.path.exists(path) else os.path.dirname(path)
    return os.access(target, os.W_OK)


def merge_registry_mirrors(existing, candidates):
    """Return the merged daemon dict plus the keys this run adds (never overwrite)."""
    merged = dict(existing or {})
    current = merged.get("registry-mirrors")
    current = list(current) if isinstance(current, list) else ([] if current is None else [current])
    added = []
    for host in candidates:
        url = "https://%s" % _strip_scheme(host)
        if not any(_strip_scheme(str(c)) == _strip_scheme(url) for c in current):
            current.append(url)
            added.append(url)
    merged["registry-mirrors"] = current
    return merged, added


def apply_daemon_mirrors(candidates, environ=None, daemon_path=DAEMON_JSON):
    """Back up, merge and restart so the daemon itself stops hitting the blocked hub.

    Returns (handled, note). Refuses to restart while a container that this
    product did not create is running, and prints the literal command instead;
    a foreign workload must not be interrupted by an installer.
    """
    if not docker_available():
        return False, "本机没有 docker 命令，加速器无法配置"
    existing = {}
    if os.path.exists(daemon_path):
        try:
            with open(daemon_path, encoding="utf-8") as fh:
                text = fh.read().strip()
            existing = json.loads(text) if text else {}
        except (OSError, ValueError) as exc:
            return False, "%s 解析失败（%s）——不敢改动，请先人工确认该文件内容" % (daemon_path, exc)
        if not isinstance(existing, dict):
            return False, "%s 顶层不是对象——不敢改动" % daemon_path
        backup = "%s.bak-%s" % (daemon_path, os.getpid())
        try:
            shutil.copyfile(daemon_path, backup)
        except OSError as exc:
            return False, "%s 备份失败（%s）——放弃改动" % (daemon_path, exc)
    merged, added = merge_registry_mirrors(existing, candidates)
    if not added:
        return True, "%s 已含全部加速器候选，无需改动（幂等）" % daemon_path
    try:
        os.makedirs(os.path.dirname(daemon_path), exist_ok=True)
        with open(daemon_path, "w", encoding="utf-8") as fh:
            json.dump(merged, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
    except OSError as exc:
        return False, "%s 写入失败（%s）" % (daemon_path, exc)
    foreign = foreign_containers(environ)
    if foreign:
        return False, ("加速器已写入 %s（原文件备份在 %s），但以下容器不属于本系统、"
                       "重启 docker 会打断它们：%s。请自行择机执行：sudo systemctl restart docker"
                       % (daemon_path, _backup_of(daemon_path), "、".join(foreign)))
    restarted = _restart_engine()
    if not restarted:
        return False, ("加速器已写入 %s（原文件备份在 %s），但引擎未能由本脚本重启。"
                       "请执行：sudo systemctl restart docker" % (daemon_path, _backup_of(daemon_path)))
    claimed = {_strip_scheme(str(c)) for c in (configured_mirrors() or [])}
    missing_claim = [a for a in added if _strip_scheme(a) not in claimed]
    if missing_claim:
        return False, ("加速器已写入 %s，但 docker info 回读未见生效（缺：%s）——"
                       "请检查引擎状态后重跑" % (daemon_path, "、".join(missing_claim)))
    return True, "加速器已生效：%s（docker info 回读确认）" % "、".join(_strip_scheme(a) for a in added)


def _backup_of(daemon_path):
    return "%s.bak-%s" % (daemon_path, os.getpid())


def foreign_containers(environ=None):
    """Running containers whose names fall outside this product's prefix."""
    prefix = env_value(CONTAINER_PREFIX_KEY, SHIPPED_CONTAINER_PREFIX, environ)
    names = running_container_names()
    if names is None:
        return []
    return [n for n in names if not n.startswith(prefix)]


def _restart_engine():
    if os.environ.get("INSTALLKIT_SYSTEMCTL_BIN"):
        bin_ = os.environ["INSTALLKIT_SYSTEMCTL_BIN"]
    else:
        bin_ = shutil.which("systemctl")
    if not bin_:
        return False
    try:
        proc = subprocess.run([bin_, "restart", "docker"], capture_output=True, text=True,
                              timeout=120, check=False)
    except (subprocess.TimeoutExpired, OSError):
        return False
    return proc.returncode == 0


# ------------------------------------------------------------ relay pulling
def pull(image, timeout):
    return _docker(["pull", image], timeout=timeout)


def relay_one(image, mirror, direct_timeout, relay_timeout):
    """Pull ``image`` through ``mirror`` and re-tag it to the canonical name.

    Returns (ok, detail). The canonical tag is what compose looks for, so the
    compose file itself never needs to change.
    """
    ref = relay_ref(image, mirror)
    rc, _out, err = pull(ref, relay_timeout)
    if rc != 0:
        return False, "%s 拉取失败：%s" % (ref, (err or "").strip()[-300:] or "无输出")
    tag_rc, _o, tag_err = _docker(["tag", ref, image], timeout=30)
    if tag_rc != 0:
        return False, "%s 已拉到本机但改名 %s 失败：%s" % (ref, image, (tag_err or "").strip()[-200:])
    _docker(["rmi", ref], timeout=30)
    return True, "经 %s 取到并改名为 %s" % (_strip_scheme(mirror), image)


def ensure_images(images, environ=None, printer=None, candidates=None, known=None):
    """Make every image in ``images`` available locally, relaying what is missing.

    Report buckets: ``present`` (already on disk, untouched), ``pulled`` (the
    engine got it directly — either the hub answers or the daemon already has
    working mirrors), ``relayed`` (fetched through a mirror prefix and re-tagged),
    ``failed`` (list of (image, reason)). ``known`` may carry a pre-read tag set
    so a whole stack costs one docker call instead of N.
    """
    printer = printer or (lambda text: print(text))
    candidates = tuple(candidates if candidates is not None else mirrors(environ))
    direct_timeout = _seconds(DIRECT_TIMEOUT_KEY, 120, environ)
    relay_timeout = _seconds(RELAY_TIMEOUT_KEY, 900, environ)
    if known is None:
        known = local_image_tags()
    report = {"present": [], "pulled": [], "relayed": [], "failed": []}
    for image in images:
        if present_locally(image, known=known):
            report["present"].append(image)
            continue
        rc, _out, err = pull(image, direct_timeout)
        if rc == 0:
            report["pulled"].append(image)
            printer("镜像 %s 已由本机引擎直接取到。" % image)
            continue
        last_error = (err or "").strip()
        got = False
        for host in candidates:
            ok, detail = relay_one(image, host, direct_timeout, relay_timeout)
            if ok:
                report["relayed"].append(image)
                printer("镜像 %s：%s" % (image, detail))
                got = True
                break
            printer("镜像 %s：加速器 %s 未取到（%s）" % (image, _strip_scheme(host), detail))
        if not got:
            report["failed"].append((image, last_error[-300:] or "官方仓库与各加速器均未取到"))
    return report


def ferry_guide(images, mirror_list=None):
    """Offline recipe for a machine with no reachable registry at all.

    The save side runs on any machine that already has the images (a developer
    box), the load side lands the same tar on the install target.
    """
    listed = " ".join(images)
    return (
        "本机与全部加速器都取不到镜像，可离线摆渡（不需要外网）：\n"
        "  1) 在任意已装好 docker 且能拉到镜像的机器上执行：\n"
        "     docker pull %s\n" % "\n     docker pull ".join(images or ["<镜像名:标签>"])
        + "     docker save -o testpike-images.tar %s\n" % listed
        + "  2) 把 testpike-images.tar 拷到装机机（scp / U 盘 / 内网共享目录均可），执行：\n"
        "     docker load -i testpike-images.tar\n"
        "  3) 原样重跑同一条装机命令即从断点续跑。\n"
        "内网另有可用仓库时，把它作为加速器登记即可免改代码：\n"
        "  export %s=%s\n" % (MIRRORS_KEY, (mirror_list or "<内网仓库地址>") + "[,更多候选]")
    )


def ensure_for_compose(compose_path, profiles=(), environ=None, printer=None):
    """Bring-up preflight: guarantee the stage's images exist before compose runs.

    Returns True when every needed image is local afterwards (also the honest
    answer when docker or the compose file is absent — callers keep their own
    failure path), False when something could not be delivered.
    """
    printer = printer or (lambda text: print(text))
    environ = os.environ if environ is None else environ
    if not relay_enabled(environ) or not docker_available():
        return True
    if not os.path.exists(compose_path):
        return True
    images = pullable_images(compose_path, profiles=profiles, environ=environ)
    known = local_image_tags()
    missing = [i for i in images if not present_locally(i, known=known)]
    if not missing:
        return True
    printer("本机缺少 %d 个中间件镜像：%s" % (len(missing), "、".join(missing)))
    hub = hub_reachable(environ)
    daemon_mirrors = configured_mirrors() or []
    if hub is False and daemon_mirrors:
        printer("官方镜像仓库 %s 不可达，但本机引擎已配加速器（%s），先按引擎直连取。"
                % (HUB_HOST, "、".join(_strip_scheme(str(m)) for m in daemon_mirrors)))
    elif hub is False:
        printer("官方镜像仓库 %s 不可达，自动改道取镜像。" % HUB_HOST)
    if can_write_daemon(environ=environ):
        handled, note = apply_daemon_mirrors(mirrors(environ), environ=environ)
        printer(note)
        if handled:
            report = ensure_images(missing, environ=environ, printer=printer, known=known)
            if report["failed"]:
                return _report_failed(report, images, printer)
            return _summary(report, printer)
    report = ensure_images(missing, environ=environ, printer=printer, known=known)
    if report["failed"]:
        return _report_failed(report, images, printer)
    return _summary(report, printer)


def _summary(report, printer):
    """One honest line about what the relay actually did."""
    if not report["pulled"] and not report["relayed"]:
        return True
    printer("镜像备齐：本机引擎直取 %d 个，经加速器改道 %d 个。"
            % (len(report["pulled"]), len(report["relayed"])))
    return True


def _report_failed(report, images, printer):
    failed = [i for i, _reason in report["failed"]]
    printer(ferry_guide(failed, ",".join(mirrors())))
    return False


def main(argv=None):
    parser = _make_parser()
    args = parser.parse_args(argv)
    if args.cmd == "ensure":
        ok = ensure_for_compose(args.compose_file, profiles=tuple(args.profile or ()),
                                printer=print)
        return 0 if ok else 1
    if args.cmd == "plan":
        for image in pullable_images(args.compose_file, profiles=tuple(args.profile or ())):
            print(image)
        return 0
    if args.cmd == "guide":
        images = [a for a in (args.image or []) if a] or pullable_images(
            args.compose_file, profiles=tuple(args.profile or ()))
        print(ferry_guide(images, ",".join(mirrors())))
        return 0
    return 2


def _make_parser():
    parser = argparse.ArgumentParser(
        prog="image_relay.py",
        description="装机镜像改道件（Docker Hub 不可达时自动经加速器取镜像）")
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("ensure", "plan", "guide"):
        p = sub.add_parser(name, help={"ensure": "补齐 compose 所需镜像",
                                        "plan": "只列出该阶段需要的镜像",
                                        "guide": "只打印离线摆渡指引"}[name])
        p.add_argument("--compose-file", required=(name != "guide"))
        p.add_argument("--profile", action="append", default=[])
        if name == "guide":
            p.add_argument("--image", action="append", default=[])
    return parser


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
