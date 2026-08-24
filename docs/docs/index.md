# Welcome to the KMP Starter Pack Documentation

This site centralizes all technical documentation, architecture guidelines, and API references for the **Kotlin Multiplatform (KMP) Starter Pack**.

---

## 🚀 Key Features

*   **Complete Multiplatform Support**: Code sharing targeting **Android**, **iOS**, and **Desktop (JVM)**.
*   **Guided Architecture (Clean Architecture / DDD)**: Strict separation of business logic (Domain), infrastructure (Data), and user interface (Presentation).
*   **Modern Tech Stack**: **Kotlin 2.4.0**, **Gradle 9.5.0**, **AGP 9.0.0**, and **Java 25**.
*   **Conditional Continuous Integration**: A double-speed CI/CD workflow (10-second JVM Fast-Track vs full Deep-Testing suite before merging into `master`).
*   **API Documentation Engine**: Automated API doc generation via **Dokka v2** and beautiful rendering through **MkDocs Material**.

---

## 🧱 Architectural Organization of the Project

The shared module `:shared` strictly follows the guidelines of the **Kotlin Architect** skill:

1.  **Domain Layer**:
    *   Written in pure Kotlin (zero external framework dependencies).
    *   Contains self-contained UseCases modeling distinct business features.
    *   Robust, self-validating data models (using inline `value class` patterns).
2.  **Data Layer**:
    *   Repository implementations and low-level communication (networking via Ktor, database/persistence).
    *   Transparent Flow exception handling (ensuring `AbortFlowException` is never caught by accident).
3.  **Presentation Layer**:
    *   Reactive user interface driven by immutable `StateFlow` structures.
    *   Properly managed coroutine scopes and decoupled ViewModels.

---

## 💻 Useful Commands

### Run tests (Fast-Track JVM)
```bash
./gradlew :shared:jvmTest
```

### Run all tests (All Platforms)
```bash
./gradlew allTests
```

### Generate and embed API documentation locally (Dokka → MkDocs)
```bash
./gradlew :docs:embedDokkaIntoMkDocs
```

### Compile the MkDocs site locally
```bash
mkdocs build -f docs/mkdocs.yml
```
