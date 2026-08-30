# Getting started

## Requirements

- JDK 25
- Python 3 with MkDocs Material and `mkdocs-static-i18n` to build the site

## Verify the Kalligraphie font modules

```bash
./gradlew check
```

## Build the API reference and site

```bash
./gradlew :docs:embedDokkaIntoMkDocs
mkdocs build -f docs/mkdocs.yml
```

The generated site is written to `_site/`.
