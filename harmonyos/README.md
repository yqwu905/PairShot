# PairShot · 原生鸿蒙版

原生 ArkTS / ArkUI 工程，包名 `com.pairshot.capture`，开发测试版本 `0.2.0`。面向 HarmonyOS 5.1 / API 18 及以上的手机和平板；不是 Android APK 的格式转换。DevEco 工程入口和源码压缩包的对应关系见 [DEVECO.md](DEVECO.md)。

0.1.1 修复了旧包名含有 AppGallery Connect 保留段 `harmony` 的问题。请使用新包名 `com.pairshot.capture` 注册应用并重新生成对应的签名 Profile；为旧包名生成的 Profile 不能用于新包。发布流程会检查包名格式及 `oh`、`ohos`、`harmony`、`harmonyos`、`openharmony`、`system` 等保留段。规则来源：[华为创建应用文档](https://developer.huawei.com/consumer/cn/doc/App/agc-help-create-app-0000002247955506/)。

## 功能

- **物理镜头发现**：合并基础相机、按位置/类型查询的相机，以及运行系统提供的 `getCameraDevices`（API 23）和 `constituentCameraDevices`（API 24）结果。按真实 cameraId 去重，并复用返回的 CameraDevice 对象打开镜头。最多选择四枚镜头。
- **多镜头并发拍照**：默认多选后调用 `getCameraConcurrentInfos`，按每枚相机在普通拍照模式下的并发能力选择预览和 JPEG 配置，再以系统返回的并发类型打开独立 CameraInput/PhotoSession。全部会话就绪后，在等待任何照片前向所有 PhotoOutput 提交快门。每枚镜头有独立预览、回调、超时和资源释放。
- **显式顺序拍照**：也可手动选择顺序模式，逐个打开并拍摄，每张立即保存。不支持并发 JPEG 的组合不会自动伪装为并发；界面会显示原因，允许更换组合或选择顺序模式。
- **镜头诊断**：可复制基础/扩展查询结果、物理组成、被过滤的原因、所选组合的并发能力及会话错误，便于核验 Mate 90 等真机开放的镜头。
- **USB / OTG 相机**：查找标准 Still Image / PTP 接口，由系统请求 USB 访问授权；显示品牌型号及协议错误。
- **手机 → 相机**：提交手机和相机拍摄请求。精确匹配第一代 Nikon Z6、厂商 ID 和声明的 `0x9207` 指令时使用尼康存储卡快门；其他型号仅在声明标准 `InitiateCapture` 时尝试遥控快门。失败不自动重发快门。
- **相机 → 手机**：开启联动时读取存储卡基线，之后通过新增对象扫描触发手机跟拍。它是相机生成文件之后的延迟跟拍，不是快门按下瞬间的同步曝光。
- **照片归组**：按存储位置和同名文件将 RAW / JPEG 归到同组，保留手机照片、相机原片元数据和失败信息；只有手机照片和所需相机原片齐全时才算完整。
- **导入与归档**：分块读取相机原片，重新核对对象信息，检查大小和 SHA-256；导出前再校验照片，通过系统文件保存器保存 ZIP。未完成记录需明确勾选才能导出，已保存但损坏的文件会阻止导出。
- **本地记录**：批次、历史记录、原子清单保存、未完成拍摄恢复。进入后台停止联动并释放设备，返回前台需重新连接相机和开启联动。

界面只展示请求倍率；倍率可能涉及数字裁切，不能当作光学焦距。系统公开的摄像头 ID 不一定对应每一枚物理镜头。JPEG 中的方向由 CameraKit 处理，清单中的 `capturedAt` 是请求时刻，不是传感器曝光时刻。

华为当前[多摄同开说明](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/camera-concurrent-open/)仍将“前置/后置相机同时拍照”标为待开放。因此本版只在真机返回完整的 JPEG 并发能力且实际会话建立成功后开放并发快门，不保证 Mate 90 或任意后置物理镜头组合必然可并发。多路快门是并发提交，不承诺传感器在同一时刻曝光。

最低系统仍为 API 18。为兼容旧系统，新增 API 的准确公开签名放在带运行时检测的兼容接口中；运行系统没有 API 23/24 方法或属性时，会继续使用基础和 API 18 的按类型查询。没有修改 SDK 声明，也不猜测隐藏相机 ID。接口定义参考：[CameraManager](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/arkts-apis-camera-cameramanager/)、[CameraDevice](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/arkts-apis-camera-i/)。

## 构建

环境要求：DevEco Command Line Tools 5.1.0.840、随附 Node 18.20.1、Hvigor 5.18.5、HarmonyOS 5.1 SDK（API 18 / 5.1.0.125）、JDK 21。本项目没有外部 OHPM 依赖。

在本次云环境中：

```bash
cd /workspace/PairShot/harmonyos
source /workspace/.harmony-environment/env.sh
export HVIGOR_USER_HOME=/workspace/.hvigor
bash build.sh
```

在其他安装了官方命令行工具的 Linux 环境中，先将 `DEVECO_HOME` 设置为 Command Line Tools 目录并配置 JDK，再执行 `bash build.sh`；也可在 DevEco Studio 中打开本目录构建。构建脚本将新增缓存放在项目旁的 `work/harmony/` 目录。

构建产物：

```text
entry/build/default/outputs/default/entry-default-unsigned.hap
```

GitHub 上推送与 `AppScope/app.json5` 版本一致的 `harmonyos-v*` 标签会触发 `.github/workflows/harmonyos.yml`：校验官方工具链下载摘要，运行测试并构建，再上传附件并发布为未签名预发布版。源码分支普通推送不会自动发布。构建任务只读仓库，发布任务在构建成功后使用仓库的 `GITHUB_TOKEN` 上传；不会将该标签标为正式最新版。

## 签名与安装

源码默认不包含个人私钥、设备调试 profile 或 `signingConfig`，因此构建结果是 **未签名 HAP，不能直接作为已签名安装包安装到普通鸿蒙真机**。

在 DevEco Studio 打开 `harmonyos` 目录，通过项目签名设置登录自己的华为开发者账号，为目标设备配置自动调试签名，随后重新构建并运行。也可用自己的证书、profile 和官方签名工具完成离线签名。不要把私钥、密码或设备 profile 提交到仓库。

## 使用

1. 仅手机模式下授权摄像头，选择一至四枚镜头。多选默认并发模式；所有独立预览就绪后可点击“并发拍照”。系统不支持此组合时查看提示，选择其他组合，或手动切为“顺序拍照”。
2. USB 联动时用数据线和 OTG 连接相机，切换到 PTP 模式。点击刷新，选择“连接并授权”，然后开启联动。建立基线期间不要拍摄。
3. 手机主导时每次只发起一组拍摄，等待本组完成再继续；不要同时独立按相机快门，避免基于新增文件的配对产生歧义。相机主导时按相机快门，应用发现新照片后跟拍。
4. 点击“复制本批次相机原片”，完成后“打包照片 ZIP”，选择系统允许保存的位置。导入时会停止联动，不删除或修改相机原片。
5. 新建记录可以另起批次；历史可以重新打开已有批次。清除应用数据或卸载前，请先导出所需照片。

没有可靠设备序列号时，只允许在同一次连接内导入新记录；重启或重新连接后拒绝使用不能确认身份的旧句柄。有可靠身份时也会再次核对文件名、大小、存储位置和拍摄日期。应用只保存设备身份摘要，不记录明文相机序列号。

## ZIP 格式

```text
manifest.json
phone/<shot-id>/<encoded-camera-id>.jpg
camera/<shot-id>/<handle>_<safe-filename>
```

清单包含平台、联动模式、手机拍摄方式（`SINGLE` / `SEQUENTIAL` / `CONCURRENT`）、请求时刻、请求倍率、照片大小、SHA-256 和缺失/失败状态。收到 `frameShutter` 时保存 API 返回的快门时间戳 `shutterTimestampMs`；0 表示未收到，不能据此推断经过校准的曝光同步精度。旧批次缺少新字段时仍可读取。使用不改变原片内容的 ZIP STORE 流式归档；经典 ZIP 限制为整包小于 4 GiB、最多 65,535 项，超过限制会报错。

## 自动化验证与真机边界

`tests/*.test.cjs` 转译实际服务源码，通过模拟 SDK 验证协议、存储、相机生命周期及联动状态，不依赖模拟器：

```bash
node --test tests/*.test.cjs
```

测试需要 Node.js、Python 3 和 TypeScript。其他环境设置 `DEVECO_HOME` 为官方命令行工具目录，测试会使用其 `codelinter/node_modules/typescript`。Python 用于独立验证 ZIP 目录、内容和 CRC。

构建通过只能证明 ArkTS、资源与 HAP 打包通过。当前环境没有 USB 真机直通或鸿蒙模拟器，以下仍需真机验收：

- 基础和扩展物理镜头枚举、逐路预览及成片方向、并发能力、并发拍照组合、倍率范围和顺序回退；
- 摄像头/USB 权限拒绝与重试、设备忙、USB 拔线、切后台、重新进入应用；
- Nikon Z6 及其他声明支持的 PTP 相机：远程快门、机身快门、RAW+JPEG、长写卡延迟；
- 原片分块导入、存储空间不足、历史恢复、系统保存器取消与保存；
- 调试签名后的实际安装、各厂商设备开放的 USB Host 和 CameraKit 能力。

相机文件扫描间隔为 1.5 秒，同名文件静默 3 秒后归组处理，手机主导等待相机文件最长为 45 秒；这些不是曝光同步精度。传输中切到后台会立即关闭 USB，可能来不及向尼康发送恢复本地快门指令；若相机仍处于遥控状态，请断开 USB 再恢复机身操作。

第一版不提供硬件同步、跨设备时间校准、USB 相机实时取景、云端上传、批次删除或超过 4 GiB 的 ZIP64 导出。

协议事实参考：[PTP / Nikon 常量](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)、现有 PairShot Android 实现。协议代码独立实现，没有将 libgphoto2 打包进应用。
