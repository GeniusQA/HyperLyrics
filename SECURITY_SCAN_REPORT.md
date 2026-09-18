# HyperLyrics 敏感信息扫描报告

> 扫描时间：2026-09-11  
> 扫描范围：`app/src/**`、`*gradle*`、`.github/**`、构建脚本、资源文件  
> 结论：**无泄露性敏感信息（密钥/签名/Token）被纳入版本控制**。仅存在 1 处需要清理的上游服务地址残留。

## 1. 签名 / 密钥

| 位置 | 内容 | 是否入库 | 风险 | 处理状态 |
|---|---|---|---|---|
| `keystore.properties`（本地存在，未跟踪） | `storeFile` / `storePassword` / `keyPassword` / `keyAlias` | 否 | 若入库则高风险；当前已在 `.gitignore` 第 10 行忽略 | 无需处理 |
| `.github/workflows/android-release.yml` | `secrets.SIGNING_KEY`、`secrets.KEY_STORE_PASSWORD`、`secrets.KEY_PASSWORD`、`secrets.ALIAS` | 以 GitHub Actions Secrets 引用，未硬编码 | 低风险 | 无需处理 |

## 2. Token / 凭据

| 位置 | 内容 | 是否硬编码 | 风险 | 处理状态 |
|---|---|---|---|---|
| `settings.gradle.kts:33` | `gpr.key` / `GITHUB_TOKEN` 用于 GPR Maven 认证 | 否，从 env 读取 | 无 | 无需处理 |
| `fetch_contributors.gradle:14` | `GITHUB_TOKEN` / `GH_TOKEN` 用于拉取贡献者头像 | 否，从 env 读取 | 无 | 无需处理 |
| `ChangelogData.kt:34` | `api.github.com/repos/QuanTum2088/HyperLyrics/releases` | 仅公开 API URL | 无 | 无需处理 |
| AI 翻译 `apiKey`（`RootConstants`、`RootLyricSink`、`BackupRestoreManager`） | 用户自行配置，仅存 SharedPreferences | 否 | 无；且备份/日志中已做脱敏处理 | 无需处理 |

## 3. 本地路径

| 位置 | 内容 | 是否入库 | 风险 | 处理状态 |
|---|---|---|---|---|
| `local.properties` | `sdk.dir=D:/AndroidTools/sdk` | 否，已在 `.gitignore` 忽略 | 无 | 无需处理 |

## 4. 远程端点 / 上游残留

| 位置 | 内容 | 是否敏感 | 风险 | 处理状态 |
|---|---|---|---|---|
| `app/src/main/java/com/genius/hyperlyrics/common/RootConstants.kt:787-788` | `DEFAULT_HOOK_AI_TRANS_MODEL = "mimo-v2-flash"` / `DEFAULT_HOOK_AI_TRANS_BASE_URL = "https://api.xiaomimimo.com/v1/"` | 非凭据，但为原作者私有后端地址与模型名 | 中低；属于独立发布线应清理的上游残留 | **已处理：改为 OpenAI 官方默认值** |
| `app/src/main/java/com/genius/hyperlyrics/lyric/style/AiTranslationProvider.kt:4-8` | `provider = "xiaomimimo"` / `model = "mimo-v2-flash"` / `url = "https://api.xiaomimimo.com/v1"` | 非凭据，但为上游私有后端 | 中低 | **已处理：改为 OpenAI 官方默认值** |

## 5. 已入库的敏感产物

- 无 `.apk`、`.mapping`、`.jks`、`.keystore`、`.p12`、`.env`、`google-services.json` 等文件被 git 跟踪。
- 无 `sk-*`、`ghp_*`、`github_pat_*`、`AIza*`、`AKIA*`、SSH key、`-----BEGIN` 等典型密钥格式命中。

## 6. 处理建议（已执行）

1. 保持 `keystore.properties`、`local.properties`、`*jks`、`*keystore` 在 `.gitignore` 中。
2. CI 签名继续走 `secrets.*`。
3. AI 翻译默认值从 `api.xiaomimimo.com / mimo-v2-flash` 切换为 `api.openai.com / gpt-4o-mini`，避免默认指向原作者私有后端；用户仍可自行配置任意 OpenAI-compatible 端点。

## 7. 复查命令

```bash
# 确认无 xiaomimimo 残留
git grep -n "xiaomimimo\|mimo-v2-flash"

# 确认无硬编码密钥
git grep -nE "(sk-|ghp_|github_pat_|AIza|AKIA|-----BEGIN)"
```
