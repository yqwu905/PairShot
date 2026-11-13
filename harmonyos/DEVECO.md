# 在 DevEco Studio 中打开 PairShot

仓库的默认 `master` 分支是 Android 工程；鸿蒙版在独立分支 `harmonyos/native-v0.1.0` 中，工程根目录为 `harmonyos/`。分支名称保留了最初版本号，后续鸿蒙版本仍在此分支更新。Release 标签固定对应某次源码提交。

## 从 Git 克隆

```bash
git clone --branch harmonyos/native-v0.1.0 --single-branch https://github.com/yqwu905/PairShot.git PairShot-harmony
```

DevEco Studio 选择 **Open**，打开 `PairShot-harmony/harmonyos`。不要将含 `build.gradle.kts` 的仓库根目录当作鸿蒙工程打开。

需要和某个 Release 精确一致时，检出相应标签，例如：

```bash
cd PairShot-harmony
git fetch origin tag harmonyos-v0.2.0
git switch --detach harmonyos-v0.2.0
```

然后仍打开 `harmonyos/` 子目录。

## 从 Release 下载

| 下载项 | 内容 | DevEco 应打开的目录 |
| --- | --- | --- |
| `PairShot-HarmonyOS-<版本>-source.zip` | 从该标签提取的独立鸿蒙子工程 | 解压后的 `PairShot-HarmonyOS` |
| GitHub 自动生成的 `Source code (zip)` | 该标签的整个仓库，包含 Android 和鸿蒙 | 解压目录下的 `harmonyos` |
| `*-unsigned.hap` | 构建产物 | 不能作为源码工程打开；安装前需签名 |

正确的工程根目录同时包含：

```text
AppScope/
entry/
hvigor/
build-profile.json5
hvigorfile.ts
oh-package.json5
```

独立源码 ZIP 中保留各文件在 `harmonyos/` 下的原始相对路径，另附 `SOURCE-SHA256.json`。该清单记录同一标签的源码文件摘要；不同分支或不同标签的文件可能发生变化。Release 的 `BUILD-INFO.txt` 记录实际构建提交，`SHA256SUMS` 记录附件摘要。

## SDK 与签名

在 SDK Manager 中安装本工程所需的 HarmonyOS 5.1 / API 18 SDK；仅安装最新 SDK 可能无法解析工程中的 `5.1.0(18)` 配置。命令行已使用官方 CLI 5.1.0.840、Hvigor 5.18.5 和 SDK 5.1.0.125 验证；DevEco Studio 如提示升级工程配置，请先确认已打开以上正确目录，并保留原始配置以便检查版本差异。

连接手机后，在 **File → Project Structure → Project → Signing Configs** 登录华为开发者账号并启用自动签名。包名为 `com.pairshot.capture`，Profile 必须与此包名及目标调试设备对应。随后选择设备并点击 **Run**。

若仍无法同步工程，请保留 DevEco Studio 版本号和第一条实际错误；Android Gradle 错误通常说明打开了错误的分支或目录，SDK/Hvigor 版本错误则需要核对本地工具链。
