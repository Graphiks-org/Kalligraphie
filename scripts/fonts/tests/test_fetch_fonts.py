import base64
import contextlib
import hashlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import fetch_fonts


# Sentinel: the family declares the licence file the corpus layout gives it, and the helper
# writes that file under the temporary root so a healthy family really is healthy.
DECLARED_LICENSE = object()


def manifest_with(path, sha256, size, root=None, license_id="OFL-1.1", license_file=DECLARED_LICENSE):
    """Builds a one-family manifest.

    By default the family declares its own licence file and, when `root` is given, that file is
    written there. Pass `license_file=None` to declare none, or an explicit path to point at a
    file the test deliberately leaves absent.
    """
    if license_file is DECLARED_LICENSE:
        license_file = "test-fixtures/fonts/tiny/LICENSE.txt"
        if root is not None:
            declared = root / license_file
            declared.parent.mkdir(parents=True, exist_ok=True)
            declared.write_text("SIL Open Font License 1.1 (test fixture)\n", encoding="utf-8")
    return {
        "schema": "kalligraphie.font-corpus/v1",
        "families": [
            {
                "key": "tiny",
                "files": [
                    {
                        "path": path,
                        "url": "https://example.invalid/tiny.ttf",
                        "rawUrl": "https://example.invalid/raw/tiny.ttf",
                        "revision": "0" * 40,
                        "sha256": sha256,
                        "sizeBytes": size,
                    }
                ],
                "license": license_id,
                "licenseFile": license_file,
                "synthetic": False,
                "builtBy": None,
            }
        ],
    }


class CheckFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.font = self.root / "test-fixtures/fonts/tiny/tiny.ttf"
        self.font.parent.mkdir(parents=True)
        self.bytes = b"\x00\x01\x00\x00tiny"
        self.font.write_bytes(self.bytes)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_matching_hash_and_size_pass(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        self.assertEqual([], fetch_fonts.check_files(manifest, self.root))

    def test_a_wrong_hash_is_reported(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", "0" * 64, len(self.bytes), root=self.root)
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("sha256", errors[0])

    def test_a_wrong_size_is_reported(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes) + 1, root=self.root)
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("size", errors[0])

    def test_a_missing_file_is_reported(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/absent.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("missing", errors[0])

    def test_a_missing_license_file_is_reported(self):
        manifest = manifest_with(
            "test-fixtures/fonts/tiny/tiny.ttf",
            hashlib.sha256(self.bytes).hexdigest(),
            len(self.bytes),
            root=self.root,
            license_file="test-fixtures/fonts/tiny/LICENSE.txt",
        )
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("license", errors[0])

    def test_an_unknown_license_blocks(self):
        manifest = manifest_with(
            "test-fixtures/fonts/tiny/tiny.ttf",
            hashlib.sha256(self.bytes).hexdigest(),
            len(self.bytes),
            root=self.root,
            license_id="Proprietary-EULA",
        )
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("Proprietary-EULA", errors[0])

    def test_a_synthetic_family_without_a_builder_blocks(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        manifest["families"][0]["synthetic"] = True
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("builtBy", errors[0])

    def test_a_real_family_without_a_url_blocks(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        manifest["families"][0]["files"][0]["url"] = None
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("url", errors[0])

    def test_a_real_family_without_a_raw_url_or_a_note_blocks(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        manifest["families"][0]["files"][0]["rawUrl"] = None
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("fetchNote", errors[0])

    def test_a_real_family_with_a_fetch_note_instead_of_a_raw_url_is_accepted(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        manifest["families"][0]["files"][0]["rawUrl"] = None
        manifest["families"][0]["files"][0]["fetchNote"] = "release archive; unzip manually"
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual([], errors)

    def test_a_real_family_that_declares_no_license_file_blocks(self):
        manifest = manifest_with(
            "test-fixtures/fonts/tiny/tiny.ttf",
            hashlib.sha256(self.bytes).hexdigest(),
            len(self.bytes),
            root=self.root,
            license_file=None,
        )
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("licenseFile", errors[0])

    def test_the_declared_license_file_is_a_real_file_and_passes(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes), root=self.root)
        self.assertTrue((self.root / manifest["families"][0]["licenseFile"]).exists())
        self.assertEqual([], fetch_fonts.check_files(manifest, self.root))


class ManifestShapeTest(unittest.TestCase):
    def test_the_committed_manifest_parses_and_uses_known_licenses(self):
        root = pathlib.Path(__file__).resolve().parents[3]
        manifest = fetch_fonts.load_manifest(root / "scripts/fonts/corpus.json")
        self.assertEqual("kalligraphie.font-corpus/v1", manifest["schema"])
        unknown = sorted({family["license"] for family in manifest["families"]} - fetch_fonts.ALLOWED_LICENSES)
        self.assertEqual([], unknown)


class FixtureTreeCoverageTest(unittest.TestCase):
    """The manifest and the committed fixture tree must describe exactly the same files.

    Every other check walks the families the manifest declares, so this is the only guard against a
    font dropped into the tree by hand, and the only one that sees a family directory the manifest
    never mentions.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def manifest(self, families):
        """Builds a manifest from `{family key: [file paths]}`, with nothing else declared."""
        return {
            "schema": "kalligraphie.font-corpus/v1",
            "families": [
                {"key": key, "files": [{"path": path} for path in paths]} for key, paths in families.items()
            ],
        }

    def commit(self, *paths):
        for relative in paths:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"\x00\x01\x00\x00font")

    def test_a_declared_font_that_is_committed_passes(self):
        self.commit("test-fixtures/fonts/tiny/tiny.ttf")
        manifest = self.manifest({"tiny": ["test-fixtures/fonts/tiny/tiny.ttf"]})
        self.assertEqual([], fetch_fonts.coverage_errors(manifest, self.root))

    def test_a_font_committed_by_hand_and_undeclared_is_reported(self):
        self.commit("test-fixtures/fonts/tiny/tiny.ttf", "test-fixtures/fonts/tiny/extra.ttf")
        manifest = self.manifest({"tiny": ["test-fixtures/fonts/tiny/tiny.ttf"]})
        errors = fetch_fonts.coverage_errors(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("extra.ttf", errors[0])
        self.assertIn("declares no such file", errors[0])

    def test_a_family_directory_the_manifest_never_mentions_is_reported(self):
        self.commit("test-fixtures/fonts/tiny/tiny.ttf", "test-fixtures/fonts/ghost/ghost.ttf")
        manifest = self.manifest({"tiny": ["test-fixtures/fonts/tiny/tiny.ttf"]})
        errors = fetch_fonts.coverage_errors(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("ghost", errors[0])

    def test_a_family_the_manifest_declares_without_a_directory_is_reported(self):
        self.commit("test-fixtures/fonts/tiny/tiny.ttf")
        manifest = self.manifest({"tiny": ["test-fixtures/fonts/tiny/tiny.ttf"], "absent": []})
        errors = fetch_fonts.coverage_errors(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("absent", errors[0])
        self.assertIn("no directory", errors[0])

    def test_a_declared_file_that_is_not_committed_is_reported(self):
        self.commit("test-fixtures/fonts/tiny/tiny.ttf")
        manifest = self.manifest(
            {"tiny": ["test-fixtures/fonts/tiny/tiny.ttf", "test-fixtures/fonts/tiny/gone.ttf"]}
        )
        errors = fetch_fonts.coverage_errors(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("gone.ttf", errors[0])
        self.assertIn("not committed", errors[0])

    def test_companion_files_of_a_family_are_not_font_artifacts(self):
        """PROVENANCE, licence texts, builder scripts and audit records are not declared."""
        self.commit(
            "test-fixtures/fonts/tiny/tiny.ttf",
            "test-fixtures/fonts/tiny/PROVENANCE.md",
            "test-fixtures/fonts/tiny/LICENSE.txt",
            "test-fixtures/fonts/tiny/build_tiny.py",
            "test-fixtures/fonts/tiny/audit.json",
            "test-fixtures/fonts/tiny/tiny-A-outline.txt",
        )
        manifest = self.manifest({"tiny": ["test-fixtures/fonts/tiny/tiny.ttf"]})
        self.assertEqual([], fetch_fonts.coverage_errors(manifest, self.root))

    def test_a_base64_wrapper_is_a_font_artifact(self):
        self.commit(
            "test-fixtures/fonts/tiny/wrapped.ttf.b64",
            "test-fixtures/fonts/tiny/wrapped.ttf.base64",
        )
        manifest = self.manifest(
            {
                "tiny": [
                    "test-fixtures/fonts/tiny/wrapped.ttf.b64",
                    "test-fixtures/fonts/tiny/wrapped.ttf.base64",
                ]
            }
        )
        self.assertEqual([], fetch_fonts.coverage_errors(manifest, self.root))

    def test_the_committed_fixture_tree_matches_the_manifest(self):
        """The real corpus, not a synthetic tree: the guard the lint cannot provide itself."""
        root = pathlib.Path(__file__).resolve().parents[3]
        manifest = fetch_fonts.load_manifest(root / "scripts/fonts/corpus.json")
        self.assertEqual([], fetch_fonts.coverage_errors(manifest, root))


class _Response:
    """The subset of an HTTP response `fetch` uses: read() inside a with block."""

    def __init__(self, payload):
        self.payload = payload

    def read(self):
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


class FetchTransportTest(unittest.TestCase):
    """Covers the two readings of a downloaded payload and the two ways --fetch skips a file.

    Everything runs against a canned urlopen: no test in this file touches the network.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def manifest_for(self, path, sha256, size, raw_url="https://example.invalid/raw/tiny.ttf", fetch_note=None, synthetic=False, built_by=None):
        manifest = manifest_with(path, sha256, size, root=self.root)
        record = manifest["families"][0]["files"][0]
        record["rawUrl"] = raw_url
        record["fetchNote"] = fetch_note
        manifest["families"][0]["synthetic"] = synthetic
        manifest["families"][0]["builtBy"] = built_by
        return manifest

    def run_fetch(self, manifest, payload, only_key=None):
        calls = []

        def fake_urlopen(url):
            calls.append(url)
            return _Response(payload)

        with unittest.mock.patch("urllib.request.urlopen", fake_urlopen):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                written = fetch_fonts.fetch(manifest, self.root, only_key)
        return written, output.getvalue(), calls

    def test_a_base64_payload_that_decodes_to_the_pinned_digest_is_written_decoded(self):
        font = b"\x00\x01\x00\x00decoded"
        path = "test-fixtures/fonts/tiny/tiny.ttf"
        manifest = self.manifest_for(path, hashlib.sha256(font).hexdigest(), len(font))
        written, _, calls = self.run_fetch(manifest, base64.encodebytes(font))
        self.assertEqual(1, written)
        self.assertEqual(["https://example.invalid/raw/tiny.ttf"], calls)
        self.assertEqual(font, (self.root / path).read_bytes())

    def test_a_payload_that_matches_neither_reading_blocks(self):
        font = b"\x00\x01\x00\x00decoded"
        manifest = self.manifest_for("test-fixtures/fonts/tiny/tiny.ttf", "0" * 64, len(font))
        with self.assertRaises(SystemExit) as blocked:
            self.run_fetch(manifest, base64.encodebytes(font))
        self.assertIn("0" * 64, str(blocked.exception))
        self.assertFalse((self.root / "test-fixtures/fonts/tiny/tiny.ttf").exists())

    def test_a_file_with_a_fetch_note_and_no_raw_url_is_skipped_and_never_downloaded(self):
        font = b"\x00\x01\x00\x00tiny"
        manifest = self.manifest_for(
            "test-fixtures/fonts/tiny/tiny.ttf",
            hashlib.sha256(font).hexdigest(),
            len(font),
            raw_url=None,
            fetch_note="release archive; unzip manually",
        )
        written, output, calls = self.run_fetch(manifest, b"never read")
        self.assertEqual(0, written)
        self.assertEqual([], calls)
        self.assertIn("release archive; unzip manually", output)

    def test_a_synthetic_family_is_skipped_by_a_full_fetch(self):
        manifest = self.manifest_for("test-fixtures/fonts/tiny/tiny.ttf", "0" * 64, 0, synthetic=True, built_by="build_tiny.py")
        written, output, calls = self.run_fetch(manifest, b"never read")
        self.assertEqual(0, written)
        self.assertEqual([], calls)
        self.assertIn("build_tiny.py", output)

    def test_a_synthetic_family_named_by_key_blocks(self):
        manifest = self.manifest_for("test-fixtures/fonts/tiny/tiny.ttf", "0" * 64, 0, synthetic=True, built_by="build_tiny.py")
        with self.assertRaises(SystemExit) as blocked:
            self.run_fetch(manifest, b"never read", only_key="tiny")
        self.assertIn("build_tiny.py", str(blocked.exception))


if __name__ == "__main__":
    unittest.main()
