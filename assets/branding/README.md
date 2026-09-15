<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Zhimo 应用标识

设计：蓝底白色 Z（Zhimo 首字母），右下角浅青色墨笔笔尖；不把完整名称挤入小图标。
应用名称仍使用“知墨输入法 · Zhimo”。作者：立方田，managecode@gmail.com。

`zhimo.svg` 是可编辑主源，PNG（16～1024 像素）及多尺寸 `zhimo.ico` 为确定性导出。
本次延续项目原生矢量资源，不使用 AI 位图生成或第三方图标素材。

重新导出：

```bash
python3 -m venv target/icon-tools
target/icon-tools/bin/pip install -r assets/branding/requirements.txt
target/icon-tools/bin/python tools/generate-app-icons.py
```

- Android：自适应图标前景保留安全边距，Android 13 主题图标使用单色蒙版；背景沿用 #2457D6。
- Windows：ICO 嵌入 TSF DLL，注册语言配置时使用该 DLL 的第一个图标。
- Linux：IBus / Fcitx5 使用 `zhimo` 图标名，DEB core 包安装 hicolor SVG / PNG；用户安装脚本安装 SVG。
- iOS / macOS：各自 `Assets.xcassets/AppIcon.appiconset` 已生成。创建 Xcode target 后添加资源目录，
  将 App Icon Source 设为 AppIcon；尚未完成 Apple 编译、签名与安装验收。
- 鸿蒙：当前没有应用工程，未宣称已经接入；可复用主源及 PNG。

图标接入不改变输入法功能或“中／英”模式指示。旧安装包不会自动更新，必须重编译安装。
操作系统可能缓存旧图标，升级后可注销登录；无需删除用户词库。
