#!/usr/bin/env bash
# The one and only configuration source for this chain: <repo>/.env (a regular file).
#
# History that justifies the strictness: slot values used to live outside the tree
# (~/.hermes/cache/slot_env) while every worktree's .env was a symlink to the main installer
# file, whose DB_URL is the MAIN database. Anything that sourced .env without layering the
# outside file silently ran on another chain's data — measured as the second chain's runner
# holding the main chain's queue (pid 821181). One real file per chain removes that class.
#
# Validation is part of loading: the file must declare TP_CHAIN_SLOT, and that slot must match
# its own database name and port band. Mismatch = stop, not a warning to scroll past.
#
# Resolution: the tree you stand in wins over a leaked LOAD_ENV_ROOT pin; same-slot pins
# (window payloads, partial trees without their own .env) keep working untouched.
# Cross-slot leak guard: a pin left behind in the shared gateway/cron shell by another
# chain's recipe used to silently re-point this chain's whole window at a different slot's
# database (measured 2026-11-18: a slot-6 worktree resolved slot-0 and died on
# "column does not exist", the schema itself being wrongly suspected). A pin whose declared
# slot contradicts the caller tree's own slot is treated as a leak: ignore it, load the
# caller's own tree, say so loudly.
_root=""
_self_slot=""
if [ -f "${PWD}/.env" ] && [ -f "${PWD}/scripts/chain_slot.py" ]; then
  _root="$PWD"
  _self_slot="$(grep -E '^TP_CHAIN_SLOT=' "${PWD}/.env" 2>/dev/null | tail -1 | cut -d= -f2)"
fi
if [ -n "${LOAD_ENV_ROOT:-}" ] && [ "${LOAD_ENV_ROOT}" != "$_root" ]; then
  _pin_slot="$(grep -E '^TP_CHAIN_SLOT=' "${LOAD_ENV_ROOT}/.env" 2>/dev/null | tail -1 | cut -d= -f2)"
  if [ -n "$_self_slot" ] && [ -n "$_pin_slot" ] && [ "$_self_slot" != "$_pin_slot" ]; then
    echo "[load_chain_env] WARN LOAD_ENV_ROOT=${LOAD_ENV_ROOT} pins slot ${_pin_slot} but this tree declares slot ${_self_slot}——按跨槽泄漏指针处理：已摘除，装载本树自身配置。" >&2
  else
    _root="${LOAD_ENV_ROOT}"
  fi
  unset _pin_slot
fi
if [ -z "$_root" ]; then
  _root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." 2>/dev/null && pwd || true)"
fi
unset _self_slot
_tp=""
for _cand in "${TP_PYTHON:-}" /usr/bin/python3 python3 python; do
  [ -n "$_cand" ] || continue
  command -v "$_cand" >/dev/null 2>&1 || continue
  _tp="$_cand"; break
done
if [ -z "$_root" ] || [ ! -f "$_root/.env" ]; then
  echo "[load_chain_env] ✗ 找不到本链配置源（$_root/.env 不存在）" >&2
  return 1 2>/dev/null || exit 1
fi
if [ -L "$_root/.env" ]; then
  echo "[load_chain_env] ✗ 本链 .env 是指向别处的软链（$(readlink "$_root/.env")）——"        "配置源必须是本仓实体文件，否则各链会共用同一套资源。" >&2
  echo "   修好它：python3 scripts/chain_slot.py configure --tree "$_root"" >&2
  export TP_CHAIN_ENV_LOAD_FAILED=1
  return 1 2>/dev/null || exit 1
fi
set -a
. "$_root/.env"
set +a
# Cross-shell residue scrub: a chain-scoped handle THIS chain's .env does not
# declare must not survive the load. Gateway/cron shells persist across ticks,
# and a slot-N export left by another chain's tick silently re-points read
# paths here (sql_query honours TP_E2E_DB_NAME; the vite proxy reads
# TP_ADMIN_PORT/TP_API_PORT; tooling base URLs read the TP_*_BASE family).
# Measured 2026-11-05: a main-chain vision window read the empty testpike_slot1
# database while its writes went to slot0, and the window died with a false
# "no streaming assistant block" verdict. Re-measured 2026-11-07: a leaked
# TP_PG_DATABASE=testpike_slot3 made the main-chain baseline gate read the
# second chain's database (TP_* handle family extended with TP_PG_DATABASE and
# the window heartbeat/ledger handles); same round, the static-suite knobs
# (SUITE_DB/DUP_BASELINE/DIFF_BASE) left by another chain's tick in the shared
# persistent shell re-pointed the whole suite at slot3. Callers pass suite
# knobs per command (VAR=x cmd prefix), which this scrub never touches.
# The base names mirror
# scripts/slot_topology.BASE_URL_FAMILY;
# scripts/selftest_unit_execstart_chain_env.py guards the two against drift.
for _v in TP_E2E_DB_NAME TP_E2E_BASE_URL TP_E2E_API_BASE \
          TP_ADMIN_PORT TP_API_PORT \
          TP_PG_DATABASE TP_WINDOW_LIVE_FILE TP_WINDOW_LEDGER \
          SUITE_DB DUP_BASELINE DIFF_BASE \
          TP_ADMIN_BASE TP_API_BASE TP_CORE_BASE TP_AIS_BASE TP_MOCK_MGMT_BASE \
          CORE_BASE_URL AISERVICE_BASE_URL STORAGE_LOCAL_PUBLIC_BASE_URL \
          S3_ENDPOINT; do
  grep -qE "^${_v}=" "$_root/.env" 2>/dev/null || unset "$_v"
done
unset _v
if [ -z "${TP_CHAIN_SLOT:-}" ]; then
  echo "[load_chain_env] ✗ $_root/.env 没写 TP_CHAIN_SLOT——本链身份不明，按缺省会连主库，"        "拒绝继续。修好它：python3 scripts/chain_slot.py configure --tree "$_root"" >&2
  export TP_CHAIN_ENV_LOAD_FAILED=1
  return 1 2>/dev/null || exit 1
fi
_f="$(mktemp 2>/dev/null || echo /tmp/load_chain_env.$$.err)"
_ver="$_root/scripts/chain_slot.py"
if [ -n "$_tp" ] && ! "$_tp" "$_ver" verify-env --help >/dev/null 2>&1; then
  _own="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." 2>/dev/null && pwd || true)"
  if [ -n "$_own" ] && [ -f "$_own/scripts/chain_slot.py" ] \
     && "$_tp" "$_own/scripts/chain_slot.py" verify-env --help >/dev/null 2>&1; then
    # the configured tree is fine; only its validator is older than this rule
    _ver="$_own/scripts/chain_slot.py"
  else
    echo "[load_chain_env] WARN 没有支持 verify-env 的校验器（本分支代码落后），" \
         "本次只装载未校验；合回主线后自动恢复校验" >&2
    _ver=""
  fi
fi
if [ -n "$_ver" ] && ! "$_tp" "$_ver" verify-env --tree "$_root" >"$_f" 2>&1; then
  echo "[load_chain_env] ✗ 本链 .env 自身不一致（库/端口带/槽号不同槽）：" >&2
  sed -n '1,6p' "$_f" >&2
  export TP_CHAIN_ENV_LOAD_FAILED=1
fi
rm -f "$_f"
unset _root _tp _cand _f

# : capacity roll for continuous logs through the one authorized load
# channel (append-only + archive chain; hot keys TICKLOG_MAX_MB default 20 /
# TICKLOG_KEEP default 3; best-effort — a failed roll never blocks loading).
_lr_dir="$(dirname "${BASH_SOURCE[0]}")"
if [ -f "$_lr_dir/log_roll.sh" ]; then
    bash "$_lr_dir/log_roll.sh" >/dev/null 2>&1 || true
fi
unset _lr_dir
