# Référence de matérialisation des glyphes — Apple M2 Max, 10 septembre 2026

Cette page publie une observation reproductible produite par le
[programme de mesure de matérialisation](glyph-materialization-measurement.fr.md).
Il s’agit d’un point de comparaison nommé, pas d’un seuil de latence portable.
Un résultat obtenu sur une autre machine, un autre système, une autre JVM, un
autre état énergétique ou avec une charge concurrente ne constitue pas une
régression sans comparaison contrôlée.

## Environnement et protocole

- Date : 10 septembre 2026
- Révision : `7d3524957de23b431eeff4609d51546d4d0787f4`
- Machine : Mac Studio (`Mac14,13`), Apple M2 Max, 12 cœurs, 32 Go de mémoire
- Système : macOS 26.6.2 (`aarch64`)
- JVM : OpenJDK 64-Bit Server VM `25.0.1+8-LTS`
- Corpus : `portable-glyph-materialization-v3`, 23 profils et 115 glyphes
- Échantillons : 5 itérations de warmup (échauffement), puis 20 itérations
  mesurées par profil
- Politique de GC (ramasse-miettes) : deux demandes `System.gc()` avant et après
  chaque profil, sans demande de collecte entre les échantillons
- Percentiles : p50, p95 et p99 selon la méthode nearest-rank (rang supérieur)
- Allocations : octets alloués par le thread (fil d’exécution) mesuré à chaque
  itération
- Mémoire JVM conservée : variation signée du heap (tas) utilisé après les
  demandes de collecte documentées
- Empreintes SHA-256 des fontes :
  - `BungeeColor-Regular.ttf` : `cf21a786e54f43694f4edbb51a38f81331a4c3414217c524c8cb2d091aa7fd63`
  - `TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf` : `3321f267b8a242d96c0790ac31becc74b7f57656762037e16f465be1adcd84d2`
  - `ebdt_fmt1.ttf` : `e99cebed4d9421bc89964b9dc6a3bedfc6a286029d64336a07844708cce76274`
  - `LiberationSans-Regular.ttf` : `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`

Le nom d’hôte local est volontairement exclu, car il n’apporte rien à la
reproductibilité. La mémoire native conservée et les allocations natives sont
indisponibles pour tous les profils : les routes portables n’exposent aucune
frontière de comptabilité fiable.

L’exécution enregistrée utilise la commande documentée avec
`KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5`,
`KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20`, `--rerun-tasks` et
`--no-daemon`. La sortie a été écrite hors du dépôt, puis toutes les valeurs
ci-dessous ont été comparées aux 23 profils produits.

## Routes de représentations portables

Toutes les latences sont exprimées en nanosecondes ; les allocations et la
mémoire conservée sont exprimées en octets. Les identifiants de profil anglais
sont conservés, car ils correspondent exactement aux sorties du programme.

| Profil | p50 | p95 | p99 | Allocation/itération | Mémoire JVM conservée |
| --- | ---: | ---: | ---: | ---: | ---: |
| `ColrColdNormalization` | 1 321 500 | 2 374 334 | 2 588 042 | 933 618 | 53 480 |
| `ColrWarmResolution` | 12 833 | 19 625 | 20 208 | 3 272 | 10 632 |
| `SvgColdNormalization` | 617 958 | 1 142 250 | 1 411 375 | 224 705 | 61 848 |
| `SvgWarmResolution` | 8 041 | 9 459 | 10 250 | 3 624 | 4 472 |
| `BitmapColdDecode` | 172 833 | 348 500 | 364 583 | 73 464 | 19 208 |
| `BitmapWarmResolution` | 6 167 | 7 375 | 8 333 | 2 440 | 2 680 |
| `PaletteChange` | 432 542 | 610 709 | 909 166 | 278 411 | 156 160 |
| `CachePressureAndEviction` | 986 417 | 1 675 459 | 1 803 875 | 717 339 | 49 456 |
| `CooperativeCancellation` | 5 750 | 7 916 | 7 917 | 3 544 | 4 880 |

Les mentions cold et warm signifient respectivement parcours froid, depuis un
état neuf, et parcours chaud, après préparation du cache (mémoire interne de
réutilisation).

## Routes publiques du consommateur

| Profil | p50 | p95 | p99 | Allocation/itération | Mémoire JVM conservée |
| --- | ---: | ---: | ---: | ---: | ---: |
| `RenderableConsumerColdSingleFont` | 2 215 000 | 3 034 667 | 3 493 959 | 1 269 495 | 2 682 256 |
| `RenderableConsumerWarmSingleFont` | 841 542 | 1 194 000 | 1 366 708 | 551 677 | 7 248 |
| `RenderableConsumerColdMixedBidi` | 4 626 459 | 5 893 792 | 6 500 541 | 5 366 046 | 25 008 |
| `RenderableConsumerWarmMixedBidi` | 1 249 500 | 3 177 208 | 3 530 708 | 1 829 168 | 11 496 |

BiDi désigne ici un paragraphe bidirectionnel combinant plusieurs fontes.

## Étapes TrueType portables d’un éditeur

| Profil | p50 | p95 | p99 | Allocation/itération | Mémoire JVM conservée |
| --- | ---: | ---: | ---: | ---: | ---: |
| `TrueTypeColdPreparation` | 2 000 250 | 2 667 750 | 2 743 166 | 2 354 456 | 5 784 |
| `TrueTypeWarmPreparation` | 1 792 | 9 375 | 10 833 | 1 088 | 3 344 |
| `TrueTypeColdTextMapping` | 1 889 917 | 2 464 167 | 2 537 333 | 2 403 856 | 12 704 |
| `TrueTypeWarmTextMapping` | 31 792 | 38 208 | 50 208 | 31 112 | 3 344 |
| `TrueTypeColdMetrics` | 2 442 916 | 3 719 125 | 3 935 166 | 2 892 980 | 6 296 |
| `TrueTypeWarmMetrics` | 142 542 | 155 625 | 158 333 | 131 252 | 3 056 |
| `TrueTypeColdOutlines` | 2 437 917 | 3 184 000 | 3 246 959 | 3 150 658 | 5 264 |
| `TrueTypeWarmOutlines` | 21 000 | 31 417 | 33 458 | 33 712 | 3 488 |
| `TrueTypeColdDetach` | 1 884 417 | 2 592 708 | 2 645 584 | 2 719 380 | 4 384 |
| `TrueTypeWarmDetach` | 1 709 | 3 125 | 4 958 | 1 320 | 2 976 |

Le mapping désigne ici la correspondance entre le texte et les glyphes. Le
detach désigne le détachement d’une ressource de rendu de son propriétaire.

## Interprétation

Les écarts entre parcours froids et chauds sont cohérents avec les frontières
de préparation et de réutilisation définies par le protocole. La route
bidirectionnelle multi-fonte présente les latences et allocations les plus
élevées, tandis que les opérations TrueType préparées restent beaucoup moins
coûteuses que leurs équivalents froids. Ces observations décrivent uniquement
cet environnement. Un futur seuil nécessitera des mesures répétées sur une
machine de référence contrôlée et un rattachement explicite à un profil de
performance stable.
