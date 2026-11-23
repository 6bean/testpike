
from __future__ import annotations

from urllib.parse import urlsplit


class HttpExecError(ValueError):
    pass


def host_matches(host: str, pattern: str) -> bool:
    pass
    if pattern.startswith("*."):
        domain = pattern[2:]
        return host == domain or host.endswith("." + domain)
    return host == pattern


def env_hosts(env_base_url: str | None) -> list:
    pass
    host = (urlsplit(env_base_url or "").hostname or "").lower()
    return [host] if host else []


def check_url_gates(policy: dict | None, env_base_url: str | None, url: str) -> dict:
    pass
    if not isinstance(policy, dict) or "allowHosts" not in policy:
        raise HttpExecError(
            "执行策略未下发（execPolicy），拒绝执行——"
            "core 版本过旧或未随 claim 下发主机白名单，禁放行（SSRF 防护）")
    host = (urlsplit(url).hostname or "").lower()
    if not host:
        raise HttpExecError(f"url 无法解析主机名，拒绝执行：{url}")
    deny = [p.strip().lower() for p in str(policy.get("denyHosts") or "").split(",")
            if p.strip()]
    for pattern in deny:
        if host_matches(host, pattern):
            raise HttpExecError(
                f"目标主机「{host}」命中生产域名黑名单（{pattern}），禁止执行"
                "——测试环境禁打生产")
    allow = str(policy.get("allowHosts") or "").strip()
    allowed = ([p.strip().lower() for p in allow.split(",") if p.strip()]
               if allow else env_hosts(env_base_url))
    if not any(host_matches(host, p) for p in allowed):
        raise HttpExecError(
            f"目标主机「{host}」不在允许域名（exec.http.allow-hosts）内"
            f"（允许集：{'、'.join(allowed) or '（空）'}），请联系管理员补充")
    return policy
