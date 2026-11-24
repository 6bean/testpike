
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { routes } from '../../src/router/routes'
import zhCN from '../../src/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '../../src/utils/locales/en-US'

interface RouteMetaSlice {
  path?: string
  meta?: Record<string, unknown>
  children?: RouteMetaSlice[]
}

function flatten(list: RouteMetaSlice[]): RouteMetaSlice[] {
  const out: RouteMetaSlice[] = []
  for (const r of list) {
    out.push(r)
    if (r.children) out.push(...flatten(r.children))
  }
  return out
}

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('T238 P2 路由标题键骨架', () => {
  const titled = flatten(routes as RouteMetaSlice[]).filter((r) => r.meta)

  it('路由定义件存在且全部带 meta 的路由有 titleKey、无硬中文 title', () => {
    expect(titled.length).toBeGreaterThanOrEqual(40)
    for (const r of titled) {
      expect(r.meta!.title, `route ${r.path} 仍有 meta.title 硬中文`).toBeUndefined()
      expect(typeof r.meta!.titleKey, `route ${r.path} 缺 meta.titleKey`).toBe('string')
    }
  })

  it('每个 meta.titleKey 在 zh-CN 语言包命中（禁漏键）', () => {
    for (const r of titled) {
      const key = r.meta!.titleKey as string
      expect(lookup(zhCN, key), `zh-CN 缺键 ${key}（route ${r.path}）`).not.toBeUndefined()
    }
  })

  it('en-US 与 zh-CN 在 layout 域键集一致（P2 新增键必须双镜像）', () => {
    const zhKeys = Object.keys(zhCN.layout).sort()
    const enKeys = Object.keys(enUS.layout).sort()
    expect(enKeys).toEqual(zhKeys)
  })

  
  function tKeysInFile(path: string): Set<string> {
    const src = readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf-8')
    const keys = new Set<string>()
    for (const m of src.matchAll(/(?<![\w$])t\(\s*'([\w.]+)'\s*\)/g)) keys.add(m[1])
    for (const m of src.matchAll(/titleKey:\s*'([\w.]+)'/g)) keys.add(m[1])
    return keys
  }

  it('MainLayout 源码内 t()/titleKey 字面量全部命中 zh-CN（静态扫描闸）', () => {
    const keys = tKeysInFile('../../src/layouts/MainLayout.vue')
    expect(keys.size).toBeGreaterThan(20)
    for (const k of keys) {
      expect(lookup(zhCN, k), `MainLayout 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
    }
  })

 
  function stripComments(src: string): string {
    return src
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '')
  }

  function hardChineseLines(path: string): string[] {
    const src = stripComments(readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf-8'))
    return src.split('\n').filter((line) => /[\u4e00-\u9fff]/.test(line))
  }

  it('MainLayout 模板+脚本层零硬中文（P2b 收口闸：中文一律 t()）', () => {
    expect(hardChineseLines('../../src/layouts/MainLayout.vue')).toEqual([])
  })

  it('router/index.ts 守卫层零硬中文（permDenied 走 i18n 插值）', () => {
    expect(hardChineseLines('../../src/router/index.ts')).toEqual([])
  })

 
  it('T239 LangSwitch 件在位且 MainLayout 已接线', () => {
    const comp = readFileSync(fileURLToPath(new URL('../../src/components/LangSwitch.vue', import.meta.url)), 'utf-8')
    expect(comp).toContain('data-test="lang-switch"')
    const layout = readFileSync(fileURLToPath(new URL('../../src/layouts/MainLayout.vue', import.meta.url)), 'utf-8')
    expect(layout).toContain("import LangSwitch from '@/components/LangSwitch.vue'")
    expect(layout).toContain('<LangSwitch />')
    expect(lookup(zhCN, 'layout.langZh')).toBe('简体中文')
    expect(lookup(enUS, 'layout.langZh')).toBe('简体中文')
    expect(lookup(zhCN, 'layout.langEn')).toBe('English')
    expect(lookup(enUS, 'layout.langEn')).toBe('English')
    expect(String(lookup(enUS, 'layout.langSwitchTip'))).toMatch(/^[A-Za-z ]+$/)
  })

 
  const P3_VIEWS = [
    '../../src/views/project/ProjectDetailView.vue',
    '../../src/views/tool/ToolListView.vue',
    '../../src/views/case/CaseDetailView.vue',
  ]
  for (const v of P3_VIEWS) {
    it(`P3 视图 ${v.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(v)).toEqual([])
    })
    it(`P3 视图 ${v.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键）`, () => {
      const keys = tKeysInFile(v)
      expect(keys.size).toBeGreaterThan(20)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${v} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }

 
  const P4_VIEWS = [
    '../../src/views/system/ConfigListView.vue',
    '../../src/views/system/LlmConfigListView.vue',
    '../../src/views/system/AuditLogView.vue',
    '../../src/views/token/ApiTokenView.vue',
    '../../src/views/user/UserListView.vue',
    '../../src/views/rbac/RoleListView.vue',
    '../../src/views/system/UsageCalibrationView.vue',
  ]
  for (const v of P4_VIEWS) {
    it(`P4 视图 ${v.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(v)).toEqual([])
    })
    it(`P4 视图 ${v.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键）`, () => {
      const keys = tKeysInFile(v)
      expect(keys.size).toBeGreaterThan(20)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${v} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }

 
  const P5_VIEWS: Array<[string, number]> = [
    ['../../src/views/skill/SkillListView.vue', 20],
    ['../../src/views/skill/SkillRecycleDrawer.vue', 8],
    ['../../src/views/skill/SkillMarketView.vue', 15],
    ['../../src/views/tool/ToolMarketView.vue', 15],
    ['../../src/views/mcp/McpServerView.vue', 30],
    ['../../src/views/plugin/PluginListView.vue', 12],
    ['../../src/views/knowledge/KnowledgeDocListView.vue', 40],
  ]
  for (const [v, minKeys] of P5_VIEWS) {
    it(`P5 视图 ${v.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(v)).toEqual([])
    })
    it(`P5 视图 ${v.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键）`, () => {
      const keys = tKeysInFile(v)
      expect(keys.size).toBeGreaterThan(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${v} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }

  /**
 * profile/PwdExpired → DeleteRequest/StepReorder/CaseRecycle/ArchivedProject →
 */
  const P6_VIEWS: Array<[string, number]> = [
    ['../../src/views/notification/NotificationCenterView.vue', 20],
    ['../../src/views/notification/SubscriptionSettingsPanel.vue', 8],
    ['../../src/views/profile/ProfileView.vue', 20],
    ['../../src/views/PwdExpiredView.vue', 10],
    ['../../src/views/case/DeleteRequestView.vue', 20],
    ['../../src/views/case/CaseRecycleDrawer.vue', 15],
    ['../../src/views/case/StepReorderPanel.vue', 8],
    ['../../src/views/project/ArchivedProjectDialog.vue', 7],
    ['../../src/views/ai/AiGenerateView.vue', 50],
  ]
  for (const [v, minKeys] of P6_VIEWS) {
    it(`P6 视图 ${v.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(v)).toEqual([])
    })
    it(`P6 视图 ${v.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键）`, () => {
      const keys = tKeysInFile(v)
      expect(keys.size).toBeGreaterThan(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${v} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }

  it('P6b profile 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).profile).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).profile).sort()
    expect(zhKeys.length).toBeGreaterThan(30)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P6c 四域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    for (const domain of ['deleteRequest', 'caseRecycle', 'stepReorder', 'archivedProject']) {
      const zhKeys = Object.keys((zhCN as Record<string, object>)[domain] ?? {}).sort()
      const enKeys = Object.keys((enUS as Record<string, object>)[domain] ?? {}).sort()
      expect(zhKeys.length, `zh-CN 缺 ${domain} 域`).toBeGreaterThan(5)
      expect(enKeys, `${domain} 域 zh/en 键集漂移`).toEqual(zhKeys)
    }
  })

  it('P6d aiGenerate 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).aiGenerate ?? {}).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).aiGenerate ?? {}).sort()
    expect(zhKeys.length, 'zh-CN 缺 aiGenerate 域').toBeGreaterThan(40)
    expect(enKeys, 'aiGenerate 域 zh/en 键集漂移').toEqual(zhKeys)
  })

  
  const P6E_COMPONENTS: Array<[string, number]> = [
    ['../../src/components/StepTree.vue', 30],
    ['../../src/components/GlobalSearchDialog.vue', 3],
    ['../../src/components/TraceChainPanel.vue', 2],
    ['../../src/components/StepDragList.vue', 3],
    // favorite button keeps exactly the two aria labels (star/unstar)
    ['../../src/components/FavoriteButton.vue', 1],
    ['../../src/components/RowActions.vue', 2],
    ['../../src/components/EmptyState.vue', 0],
    ['../../src/components/MarkdownPreview.vue', 0],
    ['../../src/components/ExecTaskResultView.vue', 1],
    ['../../src/components/FileUploadDialog.vue', 0],
    ['../../src/components/ImportDialog.vue', 0],
  ]
  for (const [c, minKeys] of P6E_COMPONENTS) {
    it(`P6e 组件 ${c.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(c)).toEqual([])
    })
    it(`P6e 组件 ${c.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键）`, () => {
      const keys = tKeysInFile(c)
      expect(keys.size).toBeGreaterThan(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${c} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }

  it('P6e stepTree/components 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    for (const domain of ['stepTree', 'components']) {
      const zhKeys = Object.keys((zhCN as Record<string, object>)[domain] ?? {}).sort()
      const enKeys = Object.keys((enUS as Record<string, object>)[domain] ?? {}).sort()
      expect(zhKeys.length, `zh-CN 缺 ${domain} 域`).toBeGreaterThan(3)
      expect(enKeys, `${domain} 域 zh/en 键集漂移`).toEqual(zhKeys)
    }
  })

  
  const P7A_FILES: Array<[string, number]> = [
    ['../../src/config/actionVocab.ts', 90],
    ['../../src/utils/stepFormField.ts', 6],
    ['../../src/utils/scriptSource.ts', 12],
    ['../../src/utils/conditionPrecheck.ts', 8],
  ]
  
  function vocabKeysInFile(path: string): Set<string> {
    const src = readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf-8')
    const keys = new Set<string>()
    for (const m of src.matchAll(/'(vocab\.[\w.]+)'/g)) keys.add(m[1])
    return keys
  }
  for (const [f, minKeys] of P7A_FILES) {
    it(`P7a ${f.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(f)).toEqual([])
    })
    it(`P7a ${f.split('/').pop()} vocab.* 键字面量全部命中 zh+en（禁漏键，计数下限 ${minKeys}）`, () => {
      const keys = vocabKeysInFile(f)
      expect(keys.size, `${f} vocab 键字面量不足 ${minKeys}（现状 ${keys.size}）`).toBeGreaterThanOrEqual(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${f} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
        expect(lookup(enUS, k), `${f} 引用 en-US 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }

  
  function flattenDomainKeys(obj: unknown, prefix = ''): string[] {
    if (obj === null || typeof obj !== 'object') return prefix ? [prefix] : []
    const out: string[] = []
    for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
      out.push(...flattenDomainKeys(v, prefix ? `${prefix}.${k}` : k))
    }
    return out
  }
  it('P7a vocab 域键集 zh/en 一致（嵌套展平比对，禁英文态缺键回落中文）', () => {
    const zhKeys = flattenDomainKeys((zhCN as Record<string, object>).vocab ?? {}).sort()
    const enKeys = flattenDomainKeys((enUS as Record<string, object>).vocab ?? {}).sort()
    expect(zhKeys.length, 'zh-CN 缺 vocab 域').toBeGreaterThan(50)
    expect(enKeys, 'vocab 域 zh/en 键集漂移').toEqual(zhKeys)
  })

  
  const P7B_FILES: Array<[string, number]> = [
    ['../../src/api/ai.ts', 5],
    ['../../src/api/appauto.ts', 8],
    ['../../src/api/case.ts', 22],
    ['../../src/api/dataFactory.ts', 9],
    ['../../src/api/defect.ts', 4],
    ['../../src/api/defectLibrary.ts', 8],
    ['../../src/api/document.ts', 2],
    ['../../src/api/executor.ts', 14],
    ['../../src/api/favorite.ts', 3],
    ['../../src/api/mcpServer.ts', 3],
    ['../../src/api/plan.ts', 4],
    ['../../src/api/project.ts', 3],
    ['../../src/api/report.ts', 4],
    ['../../src/api/requirement.ts', 4],
    ['../../src/api/suites.ts', 2],
    ['../../src/api/trace.ts', 8],
    ['../../src/api/request.ts', 8],
    ['../../src/api/import.ts', 2],
    ['../../src/api/user.ts', 1],
    ['../../src/api/reportCenter.ts', 1],
    ['../../src/api/testReport.ts', 1],
  ]
  
  function dictKeysInFile(path: string): Set<string> {
    const src = readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf-8')
    const keys = new Set<string>()
    for (const m of src.matchAll(/'((?:dict|http)\.[\w.]+)'/g)) keys.add(m[1])
    return keys
  }
  for (const [f, minKeys] of P7B_FILES) {
    it(`P7b api ${f.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(f)).toEqual([])
    })
    it(`P7b api ${f.split('/').pop()} dict.*/http.* 键字面量全部命中 zh+en（禁漏键，计数下限 ${minKeys}）`, () => {
      const keys = dictKeysInFile(f)
      expect(keys.size, `${f} dict 键字面量不足 ${minKeys}（现状 ${keys.size}）`).toBeGreaterThanOrEqual(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${f} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
        expect(lookup(enUS, k), `${f} 引用 en-US 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }
  it('P7b dict 域键集 zh/en 一致（嵌套展平比对，禁英文态缺键回落中文）', () => {
    const zhKeys = flattenDomainKeys((zhCN as Record<string, object>).dict ?? {}).sort()
    const enKeys = flattenDomainKeys((enUS as Record<string, object>).dict ?? {}).sort()
    expect(zhKeys.length, 'zh-CN 缺 dict 域').toBeGreaterThan(100)
    expect(enKeys, 'dict 域 zh/en 键集漂移').toEqual(zhKeys)
  })
  it('P7b http 域键集 zh/en 一致（request.ts 拦截器错误文案双镜像）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).http ?? {}).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).http ?? {}).sort()
    expect(zhKeys.length, 'zh-CN 缺 http 域').toBeGreaterThan(8)
    expect(enKeys, 'http 域 zh/en 键集漂移').toEqual(zhKeys)
  })

  
  const P7C_FILES: Array<[string, number]> = [
    ['../../src/views/DashboardView.vue', 20],
    ['../../src/views/defect/DefectListView.vue', 60],
    ['../../src/views/review/ReviewWorkbenchView.vue', 30],
    ['../../src/views/suite/SuiteListView.vue', 40],
    ['../../src/views/datafactory/DataFactoryListView.vue', 60],
    ['../../src/views/tag/TagListView.vue', 25],
    ['../../src/views/coverage/CoverageMatrixView.vue', 15],
    ['../../src/views/review/ReviewContextPanel.vue', 5],
    ['../../src/views/case/ExcellentStarButton.vue', 4],
    ['../../src/views/ai/DocumentListView.vue', 25],
    ['../../src/views/LoginView.vue', 8],
  ]
  for (const [f, minKeys] of P7C_FILES) {
    it(`P7c 视图 ${f.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(f)).toEqual([])
    })
    it(`P7c 视图 ${f.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键，计数下限 ${minKeys}）`, () => {
      const keys = tKeysInFile(f)
      expect(keys.size, `${f} t() 键不足 ${minKeys}（现状 ${keys.size}）`).toBeGreaterThanOrEqual(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${f} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }
  it('P7c 新键 zh/en 双镜像（common/coverage/dataFactory/suite/tag/review + dict.reviewLog）', () => {
    const pairs: Array<[string, string]> = [
      ['common.enterProject', '进入项目'],
      ['common.selectProject', '选择项目'],
      ['coverageMatrix.legendCovered', '已覆盖（点击直达用例）'],
      ['coverageMatrix.legendUncovered', '未覆盖（黄色行，格内点击或行尾按钮直达 AI 生成）'],
      ['coverageMatrix.legendColumnHint', '列 = 用例编号，hover 列头查看用例标题'],
      ['coverageMatrix.cellTitleHint', '{caseNo} {title}（点击查看覆盖用例）'],
      ['dataFactory.poolTypes.account', '账号'],
      ['dataFactory.poolKeyWriteFail', '造数内容无法写入数据键名'],
      ['suite.runSkippedArchived', '；{n} 条已归档/删除用例自动跳过'],
      ['tag.nameRequired', '请输入标签名称'],
      ['tag.entityName', '标签「{name}」'],
      ['review.sortUnsupported', '该列暂不支持排序'],
    ]
    for (const [k, zhVal] of pairs) {
      const zv = lookup(zhCN, k)
      expect(zv, `zh-CN 缺键 ${k}`).not.toBeUndefined()
      expect(String(zv), `${k} zh 值漂移`).toBe(zhVal)
      const ev = lookup(enUS, k)
      expect(ev, `en-US 缺键 ${k}`).not.toBeUndefined()
      for (const ph of ['{n}', '{name}', '{caseNo}', '{title}']) {
        if (String(zv).includes(ph)) expect(String(ev), `${k} en 缺插值 ${ph}`).toContain(ph)
      }
    }
    for (const a of ['pass', 'revise', 'reject']) {
      expect(lookup(zhCN, `dict.reviewLog.${a}`), `zh 缺 dict.reviewLog.${a}`).not.toBeUndefined()
      expect(lookup(enUS, `dict.reviewLog.${a}`), `en 缺 dict.reviewLog.${a}`).not.toBeUndefined()
    }
  })
  
  const P7C2_FILES: Array<[string, number]> = [
    ['../../src/views/plan/PlanListView.vue', 48], // floor 50→48: two literals moved into shared pieces (column-config + helper walk); all 48 live keys still assert zh-CN hits, and the hard-Chinese sweep keeps any copy-from-locale drift red
    ['../../src/views/plan/PlanDetailView.vue', 70],
    ['../../src/views/batch/BatchListView.vue', 48],
    ['../../src/views/batch/BatchDetailView.vue', 63],
    ['../../src/views/execution/ExecutionCenterView.vue', 40],
    ['../../src/views/report/TestReportListView.vue', 52],
    ['../../src/views/report/ReportView.vue', 60],
  ]
  for (const [f, minKeys] of P7C2_FILES) {
    it(`P7c2 视图 ${f.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(f)).toEqual([])
    })
    it(`P7c2 视图 ${f.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键，计数下限 ${minKeys}）`, () => {
      const keys = tKeysInFile(f)
      expect(keys.size, `${f} t() 键不足 ${minKeys}（现状 ${keys.size}）`).toBeGreaterThanOrEqual(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${f} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }
  it('P7c2 新键 zh/en 双镜像（plan/common/batch/exec/testReport/dict.batch.status）', () => {
    const pairs: Array<[string, string]> = [
      ['common.startDate', '开始日期'],
      ['common.endDate', '结束日期'],
      ['plan.entityName', '计划「{name}」'],
      ['plan.nameRequired', '请输入计划名称'],
      ['plan.deleteForceHint', '删除将级联清理执行批次与任务，且不可恢复。确定继续吗？'],
      ['plan.progressSummary', '{total} / 通过 {passed} / 失败 {failed} / 未执行 {notRun}'],
      ['plan.envHealthAbnormal', '环境「{name}」健康检查异常：{msg}'],
      ['plan.envHealthNoReason', '未记录原因'],
      ['plan.envHealthTitle', '环境健康警告'],
      ['plan.envHealthConfirm', '{reasons}，仍要执行？'],
      ['plan.runAnyway', '仍要执行'],
      ['plan.skipNoReview', '{n} 条未通过评审用例'],
      ['plan.skipFlakyIsolated', '{n} 条不稳定隔离用例'],
      ['plan.skipAlertTitle', '{action}：已跳过 {head}'],
      ['plan.statusFallback', '状态{status}'],
      ['batch.priorityPlaceholder', '优先级'],
      ['batch.pickedCount', '已勾选 {n} 条'],
      ['batch.colCaseNo', '编号'],
      ['batch.colTitle', '标题'],
      ['batch.templateNameSuffix', '{name}模板'],
      ['batch.flakyIsolatedTag', '隔离'],
      ['exec.caseLink', '用例 #{id}'],
      ['exec.loadTasksFail', '执行器任务加载失败'],
      ['exec.loadExecutorsFail', '执行器列表加载失败'],
      ['exec.loadTrialTasksFail', '试运行任务加载失败'],
      ['exec.loadTaskDetailFail', '任务详情加载失败'],
      ['testReport.statTotal', '总用例'],
      ['testReport.statNotRun', '未执行'],
      ['testReport.statSkipped', '跳过'],
      ['testReport.statUnstableSkipped', '不稳定跳过'],
      ['testReport.statPassRate', '通过率'],
      ['dict.batch.status.draft', '草稿'],
      ['dict.batch.status.pending', '待执行'],
      ['dict.batch.status.envUnavailable', '环境不可用'],
      ['dict.batch.status.executing', '执行中'],
      ['dict.batch.status.paused', '已暂停'],
      ['dict.batch.status.done', '已完成'],
      ['dict.batch.status.partialFail', '部分失败'],
      ['dict.batch.status.canceled', '已取消'],
    ]
    for (const [k, zhVal] of pairs) {
      const zv = lookup(zhCN, k)
      expect(zv, `zh-CN 缺键 ${k}`).not.toBeUndefined()
      expect(String(zv), `${k} zh 值漂移`).toBe(zhVal)
      const ev = lookup(enUS, k)
      expect(ev, `en-US 缺键 ${k}`).not.toBeUndefined()
      for (const ph of ['{name}', '{total}', '{passed}', '{failed}', '{notRun}', '{msg}', '{reasons}', '{n}', '{action}', '{head}', '{status}', '{id}']) {
        if (String(zv).includes(ph)) expect(String(ev), `${k} en 缺插值 ${ph}`).toContain(ph)
      }
    }
  })
  it('P7c2 phase 标签单源复用（plan 视图禁本地中文 phase 副本）', () => {
    for (const f of ['../../src/views/plan/PlanListView.vue', '../../src/views/plan/PlanDetailView.vue']) {
      const src = readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf-8')
      expect(src, `${f} 仍含本地 phase 中文副本`).not.toMatch(/'未归属'/)
    }
  })

  
  const P7D_FILES: Array<[string, number]> = [
    ['../../src/views/project/ProjectListView.vue', 40],
    ['../../src/views/case/CaseListView.vue', 110],
  ]
  for (const [f, minKeys] of P7D_FILES) {
    it(`P7d 视图 ${f.split('/').pop()} 模板+脚本层零硬中文`, () => {
      expect(hardChineseLines(f)).toEqual([])
    })
    it(`P7d 视图 ${f.split('/').pop()} t() 字面量键全部命中 zh-CN（禁漏键，计数下限 ${minKeys}）`, () => {
      const keys = tKeysInFile(f)
      expect(keys.size, `${f} t() 键不足 ${minKeys}（现状 ${keys.size}）`).toBeGreaterThanOrEqual(minKeys)
      for (const k of keys) {
        expect(lookup(zhCN, k), `${f} 引用 zh-CN 缺键 ${k}`).not.toBeUndefined()
      }
    })
  }
  it('P7d 新键 zh/en 双镜像（project/case 域）', () => {
    const pairs: Array<[string, string]> = [
      ['project.archiveViewBtn', '归档项目'],
      ['project.emptyFiltered', '没有找到匹配的项目'],
      ['project.emptyFilteredSearchHint', '试试调整或清空搜索关键词'],
      ['project.emptyGuide', '点击「新建项目」创建第一个项目'],
      ['project.attributesTitle', '项目属性'],
      ['project.jiraPlaceholder', '选填，如 TP2026（大写字母开头，2~10 位字母数字；配置后缺陷编号自动为 键-序号）'],
      ['project.testOwnerPlaceholder', '选填，批次执行失败时通知该成员'],
      ['project.devOwnerPlaceholder', '选填，新建缺陷默认分派给该成员'],
      ['project.scriptPathPlaceholder', '选填，如 /data/scripts（≤300 字符；空=系统默认）'],
      ['project.recordingPathPlaceholder', '选填，如 /data/recordings（≤300 字符；空=系统默认）'],
      ['project.jiraKeyInvalid', 'JIRA 项目键格式非法：大写字母开头，2~10 位字母数字（如 TP2026）'],
      ['project.manageLabel', '管理'],
      ['project.archive', '归档'],
      ['project.archiveConfirm', '确定归档项目「{name}」吗？归档后数据保留（可恢复），列表不再显示该项目；仅系统管理员可归档。'],
      ['project.archiveSuccess', '项目已归档'],
      ['project.entityName', '项目「{name}」'],
      ['project.deleteForceHint', '删除将级联清理环境、用例、计划、批次与成员，且不可恢复。确定继续吗？'],
      ['project.purge', '彻底删除'],
      ['project.purgeSuccess', '项目已彻底删除'],
      ['case.export', '导出 Excel'],
      ['case.recycleBin', '回收站'],
      ['case.selectedMore', '…等 {n} 条'],
      ['case.assignedCount', '：{n} 条'],
      ['case.assignSkippedSuffix', '，{n} 条跳过（{reasons}）'],
      ['case.userFallback', '用户{id}'],
      ['case.exportFileName', '用例导出.xlsx'],
      ['case.entityName', '用例「{title}」'],
      ['case.deleteForceHint', '该用例仍被执行计划引用；删除为软删（历史计划数据保留可追溯）。确定继续吗？'],
      ['case.confirmDelete', '确认删除'],
      ['case.createFailed', '创建失败'],
      ['case.createStep', '新建步骤'],
      ['case.flakyIsolatedReason', '已隔离（不稳定）'],
    ]
    for (const [k, zhVal] of pairs) {
      const zv = lookup(zhCN, k)
      expect(zv, `zh-CN 缺键 ${k}`).not.toBeUndefined()
      expect(String(zv), `${k} zh 值漂移`).toBe(zhVal)
      const ev = lookup(enUS, k)
      expect(ev, `en-US 缺键 ${k}`).not.toBeUndefined()
      for (const ph of ['{name}', '{n}', '{caseNo}', '{id}', '{title}']) {
        if (String(zv).includes(ph)) expect(String(ev), `${k} en 缺插值 ${ph}`).toContain(ph)
      }
    }
  })

  it('P7c envTypeLabel 单源复用（视图层禁本地重复定义）', () => {
    for (const f of ['../../src/views/suite/SuiteListView.vue', '../../src/views/datafactory/DataFactoryListView.vue']) {
      const src = readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf-8')
      expect(src, `${f} 仍含本地 envTypeLabel 副本`).not.toMatch(/function envTypeLabel/)
    }
  })

  it('P6a notification 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).notification).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).notification).sort()
    expect(zhKeys.length).toBeGreaterThan(50)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P6a 通知事件标签/分组单源键化（api 层禁硬中文 label）', () => {
    const src = readFileSync(fileURLToPath(new URL('../../src/api/notification.ts', import.meta.url)), 'utf-8')
    expect(hardChineseLines('../../src/api/notification.ts')).toEqual([])
    expect(src).toContain('NOTIFICATION_EVENT_KEYS')
    const pref = readFileSync(fileURLToPath(new URL('../../src/api/notificationPref.ts', import.meta.url)), 'utf-8')
    expect(stripComments(pref).split('\n').filter((l) => /[\u4e00-\u9fff]/.test(l))).toEqual([])
    for (const m of src.matchAll(/:\s*'(notification\.[\w.]+)'/g)) {
      expect(lookup(zhCN, m[1]), `notification.ts 事件缺 zh 键 ${m[1]}`).not.toBeUndefined()
    }
  })

  it('P6a 事件码契约闸：后端 NotificationEventDict 全集 ⊆ 前端 CODES/PREF_EVENT_GROUPS（E2E intlify 红根因防再漂移）', () => {
    const dictSrc = readFileSync(
      fileURLToPath(new URL('../../../core/src/main/java/com/sixbean/testpike/core/notification/domain/component/NotificationEventDict.java', import.meta.url)),
      'utf-8')
    const dictCodes = [...dictSrc.matchAll(/m\.put\("(\w+)"/g)].map((m) => m[1])
    expect(dictCodes.length).toBeGreaterThanOrEqual(30)
    const apiSrc = readFileSync(fileURLToPath(new URL('../../src/api/notification.ts', import.meta.url)), 'utf-8')
    const prefSrc = readFileSync(fileURLToPath(new URL('../../src/api/notificationPref.ts', import.meta.url)), 'utf-8')
    const codeBlock = prefSrc.split('PREF_EVENT_GROUPS: Record<string, string> = {')[1].split('\n}')[0]
    for (const code of dictCodes) {
      expect(apiSrc, `notification.ts 缺事件码 ${code}`).toContain(`  ${code}: { labelKey:`)
      expect(codeBlock, `notificationPref.ts 缺分组码 ${code}`).toContain(`  ${code}: '`)
      const key = apiSrc.match(new RegExp(`  ${code}: \\{ labelKey: '([\\w.]+)'`))?.[1]
      expect(lookup(zhCN, key as string), `zh-CN 缺事件键 ${code}(${key})`).not.toBeUndefined()
      expect(lookup(enUS, key as string), `en-US 缺事件键 ${code}(${key})`).not.toBeUndefined()
    }
  })

  it('P5a skill 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    expect(Object.keys((enUS as Record<string, object>).skill).sort()).toEqual(Object.keys(zhCN.skill).sort())
  })

  it('P5b market 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).market).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).market).sort()
    expect(zhKeys.length).toBeGreaterThan(15)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P5c mcp/plugin 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    for (const dom of ['mcp', 'plugin'] as const) {
      const zhKeys = Object.keys((zhCN as Record<string, object>)[dom]).sort()
      const enKeys = Object.keys((enUS as Record<string, object>)[dom]).sort()
      expect(zhKeys.length, `${dom} 域键数不足`).toBeGreaterThan(10)
      expect(enKeys, `${dom} 域 zh/en 键集不一致`).toEqual(zhKeys)
    }
  })

  it('P5c mcp/plugin 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: {
          'zh-CN': { mcp: zhCN.mcp, plugin: zhCN.plugin },
          'en-US': {
            mcp: (enUS as Record<string, never>).mcp,
            plugin: (enUS as Record<string, never>).plugin,
          },
        },
      })
      for (const domain of ['mcp', 'plugin'] as const) {
        for (const key of Object.keys(zhCN[domain])) {
          if (typeof (zhCN[domain] as Record<string, unknown>)[key] !== 'string') continue
          rendered['zh:' + domain + '.' + key] = String(i18n.global.t(`${domain}.${key}`))
          rendered['en:' + domain + '.' + key] = String(i18n.global.t(`${domain}.${key}`, {}, { locale: 'en-US' }))
        }
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:mcp.pageTitle']).toBe('MCP 服务')
    expect(rendered['zh:mcp.probeOk']).toBe('连接成功')
    expect(rendered['zh:mcp.toolSelectTitle']).toBe('工具勾选')
    expect(rendered['zh:plugin.pageTitle']).toBe('行业插件')
    expect(rendered['zh:plugin.builtinTag']).toBe('内置样例')
    expect(String((enUS as Record<string, never>).plugin.pageTitle)).not.toMatch(/[\u4e00-\u9fff]/)
    const zhMcp = zhCN.mcp as Record<string, string>
    const enMcp = (enUS as Record<string, never>).mcp as Record<string, string>
    const zhPlugin = zhCN.plugin as Record<string, string>
    const enPlugin = (enUS as Record<string, never>).plugin as Record<string, string>
    for (const [msg, ph] of [[zhMcp.deleteConfirm, '{name}'], [enMcp.deleteConfirm, '{name}'],
      [zhPlugin.enabledOk, '{name}'], [enPlugin.enabledOk, '{name}'],
      [zhPlugin.registered, '{name}'], [enPlugin.registered, '{version}']] as const) {
      expect(String(msg), `缺插值 ${ph}`).toContain(ph)
    }
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { mcp: zhCN.mcp, plugin: zhCN.plugin } } })
      expect(String(i18n.global.t('mcp.deleteConfirm', { name: 'jira-mcp' }))).toContain('jira-mcp')
      expect(String(i18n.global.t('plugin.enabledOk', { name: 'pos-retail' }))).toBe('已启用「pos-retail」（项目可装配）')
      expect(String(i18n.global.t('plugin.registered', { name: 'pos-retail', version: '1.0.0' }))).toContain('v1.0.0')
    }
  })

  it('P5d knowledge 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys((zhCN as Record<string, object>).knowledge).sort()
    const enKeys = Object.keys((enUS as Record<string, object>).knowledge).sort()
    expect(zhKeys.length, 'knowledge 域键数不足').toBeGreaterThan(30)
    expect(enKeys, 'knowledge 域 zh/en 键集不一致').toEqual(zhKeys)
  })

  it('P5d knowledge 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: {
          'zh-CN': { knowledge: zhCN.knowledge },
          'en-US': { knowledge: (enUS as Record<string, never>).knowledge },
        },
      })
      for (const key of Object.keys(zhCN.knowledge)) {
        if (typeof (zhCN.knowledge as Record<string, unknown>)[key] !== 'string') continue
        rendered['zh:knowledge.' + key] = String(i18n.global.t(`knowledge.${key}`))
        rendered['en:knowledge.' + key] = String(i18n.global.t(`knowledge.${key}`, {}, { locale: 'en-US' }))
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:knowledge.pageTitle']).toBe('知识库台账')
    expect(rendered['zh:knowledge.rebuildResultTitle']).toBe('向量重建完成')
    expect(rendered['zh:knowledge.global']).toBe('全局')
    expect(rendered['zh:knowledge.srcTestCase']).toBe('测试用例')
    expect(String((enUS as Record<string, never>).knowledge.pageTitle)).not.toMatch(/[\u4e00-\u9fff]/)
    const zhK = zhCN.knowledge as Record<string, string>
    const enK = (enUS as Record<string, never>).knowledge as Record<string, string>
    expect(String(zhK.hitsSection), '缺插值 {count}').toContain('{count}')
    expect(String(enK.hitsSection), '缺插值 {count}').toContain('{count}')
    expect(String(zhK.lastProbeAt ?? zhK.chunkSection ?? ''), 'chunkSection 须带 {count} 或等价').not.toBe('')
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { knowledge: zhCN.knowledge } } })
      expect(String(i18n.global.t('knowledge.hitsSection', { count: 3 }))).toContain('3')
    }
  })

  it('P4a config 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys(zhCN.config).sort()
    const enKeys = Object.keys(enUS.config).sort()
    expect(enKeys.length).toBeGreaterThan(30)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P4a config 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: { 'zh-CN': { config: zhCN.config }, 'en-US': { config: enUS.config } },
      })
      for (const key of Object.keys(zhCN.config)) {
        rendered['zh:' + key] = String(i18n.global.t(`config.${key}`))
        rendered['en:' + key] = String(i18n.global.t(`config.${key}`, {}, { locale: 'en-US' }))
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:pageTitle']).toBe('系统配置')
    expect(rendered['zh:groupModel']).toBe('模型')
    const zhMsg = zhCN.config as Record<string, string>
    const enMsg = enUS.config as Record<string, string>
    for (const [k, ph] of [['runCleanup', '{n}'], ['deleteConfirm', '{key}'], ['cleanupDone', '{total}'], ['cleanupConfirm', '{n}'], ['daysN', '{n}']] as const) {
      expect(String(zhMsg[k]), `zh config.${k} 缺 ${ph}`).toContain(ph)
      expect(String(enMsg[k]), `en config.${k} 缺 ${ph}`).toContain(ph)
    }
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { config: zhCN.config } } })
      expect(String(i18n.global.t('config.runCleanup', { n: 42 }))).toContain('42')
    }
  })

  it('P4b llmConfig 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys(zhCN.llmConfig).sort()
    const enKeys = Object.keys(enUS.llmConfig).sort()
    expect(enKeys.length).toBeGreaterThan(30)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P4b llmConfig 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: { 'zh-CN': { llmConfig: zhCN.llmConfig }, 'en-US': { llmConfig: enUS.llmConfig } },
      })
      for (const key of Object.keys(zhCN.llmConfig)) {
        rendered['zh:' + key] = String(i18n.global.t(`llmConfig.${key}`))
        rendered['en:' + key] = String(i18n.global.t(`llmConfig.${key}`, {}, { locale: 'en-US' }))
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:pageTitle']).toBe('大模型配置')
    expect(rendered['zh:typeGen']).toBe('生成模型')
    expect(rendered['zh:statusOn']).toBe('启用')
    const zhMsg = zhCN.llmConfig as Record<string, string>
    const enMsg = enUS.llmConfig as Record<string, string>
    expect(String(zhMsg.deleteConfirm)).toContain('{name}')
    expect(String(enMsg.deleteConfirm)).toContain('{name}')
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { llmConfig: zhCN.llmConfig } } })
      expect(String(i18n.global.t('llmConfig.deleteConfirm', { name: 'deepseek-main' }))).toContain('deepseek-main')
    }
  })

  it('P4c audit 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys(zhCN.audit).sort()
    const enKeys = Object.keys(enUS.audit).sort()
    expect(enKeys.length).toBeGreaterThan(30)
    expect(enKeys).toEqual(zhKeys)
    expect(Object.keys(enUS.audit.act).sort()).toEqual(Object.keys(zhCN.audit.act).sort())
    expect(Object.keys(enUS.audit.obj).sort()).toEqual(Object.keys(zhCN.audit.obj).sort())
    expect(Object.keys(zhCN.audit.act).length).toBeGreaterThanOrEqual(50)
    expect(Object.keys(zhCN.audit.obj).length).toBeGreaterThanOrEqual(20)
  })

  it('P4c apiToken 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys(zhCN.apiToken).sort()
    const enKeys = Object.keys(enUS.apiToken).sort()
    expect(enKeys.length).toBeGreaterThan(25)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P4c audit/apiToken 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: {
          'zh-CN': { audit: zhCN.audit, apiToken: zhCN.apiToken },
          'en-US': { audit: enUS.audit, apiToken: enUS.apiToken },
        },
      })
      for (const domain of ['audit', 'apiToken'] as const) {
        for (const key of Object.keys(zhCN[domain])) {
          if (typeof (zhCN[domain] as Record<string, unknown>)[key] !== 'string') continue
          rendered['zh:' + key] = String(i18n.global.t(`${domain}.${key}`))
          rendered['en:' + key] = String(i18n.global.t(`${domain}.${key}`, {}, { locale: 'en-US' }))
        }
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:pageTitle']).toBe('审计日志')
    expect(rendered['zh:diffTitle']).toBe('字段变更对比（before → after）')
    const zhMsg = zhCN.audit as Record<string, string>
    const enMsg = enUS.audit as Record<string, string>
    for (const [k, ph] of [['verifyOk', '{n}'], ['verifyOk', '{at}'], ['verifyBroken', '{id}'], ['userHash', '{id}'], ['detailTitle', '{id}'], ['exported', '{n}']] as const) {
      expect(String(zhMsg[k]), `zh audit.${k} 缺 ${ph}`).toContain(ph)
      expect(String(enMsg[k]), `en audit.${k} 缺 ${ph}`).toContain(ph)
    }
    const zhTok = zhCN.apiToken as Record<string, string>
    const enTok = enUS.apiToken as Record<string, string>
    for (const ph of ['{name}', '{fp}']) {
      expect(String(zhTok.revokeConfirm)).toContain(ph)
      expect(String(enTok.revokeConfirm)).toContain(ph)
    }
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { audit: zhCN.audit, apiToken: zhCN.apiToken } } })
      expect(String(i18n.global.t('audit.verifyOk', { n: 42, at: '2026-09-21' }))).toContain('42')
      expect(String(i18n.global.t('apiToken.revokeConfirm', { name: 'ci-agent', fp: 'abcd1234' }))).toContain('终态不可恢复')
    }
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { audit: zhCN.audit } } })
      expect(String(i18n.global.t('audit.act.defect_transition'))).toBe('缺陷流转')
      expect(String(i18n.global.t('audit.obj.sys_config'))).toBe('系统配置')
    }
  })

  it('P3c caseDetail 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    const zhKeys = Object.keys(zhCN.caseDetail).sort()
    const enKeys = Object.keys(enUS.caseDetail).sort()
    expect(enKeys.length).toBeGreaterThan(60)
    expect(enKeys).toEqual(zhKeys)
  })

  it('P3c caseDetail 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: { 'zh-CN': { caseDetail: zhCN.caseDetail }, 'en-US': { caseDetail: enUS.caseDetail } },
      })
      for (const key of Object.keys(zhCN.caseDetail)) {
        rendered['zh:' + key] = String(i18n.global.t(`caseDetail.${key}`))
        rendered['en:' + key] = String(i18n.global.t(`caseDetail.${key}`, {}, { locale: 'en-US' }))
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:phaseNone']).toBe('未归属')
    expect(rendered['zh:emptyValue']).toBe('（空）')
    expect(rendered['zh:scriptSection']).toBe('自动化脚本')
    expect(rendered['zh:versionDiffTitle']).toContain('版本变更对比')
    expect(rendered['zh:rerunHintTitle']).toContain('缺陷')
  })

  it('P3b tool 域消息全量可编译（字面花括号转义回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: { 'zh-CN': { tool: zhCN.tool }, 'en-US': { tool: enUS.tool } },
      })
      for (const key of Object.keys(zhCN.tool)) {
        rendered['zh:' + key] = String(i18n.global.t(`tool.${key}`))
        rendered['en:' + key] = String(i18n.global.t(`tool.${key}`, {}, { locale: 'en-US' }))
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:inputSchemaPh']).toBe('JSON Schema，如 {"type":"object","properties":{}}')
    expect(rendered['zh:sampleInputPh']).toBe('JSON 样例入参（可留空=空体直发），如 {"key":"value"}')
    expect(rendered['en:inputSchemaPh']).toBe('JSON Schema, e.g. {"type":"object","properties":{}}')
    expect(rendered['en:inputPh']).toBe('JSON input (empty allowed = send empty body), e.g. {"key":"value"}')
    expect(rendered['zh:pageTitle']).toBe('工具管理')
    expect(rendered['zh:invTitle']).toContain('调用历史')
  })

  it('P2b 新增 layout 键在 en-US 有镜像翻译（禁英文态回落中文）', () => {
    const newKeys = [
      'search', 'notifPanelTitle', 'markAllRead', 'viewAll', 'viewDetail', 'retry',
      'notifEmpty', 'profileCenter', 'notifNoContent', 'gotIt', 'retryNoTask',
      'retrySubmitted', 'retryFailed', 'markAllReadDone', 'markAllReadNone', 'markAllReadFailed',
      'logoutConfirm', 'logoutConfirmBtn', 'permDenied',
    ]
    for (const k of newKeys) {
      expect(lookup(zhCN, `layout.${k}`), `zh-CN 缺 layout.${k}`).not.toBeUndefined()
      const en = lookup(enUS, `layout.${k}`)
      expect(en, `en-US 缺 layout.${k}`).not.toBeUndefined()
      expect(typeof en).toBe('string')
      for (const ph of ['{affected}', '{perm}']) {
        const inZh = String(lookup(zhCN, `layout.${k}`)).includes(ph)
        expect(String(en).includes(ph), `en-US layout.${k} 插值 ${ph} 与 zh 不一致`).toBe(inZh)
      }
    }
  })

 
  it('P4d user/rbac/usage 域新键在 zh-CN 命中（角色/数据范围/链路/导入报告映射表 locale 单源）', () => {
    const newKeys: Array<[string, string]> = [
      ['user.roleTester', '测试人员'],
      ['user.roleTestLead', '测试负责人'],
      ['user.roleDev', '开发人员'],
      ['user.roleAdmin', '系统管理员'],
      ['user.unknownRole', '未知({role})'],
      ['user.emptyFiltered', '没有找到匹配的用户'],
      ['user.emptyTitle', '还没有用户'],
      ['user.emptyHint', '试试调整或清空搜索关键词'],
      ['user.emptyInviteHint', '邀请成员加入后即可分配项目角色'],
      ['user.inviteAction', '邀请用户'],
      ['user.importOkCount', '成功 {n} 条'],
      ['user.importFailCount', '失败 {n} 条'],
      ['user.importColRow', 'Excel 行号'],
      ['user.importColReason', '失败原因'],
      ['rbac.builtin', '内置'],
      ['rbac.scopeAll', '全部项目'],
      ['rbac.scopeProject', '项目内'],
      ['rbac.scopeSelf', '仅本人'],
      ['rbac.unknownScope', '未知({scope})'],
      ['rbac.roleEntity', '角色「{name}」'],
      ['usage.pageTitle', 'Token 校准'],
      ['usage.pageSubtitle', '事前估算（jTokkit）× 事后真值（供应商 usage）偏差观测——正偏差=低估（超限风险向），负=高估（保守浪费向）'],
      ['usage.chainExtract', '提取/生成 (extract)'],
      ['usage.chainSummary', '摘要/归并 (summary)'],
      ['usage.chainExtractShort', '提取/生成'],
      ['usage.chainSummaryShort', '摘要/归并'],
      ['usage.keywordPlaceholder', '模型关键词'],
      ['usage.mockProviderTag', 'mock（不计生产校准）'],
    ]
    for (const [k, v] of newKeys) {
      expect(lookup(zhCN, k), `zh-CN 缺键 ${k}`).toBe(v)
    }
    expect(String(lookup(zhCN, 'common.enabled'))).toBe('启用')
    expect(String(lookup(zhCN, 'common.disabled'))).toBe('停用')
  })

  it('P4d user/rbac/usage 域键集 zh/en 一致（禁英文态缺键回落中文）', () => {
    for (const dom of ['user', 'rbac', 'usage'] as const) {
      const zhKeys = Object.keys(zhCN[dom]).sort()
      const enDomain = (enUS as Record<string, Record<string, unknown>>)[dom]
      expect(enDomain, `en-US 缺 ${dom} 域`).toBeTruthy()
      const enKeys = Object.keys(enDomain).sort()
      expect(enKeys, `${dom} 域 zh/en 键集不一致`).toEqual(zhKeys)
    }
  })

  it('P4d user/rbac/usage 域消息全量可编译（vue-i18n 编译回归闸，zh/en 双侧）', async () => {
    const { createI18n } = await import('vue-i18n')
    const errors: string[] = []
    const origError = console.error
    const origWarn = console.warn
    console.error = (...a: unknown[]) => { errors.push(a.join(' ')) }
    console.warn = (...a: unknown[]) => { errors.push(a.join(' ')) }
    const rendered: Record<string, string> = {}
    try {
      const i18n = createI18n({
        legacy: false,
        locale: 'zh-CN',
        messages: {
          'zh-CN': { user: zhCN.user, rbac: zhCN.rbac, usage: zhCN.usage },
          'en-US': {
            user: (enUS as Record<string, never>).user,
            rbac: (enUS as Record<string, never>).rbac,
            usage: (enUS as Record<string, never>).usage,
          },
        },
      })
      for (const domain of ['user', 'rbac', 'usage'] as const) {
        for (const key of Object.keys(zhCN[domain])) {
          if (typeof (zhCN[domain] as Record<string, unknown>)[key] !== 'string') continue
          rendered['zh:' + key] = String(i18n.global.t(`${domain}.${key}`))
          rendered['en:' + key] = String(i18n.global.t(`${domain}.${key}`, {}, { locale: 'en-US' }))
        }
      }
    } finally {
      console.error = origError
      console.warn = origWarn
    }
    const compileFails = errors.filter((e) => e.includes('Message compilation error'))
    expect(compileFails, compileFails.slice(0, 3).join('\n')).toEqual([])
    expect(rendered['zh:roleTester']).toBe('测试人员')
    expect(rendered['zh:scopeAll']).toBe('全部项目')
    expect(rendered['zh:pageTitle']).toBe('Token 校准')
    expect(rendered['en:roleTester']).toBe('Tester')
    const zhUser = zhCN.user as Record<string, string>
    const enUser = (enUS as Record<string, never>).user as Record<string, string>
    const zhRbac = zhCN.rbac as Record<string, string>
    const enRbac = (enUS as Record<string, never>).rbac as Record<string, string>
    for (const [msg, ph] of [[zhUser.unknownRole, '{role}'], [enUser.unknownRole, '{role}'],
      [zhUser.importOkCount, '{n}'], [enUser.importOkCount, '{n}'],
      [zhRbac.unknownScope, '{scope}'], [enRbac.unknownScope, '{scope}'],
      [zhRbac.roleEntity, '{name}'], [enRbac.roleEntity, '{name}']] as const) {
      expect(String(msg), `缺插值 ${ph}`).toContain(ph)
    }
    {
      const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { user: zhCN.user } } })
      expect(String(i18n.global.t('user.importOkCount', { n: 2 }))).toBe('成功 2 条')
      expect(String(i18n.global.t('user.unknownRole', { role: 9 }))).toBe('未知(9)')
    }
  })

  
  const SWEEP_DIRS = [
    '../../src/views',
    '../../src/components',
    '../../src/api',
    '../../src/layouts',
    '../../src/router',
  ]
  function sweepFiles(dir: string): string[] {
    const abs = fileURLToPath(new URL(dir, import.meta.url))
    const out: string[] = []
    for (const e of readdirSync(abs, { withFileTypes: true })) {
      const p = `${dir}/${e.name}`
      if (e.isDirectory()) out.push(...sweepFiles(p))
      else if (e.name.endsWith('.vue') || e.name.endsWith('.ts')) out.push(p)
    }
    return out
  }

  it('P7e 终局闸：五目录全部 .vue/.ts 逐件零硬中文（遍历式收口，禁白名单）', () => {
    const offenders: string[] = []
    let scanned = 0
    for (const dir of SWEEP_DIRS) {
      for (const f of sweepFiles(dir)) {
        scanned++
        for (const line of hardChineseLines(f)) offenders.push(`${f} → ${line.trim().slice(0, 90)}`)
      }
    }
    expect(scanned).toBeGreaterThanOrEqual(120)
    expect(offenders, '残留硬中文行：\n' + offenders.join('\n')).toEqual([])
  })

  it('P7e 终局闸：五目录全部 t()/titleKey 字面量命中 zh-CN（遍历式禁漏键）', () => {
    const missing: string[] = []
    let keyTotal = 0
    for (const dir of SWEEP_DIRS) {
      for (const f of sweepFiles(dir)) {
        const keys = tKeysInFile(f)
        keyTotal += keys.size
        for (const k of keys) {
          if (lookup(zhCN, k) === undefined) missing.push(`${f} → ${k}`)
        }
      }
    }
    expect(keyTotal).toBeGreaterThanOrEqual(500)
    expect(missing, 'zh-CN 缺键：\n' + missing.join('\n')).toEqual([])
  })
})
