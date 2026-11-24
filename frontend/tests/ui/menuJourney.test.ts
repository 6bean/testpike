
import { describe, it, expect } from 'vitest'
import { journeyGroups, allMenuLeaves } from '../../src/config/menuStructure'
import zhCN from '../../src/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '../../src/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale


const BASELINE_PATHS = [
  '/dashboard', '/cases', '/review',
  // AN-5 retired /documents into /extraction; AL batch split /requirements back out
  // tabs and /suites into the /cases tab; the four market/plugin/MCP leaves
  // retired into /skills, /tools and /extensions. Old addresses still resolve
  // via functional redirects — the snapshot moves with the leaves.
  '/extraction', '/requirements', '/ai/generate',
  '/execution', '/execution/pools',
  '/appauto/devices', '/appauto/reservations', '/appauto/healing', '/appauto/samples',
  '/appauto/engines', '/appauto/packages', '/appauto/popup-rules',
  // The element-library leaf (W5 app-automation phase 2) joined the device
  // group with its help chapter — the snapshot moves with the leaf.
  '/appauto/elements',
  // The flow-canvas leaf (read-only step-tree projection, 11-11 ecef7119c)
  // joined the appauto group with its help chapter — the snapshot moves with
  // the leaf (precedent above; this line lands late because the wiring commit
  // missed the contract update, R41 net-tree red = assertion lag).
  '/appauto/flow-canvas',
  '/cluster/upgrade', '/plans', '/plans/board', '/batches',
  // The cross-project journey orchestration leaf joined beside /batches —
  // the snapshot moves with the leaf.
  '/journeys',
  // The program set console leaf (project grouping) rides in the system
  // group — the snapshot moves with the leaf.
  // The quality dashboard leaf (Y-wave results group) also rides with the
  // leaves — the snapshot was corrected in-batch when the drift surfaced.
  '/program-dashboard',
  '/programs',
  '/defects', '/monitor', '/perf',
  // GB-2: the project-wide load-run ledger joined as a leaf beside the
  // workbench — the snapshot moves with the leaf.
  '/perf/runs',
  // AN-3 half A: /coverage leaf retired into the /report tab (AN merge
  // ruling — snapshot moves with the leaf, redirect keeps the path resolving).
  // AN-3 half B: /report-center leaf retired into the /report export tab
  // (same ruling — the ledger/drawer/export anchors ride the tab).
  '/report', '/test-report', '/cost',
  '/tags', '/data-factories', '/explore', '/api-test', '/role-bindings', '/mock/spaces', '/asset-pool',
  // 11-16 user ruling: credential vault + login-state ledger are asset leaves;
  // they moved from the appauto group to data & assets (paths unchanged).
  '/appauto/credentials', '/login-states',
  // Baseline correction (stale-assertion class, not introduced here): the mock
  // endpoint inventory /mock/endpoints joined without updating this list (red on
  // HEAD); the new /api-inventory leaf rides along so the baseline equals the live
  // scan. Same class again: the contract change-review queue
  // /api-spec-change-analysis joined (route + menu leaf) without the snapshot
  // moving — aligned to live scan.
  // AN-4: /mock/docs, /mock/scenarios, /mock/recording and /mock/inspector
  // retired as menu leaves (merged into the per-space workbench as tabs) — the
  // snapshot moves with the leaves, the old addresses still resolve via redirect.
  '/mock/endpoints', '/api-inventory',
  '/api-spec-change-analysis',
  // Baseline moves with the leaf (same stale-assertion class): the web
  // element ledger /web-elements joined the design group.
  '/web-elements',
  '/skills', '/tools', '/extensions',
  // stage 3: the standalone chat page leaf joined the AI group.
  '/ai/chat',
  '/knowledge', '/training-samples', '/prompt-templates', '/judge/report', '/judge/evalset', '/judge/runs',
  '/projects', '/api-tokens', '/delete-requests',
  '/roles', '/users', '/configs', '/llm-configs', '/audit-logs', '/scheduler-health',
  // : dead-letter ops leaf rides along with the snapshot.
  '/mq-dead-letter',
]

describe('R16 折叠深度封顶闸（全局裁决：菜单折叠深度全站上限两级=组→叶）', () => {
  it('任何一级组不得携带三级子组（subGroups 存在=违规，禁回潮）', () => {
    const offenders = journeyGroups
      .filter((g) => (g as { subGroups?: unknown }).subGroups !== undefined)
      .map((g) => g.index)
    expect(offenders, `出现三级折叠的组：${offenders.join(', ')}`).toEqual([])
  })
})

function groupBy(index: string) {
  return journeyGroups.find((g) => g.index === index)
}
function leafOf(path: string) {
  return allMenuLeaves.find((l) => l.path === path)
}
function groupOf(path: string) {
  return journeyGroups.find((g) => (g.items ?? []).some((i) => i.path === path))
}

describe('T343 方案甲（修订二）+R16 两级化（T403=APP 自动化独立一级组）结构契约', () => {
  it('一级菜单=九旅程组，顺序=工作台/设计用例/AI 能力/执行测试/APP 自动化/接口仿真/结果与缺陷/造数与资产/系统管理', () => {
    expect(journeyGroups.map((g) => g.index)).toEqual([
      'workbench', 'design', 'aiCapabilities', 'execute', 'appauto', 'mock', 'results', 'dataAssets', 'system',
    ])
  })

  it('九组名 titleKey 双轨命中且中文面值=方案甲修订二+AG 组定名+AN 组定名', () => {
    const zh = { workbench: '工作台', design: '设计用例', aiCapabilities: 'AI 能力', execute: '执行测试', appauto: 'APP 自动化', mock: '接口仿真', results: '结果与缺陷', dataAssets: '造数与资产', system: '系统管理' }
    for (const g of journeyGroups) {
      expect(lookup(zhCN, g.titleKey), `${g.titleKey} zh 缺`).toBe((zh as Record<string, string>)[g.index])
      expect(typeof lookup(enUS, g.titleKey), `${g.titleKey} en 缺`).toBe('string')
    }
  })

  it('path 零改动：叶子 path 全集与重构前基线逐项相等且无重复', () => {
    const paths = allMenuLeaves.map((l) => l.path)
    expect([...paths].sort()).toEqual([...BASELINE_PATHS].sort())
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('所有叶子 titleKey/icon/dataTest 齐备且 titleKey 双轨命中', () => {
    for (const l of allMenuLeaves) {
      expect(l.titleKey, `${l.path} 缺 titleKey`).toMatch(/^layout\./)
      expect(l.icon, `${l.path} 缺 icon`).toBeTruthy()
      expect(l.dataTest, `${l.path} 缺 dataTest`).toBeTruthy()
      expect(lookup(zhCN, l.titleKey), `${l.titleKey} zh 缺`).toBeTruthy()
      expect(lookup(enUS, l.titleKey), `${l.titleKey} en 缺`).toBeTruthy()
    }
  })

  it('设计用例组=纯用例生产旅程（文档提取→需求台账→AI 生成→用例→评审；AL 批需求台账独立成叶）', () => {
    const design = groupBy('design')
    expect(design).toBeTruthy()
    const paths = design!.items!.map((i) => i.path)
    expect([...paths].sort()).toEqual(
      ['/ai/generate', '/cases', '/extraction', '/requirements', '/review', '/web-elements'].sort())
    expect(paths[0]).toBe('/extraction')
  })

  it('AI 能力组（T407 净 6 件）=知识库/提示词/SKILL（含市场 tab）/工具（含市场 tab）/扩展集成/训练样本', () => {
    const ai = groupBy('aiCapabilities')
    expect(ai).toBeTruthy()
    const paths = ai!.items!.map((i) => i.path)
    expect(paths).toEqual([
      '/ai/chat', '/knowledge', '/prompt-templates',
      '/skills', '/tools', '/extensions', '/training-samples',
    ])
    expect(ai!.icon).toBe('MagicStick')
  })

  it('执行测试组（R16 两级化后）=计划/批次（含定时触发 tab）/旅程编排/监控/进程并入执行/API/压测直属 11 叶（T404 净减：/process→/execution tab、/schedule/fires→/batches tab；T381 GB-2 净增 /perf/runs 台账叶）', () => {
    const execute = groupBy('execute')
    expect(execute).toBeTruthy()
    const topPaths = (execute!.items ?? []).map((i) => i.path)
    expect(topPaths.sort()).toEqual(
      ['/api-inventory', '/api-spec-change-analysis', '/api-test', '/batches', '/execution',
        '/journeys',
        '/monitor', '/perf', '/perf/runs', '/plans', '/plans/board'].sort())
    expect(topPaths.some((p) => p.startsWith('/appauto'))).toBe(false)
  })

  it('R16 两级化：APP 自动化独立一级组（icon=Cellphone），9 叶平铺直属按 日常四件→资产五件 序（11-16 凭据/登录态移入造数与资产）', () => {
    const appauto = groupBy('appauto')
    expect(appauto).toBeTruthy()
    expect(appauto!.icon).toBe('Cellphone')
    expect(appauto!.titleKey).toBe('layout.journeyAppauto')
    expect((appauto!.items ?? []).map((i) => i.path)).toEqual([
      '/appauto/devices', '/appauto/reservations', '/appauto/engines', '/appauto/healing',
      '/appauto/packages', '/appauto/elements', '/appauto/flow-canvas',
      '/appauto/popup-rules', '/appauto/samples',
    ])
    // Permission keys ride up with the leaves unchanged: engine center keeps its
    // own key (the other appauto leaves stay member-visible, no menu perm); the
    // login-state ledger's session read key travels with it into dataAssets.
    expect(leafOf('/appauto/engines')!.perm).toBe('perm:appauto:engine:view')
    expect(leafOf('/login-states')!.perm).toBe('perm:session:read')
  })

  it('报障刀维持：一级组标题汉字数上界 ≤6（防撑爆行宽箭头错位；10-10 原判据语义=汉字计数，英文/数字/空格不计）', () => {
    const cjkCount = (s: string) => (s.match(/[\u4e00-\u9fff]/g) ?? []).length
    const overlong: string[] = []
    for (const g of journeyGroups) {
      const zh = String(lookup(zhCN, g.titleKey) ?? '')
      if (cjkCount(zh) > 6) overlong.push(`${g.index}: ${zh}`)
    }
    expect(overlong, `组标题超 6 汉字：${overlong.join(' | ')}`).toEqual([])
  })

  it('结果与缺陷组=报告（含覆盖矩阵/导出与快照 tab）/测试报告/缺陷/评测器（T405 净减 2 叶：/coverage 与 /report-center→/report tab）', () => {
    const paths = (groupBy('results')?.items ?? []).map((i) => i.path)
    expect([...paths].sort()).toEqual(
      ['/defects', '/judge/evalset', '/judge/report', '/judge/runs', '/program-dashboard', '/report', '/test-report'].sort())
  })

  it('造数与资产组=标签/数据工厂/探索/角色绑定+凭据保险箱/登录态账本（11-16 用户口令归组）', () => {
    const paths = (groupBy('dataAssets')?.items ?? []).map((i) => i.path)
    expect([...paths].sort()).toEqual(
      ['/appauto/credentials', '/asset-pool', '/data-factories', '/explore',
        '/login-states', '/role-bindings', '/tags'].sort())
    expect(leafOf('/login-states')!.perm).toBe('perm:session:read')
  })

  it('系统管理组=低频运维面+治理三叶并入（定案 9：无权限键叶子成员可见）+成本/执行器池/升级中心迁入', () => {
    const paths = (groupBy('system')?.items ?? []).map((i) => i.path)
    // AN-6: /usage-calibration retired as a leaf — it rides the /cost
    // page as a perm-gated tab (tab gate mirrors the route key, R24 face ④).
    for (const p of ['/projects', '/api-tokens', '/delete-requests', '/roles', '/users', '/configs',
      '/llm-configs', '/audit-logs', '/scheduler-health', '/cost',
      '/execution/pools', '/cluster/upgrade']) {
      expect(paths, `系统管理缺 ${p}`).toContain(p)
    }
    for (const p of ['/projects', '/api-tokens', '/delete-requests', '/cost']) {
      expect(leafOf(p)!.perm, `${p} 应为无权限键全员面`).toBeUndefined()
    }
    expect(leafOf('/roles')!.perm).toBe('perm:system:role-manage')
    expect(leafOf('/execution/pools')!.perm).toBe('perm:project:executor-pool')
    expect(leafOf('/cluster/upgrade')!.perm).toBe('perm:cluster:manifest:view')
  })

  it('工作台组=仪表盘直达', () => {
    expect((groupBy('workbench')?.items ?? []).map((i) => i.path)).toEqual(['/dashboard'])
  })

  it('权限键单源不漂移：迁移后各叶子 perm 与重构前同码', () => {
    // : /process leaf retired (P-1 merge into /execution tab); the key
    // perm:exec:task-cancel now rides the tab visibility gate in the page.
    expect(leafOf('/process'), 'T404 后 /process 不再是菜单叶').toBeUndefined()
    // half A: /coverage leaf retired into the /report tab. It carried no
    // perm key before the merge (backend single-point guard), so the tab is
    // visible exactly like the host page — zero permission semantics change.
    expect(leafOf('/coverage'), 'T405 后 /coverage 不再是菜单叶').toBeUndefined()
    // : /plugins retired into the /extensions host — per the ledger ruling
    // the merged leaf carries NO menu perm (attaching the plugin key to
    // /extensions would narrow the MCP visible domain = permission-surface
    // drift). The plugin key now rides the in-page tab gate inside the
    // extensions host (hasPerm precedent = MockSpaceWorkbenchView canTraffic).
    expect(leafOf('/plugins'), 'T407 后 /plugins 不再是菜单叶').toBeUndefined()
    expect(leafOf('/extensions')!.perm, '/extensions 合并叶禁带菜单权限键').toBeUndefined()
    expect(leafOf('/knowledge')!.perm).toBe('perm:knowledge:mcp-manage')
    expect(leafOf('/judge/report')!.perm).toBe('perm:judge:run:view')
    expect(leafOf('/training-samples')!.perm).toBe('perm:platform:data-mask')
    expect(leafOf('/prompt-templates')!.perm).toBe('perm:feedback:model-update')
    expect(leafOf('/configs')!.perm).toBe('perm:system:system-config')
    expect(leafOf('/llm-configs')!.perm).toBe('perm:system:llm-config')
    expect(leafOf('/audit-logs')!.perm).toBe('perm:detail:audit-view')
    expect(leafOf('/users')!.perm).toBe('perm:system:user-manage')
  })
})
