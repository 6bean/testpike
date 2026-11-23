
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Callable

from .client import CiClient, CiError

EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_ERROR = 2

DEFAULT_BASE_URL = "http://localhost:8082"
DEFAULT_TOKEN_ENV = "TESTPIKE_CI_TOKEN"
DEFAULT_INTERVAL = 5.0

_TERMINAL_EXIT = {6: EXIT_PASS, 7: EXIT_FAIL, 8: EXIT_FAIL}
_TERMINAL_STATUSES = frozenset(_TERMINAL_EXIT) | {3}


def exit_code_for_batch_status(status) -> int:
    pass
    if not isinstance(status, int):
        return EXIT_ERROR
    return _TERMINAL_EXIT.get(status, EXIT_ERROR)


def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-url", default=os.environ.get("TESTPIKE_BASE_URL",
                                                             DEFAULT_BASE_URL),
                        help="api 服务地址（默认 %(default)s）")
    parser.add_argument("--token-env", default=DEFAULT_TOKEN_ENV,
                        help="存放 API Token 的环境变量名（默认 %(default)s；"
                             "禁明文传 token）")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="testpike",
                                     description="testPike CI/CD 触发命令行")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="触发批次执行")
    _add_common(p_run)
    p_run.add_argument("--batch", type=int, help="既有草稿批次 id（与 --profile 二选一）")
    p_run.add_argument("--project", type=int, help="项目 id（--profile 链路必带）")
    p_run.add_argument("--profile", help="批次模板名（与 --batch 二选一）")
    p_run.add_argument("--env-id", type=int, help="目标环境 id")
    p_run.add_argument("--env-url", help="运行期覆盖环境 URL（不落库）")
    p_run.add_argument("--env-account-ref", help="运行期覆盖账号引用（不落库）")
    p_run.add_argument("--idempotency-key", help="显式幂等键（缺省 CLI 自动生成）")
    p_run.add_argument("--watch", action="store_true", help="触发后轮询直到批次终态")
    p_run.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                       help="--watch 轮询间隔秒（默认 %(default)s）")

    p_status = sub.add_parser("status", help="查询批次状态")
    _add_common(p_status)
    p_status.add_argument("batch_id", type=int)
    p_status.add_argument("--watch", action="store_true", help="轮询直到批次终态")
    p_status.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                          help="--watch 轮询间隔秒（默认 %(default)s）")

    p_report = sub.add_parser("report", help="拉取批次结果报告 JSON（口径=O10）")
    _add_common(p_report)
    p_report.add_argument("batch_id", type=int)
    p_report.add_argument("--out", help="落盘文件路径（缺省打印 stdout）")
    return parser


def _build_payload(args: argparse.Namespace) -> dict:
    payload: dict = {}
    if args.batch is not None:
        payload["batch"] = args.batch
    else:
        payload["profile"] = args.profile
        payload["projectId"] = args.project
    if args.env_id is not None:
        payload["envId"] = args.env_id
    overrides: dict = {}
    if args.env_url:
        overrides["url"] = args.env_url
    if args.env_account_ref:
        overrides["accountRef"] = args.env_account_ref
    if overrides:
        payload["envOverrides"] = overrides
    return payload


def _print_status_line(batch_id: int, data: dict) -> None:
    pass
    label = data.get("statusLabel") or ""
    print(f"批次 {batch_id} 状态：{data.get('status')} {label}".rstrip())


def _poll_until_terminal(client: CiClient, batch_id: int, interval: float,
                         sleeper: Callable[[float], None]) -> dict:
    while True:
        data = client.get_status(batch_id)
        status = data.get("status")
        _print_status_line(batch_id, data)
        if status in _TERMINAL_STATUSES:
            return data
        sleeper(interval)


def main(argv: list | None = None, *,
         environ: dict | None = None,
         client_factory: Callable[[str, str], CiClient] | None = None,
         sleeper: Callable[[float], None] | None = None) -> int:
    env = os.environ if environ is None else environ
    sleep_fn = time.sleep if sleeper is None else sleeper
    make_client = client_factory or (
        lambda base, token: CiClient(base, token))

    args = build_parser().parse_args(argv)
    token = env.get(args.token_env, "")
    if not token:
        print(f"未找到 API Token：请设置环境变量 {args.token_env}"
              f"（G6 控制台创建 scope=ci 的 Token），或用 --token-env 指定变量名。",
              file=sys.stderr)
        return EXIT_ERROR
    if args.command == "run":
        if args.batch is not None and (args.profile or args.project):
            print("--batch 与 --project/--profile 二选一，不能同时传。", file=sys.stderr)
            return EXIT_ERROR
        if args.batch is None and not (args.profile and args.project):
            print("必须指定 --batch <批次id>，或 --profile <模板名> 且 --project <项目id>。",
                  file=sys.stderr)
            return EXIT_ERROR
    client = make_client(args.base_url, token)
    try:
        return _dispatch(args, client, sleep_fn)
    except CiError as e:
        print(str(e), file=sys.stderr)
        return EXIT_ERROR
    except ValueError as e:
        print(f"结果解析失败：{e}", file=sys.stderr)
        return EXIT_ERROR


def _dispatch(args: argparse.Namespace, client: CiClient, sleep_fn) -> int:
    if args.command == "run":
        data = client.run_batch(_build_payload(args),
                                idempotency_key=args.idempotency_key)
        batch_id = data.get("id") or data.get("batchId")
        label = data.get("statusLabel") or ""
        print(f"已触发批次 {batch_id}（状态 {data.get('status')} {label}）")
        if not args.watch:
            return EXIT_PASS
        final = _poll_until_terminal(client, int(batch_id), args.interval, sleep_fn)
        return exit_code_for_batch_status(final.get("status"))
    if args.command == "status":
        if args.watch:
            data = _poll_until_terminal(client, args.batch_id, args.interval, sleep_fn)
        else:
            data = client.get_status(args.batch_id)
            _print_status_line(args.batch_id, data)
        return exit_code_for_batch_status(data.get("status"))
    report = client.get_report(args.batch_id)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
        print(f"报告已写入 {args.out}")
    else:
        print(text)
    return exit_code_for_batch_status(report.get("status"))


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
