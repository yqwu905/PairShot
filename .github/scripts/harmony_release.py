"""Package a tested native HarmonyOS build for a tag-triggered prerelease."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def validate_bundle_name(bundle_name):
    """Validate AGC naming rules, including reserved standalone segments."""
    if not isinstance(bundle_name, str) or not 7 <= len(bundle_name.encode('utf-8')) <= 128:
        raise ValueError('Bundle name must contain 7 to 128 bytes')
    parts = bundle_name.split('.')
    if len(parts) < 3:
        raise ValueError('Bundle name must contain at least three segments')
    reserved = {'oh', 'ohos', 'harmony', 'harmonyos', 'openharmony', 'system'}
    for index, part in enumerate(parts):
        pattern = r'[A-Za-z][A-Za-z0-9_]*' if index == 0 else r'[A-Za-z0-9][A-Za-z0-9_]*'
        if not re.fullmatch(pattern, part) or not part[-1].isalnum():
            raise ValueError('Invalid bundle name segment: ' + part)
        if part.lower() in reserved:
            raise ValueError('Reserved bundle name segment: ' + part)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    project = repo / 'harmonyos'
    app = json.loads((project / 'AppScope/app.json5').read_text())['app']
    validate_bundle_name(app['bundleName'])
    version = app['versionName']
    if args.tag != f'harmonyos-v{version}':
        raise SystemExit('Release tag must match AppScope app versionName')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    hap = project / 'entry/build/default/outputs/default/entry-default-unsigned.hap'
    with zipfile.ZipFile(hap) as archive:
        assert archive.testzip() is None, 'HAP archive CRC failed'
        metadata = json.loads(archive.read('module.json'))['app']
        assert metadata['bundleName'] == app['bundleName']
        assert metadata['versionName'] == version
        assert metadata['minAPIVersion'] == 50100018
        abc = archive.read('ets/modules.abc')
        for module in ['CameraService', 'SessionStore', 'PtpCamera', 'PtpCodec', 'PairShotController', 'EntryAbility', 'Index']:
            assert module.encode() in abc, f'Missing compiled module: {module}'
    logs = repo / 'work/harmony-release'
    tests = (logs / 'tests.log').read_text()
    passed = re.search(r'^# pass (\d+)$', tests, re.MULTILINE)
    total = re.search(r'^# tests (\d+)$', tests, re.MULTILINE)
    assert passed and total and int(passed[1]) > 0 and passed[1] == total[1]
    assert re.search(r'^# fail 0$', tests, re.MULTILINE), 'Test failures present'
    build = (logs / 'build.log').read_text()
    assert 'BUILD SUCCESSFUL' in build and 'BUILD FAILED' not in build
    output = Path(args.output).resolve()
    assets = output / 'assets'
    assets.mkdir(parents=True, exist_ok=True)
    stem = f'PairShot-HarmonyOS-{version}'
    shutil.copyfile(hap, assets / f'{stem}-unsigned.hap')
    names = subprocess.check_output(['git', 'ls-files', '-z', 'harmonyos'], cwd=repo).decode().split('\0')
    files = [repo / name for name in names if name]
    manifest = {path.relative_to(project).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    with zipfile.ZipFile(assets / f'{stem}-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, Path('PairShot-HarmonyOS') / path.relative_to(project))
        archive.writestr('PairShot-HarmonyOS/SOURCE-SHA256.json', json.dumps(manifest, indent=2) + '\n')
    digest = hashlib.sha256(hap.read_bytes()).hexdigest()
    shutil.copyfile(project / 'README.md', assets / 'HarmonyOS-README.md')
    ansi = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')
    (assets / 'build.log').write_text(ansi.sub('', build))
    (assets / 'tests.log').write_text(tests)
    (assets / 'BUILD-INFO.txt').write_text(
        f'PairShot HarmonyOS {version}\nSource commit: {commit}\nTag: {args.tag}\n'
        f'Bundle: {app["bundleName"]}\nMinimum OS: HarmonyOS 5.1 / API 18\n'
        f'CLI: 5.1.0.840; Hvigor: 5.18.5; SDK: 5.1.0.125\n'
        f'Tests: {passed[1]}/{total[1]} passed (mocked SDK, not device validation)\n'
        f'Build: SUCCESS; signing: UNSIGNED DEBUG; device/USB validation: NOT PERFORMED\n'
        f'HAP SHA-256: {digest}\n'
    )
    checksum_files = sorted(path for path in assets.iterdir() if path.is_file() and path.name != 'SHA256SUMS')
    (assets / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in checksum_files))
    (output / 'notes.md').write_text(f'''PairShot 原生鸿蒙开发测试版 {version}，源码位于独立分支 `harmonyos/native-v0.1.0` 的 `harmonyos/` 目录。

## 下载与安装

- `{stem}-unsigned.hap`：原生鸿蒙 HAP，{hap.stat().st_size:,} 字节。
- **未签名 Debug HAP：需在 DevEco Studio 中为目标设备配置自己的调试签名后重新构建安装，不能直接作为已签名安装包安装。**
- 适用 HarmonyOS 5.1 / API 18 及以上，包名 `{app['bundleName']}`。
- 请以当前包名注册应用并生成对应的签名 Profile；旧包名的 Profile 不能用于此包。0.1.1 已修复旧包名包含保留段 `harmony` 的问题。
- `{stem}-source.zip` 为独立鸿蒙源码；使用、构建和签名说明见 `HarmonyOS-README.md`，附件校验值见 `SHA256SUMS`。

## 功能

系统公开镜头枚举、预览、变焦、最多双镜头顺序 JPEG 拍摄；USB/PTP 连接与双向联动；第一代 Nikon Z6 精确协议适配；RAW/JPEG 配对、流式原片导入、历史恢复、大小和 SHA-256 校验及 ZIP 导出。

## 验证与限制

GitHub Actions 使用官方固定版本工具链构建通过，{passed[1]} 项自动化测试通过。测试使用模拟 SDK，**摄像头、USB/PTP 及 Nikon 功能仍需真机验收**。

双镜头按顺序拍摄，联动不保证同时曝光；相机生成文件后延迟跟拍。扫描间隔 1.5 秒、归组静默窗口 3 秒、等待文件最长 45 秒；ZIP 小于 4 GiB。

源码提交：`{commit}`。构建：CLI 5.1.0.840 / Hvigor 5.18.5 / SDK 5.1.0.125。

HAP SHA-256：`{digest}`
''')
    print(f'Packaged {stem}: {digest}, {passed[1]} tests passed')


if __name__ == '__main__':
    main()
