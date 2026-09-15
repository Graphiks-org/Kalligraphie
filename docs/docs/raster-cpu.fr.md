# Raster CPU

Kalligraphie fournit un rastériseur (moteur de remplissage de pixels) CPU
déterministe réservé aux tests et aux démonstrations. Il n'entre jamais dans le
graphe de dépendances du consommateur `org.graphiks:kalligraphie`.

Le module rastérise les trois routes de représentation portables certifiées :

- les contours (`GlyphOutlineIR`) en images de couverture (coverage) sur huit bits ;
- les graphes de peinture (`GlyphPaintIR`) en images RGBA non prémultipliées avec
  composition `SOURCE_OVER` ;
- les bitmaps (images matricielles) embarqués (`BitmapGlyphIR`, `ALPHA_8`) avec une encre explicite.

Chaque opération applique des limites déclarées avant toute allocation et
retourne soit une image immuable, soit un refus typé (`InvalidRequest`,
`LimitExceeded`). Les courbes sont aplaties avec une tolérance fixe, la
couverture utilise seize sous-échantillons fixes et la composition emploie
l'arithmétique entière : des entrées identiques produisent des octets identiques
sur toutes les plateformes.

La démonstration opt-in (à activation explicite) écrit des images PGM et PPM accompagnées d'un manifeste
(fichier d'inventaire). La tâche dédiée s'exécute toujours lorsqu'elle est
invoquée explicitement :

```bash
env KALLIGRAPHIE_RASTER_DUMPS=true \
    KALLIGRAPHIE_RASTER_DUMPS_OUTPUT=/tmp/kalligraphie-raster \
    ./gradlew :kalligraphie:raster-cpu:rasterDumps
```

`KALLIGRAPHIE_RASTER_DUMPS_OUTPUT` doit être un chemin absolu hors du dépôt. Le
runner (programme d'exécution) est exclu de `check` ; il ne contient aucun seuil de performance.
