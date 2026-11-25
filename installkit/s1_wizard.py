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
             "Role of this machine? 1=platform server (runs platform + middleware + browser executor, default)  2=executor-only box (runs the engines to do the work)",
             "choice"),
    Question("engines",
             "Which app automation engines to install? Comma-separated, default=%s (recipes for the remaining engines ship in later versions)" % DEFAULT_ENGINES,
             "csv"),
    Question("mirror_base_url",
             "Internal mirror registry base URL? (company Nexus root; leave empty for the official registry; the default is read from the platform config key)",
             "text"),
    Question("install_dir",
             "Directory for engines and runtimes? (default ~/.testpike-runtime, no root required)",
             "text"),
    Question("autostart",
             "Start on boot? y=register the system service / NVM startup entry  n=no autostart (default n)",
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
    print("%s [default: %s]" % (question.prompt, default_display))
    line = _read_line(stream)
    if line is None or line == "":
        return default_display if default_value is None else default_value
    return line


def _choice(line, mapping, default, label):
    value = mapping.get(line.strip().lower())
    if value is None:
        print("  ↳ '%s' is not one of the offered options; continuing with the default '%s' (notice only, the installation is not interrupted)" % (line, default))
        return default
    print("  ↳ %s=%s" % (label, value))
    return value


def run(input_text=None, answers_path=None, mirror_default="", home=os.path.expanduser("~")):
    pass
    stream = io.StringIO(input_text) if input_text is not None else sys.stdin
    answers = {}

    line = _ask(stream, QUESTIONS[0], "1")
    answers["role"] = _choice(line, ROLE_MAP, "platform", "role")

    line = _ask(stream, QUESTIONS[1], DEFAULT_ENGINES)
    picked = [e.strip() for e in line.split(",") if e.strip()]
    if not picked:
        picked = DEFAULT_ENGINES.split(",")
    answers["engines"] = picked
    print("  ↳ engines=%s" % ", ".join(picked))

    line = _ask(stream, QUESTIONS[2], mirror_default or "(official registry)",
                default_value=mirror_default)
    answers["mirror_base_url"] = line

    line = _ask(stream, QUESTIONS[3], os.path.join(home, ".testpike-runtime"))
    answers["install_dir"] = line

    line = _ask(stream, QUESTIONS[4], "n")
    answers["autostart"] = line.strip().lower() in ("y", "yes")
    print("  ↳ autostart=%s" % ("yes" if answers["autostart"] else "no"))

    if answers_path:
        with open(answers_path, "w", encoding="utf-8") as fh:
            json.dump(answers, fh, ensure_ascii=False, indent=2)
    return answers


def main(argv=None):
    parser = argparse.ArgumentParser(prog="s1_wizard.py",
                                     description="Five S1 first-install questions (press Enter to accept every default; non-interactive = all defaults)")
    parser.add_argument("--answers-file", required=True, help="Path of the answers JSON file (consumed by later checkpoints)")
    parser.add_argument("--mirror-default", default="",
                        help="Default for question 3: mirror base URL passed through from CLI --mirror-base-url or the platform config key (empty = official registry)")
    args = parser.parse_args(argv)
    answers = run(answers_path=args.answers_file, mirror_default=args.mirror_default)
    print("Questions complete: role=%s engines=%s mirror=%s dir=%s" % (
        answers["role"], ", ".join(answers["engines"]),
        answers["mirror_base_url"] or "official registry", answers["install_dir"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
