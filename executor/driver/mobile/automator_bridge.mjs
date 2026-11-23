//

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const send = (frame) => process.stdout.write(JSON.stringify(frame) + '\n');

let enginePromise = null;
async function loadEngine() {
  if (!enginePromise) {
    const testModule = process.env.AUTOMATOR_TEST_ENGINE_MODULE;
    enginePromise = (testModule ? import(testModule) : import('miniprogram-automator'))
      .then((m) => m.default ?? m);
  }
  return enginePromise;
}

let miniProgram = null;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function currentPage() {
  const page = await miniProgram.currentPage();
  if (!page) throw new Error('小程序当前页不可达（开发者工具会话可能已断开）');
  return page;
}

async function locateWeb(params) {
  const hostSel = String(params.webViewSelector || 'web-view').trim();
  const page = await currentPage();
  const host = await page.$(hostSel);
  if (!host) {
    return [null, { result: 'fail',
      detail: `WebView 落区步失败：当前页（${page.path || '?'}）未找到 web-view 宿主节点`
        + `「${hostSel}」——请确认该步确实落 WebView 区域（小程序 web-view 组件在位），`
        + '或修正步骤的 webViewSelector 参数（禁回落原生层冒充命中）' }];
  }
  let web = null;
  try {
    web = typeof host.web === 'function' ? await host.web() : null;
  } catch { web = null; }
  if (!web || typeof web.$ !== 'function') {
    return [null, { result: 'fail',
      detail: 'WebView 落区步失败：web-view 宿主节点的 web 域不可达（host.web() 缺位'
        + '或会话异常）——请确认开发者工具版本支持 web-view 自动化，或换 Appium'
        + '(WEBVIEW context)/Mobilewright(无障碍树穿透) 执行（IR 动词不变）' }];
  }
  const selector = String(params.selector || params.testId || params.accessibilityId || '').trim();
  if (!selector) {
    return [null, { result: 'fail',
      detail: 'WebView 落区步缺 web 域选择器（selector）——automator web-view 内按'
        + '页面选择器定位，信封无选择器无法凭坐标猜元素，请修正脚本步参数' }];
  }
  const el = await web.$(selector);
  if (!el) {
    return [null, { result: 'fail',
      detail: `WebView 落区步元素未找到：${selector}（web-view 域，宿主 ${hostSel}，`
        + `当前页 ${page.path || '?'}）——禁静默回落原生层` }];
  }
  return [el, null];
}

async function locate(params) {
  const selector = String(params.selector || params.testId || params.accessibilityId || '').trim();
  if (!selector) {
    return [null, { result: 'fail',
      detail: '缺少定位器（selector）——automator 小程序步按页面元素选择器定位，'
        + '信封无选择器无法凭坐标猜元素，请修正脚本步参数' }];
  }
  const page = await currentPage();
  const el = await page.$(selector);
  if (!el) {
    return [null, { result: 'fail', detail: `元素未找到：${selector}（当前页 ${page.path || '?'}）` }];
  }
  return [el, null];
}

async function locateStep(params) {
  return params.webview === true ? locateWeb(params) : locate(params);
}

async function runVerb(verb, params) {
  switch (verb) {
    case 'launch':
      await miniProgram.reLaunch(String(params.page || '/'));
      return { result: 'pass' };
    case 'close':
      await miniProgram.close();
      return { result: 'pass' };
    case 'tap': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.tap();
      return { result: 'pass' };
    }
    case 'longPress': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.longpress();
      return { result: 'pass' };
    }
    case 'doubleTap': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.tap();
      await sleep(Math.max(Number(params.intervalMs) || 200, 1));
      await el.tap();
      return { result: 'pass' };
    }
    case 'input': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.input(String(params.text ?? params.value ?? ''));
      return { result: 'pass' };
    }
    case 'clearInput': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.input('');
      return { result: 'pass' };
    }
    case 'swipe': {
      const coords = [params.x1, params.y1, params.x2, params.y2];
      if (coords.some((v) => v === undefined || v === null)) {
        return { result: 'fail', detail: 'swipe 缺滑动起止坐标（x1/y1/x2/y2 四值必备）'
          + '——请补脚本坐标或改用 scroll' };
      }
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.touchstart({ x: coords[0], y: coords[1] });
      await el.touchmove({ x: coords[2], y: coords[3] });
      await el.touchend({ x: coords[2], y: coords[3] });
      return { result: 'pass' };
    }
    case 'scroll': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      await el.scrollTo(Number(params.x ?? 0), Number(params.y ?? 0));
      return { result: 'pass' };
    }
    case 'back':
      await miniProgram.navigateBack();
      return { result: 'pass' };
    case 'screenshot':
      return { result: 'pass' };
    case 'assertVisible': {
      if (params.webview === true) {
        const [el, err] = await locateWeb(params);
        return err ?? { result: 'pass' };
      }
      const page = await currentPage();
      const el = await page.$(String(params.selector || ''));
      return el ? { result: 'pass' }
        : { result: 'fail', detail: `断言失败：页面未出现元素 ${params.selector}` };
    }
    case 'assertNotVisible': {
      if (params.webview === true) {
        const [el, err] = await locateWeb(params);
        if (el) return { result: 'fail',
          detail: `断言失败：预期不出现的元素 ${params.selector} 仍在 web-view 域` };
        if (err && !err.detail.includes('元素未找到')) return err;
        return { result: 'pass' };
      }
      const page = await currentPage();
      const el = await page.$(String(params.selector || ''));
      return el ? { result: 'fail', detail: `断言失败：预期不出现的元素 ${params.selector} 仍在页面` }
        : { result: 'pass' };
    }
    case 'assertText': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      const actual = String(await el.text() ?? '');
      const expectText = String(params.text ?? '');
      const mode = params.mode || 'contains';
      const hit = mode === 'equals' ? actual === expectText
        : mode === 'regex' ? new RegExp(expectText).test(actual)
        : actual.includes(expectText);
      return hit ? { result: 'pass' }
        : { result: 'fail', detail: `文案断言不符：模式 ${mode} 预期「${expectText}」实际「${actual}」` };
    }
    case 'waitVisible': {
      const web = params.webview === true;
      const page = await currentPage();
      const timeoutMs = Math.min(Math.max(Number(params.timeout) || 5, 1), 120) * 1000;
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        const el = web
          ? (await locateWeb(params))[0]
          : await page.$(String(params.selector || ''));
        if (el) return { result: 'pass' };
        if (Date.now() >= deadline) {
          return { result: 'fail', detail: `等待超时（${timeoutMs / 1000}s）：元素 ${params.selector} 未出现` };
        }
        await sleep(200);
      }
    }
    case 'extractText': {
      const [el, err] = await locateStep(params);
      if (err) return err;
      return { result: 'pass', extracted: String(await el.text() ?? '') };
    }
    case 'deepLink':
    case 'openUrl': {
      const url = String(params.url ?? '').trim();
      if (!url) return { result: 'fail', errorType: 1,
        detail: `${verb} 缺 url 参数（步骤信封为空）——请修正脚本步参数` };
      await miniProgram.callWxMethod('navigateTo', { url });
      return { result: 'pass' };
    }
    default:
      return { result: 'fail', detail: `动词 ${verb} 不在 automator 映射面（越界应由 Python 侧先闸，双闸防御点名）` };
  }
}

const rl = readline.createInterface({ input: process.stdin });

rl.on('line', async (line) => {
  let frame;
  try {
    frame = JSON.parse(line);
  } catch {
    send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: `桥帧不是合法 JSON: ${line.slice(0, 120)}` } });
    return;
  }
  const { id, method, params = {} } = frame;
  try {
    let result;
    if (method === 'prepare') {
      const eng = await loadEngine();
      const port = params.autoPort || 9420;
      try {
        miniProgram = await eng.launcher.connect({ wsEndpoint: `ws://127.0.0.1:${port}` });
      } catch {
        miniProgram = await eng.launcher.launch({
          projectPath: params.projectPath,
          cliPath: params.cliPath || undefined,
          port,
        });
      }
      result = { ok: true, engine: 'miniprogram-automator' };
    } else if (method === 'execute') {
      if (!miniProgram) {
        result = { result: 'fail', errorType: 2, detail: 'automator 会话未建立（prepare 未执行）' };
      } else {
        result = await runVerb(params.verb, params);
      }
    } else if (method === 'collect') {
      const dir = params.artifactDir || '/tmp/testpike-mpa-artifacts';
      fs.mkdirSync(dir, { recursive: true });
      const artifacts = [];
      const file = path.join(dir, `step-${params.stepNo || 0}-automator.png`);
      await miniProgram.screenshot({ path: file });
      artifacts.push({ kind: 'screenshot', localPath: file });
      try {
        const page = await miniProgram.currentPage();
        const data = page ? await page.data() : null;
        const treeFile = path.join(dir, `step-${params.stepNo || 0}-viewtree.json`);
        if (!data) throw new Error('empty page data');
        fs.writeFileSync(treeFile, JSON.stringify({ path: page.path ?? null, data }));
        artifacts.push({ kind: 'dom_snapshot', localPath: treeFile,
                         contentType: 'application/json' });
      } catch {  }
      result = { artifacts };
    } else if (method === 'teardown') {
      if (miniProgram) {
        try { await miniProgram.close(); } catch {  }
        try { miniProgram.disconnect(); } catch {  }
      }
      miniProgram = null;
      result = { ok: true };
    } else if (method === 'ping') {
      result = { ok: true };
    } else {
      result = { ok: false, error: `未知桥方法：${method}` };
    }
    send({ jsonrpc: '2.0', id, result });
  } catch (e) {
    send({ jsonrpc: '2.0', id, error: { code: -32000, message: String(e && e.message || e) } });
  }
});
