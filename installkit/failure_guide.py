# -*- coding: utf-8 -*-
"""Failure-injection guidance for the installer (open-delivery batch T293d).

Turns raw failures captured from docker/maven/postgres output into a short
recovery note with three parts: the offending value named, a literal place to
fix it (file and key, or a probe command), and the exact rerun command that
resumes the interrupted scenario. Unknown output degrades to None so callers
keep their own generic fallback; nothing here ever suggests a manual refresh.
"""

import re

import image_relay  # accelerator candidates + reference shape live there (no second list)

RESUME_HINT = "修复后原样重跑同一条命令即断点续跑：bash installkit/install.sh --scenario=<本次场景>"

# Tagged image references as docker prints them, with or without quotes:
# "docker.io/library/nginx:1.27-alpine" -> nginx:1.27-alpine.
_IMAGE_REF = re.compile(
    r"[\"']?((?:[a-z0-9][a-z0-9.\-]*\.[a-z]{2,}/)?[a-z0-9][a-z0-9.\-]*"
    r"(?:/[a-z0-9._\-]+)*:[A-Za-z0-9_][A-Za-z0-9.\-]*)[\"']?")


def canonical_image(token):
    """Strip what compose never writes: the ``docker.io/`` and ``library/`` prefixes."""
    named = token[len("docker.io/"):] if token.startswith("docker.io/") else token
    if named.startswith("library/"):
        named = named[len("library/"):]
    return named


_IPV4 = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")


def _is_endpoint(token):
    """True for ``128.242.250.157:443`` style noise in a dial-timeout sentence."""
    head = token.split("/")[0]
    return ":" in head and _IPV4.match(head.split(":")[0]) is not None and "/" not in token


def images_in_output(text):
    """Pull the offending image names out of raw docker output, deduplicated.

    URLs and endpoints are skipped so the note lists only images an operator
    can act on: a manifest URL never carries a ``:tag`` after its path, and a
    bare address:port is the socket that timed out, not something to pull.
    """
    out = []
    for token in _IMAGE_REF.findall(text or ""):
        if _is_endpoint(token):
            continue
        named = canonical_image(token)
        if named not in out:
            out.append(named)
    return out


def relay_example(image):
    """The detour reference for one image, through the first shipped accelerator."""
    return image_relay.relay_ref(canonical_image(image), image_relay.SHIPPED_MIRRORS[0])


def note_for_failure(text, **facts):
    """Classify raw output and build its recovery note in one call (None = unknown).

    Single entry so callers stop re-pairing classify+teach; explicit ``facts``
    win over anything scraped from the output itself.
    """
    kind = classify_failure(text)
    if not kind:
        return None
    if kind == "registry_blocked":
        facts.setdefault("output", text or "")
    elif kind == "port_conflict" and "port" not in facts:
        match = re.search(
            r"(?:address already in use|port is already allocated|bind for)[^0-9]*(\d{2,5})",
            (text or "").lower())
        if match:
            facts["port"] = match.group(1)
    return teach(kind, **facts)


# Lower-cased substrings grouped by failure family. Kept data-driven so new
# vendor message variants join as one entry instead of code changes.
_SIGNATURES = (
    ("disk_short", ("no space left", "disk quota exceeded")),
    ("pg_existing", ("already exists", "duplicate_database")),
    # Vendor wording for "the image never arrived". Kept ahead of egress_dead so
    # a blocked hub teaches the accelerator detour instead of a generic
    # "check your network" line; DNS-resolution wording still lands in egress_dead.
    ("registry_blocked", (
        "failed to resolve reference",
        "failed to do request",
        "i/o timeout",
        "tls handshake timeout",
        "toomanyrequests",
        "pull access denied",
        "repository does not exist",
        "error from registry: manifest unknown",
    )),
    ("egress_dead", (
        "temporary failure in name resolution",
        "name or service not known",
        "could not resolve host",
        "dial tcp: lookup",
        "connection timed out",
        "network is unreachable",
        "request canceled while waiting for connection",
    )),
    ("port_conflict", (
        "address already in use",
        "port is already allocated",
        "bind for",
    )),
)


def classify_failure(text):
    """Return the failure family for captured output text, or None if unknown."""
    if not text:
        return None
    low = text.lower()
    for kind, needles in _SIGNATURES:
        if any(n in low for n in needles):
            # "already exists" is broad: keep it bound to database wording so a
            # file/dir conflict is not mis-taught as a Postgres duplicate.
            if kind == "pg_existing" and "database" not in low:
                continue
            return kind
    return None


def teach(kind, **facts):
    """Build the recovery note for a classified failure; None for unknown."""
    if kind == "port_conflict":
        port = facts.get("port", "?")
        key = facts.get("key", "SERVER_PORT_OFFSET")
        return (
            "端口 %s 已被本机其他进程占用。\n"
            "查看占用者：ss -ltnp | grep %s\n"
            "两条出路：停掉旧服务，或在 .env 把 %s 改为空闲端口（整机端口带也可用 "
            "SERVER_PORT_OFFSET 偏移）。\n%s" % (port, port, key, RESUME_HINT)
        )
    if kind == "disk_short":
        need = facts.get("required_mb", "?")
        avail = facts.get("available_mb", "?")
        return (
            "磁盘空间不足：本档装机需要约 %s MB，当前分区仅余 %s MB。\n"
            "先确认各分区余量：df -h\n"
            "可行清理（任选，均有删除影响，执行前看清楚对象）：docker system prune "
            "回收无引用镜像与构建缓存；或把 Docker 数据目录迁到更大的分区。\n%s"
            % (need, avail, RESUME_HINT)
        )
    if kind == "pg_existing":
        db = facts.get("db", "?")
        return (
            "数据库 %s 已存在。若这是本机上一次装机保留的数据，直接继续即可"
            "（安装步骤幂等，不会清库）。\n"
            "若要换一个全新环境：在 .env 给库改名——应用库改 DB_URL 里的库名段，"
            "对象存储元数据库改 SWF_META_DB_NAME——再重跑。\n"
            "只有确认该库内数据不再需要时才手动删除，删除前请先备份。\n%s"
            % (db, RESUME_HINT)
        )
    if kind == "registry_blocked":
        images = facts.get("images") or images_in_output(facts.get("output") or "")
        tried = facts.get("mirrors_tried") or list(image_relay.SHIPPED_MIRRORS)
        shown = "、".join(images) if images else "见上方 docker 输出的镜像名"
        sample = images[0] if images else "nginx:1.27-alpine"
        relay = relay_example(sample)
        mirror_urls = ", ".join('"https://%s"' % m for m in image_relay.SHIPPED_MIRRORS)
        return (
            "以下镜像取不到，本机到 Docker Hub（%s）的直连不通或被限流：%s。\n"
            % (image_relay.HUB_HOST, shown)
            + ("已自动尝试的加速器：%s（均未取全）。\n" % "、".join(tried) if tried else "")
            + "三条出路，按代价从低到高：\n"
              "  A 免管理员，改道取回后改成本机认得的名字（加速器地址可换任一可用项）：\n"
              "     docker pull %s\n"
              "     docker tag  %s %s\n" % (relay, relay, sample)
            + '  B 管理员一次性配好引擎（保留 /etc/docker/daemon.json 原有键，只新增这一段）：\n'
              '     "registry-mirrors": [%s]\n' % mirror_urls
            + "     再执行 sudo systemctl restart docker —— 会重启容器引擎，"
              "本机其它在跑的容器会被中断，确认后再做\n"
            + "  C 本机彻底无外网：在任意能拉到镜像的机器上执行 docker save -o testpike-images.tar %s，"
              "把该文件拷到本机后 docker load -i testpike-images.tar\n"
              % (" ".join(images) if images else "<镜像名:标签>")
            + "想让脚本以后自动走内网仓库：export %s=<内网仓库地址>（可写进 .env，多个用逗号分隔），"
              "重跑同一命令即生效。\n" % image_relay.MIRRORS_KEY
            + "%s" % RESUME_HINT
        )
    if kind == "egress_dead":
        target = facts.get("target", "外部镜像源")
        return (
            "无法连接 %s：本机出网不通或该站点暂时不可达。\n"
            "检查网络与代理后直接重跑即可续传；若平台配置了镜像源，"
            "用 --mirror-base-url=<镜像地址> 指定备用源再跑。\n%s"
            % (target, RESUME_HINT)
        )
    return None
