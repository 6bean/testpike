
UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "3.8.0",
  "hosts": {
    "windows": {
      "probe": ["node --version", "appium --version"],
      "install": [{"cmd": "npm install -g appium@3.8.0", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g appium"}]
    },
    "macos": {
      "probe": ["node --version", "appium --version"],
      "install": [{"cmd": "npm install -g appium@3.8.0", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g appium"}]
    },
    "linux": {
      "probe": ["node --version", "appium --version"],
      "install": [{"cmd": "npm install -g appium@3.8.0", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g appium"}]
    }
  },
  "manual_steps": [
    "node>=20 需先行具备（配方不代装 node 运行时，缺失时体检会点名）",
    "Android 侧需再装 uiautomator2 driver：appium driver install uiautomator2（iOS 侧见 XCUITest 引擎配方）",
    "设备侧 ADB/HDC 与模拟器由使用者自备（testPike 不随包分发驱动二进制）"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'APPIUM' AND install_recipe = '{}'::jsonb;

UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "12.15.0",
  "hosts": {
    "macos": {
      "probe": ["node --version", "appium --version", "xcrun --version"],
      "install": [
        {"cmd": "npm install -g appium@3.8.0", "mirror_rewrite": "registry"},
        {"cmd": "appium driver install xcuitest@12.15.0"}
      ],
      "uninstall": [
        {"cmd": "appium driver remove xcuitest"},
        {"cmd": "npm uninstall -g appium"}
      ]
    }
  },
  "manual_steps": [
    "Xcode 与命令行工具需自行安装并同意许可（sudo xcodebuild -license accept），Appium driver 无法代装",
    "iOS 模拟器需先启动（xcrun simctl list 可见目标机），真机需在设备上开启开发者模式",
    "先决条件=Appium 引擎已按配方安装；未装时本配方安装段会自带补装"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'XCUITEST' AND install_recipe = '{}'::jsonb;

UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "2.11.0",
  "hosts": {
    "windows": {
      "probe": ["java -version", "maestro --version"],
      "install": [
        {"cmd": "curl -fsSL -o %TEMP%\\maestro.zip https://github.com/mobile-dev-inc/maestro/releases/download/cli-2.11.0/maestro.zip"},
        {"cmd": "tar -xf %TEMP%\\maestro.zip -C C:\\maestro"},
        {"cmd": "setx PATH \"%PATH%;C:\\maestro\\maestro\\bin\""}
      ],
      "uninstall": [{"cmd": "rmdir /s /q C:\\maestro"}]
    },
    "macos": {
      "probe": ["java -version", "maestro --version"],
      "install": [
        {"cmd": "curl -fsSL -o /tmp/maestro.zip https://github.com/mobile-dev-inc/maestro/releases/download/cli-2.11.0/maestro.zip"},
        {"cmd": "unzip -o /tmp/maestro.zip -d $HOME/.maestro"}
      ],
      "uninstall": [{"cmd": "rm -rf $HOME/.maestro"}]
    },
    "linux": {
      "probe": ["java -version", "maestro --version"],
      "install": [
        {"cmd": "curl -fsSL -o /tmp/maestro.zip https://github.com/mobile-dev-inc/maestro/releases/download/cli-2.11.0/maestro.zip"},
        {"cmd": "unzip -o /tmp/maestro.zip -d $HOME/.maestro"}
      ],
      "uninstall": [{"cmd": "rm -rf $HOME/.maestro"}]
    }
  },
  "manual_steps": [
    "下载源=GitHub Releases，内网不可达时需外网代理或人工取包放入安装目录（镜像改道键不覆盖二进制直下段）",
    "jdk>=17 需先行具备（体检点名，配方不代装 JDK）",
    "maestro 不在 PATH 时请把手工解压目录追加进 PATH 后重跑体检"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'MAESTRO' AND install_recipe = '{}'::jsonb;

UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "1.4.4",
  "hosts": {
    "windows": {
      "probe": ["python -m pip show hmdriver2", "hdc -v"],
      "install": [{"cmd": "python -m pip install hmdriver2==1.4.4", "mirror_rewrite": "index_url"}],
      "uninstall": [{"cmd": "python -m pip uninstall -y hmdriver2"}]
    },
    "macos": {
      "probe": ["python3 -m pip show hmdriver2", "hdc -v"],
      "install": [{"cmd": "python3 -m pip install hmdriver2==1.4.4", "mirror_rewrite": "index_url"}],
      "uninstall": [{"cmd": "python3 -m pip uninstall -y hmdriver2"}]
    },
    "linux": {
      "probe": ["python3 -m pip show hmdriver2", "hdc -v"],
      "install": [{"cmd": "python3 -m pip install hmdriver2==1.4.4", "mirror_rewrite": "index_url"}],
      "uninstall": [{"cmd": "python3 -m pip uninstall -y hmdriver2"}]
    }
  },
  "manual_steps": [
    "hdc 命令行工具随 HarmonyOS DevEco Studio/SDK 分发，需自行安装并在 PATH（testPike 不随包分发），版本旗标是 -v 不是 --version",
    "鸿蒙真机需在开发者选项中开启「USB 调试」并授权当前电脑",
    "python>=3.11 需先行具备（缺失时体检点名）"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'HMNEXTAUTO' AND install_recipe = '{}'::jsonb;

UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "0.12.1",
  "hosts": {
    "windows": {
      "probe": ["node --version", "python -c \"import socket;socket.create_connection((''127.0.0.1'',9420),2)\""],
      "install": [{"cmd": "npm install -g miniprogram-automator@0.12.1", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g miniprogram-automator"}]
    },
    "macos": {
      "probe": ["node --version", "python3 -c \"import socket;socket.create_connection((''127.0.0.1'',9420),2)\""],
      "install": [{"cmd": "npm install -g miniprogram-automator@0.12.1", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g miniprogram-automator"}]
    },
    "linux": {
      "probe": ["node --version", "python3 -c \"import socket;socket.create_connection((''127.0.0.1'',9420),2)\""],
      "install": [{"cmd": "npm install -g miniprogram-automator@0.12.1", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g miniprogram-automator"}]
    }
  },
  "manual_steps": [
    "微信开发者工具无法静默安装：① 官方页下载并安装 ② 打开设置→安全→开启服务端口 ③ 扫码登录小程序账号",
    "连通探针=开发者工具服务端口 9420，装机完成后自动验证；探测失败请先确认工具已启动且端口开关已开",
    "安装后请在环境档案选择微信开发者工具安装路径（本机无法替你猜路径）"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'MINIPROGRAM_AUTOMATOR' AND install_recipe = '{}'::jsonb;
