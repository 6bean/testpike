/**
 * global layout contract: no page may render the top-bar heading
 * a second time through an in-page .page-title heading whose resolved zh text
 * equals (or mutually contains) the route/menu titleKey value — the same
 * information on two channels is noise (2026-10-10 ruling, AN batch iron law).
 *
 * Mirrors the S-7 criterion of scripts/ui_audit_scan.py against the live tree:
 * - comparison source = disk truth (routes.ts + menuStructure.ts + zh-CN
 * locale + views/*.vue h1/h2 with class page-title), never a memory list;
 * - detail routes (meta carries parent — breadcrumb hosts the entity name)
 * and dynamic-title headings (interpolation instead of a static t('key'))
 * are legal channels and stay out of the comparison;
 * - an intentional keep needs the literal marker 'tp-title-dedup-exempt';
 * - the allowlist below is the frozen in-flight ledger (user's terminology
 * sweep touches these files concurrently — yielded to them);
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

import { routes } from '../../src/router/routes'
import { lookupLocale } from '../_localeLookup'
import zhCN from '../../src/utils/locales/zh-CN'

const SRC = join(__dirname, '..', '..', 'src')
const VIEWS = join(SRC, 'views')

// The walk lives in the shared spec helper; this suite's own contract narrows the result to a
// translated string, so the narrowing stays here rather than loosening the shared helper.
const lookup = (obj: unknown, dotted: string): string | undefined =>
  lookupLocale(obj, dotted) as string | undefined

/** path -> top-bar titleKey from route meta (recursively) — MainLayout reads
 * meta.titleKey for the header-title, so this is the authoritative channel.
 * Routes whose meta carries `parent` are detail pages (breadcrumb hosts the
 * entity name = legal single channel) and are collected separately. */
function routeTitleMap(list: readonly unknown[], acc: Map<string, string>) {
  for (const r of list) {
    const rec = r as { path?: string; meta?: Record<string, unknown>; children?: readonly unknown[]; component?: unknown }
    if (rec.path && rec.meta && typeof rec.meta.titleKey === 'string') {
      acc.set(rec.path, rec.meta.titleKey)
    }
    if (rec.children) routeTitleMap(rec.children, acc)
  }
  return acc
}

function detailPathSet(list: readonly unknown[], acc: Set<string>) {
  for (const r of list) {
    const rec = r as { path?: string; meta?: Record<string, unknown>; children?: readonly unknown[] }
    if (rec.path && rec.meta && 'parent' in rec.meta) acc.add(rec.path)
    if (rec.children) detailPathSet(rec.children, acc)
  }
  return acc
}

/** view-file (src/views/…​.vue relative path) -> route path, parsed from the
 * router source (component import statements) — routes carry lazy imports that
 * are not reachable through runtime introspection in a node vitest env. */
function fileRouteMap(): Map<string, string> {
  const src = readFileSync(join(SRC, 'router', 'routes.ts'), 'utf-8')
  const map = new Map<string, string>()
  let curPath: string | null = null
  for (const line of src.split('\n')) {
    const pm = line.match(/path:\s*'([^']+)'/)
    if (pm) curPath = pm[1]
    const cm = line.match(/import\(['"][^'"]*?views\/([\w/-]+\.vue)/)
    if (cm && curPath && !map.has(`views/${cm[1]}`)) map.set(`views/${cm[1]}`, curPath)
  }
  return map
}

function walkVue(dir: string, out: string[]) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walkVue(p, out)
    else if (name.endsWith('.vue')) out.push(p)
  }
}

describe('T411 S-7 双通道标题全局契约（新增页禁页内重复顶栏标题）', () => {
  it('盘上实扫：页内静态标题与顶栏面值相等/互含 = 仅限冻结在途账，新增违规即红', () => {
    const titles = routeTitleMap(routes as unknown as readonly unknown[], new Map<string, string>())
    const details = detailPathSet(routes as unknown as readonly unknown[], new Set<string>())
    const norm = (p?: string | null) => (p || '').replace(/^\//, '').toLowerCase()
    const fileRoute = fileRouteMap()
    // stage-4 mirror: routes outside MainLayout (meta.public — login and
    // the forced password-change page) have no top-bar channel, so the
    // in-page heading is the single source and stays out of the comparison.
    const routerSrc = readFileSync(join(SRC, 'router', 'routes.ts'), 'utf-8')
    const publicPaths = new Set(
      [...routerSrc.matchAll(/path:\s*'([^']+)'[\s\S]*?meta:\s*\{[^}]*\bpublic:/g)].map((m) => m[1]))
    const files: string[] = []
    walkVue(VIEWS, files)
    const violations: string[] = []
    for (const f of files.sort()) {
      const rel = relative(SRC, f).replace(/\\/g, '/')
      const text = readFileSync(f, 'utf-8')
      // stage-4 coverage fix: the comparison no longer requires the
      // literal class page-title — any page-level h1/h2 counts (block-section
      // headings whose class carries 'section' name a sub-region, not the
      // page, and stay out). Custom-class page titles escaping the old gate
      // was the known coverage gap this stroke closes.
      if (!/<h[12]/.test(text)) continue
      const rp = norm(fileRoute.get(rel))
      // detail pages (breadcrumb hosts the entity name) = legal channel
      if (rp && [...details].some((d) => norm(d) === rp)) continue
      if (rp && publicPaths.has(`/${rp}`)) continue
      if (text.includes('tp-title-dedup-exempt')) continue
      const meta = [...titles.entries()].find(([k]) => norm(k) === rp)?.[1]
      if (!meta) continue
      const top = lookup(zhCN, meta)
      if (!top) continue
      for (const m of text.matchAll(/<(h[12])([^>]*)>([\s\S]*?)<\/\1>/g)) {
        const cls = m[2].match(/class="([^"]*)"/)?.[1] ?? ''
        if (cls.includes('section') && !cls.includes('page-title')) continue
        const km = m[3].match(/\{\{\s*t\('([^']+)'\s*\)\s*\}\}/)
        if (!km) continue // dynamic entity name channel = legal
        const val = lookup(zhCN, km[1])
        if (!val) continue
        if (val === top || top.includes(val) || val.includes(top)) {
          violations.push(`${rel} 「${val}」 vs 顶栏「${top}」`)
        }
      }
    }
    // The frozen in-flight ledger (review/skill/tool, three pages) was paid
    // off with the R23 page-name single-source cleanup: zero tolerance now.
    expect(violations, `双通道标题页=${JSON.stringify(violations)}`).toEqual([])
  })

  it('清偿账归零：三页不得再渲染静态 page-title（防空壳回潮）', () => {
    const stillDirty = ['review/ReviewWorkbenchView.vue', 'skill/SkillMarketView.vue', 'tool/ToolMarketView.vue']
      .filter((p) => readFileSync(join(VIEWS, p), 'utf-8').match(/class="[^"]*page-title[^"]*"/))
    expect(stillDirty, `page-title 回潮页=${JSON.stringify(stillDirty)}`).toEqual([])
  })
})

/**
 * stage-4 RD-2 mirror contract (page-name single source, second gate):
 * a routed page template must not hang class="page-subtitle" directly —
 * descriptive subtitles restate the top-bar help chapter and were swept in
 * stage-3. The 11 legitimate keeps (rule G clause-3 segment chips, live data
 * chips, detail-page descriptors, block panels — ledger §K) carry the
 * file-level 'tp-subtitle-exempt' marker + reason. Mirrors the RD-2
 * criterion of scripts/ui_audit_struct_s6.py against the live tree.
 */
describe('T417 RD-2 页面副题直挂契约（保留=豁免注记，新增直挂即红）', () => {
  it('盘上实扫：路由页面 page-subtitle 直挂而无 tp-subtitle-exempt = 违规', () => {
    const fileRoute = fileRouteMap()
    const details = detailPathSet(routes as unknown as readonly unknown[], new Set<string>())
    const files: string[] = []
    walkVue(VIEWS, files)
    const violations: string[] = []
    for (const f of files.sort()) {
      const rel = relative(SRC, f).replace(/\\/g, '/')
      if (!rel.startsWith('views/')) continue
      if (!fileRoute.has(rel)) continue // only routed pages carry the page template
      // page-level boundary mirrors the S6 scanner: overlay components and
      // detail/dashboard pages keep their own heading vocabulary (ledger §K
      // items ⑥⑦ — detail-page descriptors stay without markers).
      if (/(__Detail|Detail|Dialog|Drawer|Panel)\.vue$/.test(rel) || rel.includes('Dashboard')) continue
      const rp = fileRoute.get(rel) || ''
      if ([...details].some((d) => d.replace(/^\//, '') === rp.replace(/^\//, ''))) continue
      const text = readFileSync(f, 'utf-8')
      const tpl = text.slice(text.indexOf('<template>'), text.indexOf('<style') > 0 ? text.indexOf('<style') : text.length)
      if (!/class="[^"]*\bpage-subtitle\b/.test(tpl)) continue
      if (text.includes('tp-subtitle-exempt')) continue
      violations.push(rel)
    }
    expect(violations, `page-subtitle 直挂无注记页=${JSON.stringify(violations)}`).toEqual([])
  })
})
