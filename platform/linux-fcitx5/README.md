# Fcitx 5 适配器

该原生插件把 Fcitx 5 的按键、预编辑、候选选择和提交映射到公共 C ABI，并将学习数据保存到
`$XDG_DATA_HOME/shurufa/user`（可用 `SHURUFA_USER_DATA_DIR` 覆盖）。实现依据 Fcitx 5
官方 addon/input-method engine 接口，要求 Fcitx5Core 开发包和已构建的 `libime_ffi.so`。

```bash
cargo build -p ime-ffi
cmake -S platform/linux-fcitx5 -B target/fcitx5 \
  -DSHURUFA_IME_LIBRARY="$PWD/target/debug/libime_ffi.so" \
  -DCMAKE_INSTALL_PREFIX="$HOME/.local"
cmake --build target/fcitx5
cmake --install target/fcitx5
fcitx5 -r
```

当前仓库已用 Ubuntu Fcitx 5.1.19 官方开发包头文件完成 C++20 严格编译检查；本机未安装
Fcitx 5 桌面运行时，因此插件的实际加载、Wayland/X11 客户端和候选点击仍需在 Linux
桌面上验收。
