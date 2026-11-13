"""Offline registration asset integrity; uses only Python's standard library."""
import hashlib
import json
from pathlib import Path
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/registration"
RUNTIME = ASSETS / "runtime"
ALGORITHMS = {"engine.py", "color_global.py", "color_local.py"}
PACKAGES = {"numpy", "pillow", "opencv-python"}


class RegistrationAssetsTest(unittest.TestCase):
    def test_algorithm_files_match_recorded_source_hashes(self):
        source = json.loads((ASSETS / "SOURCE.json").read_text(encoding="utf-8"))
        self.assertEqual(ALGORITHMS, set(source["algorithm_sha256"]))
        self.assertRegex(source["source_commit"], r"^[0-9a-f]{40}$")
        for name, expected in source["algorithm_sha256"].items():
            with self.subTest(algorithm=name):
                self.assertRegex(expected, r"^[0-9a-f]{64}$")
                actual = hashlib.sha256((ASSETS / name).read_bytes()).hexdigest()
                self.assertEqual(expected, actual, "Algorithm differs from its recorded source")

    def test_required_runtime_and_license_files_are_bundled(self):
        required = (
            "pyodide.mjs", "pyodide.asm.js", "pyodide.asm.wasm",
            "pyodide-lock.json", "python_stdlib.zip", "PYODIDE-LICENSE.txt",
            "CPYTHON-LICENSE.txt", "THIRD_PARTY_LICENSES.txt", "NOTICE.txt",
        )
        for name in required:
            with self.subTest(asset=name):
                path = RUNTIME / name
                self.assertTrue(path.is_file(), "Runtime asset must be installed with the APK")
                self.assertGreater(path.stat().st_size, 0)
        with (RUNTIME / "pyodide.asm.wasm").open("rb") as wasm:
            self.assertEqual(b"\x00asm\x01\x00\x00\x00", wasm.read(8))
        with zipfile.ZipFile(RUNTIME / "python_stdlib.zip") as standard_library:
            self.assertIsNone(standard_library.testzip())

    def test_local_wheels_match_lockfile_and_are_complete(self):
        lock = json.loads((RUNTIME / "pyodide-lock.json").read_text(encoding="utf-8"))
        for name in PACKAGES:
            with self.subTest(package=name):
                package = lock["packages"][name]
                filename = package["file_name"]
                self.assertEqual(filename, Path(filename).name)
                self.assertTrue(filename.endswith(".whl"))
                path = RUNTIME / filename
                self.assertEqual(package["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
                with zipfile.ZipFile(path) as wheel:
                    self.assertIsNone(wheel.testzip())

    def test_package_dependency_closure_needs_no_remote_downloads(self):
        packages = json.loads((RUNTIME / "pyodide-lock.json").read_text(encoding="utf-8"))["packages"]
        pending, visited = list(PACKAGES), set()
        while pending:
            name = pending.pop()
            if name in visited:
                continue
            visited.add(name)
            package = packages[name]
            filename = package["file_name"]
            self.assertEqual(filename, Path(filename).name)
            self.assertTrue((RUNTIME / filename).is_file(), f"Missing offline dependency: {name}")
            pending.extend(package.get("depends", []))


if __name__ == "__main__":
    unittest.main()
