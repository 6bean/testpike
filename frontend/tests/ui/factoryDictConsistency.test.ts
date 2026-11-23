
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { PRIORITY_LABEL, END_TYPE_LABEL } from '@/api/case'
import { DEFECT_SEVERITY_LABEL } from '@/api/defectLibrary'
import { MOBILE_FAMILY_TARGETS, wantedPlatformsForTarget } from '@/utils/automationTarget'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

function readRepo(rel: string): string {
  return readFileSync(fileURLToPath(new URL(`../../../${rel}`, import.meta.url)), 'utf-8')
}

const CASE_IMPL = readRepo('core/src/main/java/com/sixbean/testpike/core/cases/domain/service/CaseServiceImpl.java')
const DEFECT_LABELS = readRepo('common/src/main/java/com/sixbean/testpike/common/defect/DefectLabels.java')
const DISPATCH = readRepo('core/src/main/java/com/sixbean/testpike/core/executor/domain/component/AppAutoDispatchSupport.java')
const SCHEMA = readRepo('db/schema-init.sql')
const EXEC_CAPS = readRepo('executor/capabilities.py')

// Perf engine codes as declared by the tp_perf scenario engine CHECK clauses
// in schema-init (the table DDL plus later widenings such as V354/JMETER).
// Union across all CHECK occurrences: the perf domain only grows, and the
// last ALTER always re-lists the full set. Single source: parsed from the
// schema text, never a hand-maintained list.
const PERF_ENGINES = new Set<string>()
for (const m of SCHEMA.matchAll(/\(\s*engine IS NULL OR engine IN \(([^)]*)\)\s*\)/g)) {
  for (const c of m[1].matchAll(/'([A-Z_]+)'/g)) PERF_ENGINES.add(c[1])
}
if (PERF_ENGINES.size === 0) throw new Error('schema-init perf scenario engine CHECK not found')


function insertRowLiterals(table: string, columns: string[]): string[][] {
  const colRe = new RegExp(`INSERT INTO ${table} \\(\\s*${columns.map((c) => c.replace(/_/g, '_')).join('\\s*,\\s*')}\\s*\\)`)
  const mm = colRe.exec(SCHEMA)
  expect(mm, `schema-init 必须含 ${table} 出厂 INSERT（对账锚）`).not.toBeNull()
  const start = mm!.index
  const valuesAt = SCHEMA.indexOf('VALUES', start)
  const end = SCHEMA.indexOf('ON CONFLICT', valuesAt)
  expect(end, `${table} INSERT 段须以 ON CONFLICT 收尾（幂等出厂段锚）`).toBeGreaterThan(valuesAt)
  const body = SCHEMA.slice(valuesAt + 'VALUES'.length, end)
  const rows: string[][] = []
  let depth = 0
  let inQuote = false
  let cur = ''
  for (let i = 0; i < body.length; i++) {
    const c = body[i]
    if (inQuote) {
      if (c === "'" && body[i + 1] === "'") { cur += "''"; i++; continue }
      if (c === '\\') { cur += c + (body[i + 1] ?? ''); i++; continue }
      if (c === "'") inQuote = false
      cur += c
      continue
    }
    if (c === "'") { inQuote = true; if (depth > 0) cur += c; continue }
    if (c === '(') { depth++; if (depth === 1) { cur = ''; continue } }
    if (c === ')') {
      depth--
      if (depth === 0) { rows.push(splitTupleRespectingBrackets(cur)); cur = '' }
      else cur += c
      continue
    }
    if (depth > 0) cur += c
  }
  expect(rows.length, `${table} 出厂行数解析不可为空`).toBeGreaterThan(0)
  return rows
}


function splitTupleRespectingBrackets(tuple: string): string[] {
  const out: string[] = []
  let cur = ''
  let inQuote = false
  let inBracket = false
  for (let i = 0; i < tuple.length; i++) {
    const c = tuple[i]
    if (c === "'" && tuple[i - 1] !== '\\') inQuote = !inQuote
    if (!inQuote && c === '[') inBracket = true
    if (!inQuote && c === ']') inBracket = false
    if (c === ',' && !inQuote && !inBracket) {
      out.push(cur.trim())
      cur = ''
      continue
    }
    cur += c
  }
  out.push(cur.trim())
  return out
}

function unquote(lit: string): string {
  const m = /^\s*'([\s\S]*)'\s*(?:::jsonb)?\s*$/.exec(lit)
  return m ? m[1] : lit.trim()
}

function jsonbCodes(lit: string): string[] {
  return [...unquote(lit).matchAll(/"([A-Z_]+)"/g)].map((x) => x[1])
}

describe('出厂字典族①用例优先级：core 校验区间 ↔ 前端 label 键集', () => {
  it('core validatePriority 区间与前端 PRIORITY_LABEL 键一一对应', () => {
    const m = /private void validatePriority\(int priority\)\s*\{\s*if \(priority < (\d+) \|\| priority > (\d+)\)/.exec(CASE_IMPL)
    expect(m, 'core 优先级校验单点（validatePriority）必须可解析').not.toBeNull()
    const [lo, hi] = [Number(m![1]), Number(m![2])]
    const keys = Object.keys(PRIORITY_LABEL).map(Number).sort((a, b) => a - b)
    const expected: number[] = []
    for (let p = lo; p <= hi; p++) expected.push(p)
    expect(keys).toEqual(expected)
    expect(PRIORITY_LABEL[0]).toBe('P0')
    expect(PRIORITY_LABEL[hi]).toBe(`P${hi}`)
  })
})

describe('出厂字典族②缺陷严重级：core label 分支 ↔ 前端映射 ↔ locale 双写', () => {
  it('DefectLabels.severityLabel 分支码集与 DEFECT_SEVERITY_LABEL 键集一致', () => {
    const block = DEFECT_LABELS.slice(
      DEFECT_LABELS.indexOf('severityLabel'),
      DEFECT_LABELS.indexOf('errorTypeLabel'),
    )
    const codes = [...block.matchAll(/case (\d+) -> "(P\d)"/g)].map((x) => [Number(x[1]), x[2]] as const)
    expect(codes.length, 'core 严重级 switch 分支不可解析').toBeGreaterThan(0)
    const feKeys = Object.keys(DEFECT_SEVERITY_LABEL).map(Number).sort((a, b) => a - b)
    expect(feKeys).toEqual(codes.map((c) => c[0]).sort((a, b) => a - b))
    codes.sort((a, b) => a[0] - b[0])
    codes.forEach(([code, label], i) => expect(label).toBe(`P${i}`))
  })

  it('严重级 label locale 键 zh/en 双写齐全（i18n 双轨）', () => {
    for (const k of ['p0', 'p1', 'p2', 'p3']) {
      const path = `dict.defectLib.severity.${k}`
      const grab = (loc: Record<string, unknown>) =>
        path.split('.').reduce<unknown>((o: unknown, p) => (o as Record<string, unknown>)?.[p], loc)
      expect(typeof grab(zhCN), `zh-CN 缺键 ${path}`).toBe('string')
      expect(typeof grab(enUS), `en-US 缺键 ${path}`).toBe('string')
    }
  })
})

describe('出厂字典族③端类型：core END_TYPES ↔ 前端 END_TYPE_LABEL', () => {
  it('值域逐枚一致（core=写侧校验单点，前端=展示面镜像）', () => {
    const m = /END_TYPES\s*=\s*List\.of\(([^)]+)\)/.exec(CASE_IMPL)
    expect(m, 'core END_TYPES 单源不可解析').not.toBeNull()
    const coreTypes = [...m![1].matchAll(/"([^"]+)"/g)].map((x) => x[1]).sort()
    const feTypes = Object.keys(END_TYPE_LABEL).sort()
    expect(feTypes).toEqual(coreTypes)
  })

  it('导入面 CaseExcelParser 端类型集与写侧校验同源一致', () => {
    const impl = readRepo('core/src/main/java/com/sixbean/testpike/core/imports/domain/component/CaseExcelParser.java')
    const m = /END_TYPES\s*=\s*Set\.of\(([^)]+)\)/.exec(impl)
    expect(m, '导入面 END_TYPES 不可解析').not.toBeNull()
    const importTypes = [...m![1].matchAll(/"([^"]+)"/g)].map((x) => x[1]).sort()
    expect(importTypes).toEqual(Object.keys(END_TYPE_LABEL).sort())
  })
})

describe('出厂字典族④平台/引擎描述符：schema-init ↔ executor 能力上报面对账', () => {
  const platformRows = insertRowLiterals('tp_appauto_platform', ['code', 'name', 'device_kind_codes', 'carrier_types', 'note', 'status'])
  const platformOf = new Map(platformRows.map((r) => [unquote(r[0]), { codes: jsonbCodes(r[2]), status: Number(unquote(r[5])) }]))
  const activePlatforms = [...platformOf].filter(([, v]) => v.status === 1).map(([k]) => k)

  const engineRows = insertRowLiterals('tp_appauto_engine', [
    'code', 'name', 'platform_codes', 'device_kind_codes', 'default_priority',
    'runtime_type', 'runtime_version_req', 'capabilities', 'driver_requirements', 'env_check_spec',
    'status', 'install_doc_url', 'license',
  ])
  const engineOf = new Map(engineRows.map((r) => [unquote(r[0]), { platforms: jsonbCodes(r[2]), status: Number(unquote(r[10])) }]))
  const appendRe = /SET platform_codes = platform_codes \|\| '\["([A-Z_]+)"\]'::jsonb\s*,?\s*(?:--[^\n]*\n\s*)?updated_at[^\n]*\n[^\n]*\n?\s*WHERE code IN \(([^)]+)\)/g
  for (const m of SCHEMA.matchAll(appendRe)) {
    const added = m[1]
    for (const lm of m[2].matchAll(/'([A-Z_]+)'/g)) {
      const eng = engineOf.get(lm[1])
      if (eng && !eng.platforms.includes(added)) eng.platforms.push(added)
    }
  }

  it('平台行码集含五活跃平台（ANDROID/IOS/MP/HARMONY_NEXT/QUICKAPP）', () => {
    expect([...activePlatforms].sort()).toEqual(['ANDROID', 'HARMONY_NEXT', 'IOS', 'MP', 'QUICKAPP'])
  })

  it('引擎 platform_codes 引用完整：声明的平台码必在平台全集内', () => {
    for (const [code, eng] of engineOf) {
      for (const p of eng.platforms) {
        expect(platformOf.has(p), `引擎 ${code} 声明平台 ${p} 无平台行=悬空引用`).toBe(true)
      }
    }
  })

  it('每个活跃平台至少有一个启用引擎声明覆盖（首装即有可跑面）', () => {
    for (const p of activePlatforms) {
      const covering = [...engineOf].filter(([, e]) => e.status === 1 && e.platforms.includes(p))
      expect(covering.length, `活跃平台 ${p} 零启用引擎覆盖`).toBeGreaterThan(0)
    }
  })

  it('executor 能力装载表与描述符同源：键⊆引擎码集，停用候补引擎禁进上报面', () => {
    const reqBlock = EXEC_CAPS.slice(EXEC_CAPS.indexOf('ENGINE_REQUIREMENTS'), EXEC_CAPS.indexOf('}', EXEC_CAPS.indexOf('}', EXEC_CAPS.indexOf('ENGINE_REQUIREMENTS'))))
    const reqEngines = [...EXEC_CAPS.matchAll(/^\s{4}"([A-Z_]+)":\s*\{/gm)]
      .map((x) => x[1])
      .filter((x) => !['tools', 'runtimes', 'python_imports', 'host_os', 'env_dirs'].includes(x))
      // Load engines (K6/GATLING/JMETER) belong to the perf engine domain
      // (tp_perf scenario engine CHECK in schema-init), not the appauto
      // descriptor table this contract reconciles against. The excluded set
      // is parsed from that CHECK instead of hard-coded, so widening the
      // perf family (V354 added JMETER) cannot break this contract again.
      .filter((x) => !PERF_ENGINES.has(x))
    expect(reqEngines.length, 'executor ENGINE_REQUIREMENTS 不可解析').toBeGreaterThan(0)
    for (const e of reqEngines) {
      expect(engineOf.has(e), `executor 装载未知引擎 ${e}=描述符外私设`).toBe(true)
    }
    const disabled = [...engineOf].filter(([, e]) => e.status === 0).map(([c]) => c)
    for (const e of disabled) {
      expect(reqEngines, `停用引擎 ${e} 混入 executor 上报面`).not.toContain(e)
    }
    expect(reqBlock.length).toBeGreaterThan(0)
  })
})

describe('出厂字典族⑤目标→平台三分支：前端镜像 ↔ core 派发裁决单源', () => {
  const coreBlock = DISPATCH.slice(
    DISPATCH.indexOf('wantedPlatformsForTarget(String target)'),
    DISPATCH.indexOf('wantedPlatformsForTarget(String target)') + 400,
  )
  const coreMap = new Map<string, string[]>()
  for (const m of coreBlock.matchAll(/case "(\w+)" -> List\.of\(([^)]+)\)/g)) {
    coreMap.set(m[1], [...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]))
  }
  const coreDefault = /default -> List\.of\(([^)]+)\)/.exec(coreBlock)
  expect(coreDefault, 'core wantedPlatformsForTarget default 分支不可解析').not.toBeNull()
  const coreDefaultList = [...coreDefault![1].matchAll(/"([^"]+)"/g)].map((x) => x[1])

  it('core 三分支可解析且前端 wantedPlatformsForTarget 逐分支一致', () => {
    expect(coreMap.size, 'core 平台映射分支不可解析').toBeGreaterThan(0)
    for (const [target, platforms] of coreMap) {
      expect(wantedPlatformsForTarget(target), `目标 ${target} 平台覆盖面漂移`).toEqual(platforms)
    }
    for (const t of MOBILE_FAMILY_TARGETS) {
      if (coreMap.has(t)) continue
      expect(wantedPlatformsForTarget(t), `移动端族 ${t} 未落 default 覆盖面`).toEqual(coreDefaultList)
    }
    expect(wantedPlatformsForTarget('web')).toEqual(coreDefaultList)
    expect(wantedPlatformsForTarget(null)).toEqual(coreDefaultList)
  })

  it('core normalizeTarget 值域含 web/app/mp/harmony/api 五档（T342 扩 harmony / T368 扩 api 出厂锚）', () => {
    const m = /case ("(?:\w+"(?:,\s*")?)*)\s*->\s*t;/.exec(DISPATCH)
    expect(m, 'core normalizeTarget 白名单不可解析').not.toBeNull()
    const targets = [...m![1].matchAll(/"(\w+)"/g)].map((x) => x[1])
    expect(targets).toEqual(['web', 'app', 'mp', 'harmony', 'api'])
  })
})
