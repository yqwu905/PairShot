#!/usr/bin/env python3
"""Build metadata, isolated signing setup, verified assets and draft-first releases."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


VERSION = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:alpha|beta|rc)\.[1-9]\d*)?$")
SIGNING_NAMES = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")


def run(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"Command failed: {args[0]} {args[1]}\n{result.stderr.strip()}")
    return result.stdout.strip()


def write_outputs(values, env=os.environ):
    if env.get("GITHUB_OUTPUT"):
        with open(env["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for name, value in values.items():
                text = str(value).lower() if isinstance(value, bool) else str(value)
                if "\n" in text or "\r" in text:
                    raise ValueError("Multiline action output is not permitted")
                output.write(f"{name}={text}\n")


def metadata(properties, env):
    version = {}
    for line in Path(properties).read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            version[key.strip()] = value.strip()
    base = version["VERSION_NAME"]
    if not VERSION.fullmatch(base) or "-" in base:
        raise ValueError("VERSION_NAME must be a stable major.minor.patch version")
    number = int(env["GITHUB_RUN_NUMBER"])
    if number < 1:
        raise ValueError("Invalid workflow run number")
    base_code = int(version["VERSION_CODE"])
    if base_code < 1:
        raise ValueError("VERSION_CODE must be positive")
    code = base_code + number
    if not 0 < code <= 2_100_000_000:
        raise ValueError("Android versionCode out of range")
    sha = env["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected an immutable full commit SHA")
    ref = env["GITHUB_REF"]
    tagged = ref.startswith("refs/tags/")
    if tagged:
        tag = ref.removeprefix("refs/tags/")
        name = tag.removeprefix("v")
        if not tag.startswith("v") or not VERSION.fullmatch(name) or name.split("-", 1)[0] != base:
            raise ValueError(f"Version tag must match VERSION_NAME (for example v{base} or v{base}-rc.1)")
    else:
        tag = f"build-{number}"
        name = f"{base}-ci.{number}"
    publishable = env.get("GITHUB_EVENT_NAME") in ("push", "workflow_dispatch") and (ref == "refs/heads/master" or tagged)
    return dict(version_name=name, version_code=code, tag=tag, source_commit=sha,
                run_number=number, tagged=tagged, publishable=publishable)


def configure_signing(directory, env):
    present = [name for name in SIGNING_NAMES if env.get(name, "").strip()]
    if present and len(present) != len(SIGNING_NAMES):
        raise ValueError("Incomplete signing secrets; missing: " + ", ".join(name for name in SIGNING_NAMES if name not in present))
    if not present:
        return {"variant": "debug", "keystore": ""}
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / "release.jks"
    contents = base64.b64decode("".join(env["ANDROID_KEYSTORE_BASE64"].split()), validate=True)
    if not contents:
        raise ValueError("The signing keystore is empty")
    # No keystore bytes or passwords are printed or written to action outputs.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(contents)
    return {"variant": "release", "keystore": str(path.resolve())}


def package(apk, destination, information, variant, build_tools, env):
    apk, destination, tools = Path(apk), Path(destination), Path(build_tools)
    info = json.loads(Path(information).read_text())
    certificate = run([tools / "apksigner", "verify", "--verbose", "--print-certs", apk])
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", certificate)
    if len(fingerprints) != 1 or not re.fullmatch(r"[a-fA-F0-9]{64}", fingerprints[0]):
        raise ValueError("APK must have exactly one verifiable signing certificate")
    badging = run([tools / "aapt", "dump", "badging", apk])
    fields = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not fields or fields.groups() != ("com.pairshot.app", str(info["version_code"]), info["version_name"]):
        raise ValueError("APK package/version does not match this workflow run")
    if ("application-debuggable" in badging) != (variant == "debug"):
        raise ValueError("APK debuggability does not match its release label")
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("Asset directory must be empty to avoid uploading unrelated files")
    filename = f"PairShot-{info['version_name']}-{variant}.apk"
    target = destination / filename
    shutil.copyfile(apk, target)
    info.update(variant=variant, apk=filename, application_id="com.pairshot.app",
                signing_certificate_sha256=fingerprints[0].lower(),
                apk_sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                prerelease=(not info["tagged"] or "-" in info["version_name"] or variant == "debug"),
                workflow_run_url=f"https://github.com/{env['GITHUB_REPOSITORY']}/actions/runs/{env['GITHUB_RUN_ID']}")
    (destination / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
    (destination / "SIGNING-CERTIFICATE.txt").write_text(info["signing_certificate_sha256"] + "\n")
    notes = "PairShot Android APK\n\n"
    notes += f"Version: {info['version_name']} ({info['version_code']})\nCommit: {info['source_commit']}\nVariant: {variant}\n"
    notes += "\nCamera-led shooting is delayed until a photo is generated; hardware compatibility still needs Nikon Z6 and Android-device testing.\n"
    if variant == "debug":
        notes += "\nTEST BUILD: not a production-signed release. The debug signing key is cached, not permanently backed up. If its certificate changes, export your photos before uninstalling/reinstalling. Never use this key for production.\n"
    (destination / "README.txt").write_text(notes)
    with (destination / "SHA256SUMS.txt").open("w") as output:
        for item in sorted(destination.iterdir()):
            if item.name != "SHA256SUMS.txt":
                output.write(f"{hashlib.sha256(item.read_bytes()).hexdigest()}  {item.name}\n")
    write_outputs({"apk": str(target), "variant": variant, "tag": info["tag"]}, env)
    return info


def publish(directory, env):
    directory = Path(directory)
    info = json.loads((directory / "build-info.json").read_text())
    repo = env["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository name")
    ref = env.get("GITHUB_REF", "")
    eligible = env.get("GITHUB_EVENT_NAME") in ("push", "workflow_dispatch") and (ref == "refs/heads/master" or ref.startswith("refs/tags/v"))
    if not eligible or not info["publishable"] or env["GITHUB_SHA"] != info["source_commit"]:
        raise ValueError("This event/commit is not eligible to publish")
    if info["variant"] not in ("debug", "release") or (info["variant"] == "debug" and not info["prerelease"]):
        raise ValueError("A debug APK cannot be labelled as a production release")
    if Path(info["apk"]).name != info["apk"] or not info["apk"].endswith(".apk"):
        raise ValueError("Invalid APK asset name")
    # Refuse a changed APK or metadata before granting any release a public URL.
    required_assets = {info["apk"], "build-info.json", "SHA256SUMS.txt", "SIGNING-CERTIFICATE.txt", "README.txt"}
    listed = set()
    for line in (directory / "SHA256SUMS.txt").read_text().splitlines():
        digest, name = line.split("  ", 1)
        if Path(name).name != name or name in listed or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Invalid release checksum manifest")
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
            raise ValueError("Release asset checksum mismatch")
        listed.add(name)
    if listed != required_assets - {"SHA256SUMS.txt"}:
        raise ValueError("Release asset manifest is incomplete")
    if hashlib.sha256((directory / info["apk"]).read_bytes()).hexdigest() != info["apk_sha256"]:
        raise ValueError("APK digest differs from build metadata")
    if (directory / "SIGNING-CERTIFICATE.txt").read_text().strip() != info["signing_certificate_sha256"]:
        raise ValueError("Signing certificate differs from build metadata")
    tag = info["tag"]
    if not re.fullmatch(r"(?:build-[1-9]\d*|v\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.[1-9]\d*)?)", tag):
        raise ValueError("Invalid release tag")
    query = subprocess.run(["gh", "api", f"repos/{repo}/releases/tags/{tag}"], capture_output=True, text=True)
    existing = None
    if query.returncode == 0:
        existing = json.loads(query.stdout)
    elif "404" not in query.stderr and "Not Found" not in query.stderr:
        raise RuntimeError("Cannot query release: " + query.stderr.strip())
    if existing:
        try:
            sha = run(["gh", "api", f"repos/{repo}/commits/{tag}", "--jq", ".sha"])
        except RuntimeError as error:
            # A new CI tag can remain uncreated until its draft is published.
            # Only our exact-commit draft may resume without an existing tag.
            if not existing["draft"] or info["tagged"] or "404" not in str(error):
                raise
            sha = existing.get("target_commitish")
        if sha != info["source_commit"]:
            raise ValueError("Existing release tag points at a different commit; it will not be moved")
        if not existing["draft"]:
            # Reruns are idempotent: a public release is never overwritten.
            with tempfile.TemporaryDirectory() as temporary:
                previous = Path(temporary) / "build-info.json"
                run(["gh", "release", "download", tag, "--repo", repo, "--pattern", "build-info.json", "--output", previous])
                before = json.loads(previous.read_text())
            keys = ("source_commit", "version_name", "version_code", "variant", "signing_certificate_sha256")
            if any(before.get(key) != info.get(key) for key in keys):
                raise ValueError("Published release metadata differs; create a new run/tag instead of replacing assets")
            required = {info["apk"], "build-info.json", "SHA256SUMS.txt", "SIGNING-CERTIFICATE.txt", "README.txt"}
            if not required.issubset({asset["name"] for asset in existing["assets"]}):
                raise ValueError("Published release is incomplete; it will not be silently overwritten")
            url = existing["html_url"]
            write_outputs({"release_url": url}, env)
            return url
    else:
        notes = Path(env["RUNNER_TEMP"]) / "pairshot-release-notes.md"
        notes.write_text(f"自动构建：`{info['source_commit']}`\n\n"
                         f"版本：`{info['version_name']}`，versionCode：`{info['version_code']}`，构建类型：`{info['variant']}`。\n\n"
                         "APK 已通过单元测试、Lint、版本与签名检查。附件含 SHA-256 校验值和构建信息。\n\n"
                         "尼康 Z6 与手机多摄仍需实机验收，相机主导模式为照片生成后的延迟跟拍。\n\n" +
                         ("**测试包**：使用缓存的调试签名；不是正式签名发行版。缓存丢失后证书可能变化，请先导出照片再卸载重装。\n" if info["variant"] == "debug" else "使用仓库 Secrets 中的发行签名。\n"))
        args = ["gh", "release", "create", tag, "--repo", repo, "--target", info["source_commit"], "--draft",
                "--title", f"PairShot {info['version_name']} ({info['variant']})", "--notes-file", notes]
        if info["tagged"]:
            args += ["--verify-tag"]
        if info["prerelease"]:
            args += ["--prerelease", "--latest=false"]
        run(args)
    assets = [directory / name for name in (info["apk"], "build-info.json", "SHA256SUMS.txt", "SIGNING-CERTIFICATE.txt", "README.txt")]
    run(["gh", "release", "upload", tag, "--repo", repo, "--clobber", *assets])  # Only ever an unpublished draft.
    run(["gh", "release", "edit", tag, "--repo", repo, "--draft=false", f"--prerelease={str(info['prerelease']).lower()}"])
    url = json.loads(run(["gh", "release", "view", tag, "--repo", repo, "--json", "url"]))["url"]
    write_outputs({"release_url": url}, env)
    return url


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    meta = commands.add_parser("metadata")
    meta.add_argument("--properties", default="version.properties")
    meta.add_argument("--output", required=True)
    signing = commands.add_parser("signing")
    signing.add_argument("--directory", required=True)
    assets = commands.add_parser("package")
    assets.add_argument("--apk", required=True)
    assets.add_argument("--directory", required=True)
    assets.add_argument("--metadata", required=True)
    assets.add_argument("--variant", choices=("debug", "release"), required=True)
    assets.add_argument("--build-tools", required=True)
    release = commands.add_parser("publish")
    release.add_argument("--directory", required=True)
    args = parser.parse_args()
    if args.command == "metadata":
        result = metadata(args.properties, os.environ)
        Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
        write_outputs(result)
        print(f"Version {result['version_name']} ({result['version_code']}), tag {result['tag']}")
    elif args.command == "signing":
        result = configure_signing(args.directory, os.environ)
        write_outputs(result)
        print("Signing variant:", result["variant"])
    elif args.command == "package":
        info = package(args.apk, args.directory, args.metadata, args.variant, args.build_tools, os.environ)
        print("Verified APK:", info["apk"])
    elif args.command == "publish":
        url = publish(args.directory, os.environ)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
                summary.write(f"### APK published\n\n[Download from GitHub Releases]({url})\n")
        print("Published:", url)


if __name__ == "__main__":
    main()
