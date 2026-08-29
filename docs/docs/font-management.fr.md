# Gestion des fontes

Kalligraphie propose une prise en charge de fontes TrueType embarquées via
`org.graphiks:kalligraphie`, uniquement sur la cible de référence de la
machine virtuelle Java (JVM). Les contrats publics restent portables, mais
cette prise en charge exécutable est limitée à la JVM. L’utilisateur de la
bibliothèque fournit des octets SFNT capturés à `Kalligraphie.embedded(...)`,
sélectionne la face `0` (variante de fonte), crée une instance de fonte, puis utilise une
ressource de rendu pour matérialiser les contours décrits par
`GlyphOutlineIR`.

Le périmètre fonctionnel supporté est volontairement étroit :

- cible JVM de référence uniquement ;
- fontes TrueType SFNT statiques uniquement : `0x00010000` et `true` ;
- une source embarquée et l’index de face `0` ;
- `LAYOUT_ONLY` pour la table `cmap` (correspondance entre caractères et
  glyphes) et les métriques ;
- `RENDERABLE` uniquement avec la version `1` du schéma `OutlineProfile` ;
- contours de glyphes exprimés en unités de conception (unités internes de la
  fonte), avec des métriques mises à l’échelle séparément en `LayoutUnit` ;
- ressources de rendu détachées qui restent utilisables après la fermeture du
  gestionnaire propriétaire ou de la ressource attachée.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceRequest = FontFaceRequest(0)
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

L’accès aux glyphes pour le rendu exige un profil de contour explicite. Fermer
un gestionnaire de ressources ou une ressource de rendu est idempotent (répéter
la fermeture produit le même résultat). Les nouvelles acquisitions après
fermeture renvoient `font.resource-closed` ; une ressource détachée conserve les
données immuables requises par `resolveGlyph(...)`.

Hors périmètre : TTC/OTC, CFF/CFF2, variations, styles synthétiques, COLR, SVG,
glyphes sous forme d’images matricielles, fontes système, composition
contextuelle, mise en page, ajustement des contours aux pixels (hinting),
rastérisation, moteurs natifs de gestion des fontes et descripteurs de fonte
propres à la plateforme.
