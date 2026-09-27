# Mesure du layout (mise en page) incrémental

Kalligraphie mesure le layout incrémental dans le module non publié
`:kalligraphie:bench`. Ce n’est ni un test fonctionnel de latence, ni un résultat
de benchmark (mesure comparative) publié. Les profils exécutent la vraie
`JvmIncrementalParagraphLayoutSession`, l’analyse Unicode, HarfBuzz embarqué et
les fixtures (données de test fixes) de fontes DejaVu et Amiri versionnées dans
le dépôt.

Ils appartiennent à la moitié « paragraphe » du module : ils exigent la capacité
`END_TO_END_LAYOUT`, déclarée absente sur Android et iOS. Ils sont donc réservés
à la JVM, et le module les liste comme différés sur les plateformes qui ne
peuvent pas les servir au lieu de publier silencieusement moins de profils.

L’intervalle chronométré commence immédiatement avant
`session.layout(...)`. Les snapshots (instantanés immuables), catalogues de
fontes, deltas et requêtes sont construits avant le départ du chronomètre. Il se
termine seulement après vérification de la couverture complète demandée et
consommation des lignes, runs (séquences typographiques), glyphes, carets
(repères d’insertion), diagnostics et état du suffixe. Le scheduling
(ordonnancement applicatif) et le renderer (moteur de rendu) sont exclus. Pour
`Cancellation`, la latence du profil couvre toujours l’entrée de l’appel
jusqu’au retour d’annulation typé, tandis que le compteur distinct de délai
d’annulation couvre le premier signal d’annulation jusqu’à ce retour.

## Profils

- `InteractiveEdit` alterne un remplacement préparé entre `cafe` et un emoji
  dans une seule session, en réutilisant le dernier état publié.
- `ViewportLayout` alterne deux plages de viewport (zone visible) avec un
  overscan (marge de lignes complètes hors zone visible) de deux lignes, sur le
  même snapshot immuable.
- `Cancellation` demande tout le corpus et déclenche l’annulation coopérative
  après un nombre fixe de vérifications du token (jeton d’annulation). Seul un
  résultat annulé typé est accepté ; une couverture partielle ne compte jamais
  comme succès rapide.

Chaque profil ouvre une nouvelle session. Avant de pouvoir annoncer un état de
cache (mémoire interne de réutilisation) chaud, chaque profil, y compris
`Cancellation`, termine dans cette session une mise en page complète,
non chronométrée et non annulée. Le warmup (préchauffage) configuré précède
ensuite les itérations mesurées. Deux demandes `System.gc()` sont effectuées
avant et après chaque profil, jamais entre les itérations mesurées.

## Exécution reproductible

Le module mesure avec kotlinx-benchmark (JMH sur la JVM) : warmup, itérations,
durée d’itération d’une seconde et format JSON du rapport viennent de sa
configuration de `benchmark`, pas de variables d’environnement. Une seule
commande mesure tous les profils que la plateforme sert — ces trois profils font
partie des trente-sept que la JVM exécute. Résultats et compteurs sont écrits
sous le répertoire `build` du module, que git ignore :

```bash
./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark
```

`./gradlew :kalligraphie:bench:measurementReport` joint ensuite cette exécution
aux autres plateformes dans `build/bench/report-jvm.md` et un comparatif. Aucune
des deux tâches n’appartient à `check` : une mesure se demande, elle ne se
planifie pas, et aucun test fonctionnel n’affirme une durée.

## Champs du rapport

Le rapport Markdown enregistre, pour l’exécution entière :

- le commit mesuré, la machine, l’OS, le runtime et la politique de cache/GC
  (ramasse-miettes) ;
- l’identité et la description du corpus, ainsi que le SHA-256 (empreinte
  cryptographique) de chaque fixture réellement lue ;
- et, pour chaque profil : sa route, sa frontière chronométrée, son état de
  cache, ses nombres de warmup et d’itérations mesurées, les percentiles
  nearest-rank (rang supérieur) p50, p95 et p99 en nanosecondes, les compteurs
  prouvant ce que l’opération chronométrée a consommé, et des valeurs de mémoire
  étiquetées `measured`, `estimated` ou `unavailable`.

Les compteurs incluent les scalaires, lignes et paragraphes rematérialisés par
les profils réussis, ainsi que le délai d’annulation maximal observé par
`Cancellation` entre le premier signal intervenant pendant l’opération et le
retour d’annulation typé — distinct de la latence totale de ce profil. C’est le
maximum qui est publié, pas la dernière lecture : sur des millions d’opérations,
la dernière peut observer l’annulation sous la résolution de l’horloge, et un
zéro serait refusé par le contrat de profil comme une absence d’opération.

Utilisez ce modèle lors de la copie d’un résultat dans une description de
revue :

```text
Commit / machine / OS / runtime / politique de cache :
Corpus / SHA-256 :
Route du profil / frontière chronométrée / état du cache :
Warmup / itérations :
p50 / p95 / p99 :
Compteurs consommés :
Valeurs de mémoire (measured / estimated / unavailable) :
Limites :
```

Les champs d’allocation et de mémoire retenue décrivent ce programme de mesure
réduit ; ils ne constituent pas une comptabilité universelle de la mémoire JVM
ou native. La variation du tas peut être négative après la politique GC
documentée. Les octets natifs retenus sont publiés `unavailable` avec leur
raison, jamais estimés.

## Interprétation et limites

La mesure publie des observations, sans seuil de réussite ou d’échec. Un
résultat ne soutient une revendication de performance que si son environnement
complet et sa politique de reference profile (profil de référence) sont
identifiés séparément. Les contrôles fonctionnels Gradle ne vérifient jamais
le temps écoulé.

La session actuelle ne réutilise un checkpoint (point de reprise) que s’il
provient de sa publication courante. La sélection exacte d’une ligne peut
examiner de manière conservative (prudente) jusqu’à la prochaine frontière
UAX #14 obligatoire, ou jusqu’à la fin du document lorsqu’il n’en reste aucune.
Les diagnostics de rematérialisation et la latence peuvent donc croître pour un
long paragraphe avec soft wrap (retour à la ligne automatique) ; la correction
reste prioritaire.
