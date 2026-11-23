UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "0.0.63",
  "runtime_requires": {"node": ">=22.12"},
  "hosts": {
    "linux": {
      "probe": ["node --version", "mobilewright --version", "mobilecli --version"],
      "install": [
        {"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"},
        {"cmd": "npm install -g mobilecli@1.0.18", "mirror_rewrite": "registry"}
      ],
      "uninstall": [
        {"cmd": "npm uninstall -g mobilewright"},
        {"cmd": "npm uninstall -g mobilecli"}
      ]
    },
    "macos": {
      "probe": ["node --version", "mobilewright --version", "mobilecli --version"],
      "install": [
        {"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"},
        {"cmd": "npm install -g mobilecli@1.0.18", "mirror_rewrite": "registry"}
      ],
      "uninstall": [
        {"cmd": "npm uninstall -g mobilewright"},
        {"cmd": "npm uninstall -g mobilecli"}
      ]
    },
    "windows": {
      "probe": ["node --version", "mobilewright --version", "mobilecli --version"],
      "install": [
        {"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"},
        {"cmd": "npm install -g mobilecli@1.0.18", "mirror_rewrite": "registry"}
      ],
      "uninstall": [
        {"cmd": "npm uninstall -g mobilewright"},
        {"cmd": "npm uninstall -g mobilecli"}
      ]
    }
  },
  "manual_steps": [
    "Android 设备侧需 ADB；iOS 模拟器/真机需 macOS+Xcode 宿主",
    "建议先关闭 mobilewright 遥测再使用"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'MOBILEWRIGHT';
