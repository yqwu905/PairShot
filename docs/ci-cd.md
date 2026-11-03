# Android CI/CD 与 APK 下载

工作流：[Android CI/CD](https://github.com/yqwu905/PairShot/actions/workflows/android.yml)
下载页：[GitHub Releases](https://github.com/yqwu905/PairShot/releases)

## 自动行为

| 触发方式 | 检查与构建 | 发布结果 |
| --- | --- | --- |
| Pull Request | 发布规则测试、Android 单元测试、Lint、Debug APK | 仅 Actions Artifact，不创建 Release，不使用签名 Secrets |
| 推送到 `master` | 同上，成功后重新构建待发布 APK 并核对签名和版本 | 创建 `build-<run_number>` 预发布版 |
| 推送 `v0.2.0` 等版本标签 | 标签必须与 `version.properties` 的版本匹配 | 创建同名 Release；未配置发行签名时始终标记为测试预发布版 |
| Actions → Run workflow | 在 `master` 或版本标签上运行完整流程 | 与上述分支/标签规则相同；其他分支不发布 |

只在验证任务成功后发布。工作流先创建草稿，上传全部附件后再公开；失败的草稿可以通过重跑恢复。已公开的 Release 不覆盖、不移动已有版本标签；相同运行重跑时会核对来源提交、版本和证书后复用已有发布。

每个 Release 包含：

- `PairShot-<version>-debug.apk` 或 `PairShot-<version>-release.apk`：直接安装的 APK。
- `SHA256SUMS.txt`：附件 SHA-256 校验值。
- `SIGNING-CERTIFICATE.txt`：公开的签名证书指纹，不含私钥。
- `build-info.json`：应用版本、versionCode、来源提交、构建类型和运行链接。
- `README.txt`：安装和当前硬件验证边界。

Actions 中另保留 CI APK 与测试报告 14 天，已验证发布附件保留 30 天；GitHub Release 附件不受该 Artifact 保留期限影响。

## 当前默认：可安装的测试 APK

没有配置发行签名 Secrets 时，自动构建 Debug APK，并明确标记为预发布版。`master` 的版本例如 `0.2.0-ci.7`，标签为 `build-7`。

发布用的调试密钥通过 Actions Cache 尽量复用，便于覆盖安装，但缓存不是永久密钥备份，可能被清理。证书改变时 Android 会拒绝覆盖安装；请先导出应用内照片，再卸载旧测试版、安装新版本。第一次从本地安装包切换到 CI 包也可能遇到这种情况。检查 Release 中的证书指纹可判断是否使用相同签名。

测试密钥不用于正式发行；不要把正式签名密钥放入 Cache、Git 仓库、Artifacts 或 Release。

## 配置正式签名（可选）

在仓库 Settings → Secrets and variables → Actions → Repository secrets 配置全部四项：

| Secret | 含义 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | JKS/PKCS12 签名文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 alias |
| `ANDROID_KEY_PASSWORD` | 对应 key 密码 |

四项齐全后，发布任务自动切换到 `assembleRelease`；只配置了一部分时会报错，不会降级为 Debug。已有发行版本应继续使用相同密钥，单独备份自己的签名文件和密码。

可以通过 GitHub 网页配置，或在自己的已登录 GitHub CLI 中用管道上传文件，避免把编码内容显示到终端：

```bash
base64 -w0 /secure/path/pairshot-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --repo yqwu905/PairShot
gh secret set ANDROID_KEYSTORE_PASSWORD --repo yqwu905/PairShot
gh secret set ANDROID_KEY_ALIAS --repo yqwu905/PairShot
gh secret set ANDROID_KEY_PASSWORD --repo yqwu905/PairShot
```

后面三条命令会交互读取值。不要把私钥或密码发到聊天、Issue、日志或源码。

密钥仅在发布任务中解码到 Runner 临时目录，文件权限为 `0600`，任务结束后清理。PR 验证任务不读取这些 Secrets。`GITHUB_TOKEN` 仅在发布步骤注入，只有发布任务具有 `contents: write` 权限；不需要配置额外 PAT。

## 版本和标签发布

基础版本集中在 `version.properties`：

```properties
VERSION_NAME=0.2.0
VERSION_CODE=2
```

本地普通构建沿用这个版本。CI 将 `versionCode` 设置为 `VERSION_CODE + GITHUB_RUN_NUMBER`，让正常连续构建递增；不要降低基础版本号或重建工作流计数。

`master` 使用 `<VERSION_NAME>-ci.<run_number>`。版本标签必须与基础版本一致，也支持 `-alpha.N`、`-beta.N`、`-rc.N`。版本不匹配、标签格式不合法、APK 版本错误、无有效签名、校验失败或测试失败均不会公开 APK。

例如发布当前版本：

```bash
git tag v0.2.0
git push origin v0.2.0
```

只有配置完整发行签名且使用稳定版本标签时才创建正式 Release；Debug 包、CI 构建和 RC/Alpha/Beta 版本均为预发布版。发布下一版前先提交 `version.properties` 的更新，再推送对应标签。

## 本地检查工作流

```bash
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
actionlint .github/workflows/android.yml
./gradlew assembleDebug testDebugUnitTest lintDebug
```

本地需要验证发行构建时，通过环境变量提供 `PAIRSHOT_RELEASE_KEYSTORE`（本地文件路径）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，然后运行 `./gradlew assembleRelease`。不要提交签名文件。

第三方 Actions 使用固定提交 SHA，SDK 安装显式指定 Platform 36、Build Tools 36.0.0 和 platform-tools，避免旧 Action 默认安装已移除的 `tools` 包。Gradle 缓存使用 GitHub 的基础缓存实现，不启用商业缓存服务或自动发布 Build Scan。

当前应用仍属于待实机验证版本：CI 成功证明构建和自动化检查通过，不证明尼康 Z6 USB 联动、多摄组合或曝光同步精度已实机确认。
