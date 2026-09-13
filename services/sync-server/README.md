# 密文同步服务

Release 构建、运行和健康检查参见
[全平台构建、安装与验收手册](../../docs/构建与验收.md#8-密文同步服务)。

服务端只保存客户端生成的加密 envelope，不接触学习记录明文或密钥。接口支持幂等上传、增量拉取和设备撤销。

```bash
SHURUFA_SYNC_ACCOUNT=demo \
SHURUFA_SYNC_TOKEN='replace-with-at-least-32-random-characters' \
SHURUFA_SYNC_DATABASE=sync.sqlite3 \
cargo run -p sync-server
```

生产部署必须放在 TLS 反向代理后，使用正式账户/令牌签发、速率限制、审计、备份、数据保留和多租户隔离；环境变量单账户模式仅用于本地闭环与自托管原型。
