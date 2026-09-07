# Documentation de Kalligraphie

Kalligraphie est une bibliothèque portable de typographie Kotlin Multiplatform
(KMP). L’application fournit le texte, les fontes et la géométrie de
composition ; Kalligraphie publie la géométrie exacte des glyphes et de
l’édition, indépendamment du moteur de rendu. Son interface de programmation
(API) est répartie en modules Gradle spécialisés afin de faire évoluer
indépendamment les contrats, l’analyse Unicode, le façonnage, la composition et
l’accès aux glyphes.

## Modules

- `:kalligraphie` est la façade publique utilisée par les applications.
- `:kalligraphie:api` contient les contrats publics portables et les types de valeur immuables.
- `:kalligraphie:unicode` fournit le décodage canonique du texte et l’analyse Unicode de référence sur la JVM.
- `:kalligraphie:shaping` fournit l’adaptateur HarfBuzz de référence sur la JVM derrière des contrats de composition portables.
- `:kalligraphie:layout` positionne les séquences composées et fournit la géométrie exacte des lignes, paragraphes, régions de composition et résultats incrémentaux.
- `:kalligraphie:font:core` fournit les sources, faces (variantes de fonte) et instances de polices.
- `:kalligraphie:font:sfnt` analyse les données de fontes aux formats SFNT et OpenType dans des limites explicites.
- `:kalligraphie:font:scaler` calcule les métriques et les contours TrueType.
- `:kalligraphie:font:glyph` matérialise les ressources de rendu détachées.

## Guides consommateur

- [Gestion des fontes](font-management.fr.md) présente les catalogues
  embarqués, le repli ordonné, les lignes éditables exactes et les ressources
  de rendu détachées.
- [Paragraphes éditables](editable-paragraphs.fr.md) présente les paragraphes
  rectangulaires, les exclusions `FlowRegion` fournies par l’application, la
  composition dans une chaîne `FlowChain`, les fragments géométriques de
  ligne, les continuations exactes et la rematérialisation bornée en aval.
- [Typographie avancée](advanced-typography.fr.md) présente la provenance des
  glyphes dérivés, la césure, la justification, les tabulations, les objets
  dans la ligne, l’ellipsis (points de suspension) et l’écriture verticale.

Kalligraphie ne crée pas les pages et ne rend aucun pixel. L’application
possède son document, les objets page et leur placement global, la fenêtre
d’affichage, l’ordonnancement, le moteur de rendu et les ressources du
processeur graphique (GPU).

## Commandes utiles

```bash
# Exécuter le cycle de vérification Gradle standard.
./gradlew check

# Générer et intégrer la documentation de l’API (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Construire le site localement.
mkdocs build -f docs/mkdocs.yml
```
