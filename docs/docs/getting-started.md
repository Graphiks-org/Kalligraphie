# Getting Started — From Template to Your Project

Checklist of files to modify when starting a new project from this template.

## Project Identity

- [ ] `settings.gradle.kts:23` — set `rootProject.name`
- [ ] `build.gradle.kts:1` — set `group`
- [ ] `buildSrc/.../kmp-library.gradle.kts:25` — set Android `namespace`
- [ ] `buildSrc/.../kmp-library.gradle.kts:13` — set `jvmToolchain` version
- [ ] `buildSrc/.../kmp-library.gradle.kts:26-27` — set `compileSdk` / `minSdk`
- [ ] `buildSrc/.../kmp-publish.gradle.kts` — update `group`, `coordinates`, and all POM fields (name, description, url, licenses, developers, scm)
- [ ] `buildSrc/.../kmp-dokka.gradle.kts` — update `moduleName` and source link `remoteUrl`

## Source Code

- [ ] Rename package `io.ygdrasil` to your own across all source sets (`commonMain`, `androidMain`, `iosMain`, `jvmMain`)
- [ ] Move source files to match the new package directory layout
- [ ] Update imports in `Koin.kt` if module/package names changed
- [ ] Update `commonTest` package references if applicable

## CI / GitHub

- [ ] Review/update `.github/workflows/` CI files (repo references, badges)
- [ ] Update `README.md` badges (status, repo URLs)
- [ ] Update `README.md` content and description

## Documentation

- [ ] `docs/mkdocs.yml` — update `site_name`, `site_url`, `repo_url`, `repo_name`
- [ ] `docs/docs/index.md` — rewrite for your project
- [ ] `docs/docs/index.fr.md` — rewrite for your project (or delete if not needed)

## License

- [ ] `LICENSE` — update copyright holder

## Dependencies

- [ ] `gradle/libs.versions.toml` — review/update library versions as needed

## Final Verification

- [ ] `./gradlew build` succeeds
- [ ] `./gradlew :shared:jvmTest` passes
- [ ] `mkdocs build -f docs/mkdocs.yml` works
