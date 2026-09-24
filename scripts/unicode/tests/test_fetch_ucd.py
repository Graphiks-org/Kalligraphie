import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import fetch_ucd


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
MANIFEST_PATH = REPOSITORY_ROOT / "scripts" / "unicode" / "ucd.json"


def digest_of(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def manifest_with(**overrides) -> dict:
    """A one-source manifest whose single payload is a cached file called `tiny.txt`."""
    source = {
        "key": "tiny",
        "url": "https://example.invalid/ucd/tiny.txt",
        "unicodeVersion": "16.0.0",
        "retrieved": "2026-09-24",
        "license": "Unicode-3.0",
        "sha256": digest_of(b"tiny\n"),
        "sizeBytes": 5,
        "repositoryPath": None,
        "cacheFile": "tiny.txt",
        "kdocFileName": None,
        "provides": ["Script"],
    }
    source.update(overrides)
    return {
        "schema": "kalligraphie.ucd-sources/v1",
        "unicodeVersion": "16.0.0",
        "sources": [source],
    }


class ManifestErrorsTest(unittest.TestCase):
    def test_a_healthy_manifest_has_no_errors(self):
        self.assertEqual([], fetch_ucd.manifest_errors(manifest_with()))

    def test_an_unknown_schema_blocks(self):
        manifest = manifest_with()
        manifest["schema"] = "kalligraphie.ucd-sources/v2"
        self.assertTrue(fetch_ucd.manifest_errors(manifest))

    def test_sources_out_of_key_order_block(self):
        manifest = manifest_with()
        manifest["sources"].append(dict(manifest["sources"][0], key="aaa"))
        self.assertIn("sources must be sorted by key", fetch_ucd.manifest_errors(manifest))

    def test_a_duplicate_key_blocks(self):
        manifest = manifest_with()
        manifest["sources"].append(dict(manifest["sources"][0]))
        errors = fetch_ucd.manifest_errors(manifest)
        self.assertTrue(any(error.startswith("duplicate source keys") for error in errors), errors)

    def test_a_short_digest_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(sha256="abc")))

    def test_an_uppercase_digest_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(sha256=digest_of(b"tiny\n").upper())))

    def test_a_plain_http_url_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(url="http://example.invalid/tiny.txt")))

    def test_a_source_with_both_readings_blocks(self):
        manifest = manifest_with(repositoryPath="scripts/unicode/tiny.txt")
        errors = fetch_ucd.manifest_errors(manifest)
        self.assertTrue(any("exactly one of repositoryPath and cacheFile" in error for error in errors), errors)

    def test_a_source_with_neither_reading_blocks(self):
        manifest = manifest_with(cacheFile=None)
        errors = fetch_ucd.manifest_errors(manifest)
        self.assertTrue(any("exactly one of repositoryPath and cacheFile" in error for error in errors), errors)

    def test_a_cache_file_that_is_a_path_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(cacheFile="nested/tiny.txt")))

    def test_an_unknown_property_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(provides=["Nonexistent_Property"])))

    def test_empty_provides_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(provides=[])))

    def test_unsorted_provides_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(provides=["Script_Extensions", "Script"])))

    def test_a_non_positive_size_blocks(self):
        self.assertTrue(fetch_ucd.manifest_errors(manifest_with(sizeBytes=0)))


class CheckSourcesTest(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._temporary.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()

    def tearDown(self):
        self._temporary.cleanup()

    def write_cache(self, payload: bytes) -> None:
        (self.cache / "tiny.txt").write_bytes(payload)

    def test_a_matching_payload_passes(self):
        self.write_cache(b"tiny\n")
        errors, notes = fetch_ucd.check_sources(manifest_with(), self.root, self.cache)
        self.assertEqual([], errors)
        self.assertEqual([], notes)

    def test_a_wrong_digest_is_reported(self):
        self.write_cache(b"other\n")
        errors, _ = fetch_ucd.check_sources(manifest_with(), self.root, self.cache)
        self.assertTrue(any("the manifest pins" in error for error in errors), errors)

    def test_a_wrong_size_is_reported(self):
        payload = b"tiny!\n"
        self.write_cache(payload)
        errors, _ = fetch_ucd.check_sources(manifest_with(sha256=digest_of(payload)), self.root, self.cache)
        self.assertTrue(any("bytes, but the manifest pins" in error for error in errors), errors)

    def test_an_absent_cache_entry_is_a_note_and_not_an_error(self):
        errors, notes = fetch_ucd.check_sources(manifest_with(), self.root, self.cache)
        self.assertEqual([], errors)
        self.assertTrue(any("run --fetch" in note for note in notes), notes)

    def test_a_missing_committed_source_is_an_error(self):
        manifest = manifest_with(cacheFile=None, repositoryPath="scripts/unicode/tiny.txt")
        errors, notes = fetch_ucd.check_sources(manifest, self.root, self.cache)
        self.assertTrue(any("is missing" in error for error in errors), errors)
        self.assertEqual([], notes)

    def test_a_committed_source_present_and_matching_passes(self):
        declared = self.root / "scripts" / "unicode" / "tiny.txt"
        declared.parent.mkdir(parents=True, exist_ok=True)
        declared.write_bytes(b"tiny\n")
        manifest = manifest_with(cacheFile=None, repositoryPath="scripts/unicode/tiny.txt")
        errors, notes = fetch_ucd.check_sources(manifest, self.root, self.cache)
        self.assertEqual([], errors)
        self.assertEqual([], notes)


class CacheCoverageTest(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.cache = pathlib.Path(self._temporary.name) / "cache"
        self.cache.mkdir()

    def tearDown(self):
        self._temporary.cleanup()

    def test_a_declared_payload_passes(self):
        (self.cache / "tiny.txt").write_bytes(b"tiny\n")
        self.assertEqual([], fetch_ucd.coverage_errors(manifest_with(), self.cache))

    def test_an_undeclared_payload_in_the_cache_is_reported(self):
        (self.cache / "phantom.txt").write_bytes(b"phantom\n")
        errors = fetch_ucd.coverage_errors(manifest_with(), self.cache)
        self.assertTrue(any("phantom.txt is not declared" in error for error in errors), errors)

    def test_an_absent_cache_directory_is_not_a_coverage_failure(self):
        self.assertEqual([], fetch_ucd.coverage_errors(manifest_with(), self.cache / "absent"))


class _Response:
    def __init__(self, payload: bytes):
        self._payload = payload

    def read(self) -> bytes:
        return self._payload

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *_: object) -> bool:
        return False


class FetchTransportTest(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._temporary.name)
        self.cache = self.root / "cache"

    def tearDown(self):
        self._temporary.cleanup()

    def test_a_payload_matching_the_pinned_digest_is_written(self):
        with unittest.mock.patch("urllib.request.urlopen", return_value=_Response(b"tiny\n")):
            self.assertEqual(0, fetch_ucd.fetch(manifest_with(), self.root, self.cache, None))
        self.assertEqual(b"tiny\n", (self.cache / "tiny.txt").read_bytes())

    def test_a_payload_that_does_not_match_is_refused_and_never_written(self):
        with unittest.mock.patch("urllib.request.urlopen", return_value=_Response(b"other\n")):
            self.assertEqual(1, fetch_ucd.fetch(manifest_with(), self.root, self.cache, None))
        self.assertFalse((self.cache / "tiny.txt").exists())

    def test_a_committed_source_is_never_downloaded(self):
        manifest = manifest_with(cacheFile=None, repositoryPath="scripts/unicode/tiny.txt")
        with unittest.mock.patch("urllib.request.urlopen") as open_mock:
            self.assertEqual(0, fetch_ucd.fetch(manifest, self.root, self.cache, None))
        open_mock.assert_not_called()

    def test_an_unknown_key_restriction_fails_before_touching_the_network(self):
        with unittest.mock.patch("urllib.request.urlopen") as open_mock:
            self.assertEqual(2, fetch_ucd.fetch(manifest_with(), self.root, self.cache, "absent"))
        open_mock.assert_not_called()


class CommittedManifestTest(unittest.TestCase):
    """The manifest of record: structurally sound, rooted in files that really are what it pins."""

    def setUp(self):
        self.manifest = fetch_ucd.load_manifest(MANIFEST_PATH)
        self.cache = MANIFEST_PATH.parent / fetch_ucd.CACHE_DIRNAME

    def test_the_committed_manifest_is_structurally_sound(self):
        self.assertEqual([], fetch_ucd.manifest_errors(self.manifest))

    def test_the_committed_manifest_declares_the_expected_sources(self):
        declared = {source["key"]: tuple(source["provides"]) for source in self.manifest["sources"]}
        self.assertEqual(
            {
                "bidi-brackets": ("Bidi_Paired_Bracket", "Bidi_Paired_Bracket_Type"),
                "bidi-class": ("Bidi_Class",),
                "core-properties": ("Indic_Conjunct_Break",),
                "emoji-data": ("Extended_Pictographic",),
                "grapheme-break": ("Grapheme_Cluster_Break",),
                "likely-subtags": ("language-to-script",),
                "line-break": ("Line_Break",),
                "property-value-aliases": ("ISO-15924-short-names",),
                "script-extensions": ("Script_Extensions",),
                "scripts": ("Script",),
                "vertical-orientation": ("Vertical_Orientation",),
            },
            declared,
        )

    def test_every_committed_source_present_on_disk_matches_its_pinned_digest(self):
        errors, _ = fetch_ucd.check_sources(self.manifest, REPOSITORY_ROOT, self.cache)
        self.assertEqual([], errors)

    def test_the_cache_directory_holds_no_undeclared_payload(self):
        self.assertEqual([], fetch_ucd.coverage_errors(self.manifest, self.cache))

    def test_every_property_the_generator_needs_is_declared(self):
        declared = {entry for source in self.manifest["sources"] for entry in source["provides"]}
        self.assertEqual(fetch_ucd.ALLOWED_PROVIDES, declared)


if __name__ == "__main__":
    unittest.main()
