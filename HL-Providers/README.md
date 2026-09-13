# HLE Providers

HyperLyrics Enhanced 的官方 Lyricon Provider Pack 仓库。

本仓库保存 Provider 源码、目录、Pack 构建工具与发布工作流。Provider Pack 不需要作为独立 APK 安装；HyperLyrics Enhanced 下载并验签后，通过 libxposed Remote Files交给目标音乐软件进程加载。

## 安全边界

- Pack 只允许包含 `manifest.json`、`classes.dex` 和 `signature.ed25519`。
- HyperLyrics Enhanced 内置签名公钥与目标包名允许列表。
- 禁止原生库和插件二次下载代码。
- Pack API v2 不暴露 libxposed 类型；Pack 只通过稳定的宿主回调声明行为。对于音乐软件内部入口，Pack 可以提供从原始 DEX 验证的完整方法描述符，也可以提交 DexKit 查询条件。DexKit、缓存和 `module.hook()` 都由主 APK 静态宿主执行：先尝试按目标包/进程/版本缓存的精确描述符，只有缓存 Hook 失败时才重新查询；新描述符只有在 Hook 安装成功后才写入缓存。Pack 仍然不能携带 `libdexkit.so` 或其他原生库。
- 私钥由CI 通过 `HLE_PROVIDER_SIGNING_KEY_PEM` Secret 注入。

## 本地构建

```bash
provider=netease
./gradlew --no-daemon --max-workers=2 ":providers:$provider:assembleRelease"
python3 scripts/build_provider_pack.py \
  --apk "providers/$provider/build/outputs/apk/release/${provider}-release-unsigned.apk" \
  --manifest "providers/$provider/provider.json" \
  --private-key /path/to/signing-private.pem \
  --output "dist/$provider-1.0.0.hlp"
```

Pack ZIP 条目使用固定时间戳，重复构建会得到相同摘要；目录中的 `sha256` 必须与 Pack 文件一致。

## 来源

首批 Provider 逻辑移植自 Apache-2.0 许可的
`tomakino/LyricProvider`，修改后的文件保留原作者署名并追加 `juren233`，新文件将只署名为 `juren233`。

### Provider diagnostic logging

网易云 Provider 使用同一个正式 `.hlp`，不要求单独发布 Debug 插件。它在运行时调用核心
`OfficialProviderHost.isDiagnosticEnabled(): Boolean`；仅 Debug 核心返回 true。
Release 核心以及缺少该能力的旧核心默认关闭。查询失败也保持关闭，不影响歌词功能。
门控发生在采样器分配、回调计数、快照读取和日志字符串构建之前，不仅是输出过滤。
插件能力在每个运行实例初始化时确定；切换核心构建后需由用户重载对应宿主进程。
旧版插件仍按自身 BuildConfig 工作；需要更新网易云 `.hlp` 才使用新机制。
其他 Provider 暂未迁移，继续遵循各自原有日志策略。
