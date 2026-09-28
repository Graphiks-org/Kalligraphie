import hashlib
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import fetch_dictionary as fetch


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
GENERATED = REPOSITORY_ROOT / fetch.TARGET


class RfcTablesTest(unittest.TestCase):
    def test_nwords_and_offsets_match_appendix_a(self):
        self.assertEqual(
            [0, 0, 0, 0, 1024, 1024, 2048, 2048, 1024, 1024, 1024, 1024, 1024, 512, 512, 256, 128, 128, 256, 128, 128, 64, 64, 32, 32],
            fetch.nwords(),
        )
        self.assertEqual(
            [0, 0, 0, 0, 0, 4096, 9216, 21504, 35840, 44032, 53248, 63488, 74752, 87040, 93696, 100864, 104704, 106752, 108928, 113536, 115968, 118528, 119872, 121280, 122016],
            fetch.offsets(),
        )
        self.assertEqual(fetch.DICTIONARY_SIZE, fetch.offsets()[24] + 24 * fetch.nwords()[24])


class ParseDictionaryTest(unittest.TestCase):
    def test_reads_the_generated_array_between_its_markers(self):
        source = (
            "static const uint8_t kBrotliDictionaryData[] =\n"
            "/* GENERATED CODE START */\n{116,105,109,101,\n 1,2,3}\n/* GENERATED CODE END */\n;\n"
        )
        self.assertEqual(b"time\x01\x02\x03", fetch.parse_dictionary(source))

    def test_a_source_without_the_markers_is_rejected(self):
        with self.assertRaises(SystemExit):
            fetch.parse_dictionary("no generated code here")


class VerifyDictionaryTest(unittest.TestCase):
    def test_a_wrong_length_is_rejected(self):
        with self.assertRaises(SystemExit):
            fetch.verify_dictionary(bytes(fetch.DICTIONARY_SIZE - 1))

    def test_a_wrong_digest_is_rejected(self):
        with self.assertRaises(SystemExit):
            fetch.verify_dictionary(bytes(fetch.DICTIONARY_SIZE))


class CommittedArtifactTest(unittest.TestCase):
    def test_the_committed_source_is_the_pinned_data_and_up_to_date(self):
        self.assertTrue(GENERATED.exists(), f"{GENERATED} is missing; run the generator")
        text = GENERATED.read_text(encoding="utf-8")
        dictionary = fetch.parse_committed_dictionary(text)
        self.assertEqual(fetch.DICTIONARY_SIZE, len(dictionary))
        self.assertEqual(fetch.DICTIONARY_SHA256, hashlib.sha256(dictionary).hexdigest())
        self.assertEqual(text, fetch.render(dictionary))


if __name__ == "__main__":
    unittest.main()
