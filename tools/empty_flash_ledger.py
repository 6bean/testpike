#!/usr/bin/env python3
"""empty_flash_ledger.py -- site gate for the table initial-load empty flash.

Standard (user order 2026-11-02): a table page must never paint "no data"
empty-state copy during its FIRST in-flight load. Four compliant shapes:
  1) main table rendered through the DataTablePage shell (shell now renders
     TableSkeleton while loading -- guarded centrally);
  2) a TableSkeleton/el-skeleton gated on a loading ref BEFORE the first
     table, e.g. `v-if="loading"` or `v-if="loading && !rows.length"` (the
     compound form keeps stale rows on refresh; CaseListView/PlanListView and
     the Mock* family shapes) -- any loading-ish ref name counts;
  3) v-loading mask bound on the table tag itself or on an ancestor container
     before the table (theme :has rule hides the empty block under both);
  4) truthy length guard on the table tag (v-if="specs.length > 0"): the
     table is physically absent while empty, so no copy paints; negated
     guards (v-if="!rows.length") do NOT count.
Anything else is ledger debt (raw el-table that paints the empty block during
fetch). The ledger is ratcheted: baseline registered once, count may only
shrink; new violating files turn the gate RED.

Usage:
  empty_flash_ledger.py --emit-baseline      # write docs/.empty-flash-ledger.json
  empty_flash_ledger.py --check              # rc 0 if ledger only shrank / grew=RED
  empty_flash_ledger.py --list               # print current debt files
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # repo root
VIEWS = os.path.join(ROOT, 'frontend', 'src', 'views')
LEDGER = os.path.join(ROOT, 'docs', '.empty-flash-ledger.json')

SHELL_RE = re.compile(r'DataTablePage')
# Loading-state identifier in any naming shape: loading / hitsLoading /
# specsLoading / loadingBoxes ... (the old literal `loading` missed pages
# whose refs are prefixed/suffixed with other words -- false debt, 2026-11-04).
LOAD_NAME = r'\w*[Ll]oading\w*'
# Skeleton guard: TableSkeleton or el-skeleton gated on a truthy loading id,
# with optional `&& rows.length == 0` style clauses (compound conditions keep
# stale rows on refresh instead of flashing the skeleton -- compliant shape).
SKELETON_RE = re.compile(rf'<(?:TableSkeleton|el-skeleton)\s+v-(?:else-)?if="\s*{LOAD_NAME}(\s*&&[^"]*)?"')
# Mask guard: v-loading bound on the table tag itself, or an ancestor
# container mask appearing BEFORE the first table (theme :has rule hides the
# empty block under both table-root and wrapper masks -- styles/theme.scss).
MASK_ON_TABLE_RE = re.compile(r'<el-table\b(?:[^>"\x27]|\x27[^\x27]*\x27|\x22[^\x22]*\x22)*?v-loading', re.S)
MASK_ANCESTOR_RE = re.compile(rf'v-loading="\s*{LOAD_NAME}(\s*&&[^"]*)?"')
LOADREF_RE = re.compile(rf'{LOAD_NAME}\s*=\s*ref\(')
# Truthy length guard on the table tag itself (e.g. v-if="specs.length > 0" or
# v-if="report.ceiling.applicable && report.ceiling.tiers.length"): the table
# is physically absent during the first in-flight load, so no empty copy can
# paint. Negated forms (v-if="!rows.length") stay debt on purpose.
FIRST_TAG_RE = re.compile(r'<el-table\b(?:[^>"\x27]|\x27[^\x27]*\x27|\x22[^\x22]*\x22)*?>', re.S)
LENGTH_GUARD_RE = re.compile(r'v-(?:else-)?if="[^"]*(?<!!)\b[\w.]+\.length')


def is_protected(s):
    """True when the page's first table is guarded during in-flight loads."""
    if SHELL_RE.search(s):
        return True
    if not LOADREF_RE.search(s):
        return False
    m = FIRST_TAG_RE.search(s)
    first_idx = m.start() if m else s.find('<el-table')
    if first_idx < 0:
        first_idx = len(s)
    tag = m.group(0) if m else ''
    if any(mm.start() < first_idx for mm in SKELETON_RE.finditer(s)):
        return True
    if MASK_ON_TABLE_RE.match(tag):
        return True
    if LENGTH_GUARD_RE.search(tag):
        return True
    if any(mm.start() < first_idx for mm in MASK_ANCESTOR_RE.finditer(s)):
        return True
    return False


def scan():
    debt = []
    for dirpath, _dirs, files in os.walk(VIEWS):
        for fn in files:
            if not fn.endswith('.vue'):
                continue
            p = os.path.join(dirpath, fn)
            s = open(p, encoding='utf-8').read()
            if '<el-table' not in s and 'DataTablePage' not in s:
                continue
            if is_protected(s):
                continue
            debt.append(os.path.relpath(p, ROOT))
    return sorted(debt)


def selftest():
    """Bidirectional self-proof (R43): positives must pass, negatives must be
    flagged, so the widened criteria cannot silently green-light real debt."""
    cases = [
        ('exact skeleton', '<TableSkeleton v-if="loading" />\n<el-table v-else :data="rows"/>', True),
        ('compound skeleton', '<TableSkeleton v-if="loading && !rows.length" />\n<el-table v-else :data="rows"/>', True),
        ('prefixed loading ref', '<TableSkeleton v-if="hitsLoading && !hits.length" />\nconst hitsLoading = ref(false)\n<el-table v-else :data="hits"/>', True),
        ('el-skeleton guard', '<el-skeleton v-if="loading" :rows="4" />\n<el-table v-else :data="rows"/>', True),
        ('mask on table', '<el-table border v-if="specs.length > 0" v-loading="specsLoading" :data="specs"/>', True),
        ('length-guarded table', '<el-table border v-if="items.length > 0" :data="items"/>', True),
        ('ancestor mask before table', '<div v-loading="loading">\n<el-table :data="rows"/></div>', True),
        ('shell page', '<DataTablePage :rows="rows"/>', True),
        ('raw table no guard', '<el-table :data="rows"/>', False),
        ('skeleton after table', '<el-table :data="rows"/>\n<TableSkeleton v-if="loading"/>', False),
        ('negated skeleton condition', '<TableSkeleton v-if="!loading" />\n<el-table :data="rows"/>', False),
        ('negated length guard', '<el-table v-if="!rows.length" :data="rows"/>', False),
        ('mask after table only', '<el-table :data="rows"/>\n<div v-loading="loading">sub</div>', False),
        ('guard but no reactive ref', '<TableSkeleton v-if="loading" />\n<el-table v-else :data="rows"/>', False),
    ]
    failed = []
    for name, src, expect in cases:
        if bool(re.search(rf'{LOAD_NAME}\s*=\s*ref\(', src)) is False and expect and 'no reactive' not in name:
            src = src + '\nconst loading = ref(false)\nconst hitsLoading = ref(false)\nconst specsLoading = ref(false)'
        got = is_protected(src)
        if got != expect:
            failed.append(f'{name}: expect={expect} got={got}')
    if failed:
        print('SELFTEST RED:\n  ' + '\n  '.join(failed))
        return 1
    pos = sum(1 for _n, _s, e in cases if e)
    print(f'selftest OK: {len(cases)} cases bidirectional ({pos} compliant shapes + {len(cases)-pos} debt shapes)')
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--emit-baseline', action='store_true')
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--list', action='store_true')
    ap.add_argument('--selftest', action='store_true', help='bidirectional self-proof of the criteria')
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    debt = scan()
    if args.list:
        for d in debt:
            print(d)
        print(f'debt_count={len(debt)}')
        return 0
    if args.emit_baseline:
        json.dump({'debt_files': debt, 'count': len(debt)}, open(LEDGER, 'w'), indent=1)
        print(f'baseline emitted: {len(debt)} debt files -> {LEDGER}')
        return 0
    if args.check:
        if not os.path.exists(LEDGER):
            print('GATE ERROR: no baseline registered (run --emit-baseline)')
            return 2
        base = set(json.load(open(LEDGER))['debt_files'])
        cur = set(debt)
        new = sorted(cur - base)
        gone = len(base - cur)
        print(f'summary: baseline={len(base)} current={len(cur)} cleared={gone} new={len(new)}')
        for n in new:
            print(f'NEW-DEBT {n}')
        if new:
            print('GATE RED: new table page without load-state suppression (R18 standard '
                  '2026-11-02: never paint empty copy during first in-flight load)')
            return 1
        print('GATE GREEN')
        return 0
    ap.print_help()
    return 2


if __name__ == '__main__':
    sys.exit(main())
