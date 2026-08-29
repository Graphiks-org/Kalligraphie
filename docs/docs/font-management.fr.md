# Gestion des fontes

Kalligraphie J1 expose un chemin TrueType embarqué et portable via
`org.graphiks:kalligraphie`. Un consommateur fournit des octets SFNT capturés
à `Kalligraphie.embedded(...)`, résout la face `0`, crée une instance de
fonte, puis utilise un handle d’asset de rendu pour matérialiser des outlines
`GlyphOutlineIR`.

Le périmètre J1 supporté est volontairement étroit :

- TrueType SFNT statique uniquement : `0x00010000` et `true` ;
- une source embarquée et l’index de face `0` ;
- `LAYOUT_ONLY` pour cmap et métriques ;
- `RENDERABLE` uniquement avec `OutlineProfile` schema version `1` ;
- outlines de glyphes en design units (unités de dessin), avec métriques
  mises à l’échelle séparément en `LayoutUnit` ;
- assets de rendu détachés qui restent résolubles après fermeture du resolver
  propriétaire ou du handle attaché.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceRequest = FontFaceRequest(0)
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

L’accès renderable aux glyphes exige un profil d’outline explicite. Fermer un
resolver ou un asset de rendu est idempotent. Les nouvelles acquisitions après
fermeture retournent `font.resource-closed`; un asset détaché possède les
données immuables requises pour `resolveGlyph(...)`.

Exclus de J1 : TTC/OTC, CFF/CFF2, variations, styles synthétiques, COLR, SVG,
glyphes bitmap, fontes système, shaping (composition contextuelle), layout
(mise en page), hinting (ajustement aux pixels), rasterization (rastérisation),
moteurs de fontes natifs et handles de fonte plateforme.
