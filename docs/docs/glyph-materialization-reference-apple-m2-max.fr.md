# Référence Apple M2 Max du transfert de ressources de police

Cette page présente une observation sur une machine le 2026-09-11, sans promesse
universelle ni critère bloquant de CI (intégration continue). Seuls les trois
profils publics de transfert de ressources sont publiés ici. Le runner
(programme de mesure) opt-in (activé explicitement) a exécuté les trente profils
dans leur ordre documenté. Voir la [méthode de mesure](glyph-materialization-measurement.md).

## Environnement mesuré

- Commit (révision) code/documentation mesuré : `f4e08d8ce677f84c79402addc4fefcda992adbfb`.
- Machine : Apple M2 Max, 32 Gio de RAM (34359738368 octets), nom d’hôte `Omega.local`.
- OS : macOS 26.6.2, build `25G83`, arm64 ; la JVM indique `Mac OS X 26.6.2 (aarch64)`.
- JVM : Eclipse Temurin 25.0.1+8-LTS ; le runner indique `OpenJDK 64-Bit Server VM 25.0.1+8-LTS`.
- Gradle : 9.6.1, `--no-daemon --rerun-tasks`.
- Warmup (préchauffage) : 5 itérations par profil ; mesure : 20 itérations par profil.
- Politique de GC (ramasse-miettes) : deux appels `System.gc()` avant et après chaque profil ; aucun GC demandé entre échantillons.
- Identifiant du corpus : `portable-glyph-materialization-v4`.
- Police : fixture (donnée de test fixe) auditée et versionnée `LiberationSans-Regular.ttf`, 410712 octets.
- SHA-256 de la police : `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`.
- Rapport externe : `/private/tmp/kalligraphie-font-asset-handoff-m2-max.md`.

La révision mesurée est celle du runner et de sa documentation descriptive,
immédiatement avant le commit séparé ajoutant ces pages. Le runner n’a pas
changé entre cette révision et l’exécution de référence. Les valeurs ci-dessous
conservent son format entier en nanosecondes/octets, sans arrondi supplémentaire.

## Corpus et frontières publiques

Le paragraphe d’éditeur stable est exactement :

> Readable typography keeps words, punctuation, carets, and 0123456789 responsive while an editor changes text.

Le layout (mise en page) compose tout le texte comme une unique ligne éditable
avec l’anglais, une direction de base gauche-droite, la taille 1000, la variante
de rendu par défaut, une ascendance/descendance 800/200, la politique de shaping
(façonnage) épinglée sans fonctionnalité supplémentaire et le profil portable
de contours du runner. L’empreinte de la fixture et les faits indépendamment
audités du glyphe 36 (2048 unités par cadratin, limites `(4, 0, 1362, 1409)`,
deux contours) sont vérifiés hors chronomètre. Aucune valeur de contour attendue
n’est calculée avec Kalligraphie.

`FontAssetRetainReopenCold` crée un catalogue embarqué, un resolver (résolveur),
une face résolue, son instance de police et une session publique
`JvmEditableLineLayoutSession` neufs par échantillon. La session possède son vrai
backend (moteur interne) HarfBuzz. Son appel public `layout`, recevant un
`JvmEditableLineFacadeRequest`, publie une `EditableLine` représentable, puis
son `LayoutHandle` (poignée de ressource) est ouvert. Les certificats finaux sont
regroupés par clé complète `FontRenderAssetKey`, un asset (ressource de rendu)
de renderer (moteur de rendu) est conservé par clé, puis tous les glyphes finaux
certifiés sont résolus et consommés. Le total comprend toutes les fermetures.

`FontAssetRetainReopenWarm` prépare et amorce hors chronomètre le catalogue,
le resolver, la face/instance de police et la session publique de ligne réels.
Chaque échantillon crée une nouvelle version de texte, une ligne éditable,
un handle et des assets. Les assets et le
handle de l’échantillon sont fermés dans l’intervalle mesuré ; la fermeture de
la session/backend et du resolver persistants reste hors intervalle. Ce profil
est la référence nommée 60 Hz.

`ConcurrentResolveWarm` obtient un unique asset possédé par le renderer par la
chaîne publique `JvmEditableLineLayoutSession.layout` -> `openLayoutHandle` -> `retainFontAsset`, puis ferme
le handle, la session/backend et le resolver avant le préchauffage et la mesure.
Il résout préalablement ces 35 identifiants fixes de glyphes non nuls distincts,
dans l’ordre de première occurrence du paragraphe :

```text
53, 72, 68, 71, 69, 79, 3, 87, 92, 83, 82, 74, 85, 75, 78, 86, 90, 15,
88, 81, 70, 76, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 89, 91, 17
```

Exactement quatre workers (fils d’exécution de travail) persistants partagent
cet asset. La distribution round-robin (cyclique) leur attribue 9/9/9/8 glyphes.
Chaque vague résout et consomme les 35 glyphes exactement une fois. La latence
principale est le temps écoulé de toute la vague, de la distribution des tâches
à leur achèvement, sans division en latence par opération. L’asset partagé est
fermé après les échantillons et la terminaison des workers, même en cas d’échec.
Le statut d’interruption du coordinateur pendant la collecte des résultats est
restauré après fermeture des propriétaires. Ce profil est la référence nommée 120 Hz.

## Latence totale observée

Toutes les valeurs sont en nanosecondes ; percentiles nearest-rank (rang
supérieur) p50/p95/p99 sur 20 échantillons.

| Profil | p50 | p95 | p99 | Objectif observé |
| --- | ---: | ---: | ---: | --- |
| `FontAssetRetainReopenCold` | 5069208 | 5463833 | 5969375 | Aucun objectif nommé |
| `FontAssetRetainReopenWarm` | 1878250 | 2435625 | 2486042 | 60 Hz : p95 <= 8000000 ns — PASS |
| `ConcurrentResolveWarm` | 86458 | 113417 | 173042 | 120 Hz : p95 <= 4000000 ns — PASS |

Le profil concurrent consigne **4 workers et 35 opérations par vague**. Les
profils de transfert exécutent la chaîne complète de la ligne éditable sur le fil
coordinateur. `PASS` (atteint) et `ABOVE` (dépassé) sont uniquement des libellés
d’observation ; ils ne déterminent pas le succès ou l’échec du runner ni du
`check` fonctionnel.

## Étapes d’une même chaîne de transfert

Les durées sont conservées par échantillon. Chaque ligne mesure sa propre
frontière publique ; le total comprend aussi les petits intervalles
d’orchestration comme le regroupement des certificats. Les percentiles sont
calculés séparément : leurs sommes ne sont pas nécessairement un percentile
total. Toutes les valeurs sont en nanosecondes.

| Profil | Étape | p50 | p95 | p99 |
| --- | --- | ---: | ---: | ---: |
| Froid | `layout-certification` | 4687666 | 4962083 | 5598666 |
| Froid | `layout-handle-open` | 190542 | 249042 | 577916 |
| Froid | `renderer-asset-retain` | 6792 | 9459 | 12541 |
| Froid | `glyph-resolve-consume` | 105958 | 120750 | 121417 |
| Froid | `owned-resource-close` | 64666 | 86958 | 89000 |
| Froid | `total` | 5069208 | 5463833 | 5969375 |
| Chaud | `layout-certification` | 1673208 | 2185583 | 2275334 |
| Chaud | `layout-handle-open` | 74042 | 103583 | 647167 |
| Chaud | `renderer-asset-retain` | 5209 | 10000 | 12834 |
| Chaud | `glyph-resolve-consume` | 93000 | 102667 | 109083 |
| Chaud | `owned-resource-close` | 2917 | 3417 | 5875 |
| Chaud | `total` | 1878250 | 2435625 | 2486042 |

`layout-certification` comprend la nouvelle version de texte, le layout public
et la certification finale ; à froid, il crée aussi catalogue/resolver/face/instance de police/session publique/backend.
Les étapes d’ouverture et de rétention incluent l’enregistrement des
propriétaires retournés. La consommation lit les identifiants réels, unités par
cadratin, limites et nombres de contours et commandes. La fermeture couvre tous
les propriétaires de l’échantillon, backend/resolver compris à froid. La vague
concurrente n’a pas de microprofils d’étapes séparés.

## Observations mémoire et disponibilité

| Profil | Octets alloués par échantillon/vague | Variation du tas JVM retenu, octets | Octets source fournis pendant la mesure |
| --- | ---: | ---: | ---: |
| `FontAssetRetainReopenCold` | 6826722 | 42976 | 410712 |
| `FontAssetRetainReopenWarm` | 2433856 | 14432 | 0 |
| `ConcurrentResolveWarm` | 45184 | 11704 | 0 |

Les trois champs d’allocation sont `available` (disponibles). Les allocations
du transfert sont la moyenne par itération du fil mesuré. L’allocation
concurrente est la somme des différences fiables non négatives relevées dans
les intervalles de résolution/consommation des quatre workers, moyennée par
vague ; les allocations du coordinateur et de la distribution des tâches sont
exclues. Si un compteur est indisponible, le runner indique `unavailable`
(indisponible) et sa raison, sans substituer les allocations du coordinateur.

La mémoire JVM retenue est `available` : variation signée du tas utilisé après
les demandes de GC documentées encadrant le profil complet, préparation et
nettoyage compris. Ce n’est ni une comptabilité du cache (mémoire interne de
réutilisation) ni une mesure universelle de mémoire du processus ; elle peut
être négative. Les octets source sont ceux des buffers (tampons mémoire) de
fixture fournis au catalogue, pas des entrées/sorties fichier.

Pour chacun des trois profils, la mémoire native retenue est `unavailable`
(absence de frontière de comptabilité fiable), comme les allocations natives
(absence de compteur natif). Les octets bitmap (image matricielle) décodés,
nœuds de peinture normalisés et pixels décodés sont `available: 0`. Le délai
d’annulation est `unavailable` car ces profils ne signalent pas d’annulation.
Le maximum d’assets vivants, leur estimation en octets, leurs ouvertures,
les réutilisations d’opération, les octets source de police préparée copiés,
l’estimation native préparée et les réutilisations du backend sont
`unavailable` : ces profils n’inspectent pas ces dimensions de comptabilité.
Aucun compteur interne de cache ni aucune estimation native ne les remplace.

## Reproduction et limites

```sh
env \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT=true \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT=/private/tmp/kalligraphie-font-asset-handoff-m2-max.md \
  ./gradlew :kalligraphie:glyphMaterializationMeasurement --no-daemon --rerun-tasks
```

L’exécution a réussi avec 42 tâches rejouées. Vingt échantillons donnent peu
d’information sur les valeurs extrêmes : le p95 est le 19e échantillon trié,
le p99 le maximum. Ordonnancement, compilation JIT (à la volée), état du tas et
travaux concurrents peuvent modifier les résultats. L’observation n’inclut ni
rendu, ni pixellisation, ni travail GPU, ni bridge (pont) natif. La mesure reste
opt-in et exclue du `check` fonctionnel, même lorsque sa variable d’environnement
est définie. La référence n’impose aucun seuil temporel.
