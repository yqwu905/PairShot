"""Regression checks for package names accepted by the HAP release gate."""

import importlib.util
import json
from pathlib import Path
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('harmony_release', SCRIPT_DIR / 'harmony_release.py')
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class BundleNameTests(unittest.TestCase):
    def test_project_bundle_avoids_reserved_segments(self):
        app = json.loads((SCRIPT_DIR.parents[1] / 'harmonyos/AppScope/app.json5').read_text())['app']
        RELEASE.validate_bundle_name(app['bundleName'])

    def test_reserved_segments_are_rejected_in_every_position(self):
        for segment in ['oh', 'ohos', 'harmony', 'harmonyos', 'openharmony', 'system']:
            for name in [f'{segment}.pairshot.capture', f'com.{segment}.capture', f'com.pairshot.{segment}']:
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Reserved'):
                    RELEASE.validate_bundle_name(name)

    def test_reserved_words_inside_larger_segments_are_allowed(self):
        for name in ['com.harmonycam.capture', 'harmony_11.huawei.com', 'com.pairshot.systemcam']:
            with self.subTest(name=name):
                RELEASE.validate_bundle_name(name)

    def test_invalid_syntax_and_lengths_are_rejected(self):
        for name in ['', 'com.app', 'com..capture', '1com.pairshot.capture', 'com._pairshot.capture',
                     'com.pairshot.capture_', 'com.pair-shot.capture', 'com.相机.capture',
                     'com.pairshot.' + 'a' * 117]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                RELEASE.validate_bundle_name(name)


if __name__ == '__main__':
    unittest.main()
