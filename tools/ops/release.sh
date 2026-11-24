#!/usr/bin/env bash
# =============================================================================
# testPike CI release builder ( implementation of the artifact contract
# written in docs/05 §5.2). One run produces one version directory:
#   releases/<version>/{core,admin,api,ai-service,mock-server}.jar
#   releases/<version>/frontend-dist.tar.gz
#   releases/<version>/service-agent.tar.gz
#   releases/<version>/release.json   (= register payload, same source)
# Every artifact carries sha256 + size so `release-manifests register` can pass
# the three ReleaseArtifactRules gates (shape / pinned coverage / host
# whitelist) and the upgrade pre-check can compare declared vs observed bytes.
#
# Notes:
# - This script BUILDS and LAYS OUT artifacts; serving the directory (mirror /
#   object storage) is operator-side, and the register call rides the existing
#   admin API. CI glue (Jenkins/GitLab) only needs: run, then serve out dir.
# - Red line 4: no credentials here at all; whitelist hosts are declared in
#   release.json as URLs under whatever base the operator publishes.
# - AGPL/Gatling engine bodies are never packaged (AI-batch red line carries
#   over): only platform services + installkit agent ship in a release.
# =============================================================================
set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION=""
OUT_DIR=""
BASE_URL=""          # public download root; release.json URLs = BASE_URL/<file>
TIMEOUT_MIN="${RELEASE_BUILD_TIMEOUT_MIN:-30}"
DRY_RUN=0

usage() {
    cat <<'USAGE'
用法: release.sh --version=<v> [选项]
  --version=<v>       发布版本号（必填，如 1.4.0；与 register 入参逐字一致）
  --out-dir=<dir>     产物目录（默认 releases/<v> 于仓库根下）
  --base-url=<url>    对外下载根（release.json 的工件 URL 前缀；缺省用相对路径，
                      镜像改道后由操作者登记时替换域）
  --dry-run           只打印将执行的构建命令，不构建（CI 排障用）
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --version=*) VERSION="${arg#*=}" ;;
        --out-dir=*) OUT_DIR="${arg#*=}" ;;
        --base-url=*) BASE_URL="${arg#*=}" ;;
        --dry-run)   DRY_RUN=1 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "未识别参数：$arg" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "缺少 --version=<v>——发布必须先定版本号（登记/预检/台账三处都按它逐字对齐）" >&2
    usage >&2
    exit 2
fi
[ -n "$OUT_DIR" ] || OUT_DIR="$ROOT/releases/$VERSION"

step() {
    echo "==> $*"
    if [ "$DRY_RUN" = 1 ]; then
        echo "    (--dry-run 跳过执行)"
        return 0
    fi
    "$@"
}

build_one() { # module jar: mvn -pl <m> package -DskipTests (regression is CI's own gate)
    local module="$1"
    ( cd "$ROOT" && mvn -q -pl "$module" -am package -DskipTests )
    local jar
    jar="$(ls "$ROOT/$module"/target/*.jar 2>/dev/null | grep -vE 'sources|javadoc|original' | head -1)"
    [ -n "$jar" ] || { echo "模块 $module 未产出 jar（先确认 mvn package 成功）" >&2; return 1; }
    if [ "$DRY_RUN" = 0 ]; then cp "$jar" "$OUT_DIR/$module.jar"; fi
    echo "    $module -> $OUT_DIR/$module.jar"
}

mkdir -p "$OUT_DIR"

for m in core admin api ai-service mock-server; do
    step build_one "$m"
done

# frontend dist (timeout guard per red line 12: npm builds can hang)
step timeout "$((TIMEOUT_MIN * 60))" bash -c "cd '$ROOT/frontend' && npm run build"
if [ "$DRY_RUN" = 0 ]; then
    tar -czf "$OUT_DIR/frontend-dist.tar.gz" -C "$ROOT/frontend" dist
fi
echo "    frontend -> $OUT_DIR/frontend-dist.tar.gz"

# host service-agent package (installkit agent files; consumers fetch via the
# same artifact URLs — unit registration on the host points ExecStart here)
step tar -czf "$OUT_DIR/service-agent.tar.gz" -C "$ROOT/installkit" \
    service_agent.py enroll_client.py tp-service-agent.service
echo "    agent -> $OUT_DIR/service-agent.tar.gz"

if [ "$DRY_RUN" = 1 ]; then
    echo "(--dry-run 完成：以上为将执行清单，release.json 未生成)"
    exit 0
fi

# release.json = register payload artifacts[] same source. Item keys MUST match
# the ReleaseArtifactRules shape ({component_family,url,sha256,size}) so the
# file can be handed to register with zero transformation (single source).
python3 - "$OUT_DIR" "$VERSION" "$BASE_URL" <<'PY'
import hashlib, json, os, sys
out_dir, version, base = sys.argv[1], sys.argv[2], sys.argv[3]
# file -> ledger family (MANAGED_SERVICES rows in installkit/service_agent.py)
FAMILY_BY_FILE = {
    "core.jar": "core",
    "admin.jar": "admin",
    "api.jar": "api",
    "ai-service.jar": "ai-service",
    "mock-server.jar": "mock-server",
    "frontend-dist.tar.gz": "frontend",
    "service-agent.tar.gz": "service-agent",
}
rows = []
for name in sorted(os.listdir(out_dir)):
    path = os.path.join(out_dir, name)
    if not os.path.isfile(path) or name == "release.json":
        continue
    family = FAMILY_BY_FILE.get(name)
    if family is None:
        print(f"    警告：{name} 不在族名映射内，跳过（发版脚本与 agent 目录漂移需对齐）")
        continue
    data = open(path, "rb").read()
    url = f"{base.rstrip('/')}/{name}" if base else name
    rows.append({"component_family": family,
                 "url": url,
                 "sha256": hashlib.sha256(data).hexdigest(),
                 "size": len(data)})
payload = {"version": version,
           "buildTime": __import__("datetime").datetime.now(
               __import__("datetime").timezone.utc).isoformat(),
           "artifacts": rows}
with open(os.path.join(out_dir, "release.json"), "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
print(f"    release.json 写出（{len(rows)} 件，逐件 sha256+size，键形=register 闸入参）")
PY

echo "发布产物目录就绪：$OUT_DIR"
echo "下一步（操作者）：把该目录放到白名单域下serve，再经 admin release-manifests register 登记（artifacts 直接取 release.json 数组）。"
