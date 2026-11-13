# Registration regression tests

The two algorithm test modules come from Site commit
`aae4dd28cedad114e299547f522a568d34881bd3`. Only their import paths were
changed to exercise the Python algorithms bundled in the Android assets.
They cover global color fitting and local correction invariants; they do
not replace camera-pair, WebView, or mobile memory checks.

Run the seven numerical tests with local development dependencies:

```sh
python3 -m venv work/registration-tests-venv
work/registration-tests-venv/bin/python -m pip install numpy==2.2.5 opencv-python-headless==4.11.0.86 Pillow==11.3.0
PYTHONDONTWRITEBYTECODE=1 work/registration-tests-venv/bin/python -m unittest discover -s tests/registration -p 'test_*.py' -v
```

The regular Android CI does not install those scientific Python packages.
It automatically runs the standard-library asset checks in
`.github/scripts/test_registration_assets.py`, checking recorded algorithm
hashes, runtime files, package dependencies, and locked wheel checksums:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
```
