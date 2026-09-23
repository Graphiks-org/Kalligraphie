# Catalogue d'attentes end-to-end

Généré depuis `ExpectationCatalog` par `./gradlew :kalligraphie:e2e:updateE2eGolden` ; ne pas modifier à la main.

## Totaux

| Statut | Entrées |
| --- | --- |
| Supporté | 23 |
| Rejet attendu | 2 |
| Pas encore | 11 |
| Hors périmètre | 1 |

## CONTAINER

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `container.woff2` | Encapsulation dans un conteneur WOFF 2.0 | — | Pas encore ; corpus non acquis |
| `container.woff` | Encapsulation dans un conteneur WOFF 1.0 | — | Pas encore ; corpus non acquis |

## OUTLINE

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `outline.glyf-simple-composite` | Contours TrueType glyf simples et composites | liberation | Supporté depuis 35c4422c |
| `outline.cff1-static` | Charstrings Type 2 CFF 1 statiques | cff-liberation | Supporté depuis 4b156eac |
| `outline.cff2-static` | Charstrings CFF 2 statiques sans deltas de variation | cff2-liberation | Supporté depuis 4b156eac |

## METRICS

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `metrics.vvar-advance-height` | Police variable portant VVAR : la scène charge la police et rastérise son contour, sans observer de delta d'avance verticale | kalligraphie-var-vvar | Supporté depuis 4b156eac |
| `metrics.vvar-real-font` | Métriques verticales vvar d'une vraie police | — | Pas encore ; aucune police réelle connue |
| `metrics.mvar-real-font` | Variations de métriques mvar d'une vraie police | — | Pas encore ; aucune police réelle connue |

## VARIATION

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `variation.wght-ladder` | Sélection `wght` en coordonnées de conception sur une vraie police variable : un texte à cinq graisses, chacune composée, mise en forme et rastérisée indépendamment, sur une même grille de lignes de base | worksans | Supporté depuis 4f9b70bd |
| `variation.avar-v2` | Cartes de segments avar version 2 | — | Pas encore ; aucune police réelle connue |
| `variation.cvar` | Variations CVT cvar | — | Pas encore ; aucune police réelle connue |
| `variation.varc` | Glyphes composites variables VARC | — | Pas encore ; aucune police réelle connue |
| `variation.stat` | Attributs de style STAT (instances nommées et leurs axes) | — | Pas encore ; le corpus le porte, le lecteur non |

## COLOR

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `color.colr-v0-single-glyph` | Graphe de peinture COLR v0 + CPAL v0 d'un glyphe isolé | emoji-two-colr-v0 | Supporté depuis fa405247 |
| `color.colr-v0-alphabet-sheet` | Planche d'alphabet latin COLR v0 + CPAL v0 | bungee-color | Supporté depuis fa405247 |
| `color.colr-v0-emoji-sheet` | Planche d'alphabet emoji COLR v0 + CPAL v0 | emoji-two-colr-v0 | Supporté depuis fa405247 |
| `color.colr-cff` | Glyphes COLR adossés à des charstrings CFF | — | Hors périmètre : Le CFF-dans-COLR ne fait pas partie de la surface de peinture supportée ; le motif est consigné dans font-management.md. |
| `color.colr-v1-variable` | Graphes de peinture COLR v1 variables ; bloqués aujourd'hui par le compositeur CPU, qui ne compose pas les nœuds GlyphClip | kalligraphie-var-colr | Pas encore ; comportement actuel épinglé (`spec:§5 color`) |
| `color.cpal-variable` | Palettes CPAL variables | — | Pas encore ; corpus non acquis |

## BITMAP

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `bitmap.ebdt-format1` | Strikes bitmap embarqués EBLC/EBDT, format 1 | skia-ebdt-format1 | Supporté depuis fa405247 |
| `bitmap.cbdt-png.u1f600.16` | Strike couleur PNG CBLC/CBDT | skia-cbdt | Supporté depuis 4b156eac |
| `bitmap.sbix-png.u1f600.16` | Strike PNG sbix | skia-sbix | Supporté depuis 4b156eac |

## SCRIPT

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `script.latin.composed-line` | Ligne latine composée via la façade de paragraphe | liberation | Supporté depuis fa405247 |
| `script.greek.composed-line` | Ligne grecque composée via la façade de paragraphe | liberation | Supporté depuis fa405247 |
| `script.cyrillic.composed-line` | Ligne cyrillique composée via la façade de paragraphe | liberation | Supporté depuis fa405247 |
| `script.arabic.composed-line` | Ligne arabe composée via la façade de paragraphe | amiri | Supporté depuis fa405247 |
| `script.devanagari.composed-line` | Ligne devanagari composée via la façade de paragraphe | noto-devanagari | Supporté depuis fa405247 |
| `script.mixed.composed-line` | Ligne composée multi-scripts sur les trois polices requises (liberation, amiri, noto-devanagari) | liberation | Supporté depuis fa405247 |
| `script.latin.outline-sheet` | Planche d'alphabet latin en contour | liberation | Supporté depuis fa405247 |
| `script.greek.outline-sheet` | Planche d'alphabet grec en contour | liberation | Supporté depuis fa405247 |
| `script.cyrillic.outline-sheet` | Planche d'alphabet cyrillique en contour | liberation | Supporté depuis fa405247 |
| `script.arabic.outline-sheet` | Planche d'alphabet arabe en contour | amiri | Supporté depuis fa405247 |
| `script.devanagari.outline-sheet` | Planche d'alphabet devanagari en contour | noto-devanagari | Supporté depuis fa405247 |

## COMPOSITION

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `composition.every-route-mosaic` | Sept routes de rendu dans une seule image : un mot en contour TrueType, le A majuscule en contour CFF 1 puis CFF 2, une ligne dont les trois scripts sont résolus par repli entre trois polices, un glyphe à quatre graisses, une peinture couleur et un strike bitmap, composés sur un même canvas couleur à marge gauche partagée | liberation | Supporté depuis 58ba2267 |
| `composition.per-span-style` | Style par plage à l'intérieur d'un paragraphe : un segment qui garde sa propre police ou sa propre instance de variation alors que le reste du paragraphe en garde une autre | — | Pas encore ; aucune entrée publique ne porte la demande |

## ROBUSTNESS

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `robustness.truncated-sfnt` | Conteneur TrueType tronqué | liberation | Rejet attendu `font.out-of-bounds` à DECODE |
| `robustness.empty-input` | Source de police de zéro octet | liberation | Rejet attendu `font.invalid-font-data` à DECODE |

