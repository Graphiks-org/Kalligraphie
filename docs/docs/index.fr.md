# Bienvenue sur la Documentation du KMP Starter Pack

Ce site regroupe l'ensemble des documentations techniques, des guides d'architecture et de la référence API du **Starter Pack Kotlin Multiplatform (KMP)**.

---

## 🚀 Fonctionnalités Clés

*   **Multiplateforme Complet** : Partage de code ciblant **Android**, **iOS** et **Desktop (JVM)**.
*   **Architecture Guidée (Clean Architecture / DDD)** : Séparation stricte de la logique métier (Domaine), de l'infrastructure (Données) et de l'interface (Présentation).
*   **Pile Technique Moderne** : **Kotlin 2.4.0**, **Gradle 9.5.0**, **AGP 9.0.0** et **Java 25**.
*   **Intégration Continue Conditionnelle** : Un workflow CI/CD à double-vitesse (JVM Fast-Track de 10 secondes vs Deep-Testing complet avant merge sur `master`).
*   **Moteur de Documentation API** : Génération automatisée de la documentation d'API via **Dokka v2** et rendu via **MkDocs Material**.

---

## 🧱 Organisation Architecturales du Projet

Le module partagé `:shared` suit les directives fournies par le skill **Architecte Kotlin** :

1.  **Couche Domaine (Domain)** :
    *   Écrite en Kotlin pur (sans dépendance).
    *   Contient les UseCases autonomes modélisant les cas d'utilisation métier.
    *   Modèles de données robustes auto-validés (utilisation de `value class` inline).
2.  **Couche Données (Data)** :
    *   Implémentation des dépôts et communication de bas niveau (réseau via Ktor, persistance).
    *   Gestion d'exceptions Flow transparente (garantissant que `AbortFlowException` n'est pas intercepté accidentellement).
3.  **Couche Présentation (Presentation)** :
    *   Interface réactive basée sur des `StateFlow` immuables.
    *   Scopes coroutines proprement gérés et ViewModels autonomes.

---

## 💻 Commandes Utiles

### Exécuter la suite de tests (Fast-Track JVM)
```bash
./gradlew :shared:jvmTest
```

### Lancer tous les tests (Toutes cibles)
```bash
./gradlew allTests
```

### Générer et intégrer localement la documentation de l'API (Dokka → MkDocs)
```bash
./gradlew :docs:embedDokkaIntoMkDocs
```

### Compiler localement le site MkDocs
```bash
mkdocs build -f docs/mkdocs.yml
```
