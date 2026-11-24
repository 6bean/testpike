# -*- coding: utf-8 -*-
pass
import argparse
import io
import json
import os
import sys
from collections import namedtuple

Question = namedtuple("Question", ["key", "prompt", "kind"])

DEFAULT_ENGINES = "mobilewright,minium"

QUESTIONS = (
    Question("role",
             "这台机器的角色？1=平台服务器（跑平台+中间件+浏览器执行器，默认）　2=纯执行器机（只跑引擎干活）",
             "choice"),
    Question("engines",
             "要装哪些 App 自动化引擎？逗号分隔，默认=%s（其余引擎配方后续版本补全）" % DEFAULT_ENGINES,
             "csv"),
    Question("mirror_base_url",
             "内网镜像源基址？（公司 Nexus 根地址，留空=官方源；默认读平台配置键）",
             "text"),
    Question("install_dir",
             "引擎与运行时安装到哪个目录？（默认 ~/.testpike-runtime，无需 root）",
             "text"),
    Question("autostart",
             "开机自启？y=注册系统服务/NVM 启动项　n=不自启（默认 n）",
             "yesno"),
)

ROLE_MAP = {"1": "platform", "2": "executor", "platform": "platform", "executor": "executor"}


def _read_line(stream):
    pass
    try:
        line = stream.readline()
    except (EOFError, ValueError):
        return None
    if line == "":
        return None
    return line.strip()


def _ask(stream, question, default_display, default_value=None):
    pass
    print("%s［默认：%s］" % (question.prompt, default_display))
    line = _read_line(stream)
    if line is None or line == "":
        return default_display if default_value is None else default_value
    return line


def _choice(line, mapping, default, label):
    value = mapping.get(line.strip().lower())
    if value is None:
        print("　↳「%s」不是可选项，已按默认「%s」继续（仅提示，装机不中断）" % (line, default))
        return default
    print("　↳ %s=%s" % (label, value))
    return value


def run(input_text=None, answers_path=None, mirror_default="", home=os.path.expanduser("~")):
    pass
    stream = io.StringIO(input_text) if input_text is not None else sys.stdin
    answers = {}

    line = _ask(stream, QUESTIONS[0], "1")
    answers["role"] = _choice(line, ROLE_MAP, "platform", "角色")

    line = _ask(stream, QUESTIONS[1], DEFAULT_ENGINES)
    picked = [e.strip() for e in line.split(",") if e.strip()]
    if not picked:
        picked = DEFAULT_ENGINES.split(",")
    answers["engines"] = picked
    print("　↳ 引擎=%s" % "、".join(picked))

    line = _ask(stream, QUESTIONS[2], mirror_default or "（官方源）",
                default_value=mirror_default)
    answers["mirror_base_url"] = line

    line = _ask(stream, QUESTIONS[3], os.path.join(home, ".testpike-runtime"))
    answers["install_dir"] = line

    line = _ask(stream, QUESTIONS[4], "n")
    answers["autostart"] = line.strip().lower() in ("y", "yes")
    print("　↳ 开机自启=%s" % ("是" if answers["autostart"] else "否"))

    if answers_path:
        with open(answers_path, "w", encoding="utf-8") as fh:
            json.dump(answers, fh, ensure_ascii=False, indent=2)
    return answers


def main(argv=None):
    parser = argparse.ArgumentParser(prog="s1_wizard.py",
                                     description="S1 首装五题问答（全默认值可一路回车，非交互=全默认）")
    parser.add_argument("--answers-file", required=True, help="答案 JSON 落盘路径（后续位点消费）")
    parser.add_argument("--mirror-default", default="",
                        help="题3 默认镜像源（CLI --mirror-base-url 或平台键透传值；留空=官方源）")
    args = parser.parse_args(argv)
    answers = run(answers_path=args.answers_file, mirror_default=args.mirror_default)
    print("问答完成：角色=%s 引擎=%s 镜像源=%s 目录=%s" % (
        answers["role"], "、".join(answers["engines"]),
        answers["mirror_base_url"] or "官方源", answers["install_dir"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
