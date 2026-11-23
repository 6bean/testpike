//   launcher.launch/connect → MiniProgram{currentPage/reLaunch/navigateBack/

const calls = [];
const record = (name, args) => calls.push({ name, args });

function makeElement(path) {
  return {
    tap: async () => record('element.tap', path),
    longpress: async () => record('element.longpress', path),
    input: async (v) => record('element.input', { path, value: v }),
    scrollTo: async (x, y) => record('element.scrollTo', { path, x, y }),
    text: async () => '欢迎',
    touchstart: async (o) => record('element.touchstart', o),
    touchmove: async (o) => record('element.touchmove', o),
    touchend: async (o) => record('element.touchend', o),
  };
}

function makeWebDomain() {
  return {
    $: async (selector) => {
      record('web.$', selector);
      if (selector === '#ghost') return null;
      return makeWebElement(selector);
    },
  };
}

function makeWebElement(path) {
  return {
    tap: async () => record('webElement.tap', path),
    longpress: async () => record('webElement.longpress', path),
    input: async (v) => record('webElement.input', { path, value: v }),
    scrollTo: async (x, y) => record('webElement.scrollTo', { path, x, y }),
    text: async () => '欢迎',
    touchstart: async (o) => record('webElement.touchstart', o),
    touchmove: async (o) => record('webElement.touchmove', o),
    touchend: async (o) => record('webElement.touchend', o),
  };
}

const page = {
  path: 'pages/index',
  $: async (selector) => {
    if (selector === '#ghost') return null;
    if (selector === 'web-view' || selector === 'webView') {
      return { ...makeElement(selector), web: async () => makeWebDomain() };
    }
    return selector ? makeElement(selector) : null;
  },
  data: async () => ({ greeting: 'hello' }),
};

const miniProgram = {
  currentPage: async () => page,
  reLaunch: async (url) => record('program.reLaunch', url),
  navigateBack: async () => record('program.navigateBack'),
  callWxMethod: async (name, opts) => record('program.callWxMethod', { name, opts }),
  screenshot: async (opts) => {
    record('program.screenshot', opts);
    const fs = await import('node:fs');
    const p = await import('node:path');
    if (opts && opts.path) {
      fs.mkdirSync(p.dirname(opts.path), { recursive: true });
      fs.writeFileSync(opts.path, Buffer.from([0x89, 0x50, 0x4e, 0x47]));
      return opts.path;
    }
    return '/tmp/fake.png';
  },
  close: async () => record('program.close'),
  disconnect: () => record('program.disconnect'),
};

export const launcher = {
  launch: async (opts) => {
    record('launcher.launch', opts);
    if (!opts || !opts.projectPath) {
      throw new Error('launch 缺 projectPath（环境档案项目路径必备）');
    }
    return miniProgram;
  },
  connect: async () => {
    throw new Error('fake: no devtools ws endpoint');
  },
};

export const __calls = calls;
