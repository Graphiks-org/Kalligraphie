# Documentation de Kalligraphie

Kalligraphie est une bibliothèque JVM de gestion de polices. Son API est répartie en modules Gradle ciblés afin de faire évoluer indépendamment le parsing, le shaping, la mise à l’échelle et l’accès aux glyphes.

## Modules

- `:font:core` fournit les primitives communes aux polices.
- `:font:sfnt` analyse les données SFNT et OpenType.
- `:font:colr` gère les tables de polices couleur COLR.
- `:font:scaler` met à l’échelle et rasterise les glyphes.
- `:font:text` réalise le shaping (mise en forme typographique) du texte.
- `:font:glyph` expose l’API orientée glyphes.
- `:font` agrège les modules publics destinés aux consommateurs.

## Commandes utiles

```bash
# Exécuter tous les tests JVM de la pile de polices.
./gradlew :font:fontTest

# Générer et intégrer la référence API (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Construire le site localement.
mkdocs build -f docs/mkdocs.yml
```
