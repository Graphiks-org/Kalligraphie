# Rasterisation CPU

Kalligraphie fournit un rastériseur (moteur de remplissage de pixels) CPU
déterministe réservé aux tests et aux démonstrations. Il n'entre jamais dans le
graphe de dépendances du consommateur `org.graphiks:kalligraphie`.

Le module rastérise les trois routes de représentation portables certifiées :

- les contours (`GlyphOutlineIR`) en images de couverture (coverage) sur huit bits ;
- les graphes de peinture (`GlyphPaintIR`) en images RGBA non prémultipliées avec
  composition `SOURCE_OVER` ;
- les bitmaps (images matricielles) embarqués (`BitmapGlyphIR`) en pixels
  `ALPHA_8` teintés par l'encre explicite, ou en pixels `RGBA_8888` droits
  (alpha non prémultiplié) copiés tels quels, l'encre étant ignorée.

Chaque opération applique des limites déclarées avant toute allocation et
retourne soit une image immuable, soit un refus typé (`InvalidRequest`,
`LimitExceeded`). Les courbes sont aplaties avec une tolérance fixe, la
couverture utilise seize sous-échantillons fixes et la composition emploie
l'arithmétique entière : des entrées identiques produisent des octets identiques
sur toutes les plateformes.

Les empreintes de conformité qui scellent ces octets résident dans le module
bout-en-bout (`:kalligraphie:e2e`), pas ici : son manifeste golden enregistre un
SHA-256 pour les glyphes isolés (contour, peinture, bitmap), pour les planches
d'alphabets latin, grec, cyrillique, arabe et devanagari, et pour de vraies lignes
de texte composées par la façade de paragraphe — dont une ligne mixte
multi-scripts résolue par repli (fallback) entre trois polices.

Ce module héberge aussi la démonstration opt-in (à activation explicite), qui
écrit une image PGM ou PPM par scène golden. Les planches et lignes composées
sont retournées verticalement pour la lisibilité ; les dumps de référence
conservent l'orientation source du rastériseur ; le strike EBDT conserve son
orientation image. La tâche dédiée s'exécute toujours lorsqu'elle est invoquée
explicitement :

```bash
env KALLIGRAPHIE_E2E_DUMPS_OUTPUT=/tmp/kalligraphie-e2e \
    ./gradlew :kalligraphie:e2e:e2eGoldenDumps
```

La tâche active elle-même le runner ; sans `KALLIGRAPHIE_E2E_DUMPS_OUTPUT`,
elle échoue plutôt que d'écrire à l'intérieur du dépôt.

`KALLIGRAPHIE_E2E_DUMPS_OUTPUT` doit être un chemin absolu hors du dépôt. Le
runner (programme d'exécution) est exclu de `check` ; il ne contient aucun
seuil de performance. La régénération des empreintes commitées est une tâche
opt-in distincte : `./gradlew :kalligraphie:e2e:updateE2eGolden`.

Les ressources du logo du dépôt sont produites par le même module sous forme de
deux artefacts distincts. Le badge compose un carré arrondi plein avec le `K`
Amiri évidé par-dessus, cadré sur un canevas transparent de 512 × 512 pour que la
marque s'insère sans recadrage dans les avatars et les favicons. Le mot-symbole
compose le mot « Kalligraphie » en Great Vibes mis en forme par le backend
HarfBuzz épinglé, sur une bande transparente. Les deux sont retournés
verticalement vers l'orientation image, entourés d'une marge, puis rendus une
fois par encre de thème :

```bash
./gradlew :kalligraphie:raster-cpu:renderLogo
```

La tâche écrit les quatre PNG transparents et un manifeste sous `docs/assets/`, à
l'intérieur du dépôt, sans variable d'environnement, et elle est exclue de
`check`. `KalligraphieLogoConformanceTest` scelle les pixels rendus et les
fichiers commités : une modification du code sans régénération, ou un fichier
édité, fait échouer `check`.

