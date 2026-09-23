# Getting started

## Requirements

- JDK 25
- Python 3 with MkDocs Material and `mkdocs-static-i18n` to build the site
- `python3` on `PATH` to run the font-corpus checks below; `./gradlew check` needs it too, because the
  e2e module re-reads its exported claims with it
- `fonttools==4.65.0` for the corpus lint, the version those audits pin:
  `uv run --with fonttools==4.65.0 python …` supplies it without installing anything

## Verify the Kalligraphie font modules

```bash
./gradlew check
```

## Verify the font corpus

`scripts/fonts/corpus.json` describes every font committed under `test-fixtures/fonts/`. These
checks are local obligations rather than CI steps — continuous integration here carries the catalog
ratchet, the golden fingerprints and the freshness of the generated artifacts — and they run
offline, never downloading a font:

```bash
python3 scripts/fonts/fetch_fonts.py --check --provenance
python3 -m unittest discover -s scripts/fonts/tests -v
uv run --with fonttools==4.65.0 python scripts/fonts/check_exhaustiveness.py
```

Run them before committing any change to `test-fixtures/fonts/`, to `corpus.json`, or to the
tables the catalog claims. `scripts/fonts/README.md` documents the manifest and the acquisition
contract; `--fetch` is the only mode that downloads.

## Build the API reference and site

```bash
./gradlew :docs:embedDokkaIntoMkDocs
mkdocs build -f docs/mkdocs.yml
```

The generated site is written to `_site/`.
