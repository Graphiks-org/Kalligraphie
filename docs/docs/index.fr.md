# Documentation de Kalligraphie

Kalligraphie est une bibliothèque portable Kotlin Multiplatform (KMP) de gestion de polices. Son interface de programmation (API) est répartie en modules Gradle spécialisés afin de faire évoluer indépendamment les contrats, l’analyse des données SFNT et OpenType (formats binaires de fontes), les métriques et l’accès aux glyphes.

## Modules

- `:kalligraphie` est la façade publique utilisée par les applications.
- `:kalligraphie:api` contient les contrats publics portables et les types de valeur immuables.
- `:kalligraphie:font:core` fournit les sources, faces (variantes de fonte) et instances de polices.
- `:kalligraphie:font:sfnt` analyse les données de fontes aux formats SFNT et OpenType dans des limites explicites.
- `:kalligraphie:font:scaler` calcule les métriques et les contours TrueType.
- `:kalligraphie:font:glyph` matérialise les ressources de rendu détachées.

## Commandes utiles

```bash
# Exécuter tous les tests JVM de la pile de polices.
./gradlew :kalligraphie:fontTest

# Générer et intégrer la documentation de l’API (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Construire le site localement.
mkdocs build -f docs/mkdocs.yml
```
