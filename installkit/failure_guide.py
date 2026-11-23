# -*- coding: utf-8 -*-
"""Failure-injection guidance for the installer (open-delivery batch T293d).

Turns raw failures captured from docker/maven/postgres output into a short
recovery note with three parts: the offending value named, a literal place to
fix it (file and key, or a probe command), and the exact rerun command that
resumes the interrupted scenario. Unknown output degrades to None so callers
keep their own generic fallback; nothing here ever suggests a manual refresh.
"""

RESUME_HINT = "修复后原样重跑同一条命令即断点续跑：bash installkit/install.sh --scenario=<本次场景>"

# Lower-cased substrings grouped by failure family. Kept data-driven so new
# vendor message variants join as one entry instead of code changes.
_SIGNATURES = (
    ("disk_short", ("no space left", "disk quota exceeded")),
    ("pg_existing", ("already exists", "duplicate_database")),
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
    if kind == "egress_dead":
        target = facts.get("target", "外部镜像源")
        return (
            "无法连接 %s：本机出网不通或该站点暂时不可达。\n"
            "检查网络与代理后直接重跑即可续传；若平台配置了镜像源，"
            "用 --mirror-base-url=<镜像地址> 指定备用源再跑。\n%s"
            % (target, RESUME_HINT)
        )
    return None
