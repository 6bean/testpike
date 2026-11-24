#!/usr/bin/env python3
"""Structural UI-convention scanner (gate code S6 in the ui-design-system skill).

The scanner measures page skeletons statically so structural rules that were
never machine-checked before cannot silently drift. Rules (mechanised form of
the ui-design-system page-template and shared-class disciplines):
  S-1 header-band shape: the first-level template structure must be one of
      {toolbar(+left/right), page-header(+header-actions)};
  S-2 layout classes defined once globally: a page using toolbar/filter-bar/
      page-header/header-actions/pagination-bar/pagination-row/pager/pager-row
      without a global theme.scss definition but with its own scoped copy =
      scoped copy drift (independent copies always diverge);
  S-3 one pagination container class: .pagination-bar site-wide
      (pagination-row/pager/pager-row/extract-footer-row/bare placement = violation);
  S-4 list pages need a skeleton: a list page rendering el-table must show
      TableSkeleton while loading;
  S-5 no scattered row-height literals: raw height:40px in a style block or a
      page-level redefinition of --ds-table-row-height (single source =
      theme.scss token + var() references).
Table-feature baseline criteria (machine gate):
  N-1 every raw <el-table> open tag must carry border (Element Plus only
      activates header column-width dragging with border). Quote-aware scan.
      Exempt: files annotated `tp-border-exempt` + reason (narrow-screen mini
      tables); DataTablePage sets border itself so its consumers are covered.
  N-2 a route-level list page whose main table (raw el-table or DataTablePage)
      must wire @sort-change or carry `tp-sort-exempt` + reason.
  N-3 a route-level list page must consume the column-config piece
      (useColumnConfig, or DataTablePage :column-config) or carry
      `tp-column-config-exempt` + reason.
N-2/N-3 legacy debt is recorded in a ratchet baseline
(docs/.ui-audit-struct-baseline.json): baselined (page, rule) pairs become
"pending" (printed, not fatal); any violation outside the baseline = new leak
= exit 1 so the frontend closeout gate turns red immediately. Shrink the
Shrink the baseline by fixing pages, never by growing it casually.
Three-band action-separation criteria (R21):
  FB-1 every view-state action parked inside a .header-actions block is a
      leak (refresh icon/token/i18n, column settings, query/search submit).
      Header band = page-level variant actions only. Exempt: file annotated
      `tp-header-action-exempt` + reason.
  FB-2 a query/search submit button inside the .filter-bar-actions trailing
      slot (R21 revision 2: submit belongs inline at the end of the filter
      group, ≤1 click from the last input; trailing slot is for refresh /
      column settings / density / export). Exempt: `tp-trailing-query-exempt`
      + reason.
FB-1/FB-2 share the ratchet baseline; migration batches shrink it.
Same-band merge criteria (FB group):
  FB-3 YELLOW CARD ONLY (printed account, never red, never exit 1): a page
      whose .header-actions holds nothing but page-level variant actions
      (create / import family), while the same page has a .filter-bar whose
      condition-control estimate is <= --merge-max-controls and whose
      .filter-bar-actions trailing slot already carries actions -- the merge
      band is available but not applied. Those pages move the variant action
      into the trailing slot (create first) at their next closeout batch.
      Exempt: `tp-merge-band-exempt` + reason.
  FB-4 HARD GATE (anti-"touched page, yellow card left behind"): when a
      touched-file list is supplied via --diff-touch (one path per line,
      taken from `git diff --name-only` by the CALLER -- the scanner itself
      never runs git), every page that is BOTH in the FB-3 yellow-card
      account AND touched by this change set is a violation (red, exits
      through the normal new-violation channel): the batch reached into a
      page whose merge-band debt is on the books, so it must either clear
      the yellow card in the same stroke or carry a `tp-merge-defer` +
      reason annotation (colon-anchored text, same anti-bare-token posture
      as tp-scope-exempt). Deferral digests the page out of the FB-3 account
      (recorded in out['fb4_touch_reconcile']['deferred']); touching an
      already-merged page is silent (the page left the account). This closes
      the mechanism hole behind the FB-3 miss: a create button merged late
      because nothing forced the card to ride the page's own closeout.
S-9 criteria (TB group tab-sync iron law):
  S-9 a ColumnSettings (or density) piece parked OUTSIDE an el-tabs block
      while some el-tab-pane contains a table that binds neither a scoped
      column-config nor a scoped density -- and the pane declares no honest
      fixed-view exemption -- is the "global-looking control acting on an
      invisible table" defect. A pane counts as compliant when:
        a. it declares mode 1 in-page (annotation `scope-aware single
           settings piece` + per-pane `settingsFor('<pane>', '<viewKey>', ...)`
           coverage of every el-tab-pane name); or
        b. the pane itself is an honestly disabled fixed view: markup carries
           `tp-pane-fixed-view` + reason AND the file-level exemption token
           `tp-scope-exempt` is paired with a settingsFor declaration whose
           defs argument is null and names a disabledReasonKey; or
        c. the whole file carries `tp-scope-exempt` + reason (mode 2 honest
           absence / mode 3 per-pane toolbars, declared out-of-band).
      Table detector inside a pane region: DataTablePage or raw el-table
      (el-table-column children of el-table are excluded by the tag boundary).
      First account: TagListView (stats pane unbound) / DataFactoryListView
      (pool pane unbound) — both cleared; the ratchet baseline captures the
      three review-pending pages (Batch/Case/ExecutionCenter) until each
      page closes at its own next closeout.
S-11 criteria (viewport-resident horizontal scrollbar, user order
2026-11-09): the wide-table bottom bar floats at the viewport's lower edge
through ONE shared source (theme.scss proxy styling + the app-level
enhancer src/utils/tableViewportHScrollbar.ts installed in main.ts). A
route page that re-styles .el-scrollbar__bar / .tp-hviewport-bar /
is-horizontal inside its own <style> block = second scrollbar source
(R40 duplication) = direct red; exemption tp-hscroll-exempt + reason.
Writes docs/.ui-audit-struct.json (the FB-3 list lives in
out['fb3_merge_candidates'], S-9 hit details in out['s9_detail']).
"""
import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ap = argparse.ArgumentParser(description='Structural UI-convention scanner (S6 gate)')
ap.add_argument('--root', default=str(REPO / 'frontend/src'))
ap.add_argument('--out', default=str(REPO / 'docs/.ui-audit-struct.json'))
ap.add_argument('--baseline', default=str(REPO / 'docs/.ui-audit-struct-baseline.json'))
ap.add_argument('--write-baseline', action='store_true',
                help='capture the current full account as the ratchet baseline and exit 0')
ap.add_argument('--merge-max-controls', type=int, default=4,
                help='FB-3 merge-band threshold: max estimated filter condition controls '
                     '(excludes query-submit/action buttons/selected chips)')
ap.add_argument('--diff-touch', default=None,
                help='FB-4 reconciliation gate arming. "auto" = the scanner enumerates '
                     'this change set\'s touched pages itself via `git diff --name-only HEAD` '
                     '(recommended closeout posture: run the gate, never hand-read the '
                     'stale JSON account). Alternatively pass a newline-separated file '
                     'list. Touched pages still sitting in the FB-3 yellow-card account '
                     'without a tp-merge-defer annotation = red.')
ap.add_argument('--selftest', action='store_true',
                help='S-12..S-16 bidirectional fixtures: planted defects must bite, '
                     'compliant shapes and annotated exemptions must read zero (R43 anti-idle).')
ARGS = ap.parse_args()

ROOT = Path(ARGS.root)
THEME = (ROOT / 'styles/theme.scss').read_text(encoding='utf-8')
LAYOUT_CLASSES = ['toolbar', 'filter-bar', 'page-header', 'header-actions',
                  'pagination-bar', 'pagination-row', 'pager', 'pager-row']
GLOBAL_DEFINED = {c for c in LAYOUT_CLASSES if re.search(rf'^\.{c}\s*[{{,]', THEME, re.MULTILINE)
                  or re.search(rf'(^|\n)\.{c}\s*\{{', THEME)}
PAGER_ALIASES = ['pagination-row', 'pager', 'pager-row']

# ---- S-12 / S-13 ( dialog discipline, pure judges so --selftest can
# plant defects without touching the tree). Both criteria speak about every
# .vue hosting an el-dialog/el-drawer (route pages AND shared components --
# the user report was born inside a shared dialog, a views-only walk would
# miss the exact shape):
#   S-12 popper clipping: a popup component pinned with :teleported="false"
#        inside a dialog host, or a page-local <style> rule moving z-index on
#        a popper/dropdown/popup class (layering is theme.scss
#        tp-popper-z-single-source only). Exemption: tp-popper-z-exempt + reason.
#   S-13 form spacing flush: a page-local rule zeroing .el-form-item margins
#        or dialog/drawer body padding while the file never rides the
#        --tp-band-gap token (spacing is the theme gap-token family).
#        Exemption: tp-dialog-gap-exempt + reason, or the token present.
DIALOG_HOST_RE = re.compile(r'<el-dialog(?![\w-])|<el-drawer(?![\w-])')
POPUP_TELEPORT_OFF_RE = re.compile(r'(?<![\w-]):?teleported="false"')
S12_EXEMPT_RE = re.compile(r'tp-popper-z-exempt\b[^\n]*[:：][^\n]*\w')
S13_EXEMPT_RE = re.compile(r'tp-dialog-gap-exempt\b[^\n]*[:：][^\n]*\w')
STYLE_BLOCK_RE = re.compile(r'<style[^>]*>(.*?)</style>', re.S)
POPPER_RULE_RE = re.compile(
    r'((?:\.el-[\w-]*(?:popper|dropdown|popup|picker-panel)[\w-]*|\.el-popper)\b[^{}@]*?)\{([^}]*)\}',
    re.I)
FORM_ITEM_RULE_RE = re.compile(r'([^.{}@]*\.el-form-item\b[^{}@]*?)\{([^}]*)\}', re.I)
BODY_PAD_RULE_RE = re.compile(
    r'([^.{}@]*\.el-(?:dialog|drawer)__body\b[^{}@]*?)\{([^}]*)\}', re.I)
MARGIN_ZERO_RE = re.compile(r'margin(?:-[a-z-]+)?:\s*0(?![.\d])', re.I)
PADDING_ZERO_RE = re.compile(r'padding(?:-[a-z-]+)?:\s*0(?![.\d])', re.I)
POPPER_Z_DECL_RE = re.compile(r'z-index\s*:', re.I)


def _line_of(src_, pos):
    return src_[:pos].count('\n') + 1


def s12_judge(src_):
    """Dropdown-in-dialog clipping: pinned teleported + page-local popper z patches."""
    hits = []
    if not DIALOG_HOST_RE.search(src_):
        return hits
    for m in POPUP_TELEPORT_OFF_RE.finditer(src_):
        hits.append({'line': _line_of(src_, m.start()),
                     'detail': 'popup pinned with teleported="false" inside a dialog host '
                               '(expansion layer rides the dialog scroll clip - mount to body instead)'})
    if not S12_EXEMPT_RE.search(src_):
        for block in STYLE_BLOCK_RE.finditer(src_):
            for m in POPPER_RULE_RE.finditer(block.group(1)):
                if POPPER_Z_DECL_RE.search(m.group(2)):
                    hits.append({'line': _line_of(src_, block.start() + m.start()),
                                 'detail': 'page-local z-index patch on a popper/dropdown class '
                                           f'(layering is theme tp-popper-z-single-source only): {m.group(1).strip()[:80]}'})
    return hits


def s13_judge(src_):
    """Dialog form spacing flush: margin/padding zeroing without the theme gap token."""
    hits = []
    if not DIALOG_HOST_RE.search(src_):
        return hits
    # A file that rides the theme gap token is the compliant posture: zeroing
    # margins while spacing moves onto `gap: var(--tp-band-gap)` is exactly
    # the token single-source pattern (fix shape), not a flush defect.
    if S13_EXEMPT_RE.search(src_) or '--tp-band-gap' in src_:
        return hits
    for block in STYLE_BLOCK_RE.finditer(src_):
        for m in FORM_ITEM_RULE_RE.finditer(block.group(1)):
            if MARGIN_ZERO_RE.search(m.group(2)):
                hits.append({'line': _line_of(src_, block.start() + m.start()),
                             'detail': 'page-local rule zeroes el-form-item spacing '
                                       f'(form-to-block gaps ride --tp-band-gap): {m.group(1).strip()[:80]}'})
        for m in BODY_PAD_RULE_RE.finditer(block.group(1)):
            if PADDING_ZERO_RE.search(m.group(2)):
                hits.append({'line': _line_of(src_, block.start() + m.start()),
                             'detail': 'dialog/drawer body padding zeroed with no gap token in file '
                                       '(fields sit flush against blocks): '
                                       f'{m.group(1).strip()[:80]}'})
    return hits


# ---- S-14 / S-15 ( dialog scale tiers + in-dialog scrollbar single
# source, user order 2026-11-09). Both speak about every .vue hosting an
# el-dialog/el-drawer (route pages AND shared components, S-12 precedent).
#   S-14 size tiers: a dialog/drawer open tag carrying a page-level
#        width/size attribute whose value is not one of the theme tiers
#        (480/720/1080 literal, or a bound expression), or a page-local
#        <style> rule declaring width on the dialog shell, instead of
#        riding class tp-dialog--sm|md|lg|xl. Exemption: tp-dialog-exempt
#        + reason. Live stock rides the ratchet baseline; the one-page
#        account lands in docs/change-eval with the inventory package.
#   S-15 shared scroll proxy: a dialog/drawer body made to scroll
#        horizontally itself, or a page-local style patch on the shared
#        proxy/EP bar classes inside a dialog host file — the bar is
#        theme.scss + tableViewportHScrollbar single source (the dialog
#        pin landed with piece 1); a second source re-splits the shape.
#        Exemption: tp-hscroll-exempt + reason.
S14_EXEMPT_RE = re.compile(r'tp-dialog-exempt\b[^\n]*[:：][^\n]*\w')
DIALOG_OPEN_TAG_RE = re.compile(r'<el-(?:dialog|drawer)\b[\s\S]*?>')
DIALOG_SIZE_ATTR_RE = re.compile(r'(?<![-\w])(?::width|width|size)="([^"]*)"')
TIER_LITERAL_RE = re.compile(r'^(?:480|720|1080)(?:px)?$')
TIER_CLASS_RE = re.compile(r'tp-dialog--(?:sm|md|lg|xl)')
DIALOG_SHELL_WIDTH_RULE_RE = re.compile(
    r'([^.{}@]*\.el-(?:dialog|drawer)\b[^{}@]*?)\{([^}]*?)\}', re.I)
DIALOG_DECL_WIDTH_RE = re.compile(r'(?:^|[;\s])width\s*:', re.I)
BODY_OVERFLOW_X_RE = re.compile(r'overflow-x\s*:\s*(?:auto|scroll)', re.I)


def s14_judge(src_):
    """Dialog size must ride the four theme tiers instead of page props."""
    hits = []
    if not DIALOG_HOST_RE.search(src_) or S14_EXEMPT_RE.search(src_):
        return hits
    for tag in DIALOG_OPEN_TAG_RE.finditer(src_):
        t = tag.group(0)
        for m in DIALOG_SIZE_ATTR_RE.finditer(t):
            raw = m.group(1).strip()
            if TIER_LITERAL_RE.match(raw):
                continue
            # Even beside a tier class the attribute wins (EP writes the
            # width inline), so the class would be decorative — split
            # source, red.
            hits.append({'line': _line_of(src_, tag.start() + m.start()),
                         'detail': f'page-level dialog/drawer size "{raw}" is not a theme tier '
                                   '(width rides class tp-dialog--sm|md|lg|xl; exempt: '
                                   'tp-dialog-exempt + reason)'})
    for block in STYLE_BLOCK_RE.finditer(src_):
        for m in DIALOG_SHELL_WIDTH_RULE_RE.finditer(block.group(1)):
            if TIER_CLASS_RE.search(m.group(1)):
                continue
            if DIALOG_DECL_WIDTH_RE.search(m.group(2)):
                hits.append({'line': _line_of(src_, block.start() + m.start()),
                             'detail': 'page-local rule sets a dialog/drawer shell width '
                                       f'(tiers are theme.scss single source): {m.group(1).strip()[:80]}'})
    return hits


def s15_judge(src_):
    """In-dialog table overflow rides the shared viewport bar, no second source."""
    hits = []
    if not DIALOG_HOST_RE.search(src_) or HSCROLL_EXEMPT_RE.search(src_):
        return hits
    for block in STYLE_BLOCK_RE.finditer(src_):
        for m in BODY_PAD_RULE_RE.finditer(block.group(1)):
            if BODY_OVERFLOW_X_RE.search(m.group(2)):
                hits.append({'line': _line_of(src_, block.start() + m.start()),
                             'detail': 'dialog/drawer body made to scroll horizontally itself '
                                       '(the overflow bar rides the shared viewport proxy pinned '
                                       'to the body edge; exempt: tp-hscroll-exempt + reason): '
                                       f'{m.group(1).strip()[:80]}'})
        for m in HSCROLL_PATCH_RE.finditer(block.group(1)):
            hits.append({'line': _line_of(src_, block.start() + m.start()),
                         'detail': 'page-local patch on the shared horizontal-bar classes inside '
                                   'a dialog host (single source is theme.scss + '
                                   f'tableViewportHScrollbar): {m.group(0)}'})
    return hits


# ---- S-16 , resident chat dock squeeze, adjudication
# §two.2 "shrink not cover"; pinned form only). Two collision criteria:
#   S-16.1 a component style rule pinned to BOTH viewport edges (position:
#          fixed + left: 0 + right: 0) is a full-bleed floating strip: while
#          the dock is resident it cuts across the reserved gutter. It must
#          ride the dock offset token (theme precedent:
#          .tp-hviewport-bar / .el-overlay in theme.scss) or carry
#          tp-dock-reserve-exempt + reason (e.g. a rule scoped inside the
#          drawer body, which lives INSIDE the dock, not across the page).
#   S-16.2 dock layout state (pinned/visible/width) and the two dock tokens
#          (--tp-chat-dock-reserve / --tp-chat-dock-overlay-offset) have one
#          owner each — ChatDock + chatDockLayout + theme/MainLayout; a page
#          reading them re-splits the squeeze source (R40).
# Both judges are pure so --selftest can plant defects (anti-idle, R43).
S16_EXEMPT_RE = re.compile(r'tp-dock-reserve-exempt\b[^\n]*[:：][^\n]*\w')
FIXED_FULL_BLEED_RULE_RE = re.compile(
    r'([^.{}@]*?)\{([^{}]*?position\s*:\s*fixed[^{}]*?)\}', re.I)
_BLEED_EDGE_RE = re.compile(r'(?:^|[;\s])left\s*:\s*0(?:px)?\b', re.I)
_BLEED_EDGE_R_RE = re.compile(r'(?:^|[;\s])right\s*:\s*0(?:px)?\b', re.I)
_DOCK_TOKEN_CONSUMERS = ('styles/theme.scss', 'layouts/MainLayout.vue',
                         'components/ChatDock.vue', 'composables/chatDockLayout.ts')
DOCK_LAYOUT_STATE_READ_RE = re.compile(
    r"chatDockState\s*\.\s*(visible|pinned|streaming|openedFrom)"
    r"|composables/useChatDockPin|composables/chatDockLayout")
DOCK_STATE_OWNER_RE = re.compile(
    r'(?:^|/)(?:useChatDock(?:Pin|Width)?|chatDockLayout|ChatDock|ChatPanel|ChatFab)\.vue?$'
    r'|(?:^|/)useChatDock\.ts$|(?:^|/)composables/chatDock')


# ---- S-17 : on a
# dock composer surface (data-test="chat-dock-input"), the textarea owns a
# full-width row aligned with the thread — no button squeezed beside it —
# the action row underneath carries both button groups, and the rows
# binding is never a hard-coded height under three lines. Pure judge so
# --selftest can plant defects (anti-idle, R43).
COMPOSER_SQUEEZE_ROW_RE = re.compile(r'<div[^>]*class="chat-composer-row"')
COMPOSER_ACTION_GROUPS_RE = re.compile(r'class="chat-composer-actions"')
COMPOSER_LEFT_GROUP_POS_RE = re.compile(r'data-test="chat-action-group-left"')
COMPOSER_RIGHT_GROUP_POS_RE = re.compile(r'data-test="chat-action-group-right"')
COMPOSER_INPUT_POS_RE = re.compile(r'data-test="chat-dock-input"')
COMPOSER_SEND_POS_RE = re.compile(r'data-test="chat-dock-(?:send|steer|resume)"')
COMPOSER_HARD_ROWS_RE = re.compile(r'(?:rows|:rows)=" ?[12] ?"')


def s16_judge(src_, rel_=''):
    """Resident-dock collision: no full-bleed fixed strips, no dock-state readers."""
    hits = []
    if S16_EXEMPT_RE.search(src_):
        return hits
    if not rel_.endswith('styles/theme.scss'):
        for block in STYLE_BLOCK_RE.finditer(src_):
            for m in FIXED_FULL_BLEED_RULE_RE.finditer(block.group(1)):
                body = m.group(2)
                if not (_BLEED_EDGE_RE.search(body) and _BLEED_EDGE_R_RE.search(body)):
                    continue
                if 'tp-chat-dock-overlay-offset' in body or 'tp-chat-dock-reserve' in body:
                    continue
                hits.append({'line': _line_of(src_, block.start() + m.start()),
                             'detail': 'full-viewport fixed strip (position:fixed + left:0 + right:0) '
                                       'crosses the resident chat-dock gutter (S-16.1: ride '
                                       '--tp-chat-dock-overlay-offset like the shared viewport bar, '
                                       f'or tp-dock-reserve-exempt + reason): {(m.group(1) or "").strip()[:80]}'})
    if not rel_.endswith('.vue') or any(rel_.endswith(o) for o in _DOCK_TOKEN_CONSUMERS) \
            or DOCK_STATE_OWNER_RE.search(rel_):
        return hits
    m = DOCK_LAYOUT_STATE_READ_RE.search(src_)
    if m:
        hits.append({'line': _line_of(src_, m.start()),
                     'detail': 'page reads dock layout state/token directly (S-16.2: the squeeze '
                               'has one owner — chatDockLayout judge + --tp-chat-dock tokens; '
                               f'exempt: tp-dock-reserve-exempt + reason): {m.group(0)[:80]}'})
    return hits


def s17_judge(src_):
    """WeChat-shape composer: full-width thread-aligned input, grouped action row."""
    hits = []
    if 'chat-dock-input' not in src_ or 'tp-composer-shape-exempt' in src_:
        return hits
    m = COMPOSER_SQUEEZE_ROW_RE.search(src_)
    if m:
        hits.append({'line': _line_of(src_, m.start()),
                     'detail': 'composer squeezes buttons beside the textarea inside one row '
                               '(S-17: textarea owns a full-width row aligned with the thread, '
                               'buttons live in the action row underneath; exempt: '
                               'tp-composer-shape-exempt + reason)'})
    if not COMPOSER_ACTION_GROUPS_RE.search(src_):
        hits.append({'line': _line_of(src_, src_.index('chat-dock-input')),
                     'detail': 'composer has no chat-composer-actions row carrying the button '
                               'groups (S-17: left icon group + right send group underneath '
                               'the full-width textarea)'})
    else:
        im = COMPOSER_INPUT_POS_RE.search(src_)
        lm = COMPOSER_LEFT_GROUP_POS_RE.search(src_)
        if im and lm and im.start() > lm.start():
            hits.append({'line': _line_of(src_, lm.start()),
                         'detail': 'action row rendered before the input row (S-17: the button row '
                                   'sits under the textarea, matching the WeChat shape)'})
        if COMPOSER_SEND_POS_RE.search(src_) and not COMPOSER_RIGHT_GROUP_POS_RE.search(src_):
            hits.append({'line': _line_of(src_, src_.index('chat-dock-input')),
                         'detail': 'send/steer button outside a right action group (S-17: the right '
                                   'group data-test="chat-action-group-right" owns the send door)'})
    for rm in COMPOSER_HARD_ROWS_RE.finditer(src_):
        hits.append({'line': _line_of(src_, rm.start()),
                     'detail': 'composer binds a hard-coded rows under 3 (S-17: rows rides the '
                               'persisted preference with factory default 3)'})
    return hits


def scoped_defs(c, cls):
    """Whether the file ships its own style definition for the class."""
    i = c.find('<style')
    return bool(i > 0 and re.search(rf'\.{cls}\s*[^a-zA-Z-]', c[i:]))

def quote_aware_opens(content, tag):
    """Yield attrs of every <tag ...> open tag, tolerating quoted '>' chars."""
    for m in re.finditer(r'<' + tag + r'(?![\\w-])', content):
        i = m.end()
        q = None
        while i < len(content):
            ch = content[i]
            if q:
                if ch == q:
                    q = None
            elif ch in ('"', "'"):
                q = ch
            elif ch == '>':
                yield content[m.end():i]
                break
            i += 1


def div_region(content, cls):
    """Yield inner text of every <div class="...cls..."> block via div pairing.

    The FB criteria are band-scoped, so the scanner needs the
    exact inner region of the header/filter trailing-slot containers rather
    than whole-file keyword matching (which would fire on compliant pages
    that merely import the Refresh icon elsewhere).
    """
    for m in re.finditer(r'<div[^>]*class="[^"]*\b' + cls + r'\b[^"]*"[^>]*>', content):
        i = m.end()
        depth = 1
        j = i
        tok = re.compile(r'<div(?![\w-])|</div>')
        while depth > 0:
            t = tok.search(content, j)
            if not t:
                break
            depth += 1 if t.group(0).startswith('<div') else -1
            j = t.end()
        yield content[i:j]


# FB-1: view-state actions must never live in the page header band (R21).
# The 10-21 revision account also counts query/search submit buttons parked
# in the header (4 pages leaked under the earlier refresh-only vocabulary).
FB1_TOKENS = [
    (r':icon="Refresh"', 'icon-refresh'),
    (r'\bRefresh\b(?!\w)', 'Refresh-token'),
    (r'<ColumnSettings', 'ColumnSettings'),
    (r't\(["\'][^"\']*\.refresh(["\'])\)', 'i18n-refresh'),
    (r't\(["\'][^"\']*\.(query|search)["\']\)', 'i18n-query/search'),
    (r'data-test="[^"]*search[^"]*"', 'data-test-search'),
    (r'<Search\s*/>', 'Search-icon'),
    (r':icon="Search"', 'icon-search'),
]
# FB-2 (R21 revision 2): a query/search submit button pushed into the
# trailing slot instead of staying inline at the end of the filter group.
FB2_TOKENS = [
    (r't\(["\'][^"\']*\.(query|search)["\']\)', 'i18n-query/search'),
    (r'data-test="[^"]*search[^"]*"', 'data-test-search'),
    (r'<Search\s*/>', 'Search-icon'),
    (r':icon="Search"', 'icon-search'),
]
# ---- FB-3 (yellow card): merge-band availability detectors ----
# Page-level variant actions (create/import family) still parked alone in
# the header band while the filter band can carry them (10-22 same-band
# merge ruling trigger conditions ①②).
FB3_VARIANT_RE = re.compile(
    r'data-test="[^"]*(?:create|new|import|add)[^"]*"'
    r"|t\(['\"][^'\"]*\.(?:create|import|new|add)[A-Za-z]*['\"]\)", re.I)
# Condition controls = screening widgets; query-submit buttons, view-state
# actions and selected chips are excluded by construction (slot region is
# stripped before counting; the pattern only matches screening widgets).
FB3_COND_RE = re.compile(
    r'<el-select(?![\w-])|<el-input(?![\w-])|<el-input-number(?![\w-])'
    r'|<el-date-picker(?![\w-])|<el-cascader(?![\w-])|<el-tree-select(?![\w-])'
    r'|<ProjectSelect(?![\w-])')
FB3_SLOT_ACTION_RE = re.compile(r'<el-button(?![\w-])|<ColumnSettings|:icon="Refresh"')


def quote_aware_attrs(content, i):
    """Index just past the '>' closing the open tag that starts at i (None at EOF)."""
    q = None
    while i < len(content):
        ch = content[i]
        if q:
            if ch == q:
                q = None
        elif ch in ('"', "'"):
            q = ch
        elif ch == '>':
            return i + 1
        i += 1
    return None


def tabs_region_span(content):
    """(start, end) of the outermost <el-tabs ...> ... </el-tabs> block, or None."""
    m = re.search(r'<el-tabs(?![\w-])', content)
    if not m:
        return None
    end = content.find('</el-tabs>', m.start())
    return (m.start(), end + len('</el-tabs>')) if end >= 0 else (m.start(), len(content))


def tab_pane_regions(content):
    """Yield (attrs, inner_text) per <el-tab-pane>, tag-paired (self-closing panes
    yield inner='')."""
    for m in re.finditer(r'<el-tab-pane(?![\w-])', content):
        after = quote_aware_attrs(content, m.end())
        if after is None:
            continue
        attrs = content[m.end():after - 1]
        if content[after - 2:after - 1] == '/':  # self-closing pane
            yield attrs, ''
            continue
        depth = 1
        j = after
        tok = re.compile(r'<el-tab-pane(?![\w-])|</el-tab-pane>')
        while depth > 0:
            t = tok.search(content, j)
            if not t:
                break
            depth += 1 if t.group(0).startswith('<el-tab-pane') else -1
            j = t.end()
        yield attrs, content[after:j].rsplit('</el-tab-pane>', 1)[0]


SCOPE_EXEMPT_REASON_RE = re.compile(r'tp-scope-exempt\b[^\n]*[:：][^\n]*\w')
# S-10: edge-gap exemption (colon-anchored reason, same anti-bare-token
# posture as tp-scope-exempt).
PAGE_ROOT_EXEMPT_RE = re.compile(r'tp-page-root-exempt\b[^\n]*[:：][^\n]*\w')
# S-11 (viewport-resident horizontal scrollbar, user order 2026-11-09):
# per-page exemptions for local table-scrollbar patches (colon-anchored
# reason, same anti-bare-token posture as tp-page-root-exempt).
HSCROLL_EXEMPT_RE = re.compile(r'tp-hscroll-exempt\b[^\n]*[:：][^\n]*\w')
HSCROLL_PATCH_RE = re.compile(r'\.el-scrollbar__bar\b|\.tp-hviewport-bar\b|is-horizontal\b')
HSCROLL_STYLE_RE = re.compile(r'<style[\s\S]*?</style>')


def s9_account(tpl, src):
    """S-9 (R25 tab-sync iron law): a ColumnSettings piece parked outside
    an el-tabs block while a pane's table binds neither :column-config nor
    :size = the global-looking control silently acts on an invisible table
    (user changes settings, the visible tab never reacts).

    Returns (hits, meta); meta echoes the disposition of every risky pane so
    the account is auditable. A pane table counts as scoped-bound when its
    open tag carries :column-config or :size (R25 wording ':column-config/
    :size'); compliance exits (checked over the whole file `src`; mode
    annotations and settingsFor declarations live in <script>):
      - mode 1 declared (`scope-aware single settings piece`, highest
        precedence): every unbound pane carries a `tp-pane-fixed-view`
        annotation + reason AND is covered by a real settingsFor('<pane>',
        ...) declaration (honest disabled fixed view). A tp-scope-exempt token
        does NOT launder a mode-1 page -- the contract there is per-pane
        wiring, not an escape hatch.
      - honest absence (R25 clause "narrow it explicitly or disable it
        honestly", mode-2
        shape): the ColumnSettings mark unmounts while the pane shows --
        (i) a v-if/v-show/v-else-if expression before the control excludes the
        pane (`pageTab !== 'P'`), or (ii) the control sits after a `v-else`
        whose sibling gate is `=== 'P'` (embedded-view merge shape). The
        button disappears with the tab, so nothing dead ever faces the user.
        Static approximation; the vitest outfit contract + E2E functional
        spectrum carry the runtime truth.
      - no mode declared: file-level `tp-scope-exempt` + reason (colon-
        anchored text; a bare token injection keeps the gate armed).
    A pane also counts as table-bearing when a child component render keyed
    on its name lives OUTSIDE the tabs block (label-only-pane merge shape:
    `<ScheduleFireListView v-if="pageTab === 'fires'" embedded />`) -- the
    static blind spot, declared or gated like any other pane.
    """
    span = tabs_region_span(tpl)
    if span is None:
        return [], {}
    if '<ColumnSettings' not in tpl[:span[0]] + tpl[span[1]:]:
        return [], {}
    exempt_file = bool(SCOPE_EXEMPT_REASON_RE.search(src))
    mode1_declared = 'scope-aware single settings piece' in src
    cs_at = tpl.find('<ColumnSettings', span[1])
    raw = []  # (pane, inner, why)
    for attrs, inner in tab_pane_regions(tpl):
        nm = re.search(r'name="([\w-]+)"', attrs)
        pane = nm.group(1) if nm else '(no name)'
        has_table = '<DataTablePage' in inner or re.search(r'<el-table(?![\w-])', inner)
        child = re.search(r'<([A-Z]\w+)[^>]*v-(?:if|show)="[^"]*===\s*\'%s\'' % re.escape(pane),
                          tpl[span[1]:])
        if not has_table and not child:
            continue
        missing = [tag for tag in ('DataTablePage', 'el-table')
                   for tattrs in quote_aware_opens(inner, tag)
                   if ':column-config' not in tattrs and ':size' not in tattrs]
        if missing:
            raw.append((pane, inner, 'table binds neither :column-config nor :size (' + '/'.join(sorted(set(missing))) + ')'))
        elif child:
            raw.append((pane, inner, f'table rendered through child component {child.group(1)} keyed on this pane'))
    meta = {'risky_panes': len(raw), 'exempt_file': exempt_file, 'mode1_declared': mode1_declared}

    def honestly_absent_while_pane_shown(pane):
        """True when the control provably unmounts while this pane shows
        (mode-2 honest absence, static approximation over the pre-control
        markup: an explicit `!== 'P'` gate, or a `v-else` section riding a
        `=== 'P'` sibling gate)."""
        if cs_at < 0:
            return False  # control lives inside the tabs block -> not S-9's target
        head = tpl[:cs_at]
        for m in re.finditer(r'v-(?:if|show|else-if)="([^"]*)"', head):
            expr = m.group(1)
            if f"!== '{pane}'" in expr or f"!= '{pane}'" in expr:
                return True
            if re.search(r"===\s*'%s'" % re.escape(pane), expr) \
                    and re.search(r'<[a-zA-Z][^>]*\bv-else\b', head[m.end():]):
                return True
        return False

    hits = []
    for pane, inner, why in raw:
        if mode1_declared:
            if 'tp-pane-fixed-view' in inner and re.search(r"settingsFor\(\s*'%s'" % re.escape(pane), src):
                meta.setdefault('fixed_view_panes', []).append(pane)
                continue
            hits.append({'pane': pane, 'detail': why + ' (mode-1 declared but the pane lacks tp-pane-fixed-view + settingsFor coverage)'})
        elif exempt_file:
            meta.setdefault('exempt_panes', []).append(pane)
        elif honestly_absent_while_pane_shown(pane):
            meta.setdefault('absent_panes', []).append(pane)
        else:
            hits.append({'pane': pane,
                         'detail': why + ' while ColumnSettings sits outside the tabs block and no tab-sync mode is declared'})
    return hits, meta


def merge_band_candidate(tpl, max_controls):
    """Return a FB-3 account dict when the merge band applies but was not applied.

    Shape: header-actions holds only page-level variant actions (every
    el-button inside is create/import family), the page has a .filter-bar
    with an already-populated .filter-bar-actions trailing slot, and the
    condition-control estimate in the filter bar (slot excluded) stays
    within max_controls. Trigger condition ① (no row-level batch action
    bar): a selection column or a selection/bulk action bar keeps the page
    on the three-band shape (merge band not available).
    """
    if re.search(r'type="selection"|selection-bar|bulk-action', tpl):
        return None
    hdr_regions = list(div_region(tpl, 'header-actions'))
    if not hdr_regions:
        return None
    hdr = ''.join(hdr_regions)
    if not FB3_VARIANT_RE.search(hdr):
        return None
    # every button in the header band must be a variant action; any other
    # kind of button means the header is not "emptied except variants"
    for m in re.finditer(r'<el-button(?![\w-])', hdr):
        close = hdr.find('</el-button>', m.end())
        seg = hdr[m.start():close if close > 0 else len(hdr)]
        if not FB3_VARIANT_RE.search(seg):
            return None
    # `div_region` returns a generator: materialize both regions once, the
    # filter-bar list and the slot list are each consumed more than below.
    bar_regions = list(div_region(tpl, 'filter-bar'))
    slot_regions = list(div_region(tpl, 'filter-bar-actions'))
    if not bar_regions or not slot_regions:
        return None
    cond_src = ''
    for reg in bar_regions:
        inner = reg
        for slot in slot_regions:
            inner = inner.replace(slot, '')
        cond_src += inner
    n = len(FB3_COND_RE.findall(cond_src))
    if n > max_controls:
        return None
    if not FB3_SLOT_ACTION_RE.search(''.join(slot_regions)):
        return None
    anchors = re.findall(r'data-test="([^"]*(?:create|new|import|add)[^"]*)"', hdr)
    return {'condition_controls': n, 'header_variant_actions': sorted(set(anchors))}



# ---- SE-1 (SE group selector iron law; account-first
# ratchet per the WBS task line: every el-select without filterable
# is accounted, statically enumerable option lists of <=10 entries are
# exempt by construction). A select is reported only when its candidate
# source is either provably larger than 10 entries (HARD) or not statically
# quantifiable, i.e. remote/store driven (SOFT). Selects whose options are
# literal lists of <=10 entries are compliant and never enter the account.
# Exempt: file annotated `tp-se1-exempt` + reason, the select carrying
# `data-se1-exempt="reason"`, or an inline `<!-- se1-exempt: reason -->`
# comment right above the tag.
SE_SEL_RE = re.compile(r'<el-select(?![\w-])')
SE_OPT_RE = re.compile(r'<el-option(?![\w-])')
SE_VFOR_RE = re.compile(r'v-for\s*=\s*"([^"]+)"')
SE_DECL_PATTERNS = [
    r'const\s+@@\s*(?::[^=\n]+)?=\s*(\[)',
    r'const\s+@@\s*=\s*ref\s*\(\s*(\[)',
    r'const\s+@@\s*=\s*computed\s*\(\s*(?:\(\)\s*)?=>\s*(?:<[^>]*>)?\s*(\[)',
    r'(?:export\s+)?const\s+@@\s*(?::[^=\n]+)?=\s*(\{)',
    r'let\s+@@\s*(?::[^=\n]+)?=\s*(\[)',
]
SE_MAX_STATIC_OPTIONS = 10


def se_tag_end(content, i):
    """Index just past the '>' that closes an open tag starting before i."""
    return quote_aware_attrs(content, i)


def se_block_inner(content, start):
    """Inner markup between this <el-select> and its matching close tag."""
    depth, j = 1, start
    tok = re.compile(r'<el-select(?![\w-])|</el-select>')
    while True:
        t = tok.search(content, j)
        if not t:
            return content[start:]
        if t.group(0).startswith('</'):
            depth -= 1
            if depth == 0:
                return content[start:t.start()]
        else:
            depth += 1
        j = t.end()


def se_literal_size(text):
    """Top-level entry count of an array/object literal (None when unbounded)."""
    depth, items, q, started, has_content, i = 0, 0, None, False, False, 0
    while i < len(text):
        ch = text[i]
        if q:
            if ch == '\\':
                i += 2
                continue
            if ch == q:
                q = None
            has_content = True
        elif ch in ('"', "'", '`'):
            q = ch
            has_content = True
        elif ch in '[{(':
            depth += 1
            if depth == 1:
                started = True
        elif ch in ']})':
            depth -= 1
            if depth == 0 and started:
                return items + (1 if has_content else 0)
        elif ch == ',' and depth == 1 and started:
            items += 1
            has_content = False
        i += 1
    return None


def se_resolve(name, texts):
    """Entry count of a literal array/object bound to `name`, or None."""
    for txt in texts:
        for pat in SE_DECL_PATTERNS:
            m = re.search(pat.replace('@@', re.escape(name)), txt)
            if m:
                size = se_literal_size(txt[m.start(1):m.start(1) + 8000])
                if size is not None:
                    return size
    return None


def se_source_size(expr, texts):
    """Quantify a v-for source expression; None when it is not a literal."""
    stripped = expr.strip()
    # Inline literal array written directly in the template
    # (`v-for="i in [0, 1, 2]"`): count top-level elements so a small fixed
    # enumeration is recognised as statically bounded instead of being
    # mis-accounted as an unquantifiable remote source.
    if stripped.startswith('[') and stripped.endswith(']'):
        body = stripped[1:-1]
        if not body.strip():
            return 0
        if '"' in body or "'" in body or '[' in body:
            return None  # string/compound members: keep conservative
        return body.count(',') + 1
    m = re.match(r'(?:Object\.(?:entries|keys|values)\s*\(\s*)?([\w$]+)', expr.strip())
    if not m:
        return None
    size = se_resolve(m.group(1), texts)
    if size is None:
        return None
    if re.match(r'Object\.(?:entries|keys|values)', expr.strip()):
        return size
    if re.search(r'\.map\s*\(|\.filter\s*\(', expr):
        return None
    return size


SE_TS_INDEX = [p.read_text(encoding='utf-8', errors='ignore')
               for p in sorted(ROOT.rglob('*.ts'))]


def se1_hits(content):
    """SE-1 account rows for one .vue file: (line, grade, detail)."""
    script = content[content.find('<script'):] if '<script' in content else ''
    if not script:
        return []
    texts = [script] + SE_TS_INDEX
    rows = []
    for m in SE_SEL_RE.finditer(content):
        end = se_tag_end(content, m.end())
        if end is None:
            continue
        attrs = content[m.end():end - 1]
        if re.search(r'(?<![\w-])filterable(?![\w-])', attrs):
            continue
        if ('data-se1-exempt' in attrs
                or re.search(r'<!--\s*se1-exempt:', content[max(0, m.start() - 160):m.start()])
                or 'tp-se1-exempt' in content):
            continue
        inner = se_block_inner(content, end)
        opts = []
        for om in SE_OPT_RE.finditer(inner):
            oe = se_tag_end(inner, om.end())
            opts.append(inner[om.end():(oe or om.end()) + 1])
        line = content[:m.start()].count('\n') + 1
        static_n = sum(1 for a in opts if not re.search(r'\bv-for\b', a))
        exprs = []
        for a in opts:
            vm = re.search(r'\bv-for\s*=\s*"[^"]*"', a)
            if not vm:
                continue
            fm = SE_VFOR_RE.search(vm.group(0))
            if fm:
                mm = re.search(r'(?:in|of)\s+(.+)$', fm.group(1))
                if mm:
                    exprs.append(mm.group(1).strip())
        if opts and not exprs:
            if static_n <= SE_MAX_STATIC_OPTIONS:
                continue
            rows.append((line, 'HARD', f'static option list: {static_n} entries > {SE_MAX_STATIC_OPTIONS}'))
            continue
        if exprs:
            sizes = [se_source_size(e, texts) for e in exprs]
            known = [s for s in sizes if s is not None]
            if known and max(known) <= SE_MAX_STATIC_OPTIONS:
                continue
            if known:
                rows.append((line, 'HARD', f'option source {exprs[0][:36]} literal list: {max(known)} entries > {SE_MAX_STATIC_OPTIONS}'))
            else:
                rows.append((line, 'SOFT', f'option source not statically quantifiable {exprs[0][:36]} (remote/store driven; needs filterable or remote-search judgement)'))
            continue
        if re.search(r'(?<![\w-]):options\b', attrs):
            rows.append((line, 'SOFT', ':options data source (virtual-scroll select needs filterable judgement)'))
        else:
            rows.append((line, 'SOFT', 'no el-option children; options come from a slot/child component'))
    return rows


# ---- S-18 (dialog dragging single source, user order 2026-11-23): the
# dragging default lives once in src/utils/dialogDragDefault.ts (Element Plus
# global config, wired in main.ts). A hand-rolled pointer listener on a dialog
# title bar, or an EP-internal useDraggable import beside an el-dialog host,
# is a second drag implementation (R40 duplication) = direct red. Site
# account at publication = 0 (the initial site-wide scan found no
# hand-rolled dialog drag), exemption = tp-dialog-drag-exempt + reason
# (colon-anchored,
# anti-bare-token posture shared with tp-hscroll-exempt).
# Second sub-criterion (residual package, 2026-11-24): a truthy per-page
# `draggable` declaration on an <el-dialog> duplicates the global default
# (dual-channel noise). Publication account = 120 stripped to 0 in the same
# package; the gate holds the account at 0 from here on.
S18_EXEMPT_RE = re.compile(r'tp-dialog-drag-exempt\b[^\n]*[:：][^\n]*\w')
S18_POINTER_TOKEN_RE = re.compile(r'\b(?:mousedown|pointerdown)\b', re.I)
S18_EP_HOOK_RE = re.compile(r'\buseDraggable\b')
S18_TAG_RE = re.compile(r'<el-dialog\b[^>]*>', re.S)
# Truthy per-page declarations only. draggable="false" and :draggable="..."
# are real overrides of the default and stay green.
S18_REDUNDANT_RE = re.compile(r'''\s+draggable(?:\s*=\s*(?:"true"|""))?(?=[\s>])''')


def s18_judge(src_):
    hits = []
    if S18_EXEMPT_RE.search(src_):
        return hits
    for m in re.finditer(r'[^\n]*dialog__header[^\n]*', src_, re.I):
        if S18_POINTER_TOKEN_RE.search(m.group(0)):
            hits.append({'line': src_[:m.start()].count('\n') + 1,
                         'detail': 'hand-rolled dialog header drag listener '
                                   '(dragging is the global default installed '
                                   'from src/utils/dialogDragDefault.ts): '
                                   + m.group(0).strip()[:120]})
    mm = S18_EP_HOOK_RE.search(src_)
    if mm and re.search(r'<el-dialog(?![\w-])', src_):
        hits.append({'line': src_[:mm.start()].count('\n') + 1,
                     'detail': 'useDraggable import beside an el-dialog host '
                               '(re-implements the global dialog dragging '
                               'default in a second source)'})
    for tm in S18_TAG_RE.finditer(src_):
        rm = S18_REDUNDANT_RE.search(tm.group(0))
        if rm:
            hits.append({'line': src_[:tm.start()].count('\n') + 1,
                         'detail': 'redundant per-page draggable declaration '
                                   'on an el-dialog (dragging is the global '
                                   'default from '
                                   'src/utils/dialogDragDefault.ts; only '
                                   'draggable="false"/:draggable overrides '
                                   'are meaningful)'})
    return hits


if ARGS.selftest:
    # Bidirectional fixtures for S-12/S-13 (R43: a detector that never bit is
    # an idle gate). Planted defects must be named; compliant + annotated
    # shapes must read zero. exit 0 = all directions pass, 1 = any miss.
    _bad12 = '<template><el-dialog><el-select :teleported="false"/></el-dialog></template>\n' \
             '<style scoped>\n.el-dialog :deep(.el-select__popper) { z-index: 2050; }\n</style>'
    _ok12 = '<template><el-dialog><el-select/></el-dialog></template>'
    _bad13 = '<template><el-dialog><el-form><el-form-item/></el-form></el-dialog></template>\n' \
             '<style scoped>\n.el-dialog__body .el-form-item { margin-bottom: 0; }\n</style>'
    _ok13gap = ('<template><el-dialog><el-form><el-form-item/></el-form></el-dialog></template>\n'
                '<style scoped>\n.form-grid { gap: var(--tp-band-gap); }\n'
                '.el-form-item { margin-bottom: 0; }\n</style>')
    _ok13ex = ('<template><el-dialog><el-form><el-form-item/></el-form></el-dialog></template>\n'
               '<!-- tp-dialog-gap-exempt: dense wizard reuses the inline row rhythm -->\n'
               '<style scoped>\n.el-form-item { margin-bottom: 0; }\n</style>')
    fails = []
    if len(s12_judge(_bad12)) != 2:
        fails.append('S-12 red side: expected 2 hits (teleported pin + z patch), got %d' % len(s12_judge(_bad12)))
    if s12_judge(_ok12):
        fails.append('S-12 green side: compliant dialog must read zero')
    if len(s13_judge(_bad13)) < 1:
        fails.append('S-13 red side: margin-zeroing rule must be named, got %d' % len(s13_judge(_bad13)))
    if s13_judge(_ok13gap):
        fails.append('S-13 green side: gap-token file must read zero')
    if s13_judge(_ok13ex):
        fails.append('S-13 digest side: tp-dialog-gap-exempt must read zero')
    _bad14 = ('<template><el-dialog width="800px"><p/></el-dialog>'
              '<el-dialog :width="dlgW" class="tp-dialog--xl"/></template>\n'
              '<style scoped>.el-dialog { width: 60%; }</style>')
    _ok14 = ('<template><el-dialog class="tp-dialog--xl"><p/></el-dialog>'
             '<el-dialog width="480px"/></template>')
    _ok14ex = ('<template><!-- tp-dialog-exempt: fixed-size preview canvas keeps its own width -->'
               '<el-dialog width="1024px"/></template>')
    _bad15 = ('<template><el-dialog><el-table/></el-dialog></template>\n'
              '<style scoped>\n.el-dialog__body { overflow-x: auto; }\n'
              '.tp-hviewport-bar { position: fixed; }\n</style>')
    _ok15 = ('<template><el-dialog><el-table/></el-dialog></template>\n'
             '<style scoped>\n.el-dialog__body { padding: 0 4px; }\n</style>')
    _j14 = s14_judge(_bad14)
    if len(_j14) != 3:
        fails.append('S-14 red side: expected 3 hits (800px + bound width + shell style), got %d' % len(_j14))
    if s14_judge(_ok14):
        fails.append('S-14 green side: tier class / tier literal widths must read zero')
    if s14_judge(_ok14ex):
        fails.append('S-14 digest side: tp-dialog-exempt must read zero')
    _j15 = s15_judge(_bad15)
    if len(_j15) != 2:
        fails.append('S-15 red side: expected 2 hits (body overflow-x + proxy patch), got %d' % len(_j15))
    if s15_judge(_ok15):
        fails.append('S-15 green side: compliant dialog host must read zero')
    _bad16 = ('<template><div/></template>\n'
              '<style scoped>.bleed-bar { position: fixed; left: 0; right: 0; bottom: 0; }\n'
              '.aside-stripe { position: fixed; top: 0; left: 0; right: 0; }</style>')
    _bad16b = '<template><div>{{ chatDockState.pinned }}</div></template>'
    _ok16 = ('<template><div/></template>\n'
             '<style scoped>.bleed-bar { position: fixed; left: 0;\n'
             '  right: var(--tp-chat-dock-overlay-offset, 0px); bottom: 0; }</style>')
    _ok16ex = ('<template><div/></template>\n'
               '<!-- tp-dock-reserve-exempt: toast rail is a transient layer above the dock -->\n'
               '<style scoped>.bleed-bar { position: fixed; left: 0; right: 0; bottom: 0; }</style>')
    _j16 = s16_judge(_bad16, 'views/demo/ThingView.vue')
    if len(_j16) != 2:
        fails.append('S-16 red side: expected 2 hits (two full-bleed fixed strips), got %d' % len(_j16))
    _j16b = s16_judge(_bad16b, 'views/demo/ThingView.vue')
    if len(_j16b) != 1:
        fails.append('S-16 red side (state read): expected 1 hit (page reading dock pin), got %d' % len(_j16b))
    if s16_judge(_ok16, 'views/demo/ThingView.vue'):
        fails.append('S-16 green side: gutter-aware fixed strip must read zero')
    if s16_judge(_ok16ex, 'views/demo/ThingView.vue'):
        fails.append('S-16 digest side: tp-dock-reserve-exempt must read zero')
    if s16_judge(_bad16b, 'components/ChatDock.vue'):
        fails.append('S-16 owner exemption: the dock itself reading its own state must read zero')
    _bad17 = ('<template><div data-test="chat-dock-input"/>'
              '<div class="chat-composer-row"><el-button data-test="chat-dock-send"/></div>'
              '<el-input :rows="2"/></template>')
    _ok17 = ('<template><el-input data-test="chat-dock-input" :rows="composerRows"/>'
             '<div class="chat-composer-actions">'
             '<span data-test="chat-action-group-left"/>'
             '<span data-test="chat-action-group-right">'
             '<el-button data-test="chat-dock-send"/></span></div></template>')
    _j17 = s17_judge(_bad17)
    if len(_j17) != 3:
        fails.append('S-17 red side: expected 3 hits (squeezed row + missing action row + hard rows=2), got %d' % len(_j17))
    if s17_judge(_ok17):
        fails.append('S-17 green side: WeChat-shape composer must read zero')
    _ok17ex = '<!-- tp-composer-shape-exempt: legacy embed keeps the inline row -->' + _bad17
    if s17_judge(_ok17ex):
        fails.append('S-17 digest side: tp-composer-shape-exempt must read zero')
    _bad18 = ('<template><el-dialog><div class="el-dialog__header" '
              '@mousedown="startDrag"/></div></el-dialog></template>')
    _bad18b = ("<script setup>import { useDraggable } from 'element-plus'"
               '</script><template><el-dialog/></template>')
    _bad18c = '<template><el-dialog draggable width="480px"/></template>'
    _ok18 = ('<template><el-dialog><div class="el-dialog__header">t</div>'
             '</el-dialog></template>')
    _ok18b = ('<template><el-dialog :draggable="moveable" '
              'draggable="false"/></template>')
    _ok18ex = ('<!-- tp-dialog-drag-exempt: legacy canvas picker keeps its '
               'own inline handle -->' + _bad18)
    _j18 = s18_judge(_bad18) + s18_judge(_bad18b) + s18_judge(_bad18c)
    if len(_j18) != 3:
        fails.append('S-18 red side: expected 3 hits (header listener + '
                     'useDraggable import + redundant per-page flag), got %d'
                     % len(_j18))
    if s18_judge(_ok18):
        fails.append('S-18 green side: plain dialog header must read zero')
    if s18_judge(_ok18b):
        fails.append('S-18 green side: draggable overrides (":draggable" '
                     'binding, ="false") must read zero')
    if s18_judge(_ok18ex):
        fails.append('S-18 digest side: tp-dialog-drag-exempt must read zero')
    print('SELFTEST ' + ('OK' if not fails else 'FAIL'))
    for f in fails:
        print('  ' + f)
    sys.exit(0 if not fails else 1)

out = {'global_defined': sorted(GLOBAL_DEFINED), 'pages': {}, 'pending': {},
       'rules': {}}
se1_detail = {}
# FB-3 yellow-card account (merge band available but not applied), page -> detail dict
fb3_account = {}
# S-9 account: every page with risky panes (violating or exempted),
# page -> {'hits', 'risky_panes', 'fixed_view_panes', 'exempt_panes', ...}
s9_account_by_page = {}
# Boundary: S-1/S-4/S-5 are page-template disciplines and only hold for route
# pages. Components embedded in a page (the SpecImportWizard dialog, the
# PerfReportView panel) take their shape from the host page; the earlier
# file-name-suffix heuristic (Dialog/Drawer/Panel.vue) missed cases and
# produced false positives. The authoritative source is now the actual import
# set of router/routes.ts (non-route components never join the three page-level
# rules; S-2/S-3 style disciplines still apply to every component).
ROUTES_TS = (ROOT / 'router/routes.ts').read_text(encoding='utf-8')
for p in sorted((ROOT / 'views').rglob('*.vue')):
    c = p.read_text(encoding='utf-8')
    if '<template>' not in c: continue
    rel = str(p.relative_to(ROOT.parent))
    vios = []
    # Template region. Boundary fix: the old cut at c.find('<script') breaks
    # when <script setup> precedes <template> (the composition convention used
    # by the perf/apitest components) -- the "template region" then collapses
    # to empty/script-header and the real header shape and skeleton escape
    # measurement (false-positive violation source); template comments/class
    # literals leaking in also misfire. The region runs from <template> to the
    # style block, and when the script block starts before the template the
    # style block is the right bound (same bound for S-1/S-2/S-3/S-4/S-5).
    tpl_start = c.find('<template>')
    style_pos = c.find('<style')
    script_pos = c.find('<script')
    if tpl_start < 0:
        tpl = ''
    else:
        right = style_pos if style_pos > tpl_start else (
            script_pos if script_pos > tpl_start else len(c))
        tpl = c[tpl_start:right]
    # Exact token membership (avoids picker-toolbar/inv-toolbar sub-region false hits)
    tokens = set()
    for m in re.finditer(r'class="([^"]*)"', tpl):
        tokens.update(m.group(1).split())
    used = {cls for cls in LAYOUT_CLASSES if cls in tokens}
    has_toolbar = 'toolbar' in tokens
    has_pghdr = 'page-header' in tokens
    is_list = '<el-table' in tpl or '<DataTablePage' in tpl
    has_raw_table = bool(re.search(r'<el-table(?![\w-])', tpl))
    has_raw_table_global = bool(re.search(r'<el-table(?![\w-])', c))
    # Route-page authority check (a literal dynamic-import path hit in
    # routes.ts = route page; otherwise it is a component embedded in a host page)
    is_route_page = f"@/views/{p.relative_to(ROOT / 'views')}".replace('\\', '/') in ROUTES_TS
    # S-1 header-band shape (boundary: constrains page-level list pages only --
    #    tables inside dialogs/drawers/panels use v-loading, detail pages use the
    #    breadcrumb+info-header template, dashboards use the section-head/
    #    page-card grid; all three are exempt by construction; non-route
    #    components are exempt too -- their shape belongs to the host page)
    is_overlay = p.name.endswith(('Dialog.vue', 'Drawer.vue', 'Panel.vue'))
    is_detail = p.name.endswith('DetailView.vue') or 'Dashboard' in p.name
    page_level = is_route_page and not (is_overlay or is_detail)
    # R23: a headerless list page that opens with the filter band is the
    # compliant end-shape (the page name rides the global top bar;
    # an empty header row would be the shell line R23 forbids). S-1 only fires
    # when NEITHER band exists at all.
    if is_list and not (has_toolbar or has_pghdr) and 'page-title' not in tokens and 'section-head' not in tokens \
            and page_level \
            and 'filter-bar' not in tokens:
        vios.append(['S-1', 'list page has no standard header shape (toolbar/page-header/page-title)', 0])
    if has_toolbar and has_pghdr:
        vios.append(['S-1', 'page-level toolbar and page-header shapes mixed', 0])
    # S-2 scoped copy drift
    for cls in sorted(used):
        if cls not in GLOBAL_DEFINED and scoped_defs(c, cls):
            vios.append(['S-2', f'.{cls} has no global definition yet the page ships its own scoped copy (drift)', 0])
    # S-3 pagination container
    if '<el-pagination' in tpl:
        m = re.search(r'<([a-z]+)([^>]*class="[^"]*")>\s*<el-pagination', tpl)
        cls_ok = bool(re.search(r'class="[^"]*pagination-bar', tpl))
        if not cls_ok:
            alias = next((a for a in PAGER_ALIASES if a in tokens or a == 'history-pager' and 'history-pager' in tokens), None)
            vios.append(['S-3', f'pagination container = {alias or "not .pagination-bar / bare placement"}', 0])
    # S-4 skeleton (same boundary as S-1: dialogs/drawers/panels/detail pages
    # exempt; report boards are exempt too -- chart hybrids a skeleton cannot
    # mirror, their loading state rides the DataTablePage shell :loading,
    # annotated honestly rather than silently passed)
    is_report_board = p.name in ('ReportView.vue', 'TestReportListView.vue', 'CoverageMatrixView.vue')
    has_shell_loading = ':loading="loading"' in tpl
    if is_list and 'TableSkeleton' not in tpl and 'el-skeleton' not in tpl \
            and page_level \
            and not (is_report_board and has_shell_loading):
        vios.append(['S-4', 'page-level list has no skeleton loading state', 0])
    # S-5 row height (rewritten): ruling = 40px row height normalised site-wide
    #    through the single theme.scss token. The old criterion "page-level main
    #    table size=small (row height below the 48 tier)" is void -- the global
    #    override in theme.scss already pulls all three Element-Plus paddings
    #    (default/small/large) back to --ds-table-row-height, so `size` only
    #    picks the font size, never the row height (density semantics exempt).
    #    New criterion = scattered row-height literals: a raw height: 40px in a
    #    style block (row rules must use var(--ds-table-row-height)) or a
    #    component redefining --ds-table-row-height (only owner = theme.scss).
    #    Out-of-domain whitelist (not the table-row domain, no scope creep):
    #    --el-menu-item-height (menu), sparkline/chart height:48px (charts),
    #    line-height text family (owned by the other scanner's T3 rule).
    stylepos = style_pos if style_pos > 0 else -1
    if stylepos > 0:
        sty = c[stylepos:]
        # Negative lookbehind skips same-suffix custom properties like
        # --el-menu-item-height; var() references never contain a literal value
        if re.search(r'(?<![\w-])height\s*:\s*40px\b', sty):
            vios.append(['S-5', 'raw height:40px in the style block (should reference var(--ds-table-row-height))', 0])
        if '--ds-table-row-height:' in sty:
            vios.append(['S-5', 'component redefines the row-height token (only owner = theme.scss)', 0])
    # ---- N-1: border on every raw el-table open tag ----
    # Quote-aware over the whole file (tables in dialog blocks are tags too);
    # DataTablePage.vue itself sets border via a computed attr binding, so the
    # shell file is structural-exempt by construction.
    shell_exempt = p.name == 'DataTablePage.vue'
    if has_raw_table_global and not shell_exempt and 'tp-border-exempt' not in c:
        for attrs in quote_aware_opens(c, 'el-table'):
            if not re.search(r'(?<![\w-])border(?![\w-])', attrs):
                vios.append(['N-1', 'el-table open tag missing border (Element Plus only enables column dragging with border; exempt: tp-border-exempt + reason)', 0])
    # ---- N-2 / N-3 only for route-level list pages (page_level) ----
    if page_level and (has_raw_table or '<DataTablePage' in tpl):
        wired_sort = '@sort-change' in tpl
        if not wired_sort and 'tp-sort-exempt' not in c:
            vios.append(['N-2', 'page-level main table not wired to sort-change (server-side sort baseline; exempt: tp-sort-exempt + reason)', 0])
        consumes_colcfg = ('useColumnConfig' in c) or \
            ('<DataTablePage' in tpl and re.search(r':column-config|columnConfig', tpl))
        if not consumes_colcfg and 'tp-column-config-exempt' not in c:
            vios.append(['N-3', 'page-level list does not consume the column-config piece (useColumnConfig / DataTablePage :column-config; exempt: tp-column-config-exempt + reason)', 0])
    # ---- SE-1 (SE group selector iron law): every candidate account
    # row rides the ratchet, so a select that leaks in after this tick is red
    # while the legacy debt prints as pending.
    se1_rows = se1_hits(c)
    if se1_rows:
        se1_detail[rel] = [{'line': r[0], 'grade': r[1], 'detail': r[2]} for r in se1_rows]
        for line, grade, detail in se1_rows:
            vios.append(['SE-1', f'el-select without filterable (selectors must be searchable; exempt: data-se1-exempt/tp-se1-exempt + reason) [{grade}] L{line}: {detail}', line])
    # ---- FB-1 / FB-2 action-band separation (R21 + R21 rev.2) ----
    # FB-1: the .header-actions band must carry only page-level variant
    # actions; view-state actions (refresh / column settings / query-submit)
    # leak here historically across 21 entries. Exempt = file annotated
    # `tp-header-action-exempt` + reason (genuinely page-level regenerate
    # actions such as "rescan assets" may stay).
    if 'tp-header-action-exempt' not in c:
        for reg in div_region(tpl, 'header-actions'):
            hits = sorted({lab for rx, lab in FB1_TOKENS if re.search(rx, reg)})
            for lab in hits:
                vios.append(['FB-1', f'header band carries a view-state action (move it to the .filter-bar-actions trailing slot; exempt: tp-header-action-exempt + reason): {lab}', lab])
    # FB-2 (R21 revision 2): a query/search submit button must sit inline at
    # the end of the filter group, not pushed right into the trailing slot.
    # Exempt = file annotated `tp-trailing-query-exempt` + reason.
    if 'tp-trailing-query-exempt' not in c:
        for reg in div_region(tpl, 'filter-bar-actions'):
            hits = sorted({lab for rx, lab in FB2_TOKENS if re.search(rx, reg)})
            for lab in hits:
                vios.append(['FB-2', f'query/search submit button parked in the filter trailing slot (belongs inline at the end of the filter group, <=1 click; exempt: tp-trailing-query-exempt + reason): {lab}', lab])
    # ---- FB-3 (yellow card, account-only): merge band available but
    # not applied. Header band holds ONLY page-level variant actions while a
    # .filter-bar exists whose condition estimate <= threshold and whose
    # trailing slot already carries actions. Never enters `vios` (no red,
    # no exit-code effect); printed + stored in out['fb3_merge_candidates']
    # so each page can merge at its next closeout.
    if page_level and is_list and 'tp-merge-band-exempt' not in c \
            and not any(re.search(rx, r) for r in div_region(tpl, 'header-actions')
                        for rx, _ in FB1_TOKENS):
        fb3 = merge_band_candidate(tpl, ARGS.merge_max_controls)
        if fb3:
            fb3_account[rel] = fb3
    # ---- RD-1 / RD-2 (R23 page-name single source) ----
    # RD-1 two leak shapes inside a routed page card: (a) an empty title
    # shell (h1/h2 with class page-title/header-title carrying no text —
    # the residue of "strip the word, keep the shell" refactors, R23 tail
    # clause); (b) the page-name i18n key (*pageTitle) still rendered in
    # the template — the page name rides the global top bar (header-title +
    # breadcrumb), so any *pageTitle render in the card body is a second
    # channel. Block-section headings (section-head h2 such as the
    # execution center's sub-section titles) are the legal channel and are
    # covered by the S-7 top-bar equality/containment comparison in
    # ui_audit_scan instead; an intentional keep needs the shared
    # 'tp-title-dedup-exempt' marker (one exemption vocabulary with S-7).
    for m in re.finditer(
            r'<(h[12])([^>]*class="[^"]*(?:page-title|header-title)[^"]*"[^>]*)>(.*?)</\1>',
            tpl, re.S):
        if not m.group(3).strip():
            vios.append(['RD-1', 'empty title shell inside the page card (R23: stripping the word must remove the shell in the same change; exempt: tp-title-dedup-exempt + reason)', 0])
    if re.search(r"t\(\s*'[\w.]*pageTitle'\s*\)", tpl) and 'tp-title-dedup-exempt' not in c:
        vios.append(['RD-1', 'page card renders a *pageTitle key (page name rides the global top bar only; keep a section heading => add tp-title-dedup-exempt + reason)', 0])
    # RD-2: a routed page template must not hang class="page-subtitle"
    # directly — descriptive subtitles restate the top-bar help chapter and
    # were swept in an earlier stage; the 11 legitimate keeps (ownership/segment
    # chips per rule G③, dynamic data chips, detail-page subtitles, block
    # panels) carry the file-level 'tp-subtitle-exempt' marker + reason
    # (ledger docs/task-archives/PROGRESS_card_t417recon_ledger_tick1398.md §K).
    if page_level and re.search(r'class="[^"]*\bpage-subtitle\b', tpl) \
            and 'tp-subtitle-exempt' not in c:
        vios.append(['RD-2', 'route-page template hangs a page-subtitle directly (descriptive subtitles should be deleted or folded into the ? hint; keep => tp-subtitle-exempt + reason)', 0])
    # ---- S-10 (edge-gap iron law): every route page mounts .page-root --
    # The theme .page-root padding (12px) is the single source of the content
    # edge gap; a route page without it hugs the viewport (reported defect:
    # /ai/chat gap=0/0/0). Live measurement, not static grep, decides a true
    # violation: pages that carry their own root padding register the file
    # annotation `tp-page-root-exempt` + reason (colon-anchored, anti-bare-
    # token posture shared with tp-scope-exempt). First account (bug report
    # section 3, 10 pages): 3 pages fixed in this batch (ai-chat / extensions /
    # extraction hosts), 5 padding-compensated pages + 2 layout-free pages
    # (login/pwd) registered exempt -> the site account is zero at publication,
    # direct red, no ratchet (S-9 anti-idle precedent).
    if page_level and 'page-root' not in tokens \
            and not PAGE_ROOT_EXEMPT_RE.search(c):
        vios.append(['S-10', 'route page root class missing page-root (outer edge gap rides the theme .page-root only; self-padded pages need tp-page-root-exempt + reason)', 0])
    # ---- S-11 (viewport-resident horizontal scrollbar, user order
    # 2026-11-09): the proxy bar lives once in theme.scss + the shared
    # enhancer (src/utils/tableViewportHScrollbar.ts, installed in main.ts);
    # a page restyling .el-scrollbar__bar / the proxy class in its own
    # <style> block is a second scrollbar source (R40 duplication, and the
    # exact recurrence pattern behind "fixed three pages, twenty still
    # broken"). Direct red, no ratchet: the site account is zero at
    # publication (vitest ratchet baseline proves the style surface is
    # clean), exemption = tp-hscroll-exempt + reason.
    if page_level and HSCROLL_EXEMPT_RE.search(c) is None:
        for block in HSCROLL_STYLE_RE.finditer(c):
            m = HSCROLL_PATCH_RE.search(block.group(0))
            if m:
                line = c[:block.start() + m.start()].count('\n') + 1
                vios.append(['S-11', 'page-local horizontal-scrollbar patch (viewport-resident bar is theme.scss + shared enhancer single source; local patch needs tp-hscroll-exempt + reason): '
                             f'{m.group(0)}', line])
                break
    # ---- S-9 (R25 tab-sync): scoped-settings gate ------------------
    # Red immediately (no ratchet entry needed): the two first-account pages
    # (TagListView stats pane / DataFactoryListView pool pane) are fixed by
    # this package and the three review-pending pages resolve to compliant
    # shapes (control unmounts per tab / bound panes), so the site-wide
    # account is zero at publication -- an inventory-first ratchet for an
    # empty account would be an empty gate (anti-idle: the selftest fixtures
    # P10-P13 prove the criterion bites, turns to zero on fix, digests
    # exemptions, and stays armed against bare-token injection).
    if page_level:
        s9_hits, s9_meta = s9_account(tpl, c)
        for h in s9_hits:
            vios.append(['S-9', f'column-settings/density piece outside the tab block is decoupled from the table inside the tab (R25: mode 1 per-tab wiring / fixed view tp-pane-fixed-view + settingsFor coverage; mode 2 honest absence; mode 3 per-pane controls or tp-scope-exempt + reason): {h["detail"]}', h['pane']])
        if s9_meta.get('risky_panes'):
            s9_account_by_page[rel] = {'hits': s9_hits, **s9_meta}
    if vios:
        out['pages'][rel] = vios

# ---- SE-1 second pass: shared components and other non-route pieces.
# The page-shape rules above only speak about routed pages; the
# selector rule is a control-level rule, so every .vue outside views/ that
# hosts an el-select is screened here and reported under its own path.
for p in sorted(ROOT.rglob('*.vue')):
    if (ROOT / 'views') in p.parents:
        continue
    c = p.read_text(encoding='utf-8')
    if '<el-select' not in c:
        continue
    rel = str(p.relative_to(ROOT.parent))
    rows = se1_hits(c)
    if not rows:
        continue
    se1_detail[rel] = [{'line': r[0], 'grade': r[1], 'detail': r[2]} for r in rows]
    for line, grade, detail in rows:
        out['pages'].setdefault(rel, []).append(
            ['SE-1', f'el-select without filterable (selectors must be searchable; exempt: data-se1-exempt/tp-se1-exempt + reason) [{grade}] L{line}: {detail}', line])

# ---- S-12 / S-13 walk: every .vue hosting an el-dialog/el-drawer, route
# pages and shared components alike (the report came from a shared
# dialog, so the views-only boundary would not see it). Entries ride the
# ratchet: the live stock is captured into the baseline the publication
# stroke registers, and every page added afterwards turns red.
s12_detail, s13_detail, s14_detail, s15_detail, s16_detail, s17_detail = {}, {}, {}, {}, {}, {}
# S-18 walk: every .vue plus shared .ts under src — a hand-rolled drag could
# also hide in a component/composable, and the report was born inside a
# shared dialog (views-only walk would miss it, same posture as S-12/S-13).
s18_detail = {}
for p in sorted(ROOT.rglob('*.vue')) + sorted((ROOT / 'src').rglob('*.ts')):
    c = p.read_text(encoding='utf-8')
    rel = str(p.relative_to(ROOT.parent))
    for h in s18_judge(c):
        s18_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-18', h['detail'], h['line']])
for p in sorted(ROOT.rglob('*.vue')):
    c = p.read_text(encoding='utf-8')
    rel = str(p.relative_to(ROOT.parent))
    for h in s16_judge(c, rel):
        s16_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-16', h['detail'], h['line']])
    for h in s17_judge(c):
        s17_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-17', h['detail'], h['line']])
for p in sorted(ROOT.rglob('*.vue')):
    c = p.read_text(encoding='utf-8')
    if not DIALOG_HOST_RE.search(c):
        continue
    rel = str(p.relative_to(ROOT.parent))
    for h in s14_judge(c):
        s14_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-14', h['detail'], h['line']])
    for h in s15_judge(c):
        s15_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-15', h['detail'], h['line']])
    for h in s12_judge(c):
        s12_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-12', f'dropdown/overlay inside the dialog can be clipped (T592: mount poppers to body, '
                      f'layering single source in theme.scss; exempt: tp-popper-z-exempt + reason) '
                      f'L{h["line"]}: {h["detail"]}', h['line']])
    for h in s13_judge(c):
        s13_detail.setdefault(rel, []).append(h)
        out['pages'].setdefault(rel, []).append(
            ['S-13', f'dialog form spacing is page-local zeroing instead of the theme gap token '
                      f'(T592: gaps ride --tp-band-gap; exempt: tp-dialog-gap-exempt + reason) '
                      f'L{h["line"]}: {h["detail"]}', h['line']])

# ---- FB-4 (yellow-card-rides-the-closeout reconciliation) ----
# The FB-3 miss (user report: the /#/data-factories create button was not
# merged) was not a detection gap — the scanner HAD the page on the books;
# nothing forced the card to ride the change set that touched the page. FB-4
# closes that mechanism hole: armed either with --diff-touch auto (the
# scanner enumerates the working-tree diff itself via `git diff --name-only
# HEAD`, so the closeout posture is one command with no hand-maintained
# touch list) or with an explicit caller-fed file list; any touched page
# still sitting in the FB-3 yellow-card account is a
# violation UNLESS the page carries `tp-merge-defer` + reason (an honest
# deferral note, anti-bare-token posture shared with tp-scope-exempt).
# Deferral digests the page out of the FB-3 account into the FB-4 detail;
# touching an already-merged page is silent. FB-4 is DIRECT red and never
# enters the ratchet baseline capture: an account-first ratchet that can
# absorb its own gate would be an idle gate (anti-idle, S-9 precedent --
# selftest fixtures P16-P18 prove it bites / stays silent / digests).
MERGE_DEFER_REASON_RE = re.compile(r'tp-merge-defer\b[^\n]*[:：][^\n]*\w')
def _git_touched_pages():
    """Enumerate working-tree-touched files (FB-4 auto mode)."""
    import subprocess
    for args_ in (['git', 'diff', '--name-only', 'HEAD'], ['git', 'diff', '--name-only']):
        try:
            proc = subprocess.run(args_, cwd=str(ROOT), capture_output=True, text=True, timeout=30)
        except Exception:
            continue
        if proc.returncode == 0:
            lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]
            # untracked additions count as touched too (a new page is a touched page)
            try:
                up = subprocess.run(['git', 'ls-files', '--others', '--exclude-standard'],
                                    cwd=str(ROOT), capture_output=True, text=True, timeout=30)
                if up.returncode == 0:
                    lines += [ln.strip() for ln in up.stdout.splitlines() if ln.strip()]
            except Exception:
                pass
            return sorted(set(lines))
    return None
fb4_detail = {'armed': bool(ARGS.diff_touch), 'touched': [], 'violations': [], 'deferred': []}
if ARGS.diff_touch == 'auto':
    _touch_lines = _git_touched_pages()
    if _touch_lines is None:
        print('FB-4: --diff-touch auto but git is unavailable — gate stays disarmed for this run',
              file=sys.stderr)
        _touch_lines = []
    fb4_detail['touch_source'] = 'git-diff-auto'
elif ARGS.diff_touch:
    try:
        _touch_lines = [ln.strip() for ln in
                        Path(ARGS.diff_touch).read_text(encoding='utf-8').splitlines()
                        if ln.strip() and not ln.strip().startswith('#')]
        fb4_detail['touch_source'] = 'caller-list'
    except OSError as exc:
        print(f'FB-4: --diff-touch unreadable ({exc}) — gate stays disarmed for this run',
              file=sys.stderr)
        _touch_lines = []
else:
    _touch_lines = None
if _touch_lines is not None:
    # normalize every touched path into the scan-root-relative family
    # ('src/...'), tolerant of absolute paths and repo-root-relative paths.
    norm_root = str(ROOT).rstrip('/')
    touched_rel = []
    for ln in _touch_lines:
        l = ln.strip().replace('\\', '/')
        if norm_root and l.startswith(norm_root + '/'):
            l = l[len(norm_root) + 1:]
        elif '/src/' in l:
            l = 'src/' + l.split('/src/', 1)[1]
        touched_rel.append(l)
    fb4_detail['touched'] = sorted(set(touched_rel))
    for rel, d in sorted(fb3_account.items()):
        if not any(t.endswith(rel) for t in touched_rel):
            continue
        page_src = (ROOT / rel[len('src/'):]).read_text(encoding='utf-8')
        if MERGE_DEFER_REASON_RE.search(page_src):
            fb4_detail['deferred'].append({'page': rel, 'variant_actions': d['header_variant_actions']})
            print(f'  FB-4 DEFER {rel}: tp-merge-defer annotation present -> digested out of the FB-3 account')
        else:
            fb4_detail['violations'].append({'page': rel, 'variant_actions': d['header_variant_actions']})
            entry = ['FB-4', 'this change set touched the page but the FB-3 yellow card was not '
                      f'cleared in the same stroke (merge now, or add tp-merge-defer + reason): '
                      f'variant actions={d["header_variant_actions"]}', 'FB-4']
            out['pages'].setdefault(rel, []).append(entry)
            print(f'  FB-4 VIOLATION {rel}: touched by this change set but the header-actions variant '
                  f'action {d["header_variant_actions"]} was not merged in the same stroke '
                  f'(clear it or annotate tp-merge-defer + reason)')
        # the account never keeps a reconciled page: violations are already
        # red, deferrals are digested; leaving them in the yellow list would
        # double-book the same debt across two ledgers.
    for v in fb4_detail['violations'] + fb4_detail['deferred']:
        fb3_account.pop(v['page'], None)

# ---- ratchet baseline: (page, rule-code) pairs known as legacy debt ----
baseline_path = Path(ARGS.baseline)
baseline = {}
if baseline_path.exists():
    try:
        loaded = json.loads(baseline_path.read_text(encoding='utf-8'))
        baseline = {k: set(v) for k, v in loaded.items()}
    except (OSError, ValueError):
        # A corrupt/missing baseline degrades to "no legacy debt recorded",
        # which only makes the gate stricter — never falsely green.
        baseline = {}

pending = {}
pages_new = {}
for rel, vios in out['pages'].items():
    bl = baseline.get(rel, set())
    new_v = [v for v in vios if v[0] not in bl]
    old_v = [v for v in vios if v[0] in bl]
    if old_v:
        pending[rel] = [[v[0], v[1]] for v in old_v]
    if new_v:
        pages_new[rel] = new_v
out['pending'] = pending
out['pages_new'] = pages_new
out['new_violations'] = sum(len(v) for v in pages_new.values())
out['pending_count'] = sum(len(v) for v in pending.values())
# FB-3 is an account, not a gate: stored for printing and JSON, never merged
# into pages_new / exit code (yellow card per the 10-22 merge-band ruling).
# Note: FB-4 reconciliation (when armed) removes touched-and-resolved pages
# from this account above, so the yellow list never double-books a debt that
# already rides the red channel or a declared deferral.
out['fb3_merge_candidates'] = fb3_account
# FB-4 detail: full reconciliation trace of the armed run (touched pages /
# violations / digested deferrals) so the gate's decisions stay auditable.
out['fb4_touch_reconcile'] = fb4_detail
# S-9 detail: disposition of every risky pane (red hits + digested exempt/
# fixed-view/absent panes) so the tab-sync account stays auditable per page.
out['s9_detail'] = s9_account_by_page
out['s12_detail'] = s12_detail
out['s13_detail'] = s13_detail
# SE-1 detail: every accounted select with its grade, line and the
# reason the candidate source cannot satisfy SE-1 statically, so the digestion
# batches can be ranked per page without re-reading sources.
out['se1_detail'] = se1_detail

out_path = Path(ARGS.out)
out_path.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
n = sum(len(v) for v in out['pages'].values())
print(f'globally defined layout classes={sorted(GLOBAL_DEFINED)}')
print(f'pages(with violations)={len(out["pages"])} violations={n} new(gate red)={out["new_violations"]} legacy pending(ratchet)={out["pending_count"]}')
from collections import Counter

cnt = Counter(v[0] for vs in out['pages'].values() for v in vs)
print('by rule:', dict(cnt))
# FB-3 yellow card: printable account only (never red, never affects exit code).
print(f'FB-3 merge band available but not applied (yellow card)={len(fb3_account)} pages (threshold --merge-max-controls={ARGS.merge_max_controls}):')
for rel, d in sorted(fb3_account.items()):
    print(f'  YELLOW {rel}: estimated condition controls={d["condition_controls"]} header variant actions={d["header_variant_actions"]}')
# FB-4 reconciliation line: only meaningful when the caller armed the gate.
if ARGS.diff_touch:
    print(f'FB-4 touched-page reconciliation: touched={len(fb4_detail["touched"])} named violations={len(fb4_detail["violations"])} '
          f'digested deferrals(tp-merge-defer)={len(fb4_detail["deferred"])}')
else:
    print('FB-4 touched-page reconciliation: disarmed (closeout must run --diff-touch auto; default = this change set was not reconciled)')
# S-9 account line: risky panes / red hits / digested exemptions site-wide.
_s9_hits = sum(len(d['hits']) for d in s9_account_by_page.values())
_s9_ex = sum(len(d.get('fixed_view_panes', []) or []) + len(d.get('exempt_panes', []) or [])
             + len(d.get('absent_panes', []) or []) for d in s9_account_by_page.values())
print(f'S-9 tab-scope gate: risky panes={sum(d["risky_panes"] for d in s9_account_by_page.values())} '
      f'red hits={_s9_hits} digested(fixed-view/exempt/honest-absent)={_s9_ex}')
for rel, d in sorted(s9_account_by_page.items()):
    for h in d['hits']:
        print(f'  S9 {rel}: pane={h["pane"]} {h["detail"]}')
# SE-1 account line: candidates are graded HARD (provably >10 entries)
# or SOFT (remote/store driven, cannot be quantified statically); both ride
# the ratchet, so only a select added after the baseline turns red.
_se1_hard = sum(1 for v in se1_detail.values() for r in v if r['grade'] == 'HARD')
_se1_soft = sum(1 for v in se1_detail.values() for r in v if r['grade'] == 'SOFT')
print(f'SE-1 selector-searchability gate: candidates={_se1_hard + _se1_soft} (HARD={_se1_hard} not statically quantifiable={_se1_soft})'
      f' files={len(se1_detail)} (exempt: data-se1-exempt/tp-se1-exempt + reason)')
for rel, rows in sorted(se1_detail.items(), key=lambda x: -len(x[1])):
    print(f'  SE1 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}[{r['grade']}]" for r in rows))
_s12n = sum(len(v) for v in s12_detail.values())
_s13n = sum(len(v) for v in s13_detail.values())
print(f'S-12 dialog popper clipping gate: sites={len(s12_detail)} hits={_s12n}')
for rel, rows in sorted(s12_detail.items()):
    print(f'  S12 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}" for r in rows))
print(f'S-13 dialog form-gap single-source gate: sites={len(s13_detail)} hits={_s13n}')
for rel, rows in sorted(s13_detail.items()):
    print(f'  S13 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}" for r in rows))
_s14n = sum(len(v) for v in s14_detail.values())
_s15n = sum(len(v) for v in s15_detail.values())
print(f'S-14 dialog size-tier gate: sites={len(s14_detail)} hits={_s14n} (exempt: tp-dialog-exempt + reason)')
for rel, rows in sorted(s14_detail.items(), key=lambda x: -len(x[1])):
    print(f'  S14 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}" for r in rows))
print(f'S-15 in-dialog shared-scroll-proxy gate: sites={len(s15_detail)} hits={_s15n}')
for rel, rows in sorted(s15_detail.items(), key=lambda x: -len(x[1])):
    print(f'  S15 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}" for r in rows))
_s16n = sum(len(v) for v in s16_detail.values())
print(f'S-16 resident-dock collision gate (pinned form, T622 slice 3): sites={len(s16_detail)} hits={_s16n}'
      ' (exempt: tp-dock-reserve-exempt + reason)')
for rel, rows in sorted(s16_detail.items(), key=lambda x: -len(x[1])):
    print(f'  S16 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}" for r in rows))
_s17n = sum(len(v) for v in s17_detail.values())
print(f'S-17 WeChat-shape composer gate (full-width input + grouped action row, T624): '
      'sites=%d hits=%d (exempt: tp-composer-shape-exempt + reason)' % (len(s17_detail), _s17n))
for rel, rows in sorted(s17_detail.items(), key=lambda x: -len(x[1])):
    print(f'  S17 {rel} ({len(rows)}): ' + '; '.join(f"L{r['line']}" for r in rows))
print('saved', out_path)

if ARGS.write_baseline:
    # FB-4 is excluded from baseline capture on purpose: the gate only arms
    # per-run through --diff-touch, and a (page, FB-4) pair written into the
    # ratchet would silently disarm the reconciliation forever.
    cap = {rel: sorted({v[0] for v in vios if v[0] != 'FB-4'}) for rel, vios in out['pages'].items()}
    cap = {rel: rules for rel, rules in cap.items() if rules}
    baseline_path.write_text(json.dumps(cap, ensure_ascii=False, indent=1, sort_keys=True), encoding='utf-8')
    print(f'baseline captured: {sum(len(v) for v in cap.values())} (page,rule) pairs -> {baseline_path}')
    sys.exit(0)

if out['new_violations']:
    for rel, vios in sorted(pages_new.items()):
        for v in vios:
            print(f'NEW {rel}: {v[0]} {v[1]}')
    sys.exit(1)
sys.exit(0)
