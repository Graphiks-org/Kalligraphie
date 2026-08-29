# Documentation de Kalligraphie

Kalligraphie est une bibliothèque KMP portable de gestion de polices. Son API est répartie en modules Gradle ciblés afin de faire évoluer indépendamment les contrats, le parsing SFNT, les métriques et l’accès aux glyphes.

## Modules

- `:kalligraphie` est la façade publique consommée par les applications.
- `:kalligraphie:api` contient les contrats publics portables et les types de valeur immuables.
- `:kalligraphie:unicode` contient les contrats Unicode portables.
- `:kalligraphie:font:core` fournit les sources, faces et instances de polices.
- `:kalligraphie:font:sfnt` analyse les données SFNT et OpenType avec des limites explicites.
- `:kalligraphie:font:scaler` résout les métriques et les contours TrueType.
- `:kalligraphie:font:glyph` matérialise les assets de rendu détachés.

## Commandes utiles

```bash
# Exécuter tous les tests JVM de la pile de polices.
./gradlew :kalligraphie:fontTest

# Générer et intégrer la référence API (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Construire le site localement.
mkdocs build -f docs/mkdocs.yml
```
