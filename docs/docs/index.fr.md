# Documentation de Kalligraphie

Kalligraphie est une bibliothèque portable Kotlin Multiplatform (KMP) de gestion de polices. Son interface de programmation (API) est répartie en modules Gradle spécialisés afin de faire évoluer indépendamment les contrats, l’analyse des données SFNT et OpenType (formats binaires de fontes), les métriques et l’accès aux glyphes.

## Modules

- `:kalligraphie` est la façade publique utilisée par les applications.
- `:kalligraphie:api` contient les contrats publics portables et les types de valeur immuables.
- `:kalligraphie:unicode` fournit le décodage canonique du texte et l’analyse Unicode de référence sur la JVM.
- `:kalligraphie:shaping` fournit l’adaptateur HarfBuzz de référence sur la JVM derrière des contrats de composition portables.
- `:kalligraphie:layout` positionne les runs composés et fournit la géométrie exacte de lignes et de paragraphes éditables.
- `:kalligraphie:font:core` fournit les sources, faces (variantes de fonte) et instances de polices.
- `:kalligraphie:font:sfnt` analyse les données de fontes aux formats SFNT et OpenType dans des limites explicites.
- `:kalligraphie:font:scaler` calcule les métriques et les contours TrueType.
- `:kalligraphie:font:glyph` matérialise les ressources de rendu détachées.

## Commandes utiles

```bash
# Exécuter le cycle de vérification Gradle standard.
./gradlew check

# Générer et intégrer la documentation de l’API (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Construire le site localement.
mkdocs build -f docs/mkdocs.yml
```
