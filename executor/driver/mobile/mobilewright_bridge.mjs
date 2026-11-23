//

import readline from 'node:readline';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

// Map an adb serial to the identifier mobilecli/mobilewright uses to select a
// device. mobilecli keys Android emulators by AVD name (e.g. "w5b-avd34"), not
// by the adb serial (e.g. "emulator-5554"), so a raw serial never matches the
// driver filter and the engine reports "no online android device found".
// Emulator serials are resolved through the adb console command `emu avd
// name`; real-device serials and values that already look like mobilecli ids
// pass through unchanged. Resolution stays on this bridge side so the Python
// layer keeps one device-selection source and no second device enumeration.
function resolveDeviceId(serialOrId) {
  const value = String(serialOrId || '');
  if (!value || !/^emulator-\d+$/.test(value)) return serialOrId;
  let out = '';
  try {
    out = execFileSync('adb', ['-s', value, 'emu', 'avd', 'name'],
                       { encoding: 'utf8', timeout: 20000 });
  } catch (e) {
    throw new Error(`cannot resolve AVD name for emulator serial ${value}: ${String(e && e.message || e)}`);
  }
  const avd = out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0];
  if (!avd) throw new Error(`adb emu avd name returned no AVD name for serial ${value}`);
  return avd;
}

const send = (frame) => process.stdout.write(JSON.stringify(frame) + '\n');

let enginePromise = null;
async function loadEngine() {
  if (!enginePromise) {
    const testModule = process.env.MW_TEST_ENGINE_MODULE;
    enginePromise = testModule
      ? import(testModule)
      : import('mobilewright');
  }
  return enginePromise;
}

let device = null;


function findWebviewNodes(tree) {
  const out = [];
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    const type = String(node.type || '');
    if (/webview/i.test(type)) out.push(node);
    (node.children || []).forEach(walk);
  };
  (Array.isArray(tree) ? tree : [tree]).forEach(walk);
  return out;
}

function matchWebTarget(webNodes, params) {
  const wantId = params.testId || params.accessibilityId || params.selector;
  const wantText = params.text;
  const hits = [];
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    if ((wantId && String(node.resourceId || '') === String(wantId))
      || (wantText && String(node.text || '') === String(wantText))) hits.push(node);
    (node.children || []).forEach(walk);
  };
  webNodes.forEach(walk);
  return hits;
}

async function probeWebviewTarget(params) {
  let tree;
  try {
    tree = await device.screen.viewTree();
  } catch (e) {
    return [null, `无障碍树不可读（${String(e && e.message || e)}）——WebView 穿透`
      + '依赖视图树面，driver 会话异常时禁盲猜定位'];
  }
  const webNodes = findWebviewNodes(tree);
  if (webNodes.length === 0) {
    return [null, '无障碍树穿透未命中 WebView 落区（树内无 webview 节点）——目标页'
      + '可能未打开 WebView 内容，或 web 内容未进入无障碍树（混合载体请确认应用已'
      + '启用 web 内容无障碍呈现）'];
  }
  const targetKeys = params.testId || params.accessibilityId || params.selector || params.text;
  if (!targetKeys) return [webNodes[0], null];
  const hits = matchWebTarget(webNodes, params);
  if (hits.length === 0) {
    return [null, `WebView 落区内目标未命中（无障碍树穿透：text/resourceId 均不匹配 `
      + `${String(targetKeys)}）——禁静默回落原生层同名元素冒充命中`];
  }
  return [hits[0], null];
}

async function runVerb(verb, params) {
  if (params.webview === true) {
    const [node, why] = await probeWebviewTarget(params);
    if (!node) {
      return { result: 'fail', detail: `Mobilewright WebView 落区步失败：${why}`
        + '——请确认该步确实落 WebView 区域，或换 Appium(WEBVIEW context)/automator'
        + '(web 节点) 执行（IR 动词不变，换引擎零改用例）' };
    }
    if (params.x === undefined && node.bounds && verb === 'tap') {
      const b = node.bounds;
      await device.screen.tap(b.x + b.width / 2, b.y + b.height / 2);
      return { result: 'pass' };
    }
  }
  const screen = device.screen;
  const locator = buildLocator(params);
  const needsLocator = !['launch', 'close', 'swipe', 'scroll', 'back', 'screenshot'].includes(verb)
    && !(params.x !== undefined && params.y !== undefined);
  if (needsLocator && !locator) {
    return { result: 'fail', detail: `动词 ${verb} 需要定位器（testId/text/placeholder/label）`
      + '或坐标（x/y），信封两者皆缺——请修正脚本步参数' };
  }
  switch (verb) {
    case 'launch':
      await device.launchApp(params.pkg || params.bundleId || params.appId,
        { timeout: params.timeoutMs });
      return { result: 'pass' };
    case 'close':
      await device.terminateApp(params.pkg || params.bundleId || params.appId);
      return { result: 'pass' };
    case 'tap':
      if (params.x !== undefined && params.y !== undefined) { await screen.tap(params.x, params.y); return { result: 'pass' }; }
      await locator.tap(); return { result: 'pass' };
    case 'longPress':
      if (params.x !== undefined && params.y !== undefined) { await screen.longPress(params.x, params.y, params.duration); return { result: 'pass' }; }
      await locator.longPress({ duration: params.duration }); return { result: 'pass' };
    case 'doubleTap':
      if (params.x !== undefined && params.y !== undefined) { await screen.doubleTap(params.x, params.y); return { result: 'pass' }; }
      await locator.doubleTap(); return { result: 'pass' };
    case 'input':
      await locator.fill(String(params.text ?? params.value ?? '')); return { result: 'pass' };
    case 'clearInput':
      await locator.clear(); return { result: 'pass' };
    case 'swipe':
      await screen.swipe(params.direction || 'up'); return { result: 'pass' };
    case 'scroll':
      if (locator) { await locator.scrollIntoViewIfNeeded(); return { result: 'pass' }; }
      await screen.swipe(params.direction || 'up'); return { result: 'pass' };
    case 'back':
      await screen.goBack(); return { result: 'pass' };
    case 'screenshot':
      await screen.screenshot();
      return { result: 'pass' };
    case 'assertVisible':
    case 'waitVisible': {
      const ok = verb === 'waitVisible'
        ? await waitVisible(locator, params.timeoutMs)
        : await locator.isVisible();
      return ok ? { result: 'pass' }
        : { result: 'fail', detail: `元素不可见：${describeLocator(params)}` };
    }
    case 'assertNotVisible': {
      const vis = await locator.isVisible();
      return !vis ? { result: 'pass' }
        : { result: 'fail', detail: `元素仍可见（预期不存在/隐藏）：${describeLocator(params)}` };
    }
    case 'assertText': {
      const actual = await locator.getText();
      const expectText = String(params.text ?? '');
      const mode = params.mode || 'contains';
      const hit = mode === 'equals' ? actual === expectText
        : mode === 'regex' ? new RegExp(expectText).test(actual)
        : actual.includes(expectText);
      return hit ? { result: 'pass' }
        : { result: 'fail', detail: `文本断言不符：期望包含「${expectText}」实际「${actual}」` };
    }
    case 'extractText': {
      const text = await locator.getText();
      return { result: 'pass', extracted: text };
    }
    default:
      return { result: 'fail', detail: `动词 ${verb} 不在 mobilewright 映射面（越界应由 Python 侧先闸，双闸防御点名）` };
  }
}

function buildLocator(params) {
  if (params.testId) return device.screen.getByTestId(params.testId);
  if (params.accessibilityId) return device.screen.getByTestId(params.accessibilityId);
  if (params.text) return device.screen.getByText(params.text,
    params.exact ? { exact: true } : undefined);
  if (params.placeholder) return device.screen.getByPlaceholder(params.placeholder);
  if (params.label) return device.screen.getByLabel(params.label);
  return null;
}

function describeLocator(params) {
  return params.testId || params.text || params.placeholder || params.label
    || params.accessibilityId || '(无定位器)';
}

async function waitVisible(locator, timeoutMs) {
  try {
    await locator.waitFor({ state: 'visible', timeout: timeoutMs });
    return true;
  } catch {
    return false;
  }
}

function writeArtifact(dir, buf) {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `step-${Date.now()}.png`);
  fs.writeFileSync(file, buf);
  return file;
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
  const { id, method, params } = frame;
  try {
    let result;
    if (method === 'prepare') {
      const engine = await loadEngine();
      const { android } = engine;
      device = await android.launch({
        deviceId: resolveDeviceId(params.serial || params.deviceId),
        bundleId: params.pkg || undefined,
        autoAppLaunch: false,
        animations: 'off',
      });
      result = { ok: true, engine: 'mobilewright', deviceId: device.id };
    } else if (method === 'execute') {
      result = await runVerb(params.verb, params);
    } else if (method === 'collect') {
      const dir = params.artifactDir || '/tmp/testpike-mw-artifacts';
      fs.mkdirSync(dir, { recursive: true });
      const artifacts = [];
      const buf = await device.screen.screenshot();
      artifacts.push({ kind: 'screenshot', localPath: writeArtifact(dir, buf) });
      try {
        const tree = await device.screen.viewTree();
        const treeFile = path.join(dir, `step-${params.stepNo || 0}-viewtree.json`);
        fs.writeFileSync(treeFile, JSON.stringify(tree ?? []));
        artifacts.push({ kind: 'dom_snapshot', localPath: treeFile,
                         contentType: 'application/json' });
      } catch {  }
      result = { artifacts };
    } else if (method === 'teardown') {
      if (device) await device.close();
      device = null;
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
