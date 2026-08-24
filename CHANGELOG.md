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

### Added
- KMP project template (Android, iOS, Desktop)
- Clean Architecture / DDD
- Maven Central publishing via Vanniktech
- Multilingual docs (EN/FR) MkDocs + Dokka
- GitHub templates (issues, PR)
- Code of Conduct, CONTRIBUTING, SECURITY, SUPPORT, CHANGELOG
- JVM-first OpenType font management imported from the pinned Kanvas font source.
- Deterministic SFNT parsing, scaling, text shaping, glyph planning and fixture provenance.

### Deferred
- GPU text contracts and gpu-api remain intentionally excluded.
- Android and iOS font implementations are not part of this release.

### Changed
- Replaced the Dokka GFM and Python post-processing pipeline with Dokka for Material for MkDocs.

### Fixed
- Default snapshot publication version when no workflow version is provided.

### Built with
- Kotlin 2.4.10, Gradle 9.6.1, AGP 9.0.0
- Koin 4.0.0, Ktor 3.0.3, Compose Multiplatform 1.11.1
