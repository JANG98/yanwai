# 言外 v1.3.0 更新说明

## 新增功能

### 1. 回复技能（Skill）系统
- 在设置页新增「回复技能」管理区域
- 支持总开关：启用后，输出回复建议时会参考已启用技能设定的风格或规则
- 支持添加自定义技能：每个技能包含名称、描述、提示词
- 支持技能的启用/禁用、编辑、删除
- 技能只影响「回复建议」环节，不改变情绪判断和事件解读，也不会自动发送消息
- 技能配置随设置同步到微信进程，重启微信后生效

### 2. 模型名自定义
- 所有渠道（Jev 官方、OpenRouter、Vercel、自定义）的模型名现在都可以自定义修改
- 预设渠道仍会自动填入默认模型名，但用户可以根据需要修改
- 模型名为空时自动使用渠道预设默认值

### 3. GitHub Actions 自动构建工作流
- 新增 `.github/workflows/build-release.yml`
- 支持推送 tag 或手动触发构建
- 自动构建 Release APK
- 自动签名（签名密钥通过 base64 编码的 GitHub Secrets 配置）
- 自动创建 GitHub Release 并上传签名 APK
- 自动生成更新日志（基于 git commit 历史）
- 自动通过 SMTP 发送构建结果和更新日志邮件

## 需要配置的 GitHub Secrets

在仓库 `Settings → Secrets and variables → Actions` 中配置：

### 签名相关（必须）
| Secret 名称 | 说明 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | 签名密钥库文件的 base64 编码（`base64 -w0 your-keystore.jks`） |
| `SIGNING_KEYSTORE_PASSWORD` | 密钥库密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

### 邮件通知相关（必须）
| Secret 名称 | 说明 |
|---|---|
| `SMTP_HOST` | SMTP 服务器地址（如 smtp.qq.com） |
| `SMTP_PORT` | SMTP 端口（SSL 用 465，TLS 用 587） |
| `SMTP_USERNAME` | 发件邮箱账号 |
| `SMTP_PASSWORD` | 邮箱授权码（非登录密码） |
| `MAIL_TO` | 收件人邮箱，多个用逗号分隔 |

### 自动提供（无需配置）
- `GITHUB_TOKEN`：GitHub 自动生成

## 触发方式

```bash
# 推送 tag 触发
git tag v1.3.0
git push origin v1.3.0

# 或在 GitHub Actions 页面手动触发
```

## 技术细节

- 版本号：1.3.0（versionCode 17）
- Skill 数据存储在 SharedPreferences，以 JSON 数组形式保存
- Skill 提示在第二轮分析（detailPayload）中注入到 action 选择问题的 instructions
- 设置同步链路新增 `skillEnabled` 和 `skillPrompt` 字段
- 所有渠道模型名自定义通过 `ApiSettings.fromInput` 统一处理：用户输入非空则用用户值，否则用渠道默认

## 文件变更清单

### 新增文件
- `app/src/main/java/dev/jev/wechatmood/core/Skill.kt` — Skill 数据类
- `app/src/main/java/dev/jev/wechatmood/core/SkillStore.kt` — Skill 存储管理
- `.github/workflows/build-release.yml` — 自动构建工作流

### 修改文件
- `app/build.gradle.kts` — 版本号升级到 1.3.0
- `app/src/main/java/dev/jev/wechatmood/MainActivity.kt` — 新增 Skill UI 管理逻辑
- `app/src/main/java/dev/jev/wechatmood/analysis/ChatAnalysis.kt` — 传递 skillPrompt 参数
- `app/src/main/java/dev/jev/wechatmood/analysis/JevProtocol.kt` — 在 action 选择中注入 Skill 提示
- `app/src/main/java/dev/jev/wechatmood/analysis/SignalAnalyzer.kt` — 读取并传递已启用 Skill 提示
- `app/src/main/java/dev/jev/wechatmood/core/ApiSettings.kt` — 所有渠道允许自定义模型名
- `app/src/main/java/dev/jev/wechatmood/core/ApiProfiles.kt` — 迁移逻辑适配所有渠道模型
- `app/src/main/java/dev/jev/wechatmood/core/ModulePrefs.kt` — 新增 Skill 相关 key 和访问属性
- `app/src/main/java/dev/jev/wechatmood/core/SettingsProvider.kt` — snapshot 中包含 Skill 配置
- `app/src/main/java/dev/jev/wechatmood/core/SettingsSession.kt` — RuntimeSettings 新增 Skill 字段
- `app/src/main/java/dev/jev/wechatmood/core/SettingsSync.kt` — 解码 Skill 配置
- `app/src/main/res/layout/activity_main.xml` — 新增回复技能 UI 区域、模型输入栏默认可见
- `app/src/test/java/dev/jev/wechatmood/core/ApiSettingsTest.kt` — 测试适配新模型逻辑
- `app/src/test/java/dev/jev/wechatmood/core/SettingsSessionTest.kt` — 测试适配 RuntimeSettings 新参数
