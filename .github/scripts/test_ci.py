import base64
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import ci


class ReleaseRulesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.properties = self.root / "version.properties"
        self.properties.write_text("VERSION_NAME=0.2.0\nVERSION_CODE=2\n")
        self.env = dict(GITHUB_RUN_NUMBER="7", GITHUB_SHA="a" * 40,
                        GITHUB_REF="refs/heads/master", GITHUB_EVENT_NAME="push")

    def test_master_build_has_unique_prerelease_version_and_increasing_code(self):
        result = ci.metadata(self.properties, self.env)
        self.assertEqual(("0.2.0-ci.7", 9, "build-7"), (result["version_name"], result["version_code"], result["tag"]))
        self.assertTrue(result["publishable"])
        later = ci.metadata(self.properties, self.env | {"GITHUB_RUN_NUMBER": "8"})
        self.assertGreater(later["version_code"], result["version_code"])

    def test_pull_request_and_manual_feature_branch_cannot_publish(self):
        for updates in ({"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/feature"}):
            self.assertFalse(ci.metadata(self.properties, self.env | updates)["publishable"])

    def test_stable_and_rc_tags_match_the_declared_app_version(self):
        for tag in ("v0.2.0", "v0.2.0-rc.1"):
            result = ci.metadata(self.properties, self.env | {"GITHUB_REF": "refs/tags/" + tag})
            self.assertEqual(tag[1:], result["version_name"])
            self.assertTrue(result["publishable"])
            self.assertTrue(result["tagged"])

    def test_mismatched_or_unsafe_tags_are_rejected_before_building(self):
        for tag in ("v0.3.0", "v0.2.0;echo-bad", "v0.2.0-rc.0", "v0.2.0/other", "v00.2.0"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                ci.metadata(self.properties, self.env | {"GITHUB_REF": "refs/tags/" + tag})

    def test_invalid_counters_and_abbreviated_commits_are_rejected(self):
        for updates in ({"GITHUB_RUN_NUMBER": "0"}, {"GITHUB_RUN_NUMBER": "2100000000"}, {"GITHUB_SHA": "1234567"}):
            with self.subTest(updates=updates), self.assertRaises(ValueError):
                ci.metadata(self.properties, self.env | updates)

    def test_missing_signing_uses_explicit_debug_variant_without_private_files(self):
        directory = self.root / "keys"
        self.assertEqual({"variant": "debug", "keystore": ""}, ci.configure_signing(directory, {}))
        self.assertFalse(directory.exists())

    def test_partial_signing_fails_instead_of_silently_publishing_debug(self):
        with self.assertRaisesRegex(ValueError, "Incomplete signing secrets"):
            ci.configure_signing(self.root / "keys", {"ANDROID_KEYSTORE_PASSWORD": "test-only"})
        self.assertFalse((self.root / "keys").exists())

    def test_signing_material_is_private_and_only_its_path_is_returned(self):
        fixture = b"TEST FIXTURE - not a real signing key"
        env = dict(ANDROID_KEYSTORE_BASE64=base64.b64encode(fixture).decode(), ANDROID_KEYSTORE_PASSWORD="test-only",
                   ANDROID_KEY_ALIAS="test", ANDROID_KEY_PASSWORD="test-only")
        result = ci.configure_signing(self.root / "keys", env)
        self.assertEqual("release", result["variant"])
        self.assertEqual(fixture, Path(result["keystore"]).read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(Path(result["keystore"]).stat().st_mode))
        self.assertEqual({"variant", "keystore"}, result.keys())

    def test_action_output_cannot_inject_extra_output_lines(self):
        with self.assertRaises(ValueError):
            ci.write_outputs({"tag": "v0.2.0\nvariant=release"}, {"GITHUB_OUTPUT": str(self.root / "output")})

    def release_fixture(self):
        info = ci.metadata(self.properties, self.env)
        info.update(variant="debug", apk="PairShot-0.2.0-ci.7-debug.apk", prerelease=True,
                    signing_certificate_sha256="b" * 64)
        directory = self.root / "dist"
        directory.mkdir()
        (directory / info["apk"]).write_bytes(b"test APK fixture")
        info["apk_sha256"] = hashlib.sha256((directory / info["apk"]).read_bytes()).hexdigest()
        (directory / "build-info.json").write_text(json.dumps(info))
        (directory / "SIGNING-CERTIFICATE.txt").write_text(info["signing_certificate_sha256"])
        (directory / "README.txt").write_text("test fixture")
        (directory / "SHA256SUMS.txt").write_text("\n".join(
            hashlib.sha256(item.read_bytes()).hexdigest() + "  " + item.name for item in sorted(directory.iterdir())))
        env = self.env | {"GITHUB_REPOSITORY": "example/PairShot", "RUNNER_TEMP": str(self.root)}
        return directory, info, env

    def test_tampered_asset_is_never_sent_to_github(self):
        directory, info, env = self.release_fixture()
        (directory / info["apk"]).write_bytes(b"damaged")
        with patch("ci.subprocess.run") as network, self.assertRaisesRegex(ValueError, "checksum mismatch"):
            ci.publish(directory, env)
        network.assert_not_called()

    def test_pull_request_cannot_publish_even_with_a_token(self):
        directory, info, env = self.release_fixture()
        info["publishable"] = False
        (directory / "build-info.json").write_text(json.dumps(info))
        with patch("ci.subprocess.run") as network, self.assertRaisesRegex(ValueError, "not eligible"):
            ci.publish(directory, env)
        network.assert_not_called()

    def test_missing_checksum_entry_stops_publication(self):
        directory, _, env = self.release_fixture()
        manifest = directory / "SHA256SUMS.txt"
        manifest.write_text("\n".join(manifest.read_text().splitlines()[:-1]))
        with patch("ci.subprocess.run") as network, self.assertRaisesRegex(ValueError, "incomplete"):
            ci.publish(directory, env)
        network.assert_not_called()

    def test_forged_publish_flag_cannot_allow_a_pull_request(self):
        directory, _, env = self.release_fixture()
        with patch("ci.subprocess.run") as network, self.assertRaisesRegex(ValueError, "not eligible"):
            ci.publish(directory, env | {"GITHUB_EVENT_NAME": "pull_request"})
        network.assert_not_called()

    def test_tag_on_another_commit_is_not_moved_or_overwritten(self):
        directory, _, env = self.release_fixture()
        existing = {"draft": False, "assets": [], "html_url": "https://github.com/example/PairShot/releases/tag/build-7"}
        with patch("ci.subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps(existing), "")), \
             patch("ci.run", return_value="c" * 40) as gh, self.assertRaisesRegex(ValueError, "different commit"):
            ci.publish(directory, env)
        self.assertEqual(1, gh.call_count)

    def test_published_release_rerun_is_idempotent_and_does_not_upload(self):
        directory, info, env = self.release_fixture()
        names = [info["apk"], "build-info.json", "SHA256SUMS.txt", "SIGNING-CERTIFICATE.txt", "README.txt"]
        url = "https://github.com/example/PairShot/releases/tag/build-7"
        existing = {"draft": False, "assets": [{"name": name} for name in names], "html_url": url}
        def gh(args):
            if args[1] == "api":
                return info["source_commit"]
            self.assertEqual(["release", "download"], args[1:3])
            Path(args[-1]).write_text(json.dumps(info))
            return ""
        with patch("ci.subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps(existing), "")), patch("ci.run", side_effect=gh):
            self.assertEqual(url, ci.publish(directory, env))

    def test_draft_can_resume_when_github_has_not_created_its_ci_tag_yet(self):
        directory, info, env = self.release_fixture()
        existing = {"draft": True, "target_commitish": info["source_commit"]}
        def gh(args):
            if args[1] == "api":
                raise RuntimeError("HTTP 404: draft tag not created")
            if args[1:3] == ["release", "view"]:
                return json.dumps({"url": "https://github.com/example/PairShot/releases/tag/build-7"})
            return ""
        with patch("ci.subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps(existing), "")), patch("ci.run", side_effect=gh):
            self.assertTrue(ci.publish(directory, env).endswith("build-7"))

    def test_failed_upload_can_resume_only_while_release_is_a_draft(self):
        directory, info, env = self.release_fixture()
        calls = []
        def gh(args):
            calls.append([str(arg) for arg in args])
            if args[1] == "api":
                return info["source_commit"]
            if args[1:3] == ["release", "view"]:
                return json.dumps({"url": "https://github.com/example/PairShot/releases/tag/build-7"})
            return ""
        with patch("ci.subprocess.run", return_value=subprocess.CompletedProcess([], 0, '{"draft":true}', "")), patch("ci.run", side_effect=gh):
            ci.publish(directory, env)
        self.assertEqual(["api", "release", "release", "release"], [call[1] for call in calls])
        self.assertIn("--clobber", calls[1])
        self.assertIn("--draft=false", calls[2])


if __name__ == "__main__":
    unittest.main()
