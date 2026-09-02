# Linux IBus adapter

该适配器通过 `ctypes` 调用与其他平台共用的 Runtime C ABI；Python 层只转换 IBus 事件和视图动作，不包含拼音逻辑。

开发运行：

```bash
cargo build -p ime-ffi
SHURUFA_IME_LIBRARY="$PWD/target/debug/libime_ffi.so" \
SHURUFA_USER_DATA_DIR="/tmp/shurufa-ibus-dev" \
  python3 platform/linux-ibus/shurufa_ibus.py
```

系统安装时应将脚本、动态库和替换了 `@SHURUFA_IBUS_EXEC@` 的组件 XML 安装到发行版规定目录，然后重启 IBus。当前自动验证只覆盖 Runtime ABI；完整 IBus 焦点、候选窗和应用兼容性必须在图形会话中测试。

当前用户安装脚本：

```bash
./platform/linux-ibus/install-user.sh
```

该操作会构建 release 动态库，将文件安装到用户的 XDG data 目录并重启 IBus；开发验证不需要执行安装。
