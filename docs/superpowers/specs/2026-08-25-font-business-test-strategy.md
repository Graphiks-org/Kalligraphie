# Stratégie de tests métier — Kalligraphie Font

## Décision

Les tests définissent le contrat de Kalligraphie. Kanvas reste une source
historique et un outil de diagnostic ponctuel ; il n'est ni un oracle de
compatibilité ni une suite de tests à recopier.

Un test doit décrire une règle observable par un consommateur de la
bibliothèque : résultat de parsing, métrique, segmentation, plan de glyphes,
erreur ou refus. Il ne doit pas démontrer que le code a été importé, qu'une
classe existe, qu'un package porte un nom donné, qu'une tâche Gradle est
enregistrée, ou qu'une fonction interne appelle une autre fonction interne.

## Règles anti-tautologie

- Les assertions passent exclusivement par une API publique ou par une erreur
  publique documentée. Un test de module peut cibler son API publique, mais
  jamais inspecter ses structures privées.
- La valeur attendue vient d'un calcul humain simple, d'une spécification
  OpenType/Unicode, ou d'un vecteur officiel versionné. Elle ne peut pas être
  générée par le système sous test.
- Un golden doit déclarer son oracle dans un manifeste : norme et version,
  calcul manuel, ou vecteur officiel. Un golden généré par Kalligraphie est
  interdit comme preuve de justesse de Kalligraphie.
- Les fontes de fixtures sont des données métier stables, jamais des fontes
  système découvertes pendant le test. Chaque fixture a un hash, une licence
  et une origine.
- Les mocks ne sont admis que pour une frontière externe future. Ils ne
  remplacent ni un parseur, ni une table OpenType, ni un moteur de shaping.
- Les tests de présence de source, de chemin, de package, de configuration
  Gradle et de compilation ne font pas partie de la stratégie produit.

## Pyramide de tests

### Contrats métier par domaine

Ces tests sont courts et déterministes. Ils couvrent les résultats produits,
les refus et les invariants qui représentent le produit.

| Domaine | Contrats initiaux |
| --- | --- |
| Source / identité | mêmes bytes et même provenance donnent la même identité ; une entrée invalide est refusée explicitement |
| SFNT | une fonte valide expose les tables attendues ; une directory tronquée, un offset hors fichier ou une longueur incohérente est refusé sans crash |
| COLR / CPAL | une fonte couleur annonce ses palettes et sa route couleur ; une fonte sans tables couleur suit la route outline documentée |
| Scaler | les bounds et advances sont cohérents avec l'échelle ; les glyphes absents ou contours impossibles ont un résultat documenté |
| Texte | les cas de référence Latin, graphemes, bidi et scripts produisent les segments documentés par Kalligraphie |
| Glyphes / atlas | mêmes entrées donnent le même plan ; les régions sont dans l'atlas et non recouvrantes ; une capacité insuffisante est refusée ou signalée |

### Tests d'intégration JVM

Un petit nombre de scénarios traverse les APIs publiques : bytes de fonte →
source → lecture SFNT → métriques/scaling ou segmentation → plan de glyphes.
Ces scénarios vérifient les contrats entre modules sans connaître leur
structure interne. Ils n'appellent aucun GPU, JNI, backend natif ou fonte
système.

### Conformance choisie

Les standards sont l'oracle externe : OpenType pour les tables supportées et
Unicode pour les algorithmes déclarés supportés. La CI de PR utilise un corpus
court, lisible et représentatif. Un corpus plus complet et versionné est
réservé à la CI nightly. Une divergence avec Kanvas déclenche une décision
produit, non un échec automatique.

### Robustesse

Les parseurs et planificateurs reçoivent un corpus d'entrées invalides :
troncatures, offsets/longueurs incohérents, dimensions négatives ou trop
grandes, et buffers de taille incompatible. Le contrat est toujours un résultat
ou une erreur contrôlée, jamais une sortie hors bornes, un overflow ou une
allocation non bornée. Des mutations déterministes de petits fichiers SFNT
complètent ce corpus en nightly.

## Fixtures et oracles

Le corpus initial se limite à :

- une TTF libre, simple et stable ;
- une petite fonte COLR/CPAL libre ;
- quelques fragments SFNT minimaux, valides et invalides ;
- des vecteurs Unicode minimaux sélectionnés ;
- des plans d'atlas calculables manuellement.

Chaque élément est déclaré dans un manifeste avec version, hash, licence,
origine et règles métier qu'il exerce. Les fichiers de référence officiels
Unicode sont introduits seulement lorsque le périmètre pris en charge les
requiert ; leur version devient alors partie du contrat de release.

## CI et politique d'acceptation

| Niveau | Exécution | But |
| --- | --- | --- |
| Pull request | contrats des modules touchés, intégration JVM courte, corpus invalide court | retour rapide et régression métier immédiate |
| Nightly | corpus Unicode/OpenType étendu, mutations déterministes et scénarios cross-module | conformance et robustesse approfondies |
| Release | nightly verte, hashes/licences des fixtures vérifiés et contrat public documenté | livrable reproductible |

La couverture de lignes peut être publiée comme indicateur, mais ne bloque pas
les merges au départ. Les gates portent sur les scénarios métier critiques :
parsing sûr, déterminisme, absence de dépendance système/GPU et parcours JVM
public.

## Premier incrément

1. Introduire le manifeste de fixtures et deux fontes libres minimales.
2. Ajouter les contrats SFNT : fonte valide, table attendue, entrée tronquée,
   offset et longueur invalides.
3. Ajouter un scénario JVM public `FontSource → SFNT`.
4. Ajouter les contrats d'atlas : déterminisme, régions dans les bornes,
   non-chevauchement et refus quand l'atlas est plein.
5. Étendre ensuite au texte/Unicode, puis à COLR et au scaler, uniquement avec
   des attentes établies indépendamment de l'implémentation.

## Hors périmètre initial

- compatibilité bit-à-bit avec Kanvas ;
- import intégral de ses fixtures ou de ses tests ;
- tests de structure, de packages ou de build ;
- GPU, backend natif, JNI et fontes système ;
- objectif de couverture chiffré bloquant.
