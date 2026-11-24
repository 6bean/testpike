# -*- coding: utf-8 -*-
pass
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import httpx

import resource_tiers
import mq_vhost
import host_ports  # host-port band resolution (compose precedence, single source)
import instance_names
import demo_seed

REPO_ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILE = "docker-compose.yml"

SERVICE_GUIDE = (
    ("admin", "8081", "面向用户管理接口"),
    ("api", "8082", "体系外对接入口（执行器/装机脚本只走这里）"),
    ("core", "8083", "服务层（调度/配置/审计）"),
    ("ai-service", "18084", "AI 编排（仅内部）"),
)


def load_answers(answers_path):
    pass
    try:
        with open(answers_path, encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def compose_stages(answers_path, repo_root=None):
    pass
    answers = load_answers(answers_path)
    role = answers.get("role") or "platform"
    if role != "platform":
        return []
    root = str(repo_root or REPO_ROOT)
    base = ["docker", "compose", "-f", os.path.join(root, COMPOSE_FILE)]
    stages = [{"phase": "middleware", "cmd": base + ["up", "-d"]}]
    profile_args = []
    for profile in resource_tiers.compose_profiles(answers.get("tier")):
        profile_args += ["--profile", profile]
    if profile_args:
        stages.append({"phase": "services", "cmd": base + profile_args + ["up", "-d"]})
    return stages


def compose_plan(answers_path, repo_root=None):
    pass
    return [stage["cmd"] for stage in compose_stages(answers_path, repo_root=repo_root)]


def _execute(cmd):
    pass
    override = os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip()
    if override and cmd[:2] == ["docker", "compose"]:
        cmd = [override] + cmd[2:]
    return subprocess.run(cmd).returncode


def four_services_guide(tier=None):
    pass
    source_lines = ["      cd %s && mvn spring-boot:run，或常驻=java -jar target/%s.jar 挂 systemd 单元"
                    % (name, name) for name, port, purpose in SERVICE_GUIDE]
    if "app" in resource_tiers.compose_profiles(tier):
        lines = ["四服务已随 compose 应用档拉起（admin/api/core/ai-service，档位投影自动起，脚本不代跑也永不停它们）：",
                 "  - 状态核对：docker compose ps；就绪判定：curl http://127.0.0.1:<admin宿主端口>/actuator/health"]
        lines.append("  - 开发者选项（源码调试跑法，非出厂交付路径；先停掉对应 compose 服务再起）：")
        lines.extend(source_lines)
        return lines
    lines = ["四服务启动指引（脚本不代跑：检测到端口被占用时绝不停他人服务，请确认后自行启动）：",
             "  - 出厂交付路径（推荐一行起服）：cd 仓库根 && docker compose --profile app up -d，"
             "就绪判定 curl http://127.0.0.1:<admin宿主端口>/actuator/health（宿主端口=装机 .env 的 APP_ADMIN_HOST_PORT，未改=8081）"]
    lines.append("  - 开发者选项（源码跑法，逐服务）：")
    lines.extend(source_lines)
    return lines


DEFAULT_ADMIN_BASE_URL = "http://localhost:8081"


def resolve_admin_base_url(explicit=""):
    pass
    if (explicit or "").strip():
        return explicit.strip()
    override = (os.environ.get("INSTALLKIT_SEED_BASE_URL") or "").strip()
    if override:
        return override
    try:
        dotenv_map = host_ports.read_dotenv(str(REPO_ROOT))
    except OSError:
        dotenv_map = {}
    port = (host_ports.overlay(dotenv_map).get("APP_ADMIN_HOST_PORT") or "").strip()
    if port:
        return "http://localhost:%s" % port
    return DEFAULT_ADMIN_BASE_URL


def seed_demo_command(base_url=None):
    pass
    url = base_url or resolve_admin_base_url()
    return "python3 installkit/s1_bootstrap.py seed-demo --base-url %s" % url


def admin_ready(base_url, timeout=5.0):
    pass
    try:
        resp = httpx.post(base_url.rstrip("/") + "/api/v1/auth/login",
                          json={"username": demo_seed.env_val(demo_seed.ENV_USER_KEY, default="admin"),
                                "password": demo_seed.env_val(demo_seed.ENV_PASSWORD_KEY)},
                          timeout=timeout)
        resp.raise_for_status()
        return bool(resp.json().get("data", {}).get("accessToken"))
    except Exception:
        return False


def cmd_compose(args):
    stages = compose_stages(args.answers_file, repo_root=args.repo_root or None)
    if not stages:
        print("本机角色=纯执行器机：跳过 compose 拉起——"
              "这台机只需装引擎运行时并注册为执行器（扩容入池用 --scenario=scale-out）")
        return 0
    root = args.repo_root or str(REPO_ROOT)
    compose_file = os.path.join(root, COMPOSE_FILE)
    if not os.path.exists(compose_file):
        print("compose 拉起失败：%s 不在——请在 testPike 仓库内运行本脚本"
              "（或 --repo-root 指到仓库根）" % compose_file, file=sys.stderr)
        return 1
    print("拉起中间件（postgres/redis/rabbitmq/seaweedfs；已在位=幂等直接绿）…")
    rc = _execute(stages[0]["cmd"])
    if rc != 0:
        print("compose 拉起失败（退出码 %s）——上方 docker 输出即原因；"
              "修复后重跑同一命令即从断点续跑" % rc, file=sys.stderr)
        return rc
    print("中间件就绪。")
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        print("MQ vhost 对账：compose 替身模式跳过（测试形态无真消息队列）")
    else:
        ok, notes = mq_vhost.ensure_vhost()
        for line in notes:
            print(line, file=None if ok else sys.stderr)
        if not ok:
            return 1
    for stage in stages[1:]:
        rc = _execute(stage["cmd"])
        if rc != 0:
            print("档位服务拉起失败（退出码 %s）——上方 docker 输出即原因；"
                  "修复后重跑同一命令即从断点续跑" % rc, file=sys.stderr)
            return rc
    answers = load_answers(args.answers_file)
    for line in four_services_guide(tier=answers.get("tier")):
        print(line)
    return 0


def cmd_seed_demo(args):
    pass
    url = resolve_admin_base_url(args.base_url)
    if not admin_ready(url):
        print("admin 服务未就绪（%s：不可达、非本产品端点或账号不符）——本次跳过自动播种，"
              "装机流程照常继续。" % url)
        print("四服务就绪后重跑下面这条命令即自动播种（同名复用幂等，重复执行不产生重复数据）：")
        print("  " + seed_demo_command(url))
        return 0
    try:
        pid, cid = demo_seed.ensure_sample(url)
        journey = demo_seed.ensure_journey(url)
        centers = demo_seed.ensure_centers(url)
    except (Exception, SystemExit) as exc:
        print("demo data 播种未完成：%s（已建条目同名复用，演示数据非装机前置，不阻断装机流程）"
              % exc, file=sys.stderr)
        print("修复上方原因后重跑同一命令即续播（幂等：已建条目直接复用）："
              + seed_demo_command(url), file=sys.stderr)
        return 0
    print("demo data 自动播种完成：样例项目（id=%s）+样例用例（id=%s）、五分钟旅程新建 %d 件、"
          "中台缩微剧本（订单/库存双中心+演示性能场景）新建 %d 件。"
          % (pid, cid, journey.get("created", 0), centers.get("created", 0)))
    print("体验动线：打开前端项目列表 → 进样例项目一键「AI 生成→执行→报告」；列表同时可见"
          "五分钟旅程件与「订单中心」「库存中心」（各带一条可跑接口用例，订单中心另挂一条"
          "演示性能场景，压测目标=内置夹具靶站）。")
    return 0


def cmd_storage(args):
    pass
    # Compose-substitute mode (unit/contract tests run install.sh with a fake
    # compose binary): there is no real storage gateway to probe, so this gate
    # short-circuits honestly, exactly like the MQ vhost reconcile above.
    # Without the skip the gate hangs 60s on the S3 probe and then hard-fails
    # the whole s1 scenario in test shape (and on fresh CI runners).
    if os.environ.get("INSTALLKIT_COMPOSE_BIN", "").strip():
        print("对象存储对账：compose 替身模式跳过（测试形态无真存储网关）")
        return 0
    answers = load_answers(args.answers_file)
    if answers.get("role", "platform") != "platform":
        print("本机角色非平台：对象存储面由平台节点承载，跳过。")
        return 0
    root = args.repo_root or str(REPO_ROOT)
    try:
        # compose precedence: exported band keys (matrix cells, systemd env)
        # must resolve identically here and in docker-compose.yml interpolation
        env = host_ports.overlay(host_ports.read_dotenv(root))
    except OSError:
        print("对象存储检查失败：找不到仓库 .env（请在本仓库内运行）", file=sys.stderr)
        return 1
    missing = [k for k in ("S3_ENDPOINT", "S3_BUCKET", "DB_USERNAME", "DB_PASSWORD",
                           "SWF_META_DB_NAME") if not env.get(k)]
    if missing:
        print("对象存储检查失败：.env 缺键 %s（cp .env.example .env 后补齐）" % missing,
              file=sys.stderr)
        return 1
    pg = os.environ.get("TP_CONTAINER_PG") or instance_names.name("postgres")
    db, user = env["SWF_META_DB_NAME"], env["DB_USERNAME"]
    # 1) metadata database (same PG instance as the application, separate database)
    q = _run_cap(["docker", "exec", pg, "psql", "-U", user, "-d", "postgres", "-tA", "-c",
                  "SELECT 1 FROM pg_database WHERE datname='%s'" % db])
    if q.returncode != 0:
        print("对象存储检查失败：PostgreSQL 容器 %s 不可达（先跑 compose 位点）" % pg,
              file=sys.stderr)
        return 1
    if q.stdout.strip() != "1":
        c = _run_cap(["docker", "exec", pg, "psql", "-U", user, "-d", "postgres", "-c",
                      "CREATE DATABASE \"%s\" OWNER \"%s\" TEMPLATE template0 "
                      "LC_COLLATE 'C' LC_CTYPE 'C' ENCODING 'UTF8'" % (db, user)])
        if c.returncode != 0:
            print("对象存储检查失败：元数据库 %s 创建失败——%s" % (db, c.stderr.strip()[-200:]),
                  file=sys.stderr)
            # Route the raw vendor output through the failure-guide single source
            # so a duplicate-database race teaches keep-or-rename instead of only
            # dumping stderr (families unknown to the guide stay silent here).
            import failure_guide
            guide = failure_guide.teach(
                failure_guide.classify_failure(c.stderr) or "", db=db)
            if guide:
                print(guide, file=sys.stderr)
            return 1
        print("元数据库 %s 已补建（程序同实例独立库）" % db)
    else:
        print("元数据库 %s 在位" % db)
    # 2) gateway container (compose single source; idempotent)
    rc = _execute(["docker", "compose", "-f", os.path.join(root, COMPOSE_FILE),
                   "up", "-d", "seaweedfs", "seaweedfs-ui-auth"])
    if rc != 0:
        print("对象存储检查失败：seaweedfs 网关/管理台鉴权代理拉起失败（compose 输出即原因）",
              file=sys.stderr)
        return 1
    # 3) S3 endpoint answers (any HTTP status incl. 403 proves the signature path is live)
    import time
    import urllib.error
    import urllib.request
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            urllib.request.urlopen(env["S3_ENDPOINT"] + "/", timeout=3)
            break
        except urllib.error.HTTPError:
            break
        except OSError:
            time.sleep(2)
    else:
        print("对象存储检查失败：%s 60 秒内无应答——查 docker logs %s"
              % (env["S3_ENDPOINT"], instance_names.name("seaweedfs")), file=sys.stderr)
        return 1
    # 3b) management-console auth gate: the Basic-Auth proxy must answer 401 anonymously
    ui_port = env.get("SWF_UI_FILER_PROXY_HOST_PORT", "9000")
    ui_code = ""
    try:
        import urllib.request as _u
        try:
            _u.urlopen("http://localhost:%s/" % ui_port, timeout=5)
            ui_code = "200"
        except urllib.error.HTTPError as he:
            ui_code = str(he.code)
    except OSError:
        pass
    if ui_code != "401":
        print("对象存储检查失败：管理台鉴权代理(%s)未返回 401，实际=%s —— 检查 "
              "%s 容器与 SWF_UI_USER/SWF_UI_PASSWORD" % (ui_port, ui_code or "无应答",
              instance_names.name("seaweedfs-ui-auth")),
              file=sys.stderr)
        return 1
    print("管理台鉴权在位：匿名 401（登录口 http://localhost:%s / :%s，凭据 .env SWF_UI_*）"
          % (ui_port, env.get("SWF_UI_MASTER_PROXY_HOST_PORT", "9001")))
    # 4) legacy provider value repair (guarded; authoritative semantics = V533 migration)
    _run_cap(["docker", "exec", pg, "psql", "-U", user, "-d", env.get("TP_MAIN_DB", "testpike"),
              "-c", "UPDATE tp_sys_config SET config_value='s3' "
                    "WHERE config_key='storage.provider' AND config_value='minio'"])
    print("对象存储就绪：S3 网关应答、桶「%s」由应用首用自动创建；storage.provider=s3 "
          "可在系统设置页热更改回 local/aliyun-oss。" % env["S3_BUCKET"])
    return 0


def _run_cap(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=120)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="s1_bootstrap.py",
                                     description="S1 首装拉起位点实装件（compose 计划/四服务指引/demo data 自动播种）")
    sub = parser.add_subparsers(dest="command", required=True)
    p_compose = sub.add_parser("compose", help="按角色拉起中间件+透出四服务指引")
    p_compose.add_argument("--answers-file", required=True)
    p_compose.add_argument("--repo-root", default=str(REPO_ROOT))
    p_compose.set_defaults(func=cmd_compose)
    p_seed = sub.add_parser("seed-demo", help="demo data 自动播种（幂等；admin 不可达时诚实降级给重跑命令）")
    p_seed.add_argument("--base-url", default="")
    p_seed.set_defaults(func=cmd_seed_demo)
    p_store = sub.add_parser("storage", help="对象存储面一键检查/自修（SeaweedFS+PG 元数据）")
    p_store.add_argument("--answers-file", required=True)
    p_store.add_argument("--repo-root", default=str(REPO_ROOT))
    p_store.set_defaults(func=cmd_storage)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
