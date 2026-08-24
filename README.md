# 🚀 Project Template - Kotlin Multiplatform (KMP)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-purple?logo=kotlin)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.5.0-blue?logo=gradle)](https://gradle.org)
[![AGP](https://img.shields.io/badge/AGP-9.0.0-green?logo=android)](https://developer.android.com/studio/releases/gradle-plugin)
[![Java](https://img.shields.io/badge/Java-25-red?logo=openjdk)](https://openjdk.org)
[![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-blue?logo=github-actions)](https://github.com/features/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Contributing](https://img.shields.io/badge/Contributing-guide-purple)](CONTRIBUTING.md)
[![Projet: Planning](https://img.shields.io/badge/Statut-Planning-blue)](https://github.com)
[![Projet: Incubating](https://img.shields.io/badge/Statut-Incubating-orange)](https://github.com)
[![Projet: Stable](https://img.shields.io/badge/Statut-Stable-green)](https://github.com)
[![Projet: Deprecated](https://img.shields.io/badge/Statut-Deprecated-red)](https://github.com)
[![Projet: Archived](https://img.shields.io/badge/Statut-Archived-lightgrey)](https://github.com)

---

This project is a **state-of-the-art Kotlin Multiplatform (KMP) Starter Pack** targeting **Android**, **iOS**, and **Desktop (JVM)**. It leverages the latest ecosystem technologies (Kotlin 2.4.0, Gradle 9.5.0, AGP 9.0, Java 25) and rigorously applies **Clean Architecture** and **Domain-Driven Design (DDD)** principles.

---

<!-- ==========================================
     BADGES DE STATUT DE PROJET PERSONNALISABLES
     Décommentez/copiez simplement le badge correspondant au statut actuel de votre projet.
     ========================================== -->

<!-- STATUT : EN PLANIFICATION (PLANNING) -->
<!-- [![Projet: Planning](https://img.shields.io/badge/Statut-Planning-blue)](https://github.com) -->

<!-- STATUT : INCUBATION / EN DÉVELOPPEMENT (INCUBATING) -->
<!-- [![Projet: Incubating](https://img.shields.io/badge/Statut-Incubating-orange)](https://github.com) -->

<!-- STATUT : STABLE / PRÊT PRODUCTION (STABLE) -->
<!-- [![Projet: Stable](https://img.shields.io/badge/Statut-Stable-green)](https://github.com) -->

<!-- STATUT : DEPRÉCIÉ (DEPRECATED) -->
<!-- [![Projet: Deprecated](https://img.shields.io/badge/Statut-Deprecated-red)](https://github.com) -->

<!-- STATUT : ARCHIVÉ (ARCHIVED) -->
<!-- [![Projet: Archived](https://img.shields.io/badge/Statut-Archived-lightgrey)](https://github.com) -->

## 🤝 Contribuer / Contributing

Les contributions sont les bienvenues ! Consultez :

Contributions are welcome! See:

- [🇬🇧 Contributing Guide](CONTRIBUTING.md)
- [🇬🇧 Code of Conduct](CODE_OF_CONDUCT.md) / [🇫🇷 Code de Conduite](CODE_OF_CONDUCT.fr.md)
- [🇬🇧 Security Policy](SECURITY.md) / [🇫🇷 Politique de Sécurité](SECURITY.fr.md)
- [🇬🇧 Support](SUPPORT.md) / [🇫🇷 Assistance](SUPPORT.fr.md)
- [Changelog](CHANGELOG.md)

---

## 🏗️ Project Architecture

The `:shared` module is organized into distinct layers to maximize testability, maintainability, and decoupling:

```mermaid
graph TD
    UI[Presentation Layer: Compose Multiplatform / ViewModel] --> Domain[Domain Layer: Use Cases / Models / Repository Interfaces]
    Data[Data Layer: Repository Impls / Ktor / Local SQL] --> Domain
    Data --> Platform[Platform-Specific Code: expect/actual]
```

### Design Layers (`shared/src/commonMain`)
*   **Domain**: Contains pure business rules with zero framework dependencies (Use Cases with `invoke` operator, self-validating models, repository interfaces).
*   **Data**: Concrete repository implementations, network communication, and database layer.
*   **Presentation**: Immutable `UiState` modeling and ViewModels using asynchronous `StateFlow`.
*   **Dependency Injection (DI)**: Centralized multiplatform configuration via **Koin**.

---

## ⚡ CI/CD Workflow

The GitHub Actions pipeline ([ci.yml](file:///.github/workflows/ci.yml)) implements a dual-speed system optimized for bandwidth and compute time:

- **Fast-Track (Feature branches)**: Compiles and tests only the local JVM target (`./gradlew :shared:jvmTest`). Runs in under 10 seconds.
- **Deep-Testing (Branches / Pull Requests to `master`)**: Runs the full test suite (`./gradlew allTests`) on all simulators and target platforms to validate code quality before production.

---

## 🛠️ Useful Development Commands

### Run local tests (JVM Fast-Track)
```bash
./gradlew :shared:jvmTest
```

### Run all tests (All targets)
```bash
./gradlew allTests
```

### Generate Gradle Wrapper
```bash
gradle wrapper
```
