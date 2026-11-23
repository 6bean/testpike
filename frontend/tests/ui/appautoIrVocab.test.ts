
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { ACTION_VOCAB, APPAUTO_CAPABILITY_FLAGS, vocabForTarget } from '@/config/actionVocab'


const JAVA_SRC = readFileSync(
  fileURLToPath(
    new URL(
      '../../../common/src/main/java/com/sixbean/testpike/common/util/UiActionContract.java',
      import.meta.url,
    ),
  ),
  'utf-8',
)

function javaListMember(name: string): string[] {
  const re = new RegExp(`${name}\\s*=\\s*(?:List|Set)\\.of\\(([\\s\\S]*?)\\)`)
  const m = re.exec(JAVA_SRC)
  expect(m, `common UiActionContract.java 必须含 ${name} 定义（对账锚）`).not.toBeNull()
  return [...m![1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
}

const JAVA_IR_VERBS = javaListMember('IR_VERBS')
const JAVA_CAPABILITY_FLAGS = javaListMember('CAPABILITY_FLAGS')

describe('T303 app.* IR 词表与 Java 单源漂移对账', () => {
  it('Java IR_VERBS 三十一枚+FLAGS 五枚在位（解析锚自检，防正则姿势失效假绿）', () => {
    expect(JAVA_IR_VERBS).toHaveLength(31)
    expect(JAVA_CAPABILITY_FLAGS).toHaveLength(5)
  })

  it('前端词表 app.* 组与 IR_VERBS 逐枚一致（顺序敏感）', () => {
    const frontVerbs = ACTION_VOCAB
      .filter((s) => s.action.startsWith('app.'))
      .map((s) => s.action.slice('app.'.length))
    expect(frontVerbs).toEqual(JAVA_IR_VERBS)
  })

  it('能力特性旗前端镜像与 Java CAPABILITY_FLAGS 一致（排序比对）', () => {
    expect([...APPAUTO_CAPABILITY_FLAGS].sort()).toEqual([...JAVA_CAPABILITY_FLAGS].sort())
  })
})

describe('T303 app.* 动词表单结构（04 §2.2/§2.3 字段权威）', () => {
  const specOf = (verb: string) => ACTION_VOCAB.find((s) => s.action === `app.${verb}`)

  it('每枚 app.* 动词 contract 钉 04 §2.2 + label 走 vocab.action.*（i18n 双轨前置）', () => {
    for (const verb of JAVA_IR_VERBS) {
      const spec = specOf(verb)
      expect(spec, `词表缺 app.${verb}`).toBeDefined()
      expect(spec!.contract, `app.${verb} 缺契约源标注`).toContain('04 §2.2')
      expect(spec!.label, `app.${verb} 缺 i18n 键`).toMatch(/^vocab\.action\./)
    }
  })

  it('定位类动词承载 locator 子表单（§2.3，accessibilityId mobile 优先）', () => {
    for (const verb of ['tap', 'longPress', 'doubleTap', 'input', 'clearInput',
      'assertVisible', 'assertNotVisible', 'assertText', 'waitVisible', 'extractText', 'scroll']) {
      expect(specOf(verb)?.fields.some((f) => f.type === 'locator'),
        `app.${verb} 缺定位器字段`).toBe(true)
    }
  })

  it('执行面参数动词字段在位（引擎适配器消费键名逐一对齐）', () => {
    expect(specOf('launch')?.fields.some((f) => f.key === 'pkg')).toBe(true)
    expect(specOf('launch')?.fields.some((f) => f.key === 'page')).toBe(true)
    expect(specOf('close')?.fields.some((f) => f.key === 'pkg')).toBe(true)
    expect(specOf('input')?.fields.some((f) => f.key === 'text')).toBe(true)
    expect(specOf('assertText')?.fields.some((f) => f.key === 'text')).toBe(true)
    expect(specOf('assertText')?.fields.some((f) => f.key === 'mode')).toBe(true)
    expect(specOf('longPress')?.fields.some((f) => f.key === 'duration')).toBe(true)
    expect(specOf('waitVisible')?.fields.some((f) => f.key === 'timeoutMs')).toBe(true)
    expect(specOf('swipe')?.fields.some((f) => f.key === 'direction')).toBe(true)
    expect(specOf('scroll')?.fields.some((f) => f.key === 'direction')).toBe(true)
  })

  it('back/screenshot 零必填字段（引擎侧 collect 统一产物面，表单不造多余键）', () => {
    expect(specOf('back')?.fields ?? []).toHaveLength(0)
    expect(specOf('screenshot')?.fields ?? []).toHaveLength(0)
  })
})

describe('T313 webview 步旗控件（混合落区步级旗入表单面）', () => {
  const specOf = (verb: string) => ACTION_VOCAB.find((s) => s.action === `app.${verb}`)

  
  const javaWebviewFlag = (() => {
    const m = /WEBVIEW_STEP_FLAG\s*=\s*"([^"]+)"/.exec(JAVA_SRC)
    expect(m, 'common UiActionContract.java 必须含 WEBVIEW_STEP_FLAG 定义（对账锚）').not.toBeNull()
    return m![1]
  })()

  it('旗键名对账：Java WEBVIEW_STEP_FLAG 为 webview（解析锚自检）', () => {
    expect(javaWebviewFlag).toBe('webview')
  })

  it('落区动词十一枚全挂 webview 布尔旗字段（键名=Java 单源，type=bool 禁自造控件）', () => {
    for (const verb of ['tap', 'longPress', 'doubleTap', 'input', 'clearInput', 'scroll',
      'assertVisible', 'assertNotVisible', 'assertText', 'waitVisible', 'extractText']) {
      const flag = specOf(verb)?.fields.find((f) => f.key === javaWebviewFlag)
      expect(flag, `app.${verb} 缺 ${javaWebviewFlag} 步旗字段`).toBeDefined()
      expect(flag!.type, `app.${verb}.${javaWebviewFlag} 必须 bool（StepTree el-switch 分派）`).toBe('bool')
      expect(flag!.label, `app.${verb}.${javaWebviewFlag} 缺 i18n 键`).toMatch(/^vocab\.field\./)
      expect(flag!.hint, `app.${verb}.${javaWebviewFlag} 缺人话说明`).toMatch(/^vocab\.hint\./)
    }
  })

  it('非落区动词（launch/close/swipe/back/screenshot）不挂步旗（落区语义仅作用于目标/文本类步）', () => {
    for (const verb of ['launch', 'close', 'swipe', 'back', 'screenshot']) {
      expect(specOf(verb)?.fields.some((f) => f.key === javaWebviewFlag),
        `app.${verb} 不应携带步旗字段`).toBe(false)
    }
  })

  it('旗字段挂动词组尾部（零必填语义，禁插队破坏既有表单序；动词序对账闸不受 fields 顺序影响）', () => {
    for (const spec of ACTION_VOCAB.filter((s) => s.action.startsWith('app.'))) {
      const idx = spec.fields.findIndex((f) => f.key === javaWebviewFlag)
      if (idx >= 0) expect(idx, `${spec.action} 步旗须为末位字段`).toBe(spec.fields.length - 1)
    }
  })
})

describe('T303 坑 d：app.* 组仅 app/mp 目标入候选（Web 零回归）', () => {
  const appSpecs = ACTION_VOCAB.filter((s) => s.action.startsWith('app.'))
  const webSpecs = ACTION_VOCAB.filter((s) => !s.action.startsWith('app.'))

  it('web/缺省目标候选零 app.* 步且=纯 Web 词表全集', () => {
    expect(vocabForTarget('web').some((s) => s.action.startsWith('app.'))).toBe(false)
    expect(vocabForTarget(undefined).some((s) => s.action.startsWith('app.'))).toBe(false)
    expect(vocabForTarget('web')).toEqual(webSpecs)
  })

  it('app/mp 目标候选含全部 app.* 三十一枚+Web 全集（混合编排合法，派发闸裁决）', () => {
    for (const target of ['app', 'mp']) {
      const got = vocabForTarget(target)
      expect(got.filter((s) => s.action.startsWith('app.'))).toEqual(appSpecs)
      expect(got).toHaveLength(webSpecs.length + 31)
    }
  })

  it('T342 包③：harmony 目标同属移动端族=app.* 组入候选（动词表随目标族裁剪，禁漏档）', () => {
    const got = vocabForTarget('harmony')
    expect(got.filter((s) => s.action.startsWith('app.'))).toEqual(appSpecs)
    expect(got).toHaveLength(webSpecs.length + 31)
  })
})
