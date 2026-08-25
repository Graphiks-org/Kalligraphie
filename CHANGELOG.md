# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Changed
- Kotlin 2.4.0 → 2.4.10
- Gradle 9.5.0 → 9.6.1
- Added blocking pull request policy checks aligned with `CONTRIBUTING.md`.
- The documentation site now embeds the API reference for the JVM font modules.

### Added
- JVM font module graph (`:font`, `:font:core`, `:font:sfnt`, `:font:colr`, `:font:scaler`, `:font:text`, and `:font:glyph`)
- Multilingual docs (EN/FR) MkDocs + Dokka
- GitHub templates (issues, PR)
- Code of Conduct, CONTRIBUTING, SECURITY, SUPPORT, CHANGELOG

### Changed
- Replaced the Dokka GFM and Python post-processing pipeline with Dokka for Material for MkDocs.

### Removed
- The `:shared` KMP template, its publishing workflow, and its CI workflow.

### Built with
- Kotlin 2.4.10 and Gradle 9.6.1
