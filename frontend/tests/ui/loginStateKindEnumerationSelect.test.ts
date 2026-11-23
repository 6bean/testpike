/**
 * Login-state kind enumeration contract (SE5: closed-enum fields are chosen,
 * never typed).
 *
 * The server validates `kind` against a closed set (SessionStateSupport.KINDS
 * = token / storage_state / cookie_set). A free-text input on that field turns
 * a closed set into a typo magnet (user report: typing `cookie_sets` got
 * - the register dialog renders an el-select on the kind field, no el-input
 * bound to createForm.kind anywhere on the page;
 * - exactly the three server enum values are offered, each label carrying the
 * enum literal plus an effect sentence from locale (both languages);
 * - the select is exempt from searchability (static 3 options) with a
 * machine-checkable data-se1-exempt reason;
 * - the form rule switches to the `change` trigger (selects never fire blur);
 * - the server-side rejection sentence keeps listing the legal values as the
 * second-line defence for direct API calls (error guidance: reject sentences
 * must show the correct spelling);
 * - frontend option values stay byte-identical to the Java KINDS constant
 * (single source of truth drift detector).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const ROOT = join(__dirname, '..', '..', '..')
const SRC = join(ROOT, 'frontend', 'src')
const viewSrc = readFileSync(join(SRC, 'views', 'session', 'LoginStateListView.vue'), 'utf8')
const zhSrc = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
const enSrc = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')
const kindsJava = readFileSync(
  join(ROOT, 'core', 'src', 'main', 'java', 'com', 'sixbean', 'testpike', 'core', 'session',
    'domain', 'component', 'SessionStateSupport.java'),
  'utf8',
)

const ENUM_VALUES = ['token', 'storage_state', 'cookie_set']

/** Extract the attributes of the tag that carries the given data-test anchor. */
function attrsOfAnchoredTag(src: string, anchor: string): string {
  const idx = src.indexOf(`data-test="${anchor}"`)
  expect(idx, `anchor ${anchor} must exist`).toBeGreaterThan(-1)
  const open = src.lastIndexOf('<', idx)
  const close = src.indexOf('>', idx)
  return src.slice(open, close === -1 ? src.length : close)
}

/** Return the locale block that defines the sessionState.* keys. */
function sessionStateBlock(localeSrc: string): string {
  const start = localeSrc.indexOf('sessionState: {')
  expect(start, 'sessionState locale block must exist').toBeGreaterThan(-1)
  const end = localeSrc.indexOf('\n  },', start)
  expect(end, 'sessionState locale block must close').toBeGreaterThan(start)
  return localeSrc.slice(start, end)
}

describe('SE5: login-state kind is a closed enumeration select, not free text', () => {
  it('kind field renders an el-select and no el-input is bound to createForm.kind', () => {
    const attrs = attrsOfAnchoredTag(viewSrc, 'login-state-create-kind')
    expect(attrs, 'kind control must be an el-select').toContain('<el-select')
    expect(viewSrc).not.toMatch(/<el-input[^>]*v-model="createForm\.kind"/)
  })

  it('the select states a machine-checkable searchability exemption (static 3 options)', () => {
    const attrs = attrsOfAnchoredTag(viewSrc, 'login-state-create-kind')
    expect(attrs).toMatch(/data-se1-exempt="[^"]{4,}"/)
  })

  it('exactly the three server enum values are offered with locale-labelled effect sentences', () => {
    for (const value of ENUM_VALUES) {
      expect(viewSrc, `option value ${value} missing`).toContain(`'${value}'`)
    }
    // Option labels must resolve through t(...) locale keys, not raw literals.
    expect(viewSrc).toMatch(/kindOptionToken|kindOption\./)
    for (const [name, block] of [['zh', sessionStateBlock(zhSrc)], ['en', sessionStateBlock(enSrc)]] as const) {
      for (const value of ENUM_VALUES) {
        const camel = value
          .replace(/_(\w)/g, (_, c) => c.toUpperCase())
          .replace(/^(\w)/, (_, c) => c.toUpperCase())
        const re = new RegExp(`kindOption${camel}:\\s*'[^']*${value}[^']*'`)
        expect(block, `${name} locale must label option ${value} with the enum literal`).toMatch(re)
      }
    }
  })

  it('form rule for kind uses the change trigger and asks the user to choose', () => {
    expect(viewSrc).toMatch(/kind: \[\{[^}]*trigger: 'change'/)
    expect(sessionStateBlock(zhSrc)).toMatch(/kindRequired: '请选择/)
  })

  it('server keeps the enum check with the correct spellings listed (API direct-call defence)', () => {
    const impl = readFileSync(
      join(ROOT, 'core', 'src', 'main', 'java', 'com', 'sixbean', 'testpike', 'core', 'session',
        'domain', 'service', 'impl', 'LoginStateServiceImpl.java'),
      'utf8',
    )
    expect(impl).toMatch(/SessionStateSupport\.KINDS\.contains\(input\.kind\(\)\)/)
    // Error guidance iron law: the rejection sentence must show the legal values.
    expect(impl).toContain('token / storage_state / cookie_set')
  })

  it('frontend option set is byte-identical to the Java KINDS constant', () => {
    const m = kindsJava.match(/KINDS\s*=\s*List\.of\(([^)]*)\)/)
    expect(m, 'KINDS constant not found').not.toBeNull()
    const javaValues = m![1].split(',').map((s) => s.trim().replace(/^"|"$/g, ''))
    expect(javaValues).toEqual(ENUM_VALUES)
  })
})
