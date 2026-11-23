
const calls = [];
const record = (name, args) => calls.push({ name, args });

function makeLocator(key, value) {
  return {
    tap: async () => record(`locator.${key}.tap`, value),
    doubleTap: async () => record(`locator.${key}.doubleTap`, value),
    longPress: async () => record(`locator.${key}.longPress`, value),
    fill: async (t) => record('locator.fill', { [key]: value, text: t }),
    clear: async () => record('locator.clear', value),
    isVisible: async () => true,
    waitFor: async () => true,
    getText: async () => (globalThis.__MW_FAKE_TEXT ?? '欢迎'),
    scrollIntoViewIfNeeded: async () => record('locator.scrollIntoView', value),
  };
}

const screen = {
  tap: async (x, y) => record('screen.tap', { x, y }),
  doubleTap: async (x, y) => record('screen.doubleTap', { x, y }),
  longPress: async (x, y, d) => record('screen.longPress', { x, y, d }),
  swipe: async (dir) => record('screen.swipe', dir),
  goBack: async () => record('screen.goBack'),
  screenshot: async () => Buffer.from('fakepng'),
  viewTree: async () => {
    record('screen.viewTree');
    const webChild = process.env.MOBILEWRIGHT_FAKE_TREE_NOWEBVIEW === '1' ? [] : [{
      type: 'WebView', text: '登录', resourceId: 'web:login', isVisible: true,
      bounds: { x: 0, y: 100, width: 1080, height: 1700 }, children: [],
    }];
    return [{ type: 'FrameLayout', isVisible: true, isEnabled: true,
              bounds: { x: 0, y: 0, width: 1080, height: 1920 },
              children: [{ type: 'Button', text: '登录', resourceId: 'com.demo:id/login',
                           isVisible: true, isEnabled: true,
                           bounds: { x: 10, y: 20, width: 100, height: 50 }, children: [] },
                         ...webChild] }];
  },
  getByTestId: (v) => makeLocator('testId', v),
  getByText: (v) => makeLocator('text', v),
  getByPlaceholder: (v) => makeLocator('placeholder', v),
  getByLabel: (v) => makeLocator('label', v),
};

const device = {
  id: 'fake-emulator-1',
  screen,
  launchApp: async (pkg) => record('device.launchApp', pkg),
  terminateApp: async (pkg) => record('device.terminateApp', pkg),
  close: async () => record('device.close'),
};

export const android = {
  launch: async (opts) => {
    record('android.launch', opts);
    if (process.env.MOBILEWRIGHT_DISABLE_TELEMETRY !== '1') {
      throw new Error('遥测未关闭（开源合规红线：MOBILEWRIGHT_DISABLE_TELEMETRY=1 必注入）');
    }
    return device;
  },
};

export const __calls = calls;
