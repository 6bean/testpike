import { chromium } from process.env.TP_PLAYWRIGHT_DRIVER || '/home/tester/.hermes/hermes-agent/venv/lib/python3.11/site-packages/playwright/driver/package/index.mjs'

const BASE = 'http://localhost:5173'
const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await ctx.newPage()
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(e.message))
await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })

let exportsList
try {
  exportsList = await page.evaluate(async () => {
    const mod = await import('/src/composables/useAutoDraft.ts')
    return Object.keys(mod)
  })
} catch (e) {
  console.log('IMPORT-ERR:', String(e).split('\n')[0].slice(0, 160))
  exportsList = null
}
if (!exportsList) {
  console.log('== RED 确认：useAutoDraft 模块不存在（功能缺失）==')
  await browser.close()
  process.exit(1)
}
check('A0 模块导出完整',
  ['scheduleAutoDraft', 'clearAutoDraft', 'peekAutoDraft', 'autoDraftKey'].every((k) => exportsList.includes(k)),
  exportsList.join(','))

await page.addScriptTag({
  type: 'module',
  content: `import * as ad from '/src/composables/useAutoDraft.ts'; window.__ad = ad`,
})
await page.waitForFunction(() => !!window.__ad, null, { timeout: 10000 })

const keyInfo = await page.evaluate(() => ({
  create: window.__ad.autoDraftKey('case', 'create', 42),
  edit: window.__ad.autoDraftKey('case', 'edit', 7),
}))
check('K1 键=tp- 前缀+项目/实体维度', keyInfo.create.startsWith('tp-auto-draft:case:') && keyInfo.create !== keyInfo.edit && keyInfo.edit.includes('7'), JSON.stringify(keyInfo))

const debounceConst = await page.evaluate(() => window.__ad.AUTO_DRAFT_DEBOUNCE_MS)
check('A0b 默认防抖 8000ms（PRD 8s）', debounceConst === 8000, String(debounceConst))

const KEY = keyInfo.create
await page.evaluate((k) => {
  localStorage.clear()
  window.__form = { title: 't1', steps: [{ d: 'a' }] }
  window.__ad.scheduleAutoDraft(k, window.__form, 300)
}, KEY)
const immediate = await page.evaluate((k) => localStorage.getItem(k), KEY)
check('A1 防抖窗口内不写盘', immediate === null, String(immediate).slice(0, 40))
await page.waitForTimeout(700)
const raw1 = await page.evaluate((k) => localStorage.getItem(k), KEY)
let rec1 = null
try { rec1 = JSON.parse(raw1) } catch { /* RED */ }
check('A2 窗口后写盘 {data,savedAt}', !!rec1 && rec1.data?.title === 't1' && !!rec1.savedAt, String(raw1).slice(0, 90))
await page.evaluate((k) => { window.__form.title = 't2'; window.__ad.scheduleAutoDraft(k, window.__form, 300) }, KEY)
await page.waitForTimeout(700)
const rec2 = JSON.parse(await page.evaluate((k) => localStorage.getItem(k), KEY))
check('A3 再变更覆盖草稿', rec2.data.title === 't2', JSON.stringify(rec2.data).slice(0, 60))
await page.evaluate((k) => {
  localStorage.removeItem(k)
  window.__form.title = 'b1'
  window.__ad.scheduleAutoDraft(k, window.__form, 400)
  window.__form.title = 'b2'
}, KEY)
await page.waitForTimeout(800)
const recMerged = JSON.parse(await page.evaluate((k) => localStorage.getItem(k), KEY))
check('A4 到点写最新内容（活引用/防抖合并）', recMerged.data.title === 'b2', JSON.stringify(recMerged.data))
await page.evaluate((k) => {
  localStorage.removeItem(k)
  for (let i = 0; i < 5; i++) { window.__form.title = `n${i}`; window.__ad.scheduleAutoDraft(k, window.__form, 250) }
}, KEY)
await page.waitForTimeout(600)
const recStorm = JSON.parse(await page.evaluate((k) => localStorage.getItem(k), KEY))
check('A5 连续 5 次调度合并为一次写（最终态 n4）', recStorm.data.title === 'n4', JSON.stringify(recStorm.data))
await page.evaluate((k) => window.__ad.clearAutoDraft(k), KEY)

const cleared = await page.evaluate(async (k) => {
  window.__ad.scheduleAutoDraft(k, { title: 'x' }, 200)
  await new Promise((r) => setTimeout(r, 400))
  const before = localStorage.getItem(k)
  window.__ad.clearAutoDraft(k)
  return { before: !!before, after: localStorage.getItem(k) }
}, KEY)
check('D1 clearAutoDraft 删除（提交/丢弃路径）', cleared.before === true && cleared.after === null, JSON.stringify(cleared))
const cancelCheck = await page.evaluate(async (k) => {
  window.__ad.scheduleAutoDraft(k, { title: 'y' }, 300)
  window.__ad.clearAutoDraft(k)
  await new Promise((r) => setTimeout(r, 600))
  return localStorage.getItem(k)
}, KEY)
check('D2 清除取消待发定时器（不复活）', cancelCheck === null, String(cancelCheck))

const peekEmpty = await page.evaluate((k) => window.__ad.peekAutoDraft(k), 'tp-auto-draft:case:create:none')
check('B1 无草稿 peek=null', peekEmpty === null)
await page.evaluate((k) => localStorage.setItem(k, '{损坏JSON!!'), KEY)
const peekBad = await page.evaluate((k) => ({
  r: window.__ad.peekAutoDraft(k),
  gone: localStorage.getItem(k) === null,
}), KEY)
check('B2 损坏 JSON→null+坏键静默清除（T105 先例）', peekBad.r === null && peekBad.gone, JSON.stringify(peekBad))
await page.evaluate((k) => localStorage.setItem(k, JSON.stringify({ data: 'not-an-object', savedAt: new Date().toISOString() })), KEY)
const peekObj = await page.evaluate((k) => ({
  r: window.__ad.peekAutoDraft(k),
  gone: localStorage.getItem(k) === null,
}), KEY)
check('B3 data 非对象→null+清除', peekObj.r === null && peekObj.gone, JSON.stringify(peekObj))
const stale = new Date(Date.now() - 25 * 3600 * 1000).toISOString()
await page.evaluate(([k, ts]) => localStorage.setItem(k, JSON.stringify({ data: { title: 'old' }, savedAt: ts })), [KEY, stale])
const peekStale = await page.evaluate((k) => ({
  r: window.__ad.peekAutoDraft(k),
  gone: localStorage.getItem(k) === null,
}), KEY)
check('B4 过期>24h→null+清除', peekStale.r === null && peekStale.gone, JSON.stringify(peekStale))
const fresh = new Date(Date.now() - 60 * 1000).toISOString()
await page.evaluate(([k, ts]) => localStorage.setItem(k, JSON.stringify({ data: { title: 'ok' }, savedAt: ts })), [KEY, fresh])
const peekOk = await page.evaluate((k) => window.__ad.peekAutoDraft(k), KEY)
check('B5 有效草稿 peek={data,savedAt}', !!peekOk && peekOk.data.title === 'ok' && !!peekOk.savedAt, JSON.stringify(peekOk).slice(0, 90))
await page.evaluate((k) => window.__ad.clearAutoDraft(k), KEY)

const fallbackInfo = await page.evaluate(async (k) => {
  localStorage.clear()
  const big = { title: '大用例标题'.repeat(10), steps: [{ description: '超长步骤内容abcde'.repeat(200000), isAssert: 0 }] }
  window.__ad.scheduleAutoDraft(k, big, 150)
  await new Promise((r) => setTimeout(r, 600))
  const raw = localStorage.getItem(k)
  let p = null
  try { p = JSON.parse(raw) } catch { /* ignore */ }
  return {
    exists: !!raw,
    bytes: raw ? raw.length : 0,
    truncated: p?.truncated === true,
    hasSavedAt: !!p?.savedAt,
    titleKept: typeof p?.data?.title === 'string' && p.data.title.length > 0,
    bigDropped: !(p?.data?.steps?.[0]?.description?.length > 1000),
  }
}, KEY)
check('C1 >1MB 降级存摘要+truncated 标记', fallbackInfo.exists && fallbackInfo.truncated && fallbackInfo.hasSavedAt && fallbackInfo.titleKept && fallbackInfo.bigDropped && fallbackInfo.bytes < 1024 * 1024, JSON.stringify(fallbackInfo))
const peekFb = await page.evaluate((k) => window.__ad.peekAutoDraft(k), KEY)
check('C2 降级摘要仍可 peek（可恢复提示不丢）', !!peekFb && peekFb.truncated === true, JSON.stringify(peekFb).slice(0, 80))

const quotaInfo = await page.evaluate(async (k) => {
  const original = localStorage.setItem.bind(localStorage)
  localStorage.setItem(JSON.stringify({ data: { title: 'keepme' }, savedAt: new Date().toISOString() }).length >= 0 ? k : k, JSON.stringify({ data: { title: 'keepme' }, savedAt: new Date().toISOString() }))
  let threw = false
  try {
    Storage.prototype.setItem = function () { throw new Error('QuotaExceededError') }
    window.__ad.scheduleAutoDraft(k, { title: 'boom', steps: [] }, 100)
    await new Promise((r) => setTimeout(r, 400))
  } catch { threw = true } finally {
    Storage.prototype.setItem = original
  }
  const raw = localStorage.getItem(k)
  let p = null
  try { p = JSON.parse(raw) } catch { /* ignore */ }
  return { threw, kept: p?.data?.title === 'keepme' }
}, KEY)
check('C3 底层写异常静默（无未捕获错误、既有草稿不损坏）', quotaInfo.threw === false && quotaInfo.kept === true, JSON.stringify(quotaInfo))
check('C3b 全程无 pageerror', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200))

await page.evaluate(() => localStorage.clear())
await browser.close()
const failed = results.filter((r) => !r.ok)
console.log(`\n== T107 harness: ${results.length - failed.length}/${results.length} PASS ==`)
process.exit(failed.length ? 1 : 0)
