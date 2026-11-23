#!/usr/bin/env python3


from __future__ import annotations

import json
import os
import re
import sys

REPO = os.environ.get("TESTPIKE_REPO", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACKAGE_JSON = os.path.join(REPO, "frontend", "package.json")
LEDGER_DOC = os.path.join(REPO, "docs", "19-开源合规与随包分发台账.md")
SECTION_MARKER = "前端随包依赖"
LICENSED_KEY = "License"
# Graph/chart families whose adoption requires per-scenario approval.
CONTROLLED_PACKAGES = ("echarts", "mermaid")
CONTROLLED_TAG = "受控引入"


def load_declared_dependencies() -> dict[str, str]:
    with open(PACKAGE_JSON, encoding="utf-8") as handle:
        manifest = json.load(handle)
    return dict(manifest.get("dependencies", {}))


def extract_section_lines(text: str) -> list[str]:
    """Return the lines of the frontend ledger section body (heading to next
    level-2 heading or EOF)."""
    lines = text.splitlines()
    start = None
    for idx, line in enumerate(lines):
        if line.startswith("#") and SECTION_MARKER in line:
            start = idx
            break
    if start is None:
        return []
    body: list[str] = []
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        body.append(line)
    return body


def parse_ledger_rows(body: list[str]) -> list[tuple[str, list[str]]]:
    """Parse pipe-table rows into (name, cells) tuples, skipping header and
    separator lines."""
    rows: list[tuple[str, list[str]]] = []
    for line in body:
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [c.strip() for c in stripped.strip("|").split("|")]
        if not cells:
            continue
        first = cells[0].strip("` ")
        if first in ("组件", "") or set(first) <= {"-", ":", " "}:
            continue
        rows.append((first, cells))
    return rows


def license_column_index(header_line: str) -> int:
    cells = [c.strip() for c in header_line.strip().strip("|").split("|")]
    for idx, cell in enumerate(cells):
        if LICENSED_KEY.lower() in cell.lower():
            return idx
    return -1


def main() -> int:
    problems: list[str] = []

    declared = load_declared_dependencies()
    declared_keys = set(declared)

    if not os.path.exists(LEDGER_DOC):
        print(f"RED: ledger document not found: {LEDGER_DOC}")
        return 1
    with open(LEDGER_DOC, encoding="utf-8") as handle:
        doc = handle.read()

    body = extract_section_lines(doc)
    if not body:
        print(
            "RED: frontend ledger section not found (heading containing "
            f"'{SECTION_MARKER}' missing in {os.path.basename(LEDGER_DOC)}); "
            f"{len(declared_keys)} declared dependencies are unregistered."
        )
        return 1

    header_line = next(
        (ln for ln in body if ln.strip().startswith("|") and LICENSED_KEY.lower() in ln.lower()),
        None,
    )
    lic_idx = license_column_index(header_line) if header_line else -1
    if lic_idx < 0:
        problems.append("RED: ledger table has no License column.")

    rows = parse_ledger_rows(body)
    ledger_keys = {name for name, _ in rows}

    unregistered = sorted(declared_keys - ledger_keys)
    stale = sorted(ledger_keys - declared_keys)
    for name in unregistered:
        problems.append(
            f"RED: dependency '{name}' ({declared[name]}) declared in "
            "frontend/package.json but absent from the frontend ledger section "
            "(new dependencies must be registered in the same batch)."
        )
    for name in stale:
        problems.append(
            f"RED: ledger row '{name}' has no matching entry in "
            "frontend/package.json dependencies (stale ledger row)."
        )

    if len(declared_keys) != len(rows):
        problems.append(
            f"RED: count mismatch — declared dependencies={len(declared_keys)}, "
            f"ledger rows={len(rows)} (set equality failed)."
        )

    if lic_idx >= 0:
        for name, cells in rows:
            lic = cells[lic_idx].strip() if lic_idx < len(cells) else ""
            if not lic:
                problems.append(f"RED: ledger row '{name}' has an empty License value.")

    for controlled in CONTROLLED_PACKAGES:
        if controlled in declared_keys:
            row = next((r for r in rows if r[0] == controlled), None)
            if row is not None and not any(CONTROLLED_TAG in c for c in row[1]):
                problems.append(
                    f"RED: '{controlled}' row lost its controlled-adoption marker "
                    f"('{CONTROLLED_TAG}'); graph/chart components require "
                    "per-scenario approval recorded in the ledger."
                )

    if problems:
        for p in problems:
            print(p)
        print(f"\nfrontend-license-ledger: RED ({len(problems)} problem(s))")
        return 1

    print(
        f"frontend-license-ledger: GREEN — declared dependencies={len(declared_keys)}, "
        f"ledger rows={len(rows)}, key sets equal, licenses registered, "
        f"controlled-adoption rows intact ({'/'.join(CONTROLLED_PACKAGES)})."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
