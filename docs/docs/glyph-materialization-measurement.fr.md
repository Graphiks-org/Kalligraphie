# Mesure de matérialisation des glyphes

Kalligraphie mesure la matérialisation portable des glyphes dans le module non
publié `:kalligraphie:bench`. Ce n’est ni un test fonctionnel de latence, ni un
résultat de benchmark (mesure comparative) publié. Le module exécute les fixtures
(données de test fixes) COLR/CPAL, SVG-in-OpenType, EBDT format 1 et Liberation
Sans TrueType auditées et versionnées, à travers les parcours publics catalogue,
resolver (résolveur), instance, asset (ressource de rendu) et `resolveGlyph(...)`
`.

Le module enregistre trente-sept profils au total ; cette page couvre les trente
qui lui appartiennent. Dix-neuf d’entre eux sont du travail portable sur les
glyphes — les neuf profils de représentation et les dix étapes portables TrueType
— et s’exécutent sur toutes les plateformes. Les onze autres composent le texte à
travers la façade de paragraphe, qui exige la capacité `END_TO_END_LAYOUT` :
toutes les plateformes déclarent cette capacité depuis que les façades et
l’analyse Unicode portable sont passées dans `commonMain`, donc ces onze profils
s’exécutent aussi sur Android et sur le simulateur iOS. Un profil du module ne
voyage pas — `ConcurrentResolveWarm` exige un instrument de harnais, quatre
threads OS persistants avec un compteur d’allocation par thread — et le rapport
iOS le nomme comme différé au lieu de mesurer une boucle séquentielle sous son
nom. Les profils de ligne éditable et de layout incrémental sont documentés dans
leurs propres pages.

Le module enregistre les trente profils de cette page dans cet ordre :

- normalisation COLR v0 / CPAL v0 froide et chaude ; *(portable)*
- normalisation SVG-in-OpenType froide et chaude ; *(portable)*
- décodage bitmap (image matricielle) EBLC v2 / EBDT v2 format 1 froid et chaud ; *(portable)*
- sélection de palette CPAL 0 vers palette 1 ; *(portable)*
- pression par clé de profil SVG, éviction LRU (least recently used, moins
  récemment utilisé) puis nouvelle résolution ; *(portable)*
- annulation coopérative pendant une matérialisation COLR réelle à deux couches ; *(portable)*
- parcours consommateur public `RENDERABLE` froid et chaud avec un glyphe latin
  Bungee Color ;
- parcours consommateur public `RENDERABLE` froid et chaud avec un paragraphe
  BiDi (bidirectionnel) mêlant Bungee Color latin et le fallback (police de
  repli) hébreu Liberation Sans ;
- sessions incrémentales réutilisables froides et chaudes pour les mêmes
  paragraphes mono-police et BiDi multi-police ;
- étapes portables TrueType froides et chaudes de préparation, correspondance
  texte-glyphe, métriques, contours et détachement sur un paragraphe d’éditeur
  Liberation Sans stable ; *(portable)*
- `FontAssetRetainReopenCold`, `FontAssetRetainReopenWarm`, puis
  `ConcurrentResolveWarm` sur ce même paragraphe ; le dernier s’exécute sur la JVM
  et sur Android, là où l’instrument de workers existe.

Pour les six profils directs historiques, un échantillon froid commence avant
la création du catalogue embarqué et se termine après consommation de la
représentation immuable retournée. Leur échantillon chaud crée et alimente le
catalogue, le resolver, l’instance et l’asset avant le chronomètre ; il mesure
seulement `resolveGlyph(...)` et la consommation du résultat. La fermeture de
l’asset et du resolver est exclue uniquement de ces deux intervalles directs.
Le profil de palette commence avant l’acquisition de l’asset palette 1 après une
amorce palette 0. Le profil de pression comprend l’amorce, cinq clés SVG
certifiées distinctes et la résolution finale. Le profil d’annulation mesure
l’entrée de l’appel jusqu’au retour d’annulation typé et, séparément, le premier
signal d’annulation intervenant pendant l’opération.

Les profils consommateur froids incluent la création du catalogue et du
resolver, puis s’arrêtent lorsque la façade publique de paragraphe a produit et
consommé un layout (mise en page) dont tous les glyphes finaux portent un
certificat de matérialisation. Les profils consommateur chauds gardent catalogue
et resolver ouverts, amorcent le cache (mémoire interne de réutilisation) de
représentations portables par un premier layout hors mesure, puis chronomètrent
la même frontière de façade publique. La façade JVM ouvre et ferme
volontairement son backend (moteur interne) de shaping
(façonnage) documenté à chaque appel : ces profils chauds mesurent donc la
réutilisation du cache d’assets, jamais une réutilisation cachée du backend.

Chaque échantillon consommateur rapporte aussi le maximum d’assets possédés par
l’opération simultanément vivants, leur borne conservatrice en octets, les
ouvertures d’asset distinctes et les preuves de glyphes finaux réutilisées après
une matérialisation antérieure dans la même opération. Ces valeurs sont des
mesures de scénario reconstruites depuis les certificats immuables et les
estimations du provider (fournisseur). Elles ne constituent ni une contrainte
de temps, ni la preuve d’un algorithme particulier de cache ou de pool (réserve
réutilisable).

`FontMaterializationCachePolicy` applique des budgets indépendants par face et par catalogue pour les octets retenus, les pixels décodés et les dimensions natives. Le runner (programme de mesure) portable actuel n’expose ni les cumuls retenus par ce coordinateur, ni ses admissions, ni ses nombres d’évictions. Ses durées et observations du tas mémoire ne prouvent pas ces plafonds. Les charges natives restent nulles ; les ressources natives et la comptabilité partagée au niveau provider/engine (fournisseur/moteur) restent hors de ce périmètre de mesure.

## Sessions HarfBuzz réutilisables

`SessionColdSingleFont`, `SessionWarmSingleFont`, `SessionColdMixedBidi` et
`SessionWarmMixedBidi` utilisent `JvmIncrementalParagraphLayoutSession`. Les
deux côtés amorcent les assets du catalogue et du resolver hors chronomètre.
Un échantillon froid ouvre sa session dans l’intervalle mesuré ; un échantillon
chaud conserve une session et son backend HarfBuzz, amorcés par un layout hors
mesure. Chaque échantillon fournit une nouvelle version de texte, compose le
paragraphe entier et consomme des glyphes certifiés. Les fermetures de session
et de resolver sont exclues de ces intervalles.

Les champs de session rapportent les octets source copiés dans les buffers
natifs retenus pendant l’échantillon, l’estimation HarfBuzz retenue en fin
d’échantillon et la réutilisation d’un backend existant (0 froid, 1 chaud,
déterminée par le cycle de vie du runner). Le compte froid provient des octets
inactifs de la session : ces petites fixtures tiennent dans la politique par
défaut sans éviction. Les échantillons chauds ne demandent aucune nouvelle copie.
Ce champ est distinct des octets source fournis au catalogue.

`PreparedFontCachePolicy` borne les entrées, les octets source, les octets
natifs estimés et leur somme, fontes actives et inactives comprises. L’admission
est réservée sous verrou avant l’allocation native ; seules les fontes inactives
sont évincées. Une admission impossible retourne `FontError.ResourceLimitExceeded`
sans layout partiel. Cette politique est indépendante du pool d’assets de rendu
et du cache de layout incrémental. `preparedFontCacheUsage` fournit un instantané
immuable lisible avant composition et après fermeture. Le backend fermé libère
immédiatement les fontes inactives, puis les actives à leur dernière restitution
de lease (emprunt de ressource), sans réouverture.

L’estimateur `harfbuzz-14.3.0-4x-source-plus-256k-v1` compte 256 Kio plus quatre
fois la longueur source pour les objets HarfBuzz, accélérateurs et caches retenus ;
le buffer source est compté séparément. Cette estimation prudente et versionnée
est une charge de politique, pas un compteur d’allocation instrumenté ni une borne
prouvée pour toute fonte. Les buffers de shaping temporaires, copies JVM, métadonnées
de l’allocateur, bibliothèque partagée et RSS (mémoire résidente du processus)
en sont exclus. Les octets réellement alloués et comptes d’allocations natifs
restent `unavailable`. Latences, allocations du thread et variations du tas sont
des mesures observées ; l’estimation native ne mesure jamais la mémoire totale
du processus.

## Étapes portables TrueType

Les dix profils TrueType portables supplémentaires utilisent Liberation Sans
Regular et ce paragraphe exact : « Readable typography keeps words,
punctuation, carets, and 0123456789 responsive while an editor changes text. »
Ses scalaires Unicode sont calculés une fois avant les opérations warm (chaudes)
chronométrées. Une mesure cold (froide) repart au contraire de l’état neuf
précisé par sa frontière. Les cinq paires mesurent :

- la préparation : la mesure froide couvre la capture du catalogue embarqué,
  la résolution de la face et la création d’instance depuis un nouveau
  catalogue ; la mesure chaude répète résolution et création depuis un unique
  catalogue déjà capturé ;
- le mapping (correspondance) texte-glyphe : la mesure froide crée une nouvelle
  instance avant de résoudre tout le paragraphe ; la mesure chaude résout la
  même séquence sur une instance préparée et consomme chaque identifiant de
  glyphe retourné ;
- les métriques : la mesure froide crée l’instance, effectue le mapping, puis
  lit l’avance et les limites de chaque glyphe ; la mesure chaude lit les mêmes
  champs sur une séquence de glyphes déjà résolue ;
- les contours : la mesure froide crée un resolver et un asset attaché avant de
  résoudre chaque glyphe non nul distinct ; la mesure chaude réutilise un asset
  amorcé hors chronomètre et consomme l’identifiant, les unités par cadratin, les
  limites, le nombre de contours et le nombre de commandes de chaque contour
  produit ;
- le détachement : la mesure froide crée puis détache un asset, ferme son
  propriétaire attaché et résout ensuite le glyphe 36 au moyen du handle
  (poignée de ressource) détaché ; la mesure chaude conserve un propriétaire
  attaché amorcé, répète des cycles indépendants de détachement, résolution et
  fermeture du handle détaché, puis ferme le propriétaire après les échantillons.

Chaque resolver, asset attaché et asset détaché possédé est fermé dans un
chemin `finally` (garanti même en cas d’échec). Les profils froids incluent la
préparation nommée par leur frontière ; les profils chauds préparent ou amorcent
cet état hors chronomètre. Chaque étape rapporte des observations de latence
p50, p95 et p99 positives, l’état des allocations du thread (fil d’exécution)
mesuré, une
observation de la mémoire JVM retenue, les octets source et l’empreinte SHA-256
de Liberation Sans avec celles des autres fixtures. La mémoire native retenue
et les allocations natives restent explicitement `unavailable` (indisponibles),
car l’API portable n’expose aucune frontière de comptabilité fiable pour ces
valeurs.

## Transfert public des ressources de police

Les trois derniers profils utilisent le paragraphe stable ci-dessus et la fixture
Liberation Sans auditée. Hors chronomètre, le runner vérifie son empreinte et les
faits littéraux de l’audit indépendant du glyphe 36 : 2048 unités par cadratin,
limites `(4, 0, 1362, 1409)`, deux contours. Il vérifie aussi la séquence distincte
des glyphes finaux du paragraphe contre le corpus fixe ci-dessous.

`FontAssetRetainReopenCold` crée un catalogue embarqué, un resolver, une face
résolue, son instance de police et une session publique `JvmEditableLineLayoutSession`
neufs par échantillon. Cette session possède son vrai backend HarfBuzz. Son
appel public `layout`, recevant un `JvmEditableLineFacadeRequest`, compose le
texte stable comme une unique `EditableLine` représentable. Le parcours appelle
`openLayoutHandle`, regroupe tous les certificats finaux
par clé complète `FontRenderAssetKey`, conserve un asset de renderer (moteur de
rendu) par clé, puis résout et consomme tous les glyphes finaux certifiés, y
compris leurs répétitions. Le total comprend la fermeture des assets, du handle,
du backend et du resolver.

`FontAssetRetainReopenWarm` prépare hors chronomètre un catalogue, un resolver,
une face/instance de police et une session publique de ligne réutilisable, puis
amorce le parcours portable complet
avant le warmup (préchauffage). Chaque échantillon fournit une nouvelle version
de texte et crée une nouvelle ligne éditable, un handle et un ensemble d’assets. Leur
fermeture est mesurée ; celle de la session/backend et du resolver persistants
reste hors intervalle. Ce profil est l’observation nommée 60 Hz, avec un objectif
p95 <= 8 000 000 ns.

Les deux profils conservent les durées individuelles d’une seule chaîne et
publient les percentiles nearest-rank (rang supérieur) p50/p95/p99 :

| Étape | Travail chronométré |
| --- | --- |
| `layout-certification` | Nouvelle version de texte, layout public de ligne et certification finale ; à froid, création du catalogue, du resolver, de la face/instance de police et de la session publique/backend comprise |
| `layout-handle-open` | Appel public `openLayoutHandle` et enregistrement du propriétaire |
| `renderer-asset-retain` | Un appel public `retainFontAsset` par clé complète et enregistrement de chaque propriétaire |
| `glyph-resolve-consume` | Résolution de chaque glyphe final certifié et consommation des champs réels de sa représentation |
| `owned-resource-close` | Tous les propriétaires de l’échantillon, backend et resolver compris à froid |
| `total` | Échantillon complet, y compris les petits intervalles d’orchestration comme le regroupement des certificats |

Les percentiles de chaque étape sont calculés séparément ; leurs sommes ne sont
pas nécessairement un percentile total. Le nettoyage s’exécute même en cas
d’échec/annulation et continue après une erreur de fermeture. Aucune
instrumentation produit ni aucun compteur interne de cache n’est ajouté.

`ConcurrentResolveWarm` obtient un unique asset de renderer par cette même
chaîne publique, puis ferme handle, backend et resolver avant le préchauffage
ou la mesure. Il résout préalablement les 35 identifiants de glyphes non nuls
distincts suivants, dans l’ordre de première occurrence du paragraphe :

```text
53, 72, 68, 71, 69, 79, 3, 87, 92, 83, 82, 74, 85, 75, 78, 86, 90, 15,
88, 81, 70, 76, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 89, 91, 17
```

Quatre workers (fils d’exécution de travail) persistants partagent cet asset.
La distribution round-robin (cyclique) leur donne 9, 9, 9 et 8 glyphes. Un
échantillon est une vague concurrente qui résout et consomme chaque glyphe du
corpus exactement une fois. La latence principale est le temps écoulé de toute
la vague, de la distribution des tâches à leur achèvement, sans division par
le nombre d’opérations. Le rapport indique quatre workers et 35 opérations par
vague. Les allocations sont la somme des différences fiables non négatives
relevées dans les quatre intervalles de résolution/consommation, moyennée par
vague ; celles du coordinateur et de la distribution des tâches sont exclues.
Si un worker ne dispose pas d’un tel compteur, le champ indique `unavailable`
(indisponible) et sa raison. Les workers se terminent avant la fermeture de
l’asset partagé, même en cas d’échec. Le statut d’interruption du coordinateur
pendant la collecte des résultats est restauré après fermeture des propriétaires.
Ce profil est l’observation nommée 120 Hz,
avec un objectif p95 <= 4 000 000 ns.

Ces objectifs produisent uniquement les champs observés `PASS` (atteint) ou
`ABOVE` (dépassé), sans faire échouer le runner ni `check`. Cette évolution de
l’outil se vérifie par exécution de fumée et mesure réelle, sans test structurel
artificiel ni assertion temporelle. La [référence Apple M2 Max](glyph-materialization-reference-apple-m2-max.md)
est une observation sur une machine, pas une promesse universelle.

## Exécution reproductible

Le module mesure avec l’outil propre à chaque plateforme : kotlinx-benchmark
(JMH) sur la JVM, un binaire de simulateur lié sur iOS, et androidx.benchmark sur
un appareil ou émulateur Android. Warmup, itérations, durée d’itération d’une
seconde et format du rapport viennent de cette configuration, pas de variables
d’environnement. Résultats et compteurs sont écrits sous le répertoire `build` du
module, que git ignore :

```bash
./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark
./gradlew :kalligraphie:bench:iosSimulatorArm64Benchmark
./gradlew --no-parallel :kalligraphie:bench:mediumPhoneAndroidDeviceTest
./gradlew :kalligraphie:bench:measurementReport
```

Les trois premières tâches sont opt-in : aucune n’appartient à `check`, aucune ne
se planifie d’elle-même. La commande JVM mesure les trente-sept profils que le
module enregistre ; la commande Android mesure les mêmes trente-sept sur le
runtime de l’appareil ; la commande iOS en mesure trente-six, car le harnais du
simulateur n’a pas d’instrument de workers et déclare `ConcurrentResolveWarm`
différé. La dernière joint les exécutions disponibles dans
`build/bench/report-jvm.md`, `report-ios.md`, `report-android.md` et
`report-comparison.md`, et échoue en nommant le chemin manquant lorsqu’une entrée
est absente, plutôt que de publier un rapport auquel une plateforme manquerait en
silence. Elle refuse aussi un rapport qui mesure moins de profils que sa
plateforme n’en sert sans en nommer la raison.

Les deux objectifs de latence nommés ci-dessous restent des observations qu’un
opérateur lit dans le rapport ; ils ne peuvent faire échouer une exécution.

## Contenu du rapport et limites

Chaque rapport consigne le commit mesuré, la machine, l’OS, le runtime, la
politique de cache/GC, l’empreinte SHA-256 de chaque fixture réellement lue et,
par profil, la route exacte, la frontière chronométrée, l’état du cache, les
nombres de warmup et d’itérations mesurées, les percentiles de latence
nearest-rank (rang supérieur) et les compteurs prouvant ce que l’opération
chronométrée a consommé. Il inclut aussi les octets source fournis au catalogue
pendant l’intervalle, les octets et pixels bitmap décodés, ainsi que le nombre de
nœuds de peinture normalisés.

Chaque valeur de mémoire ou d’allocation porte son propre état — `measured`,
`estimated`, ou `unavailable` avec sa raison. Une estimation n’est jamais
présentée comme une mesure : la JVM publie la valeur de la sonde d’allocation par
thread comme mesurée, tandis que les routes portables n’exposent aucune frontière
fiable de comptabilité de mémoire ou d’allocations natives et les publient
`unavailable`. Un profil dont les compteurs sont absents ou vides est refusé,
car un programme de mesure ne peut pas distinguer une opération rapide d’une
opération qui n’a rien fait.

Les noms de compteurs diffèrent d’une plateforme à l’autre là où l’outillage
diffère, et le rapport le dit : la JVM publie l’allocation par opération dérivée
de la sonde, iOS et Android publient leurs propres valeurs lorsqu’un instrument
existe, et le benchmark d’instrumentation Android publie une médiane et un
compte d’allocations plutôt que des percentiles — le rapport Android garde cette
médiane étiquetée comme médiane et ses p95/p99 manquants étiquetés
`unavailable`, au lieu de promouvoir la médiane en percentile.

Les quatre compteurs d’assets d’opération — maximum d’assets de rendu vivants
simultanément, leur estimation conservatrice en octets, les ouvertures d’asset
distinctes et les preuves de glyphes finaux réutilisées depuis une
matérialisation antérieure dans la même opération — sont publiés pour les profils
consommateurs publics de paragraphe et de session. Les profils directs de glyphes
et les étapes TrueType portables n’exécutent pas de composition de paragraphe
bornée par une opération : ces compteurs n’apparaissent pas plutôt que
d’apparaître vides.

Les octets source sont la taille du buffer (tampon mémoire) de fixture donné au
catalogue portable ; ce ne sont pas des compteurs d’entrées/sorties fichier. Un
profil chaud rapporte zéro octet source car son catalogue est volontairement
ouvert avant la frontière chronométrée. Les valeurs d’allocation et de tas
décrivent ce programme de mesure, pas une comptabilité universelle du processus.

Le module n’impose aucun seuil de latence, aucun test fonctionnel n’affirme une
durée, et il n’ajoute ni renderer (moteur de rendu), ni rasterizer (moteur de
pixellisation), ni API GPU, ni bridge (pont) natif.

## Rétention partagée et propriété native

Le module Apple fournit une mesure exécutable séparée, passant par les vrais
consommateurs publics de contours, peinture, bitmaps et CoreText. Cette tâche
`JavaExec` reste hors `check` et requiert les mêmes versions macOS/JDK et droits
d'accès natifs que la route Apple. Activez-la explicitement et écrivez le rapport
hors du dépôt :

```bash
env KALLIGRAPHIE_SHARED_FONT_CACHE_MEASUREMENT=true \
  KALLIGRAPHIE_SHARED_FONT_CACHE_OUTPUT=/tmp/kalligraphie-shared-retention.txt \
  ./gradlew :kalligraphie:platform:apple:sharedFontCacheMeasurement --rerun-tasks
```

Le rapport consigne empreintes du corpus, tailles source, révision et état des
sources mesurées, OS, architecture, JVM et identité complète de route. L'enregistreur
interne borné est désactivé par défaut et attaché avant toute rétention ; ses cellules
préallouées archivent les comptes supprimés. Il rapporte budgets, charges courantes
et maxima au moment de chaque événement, pour les quatre dimensions et catégories
actives, réservées, en cours de libération et résiduelles, par domaine, capture et
face. Les changements de catégorie sont observés après comptabilité complète, et
les acquittements confirmés avant suppression des comptes. Les maxima de catégories
peuvent provenir d'instants différents : leur somme n'est pas un pic simultané. La
saturation de l'enregistreur invalide explicitement l'exhaustivité de la mesure.

Les profils exercent chaque dimension par domaine/capture/face, les budgets nuls,
les résultats individuellement trop lourds et des acquisitions concurrentes réelles
de contours/peinture/bitmaps/contextes natifs. Limites de contours, couleurs de palette,
pixels décodés exacts et avances natives indépendantes auditées valident le comportement.
Un profil d'octets natifs amorce 48 contextes de tailles distinctes de la fonte GDEF
de 1772 octets, ferme leurs propriétaires consommateurs, puis acquiert DejaVu de
757076 octets dans un domaine natif limité à 757076 octets. Le candidat tient seul,
mais nécessite l'abandon des 48 petites charges. Décisions, victimes et fallback
(retour sans rétention) enregistrés montrent le quota interne actuel de 32 victimes
et deux décisions. Amorçage froid, acquisition indexée chaude, fallback, drainage du
domaine et fermeture consommateur ont leurs propres latences observées et comptes
de visites d'index ; aucun seuil temporel ou budget d'image universel n'est promis.
Ce sont des observations uniques de scénario, sans préchauffage statistique.
L'amorçage froid chronomètre les 48 acquisitions publiques face/instance/ressource,
la validation des métriques natives et la fermeture consommateur ; capture du
catalogue et ouverture du résolveur précèdent cet intervalle. La mesure chaude
répète une acquisition amorcée, avec validation et fermeture. Le fallback acquiert
et valide le grand consommateur conservé ; sa fermeture finale est séparée. La
première initialisation du probe (sonde native) peut affecter l'observation froide.
L'allocation de l'enregistreur et des rapports reste hors de ces intervalles : c'est
la mémoire du programme de mesure, pas une charge de rétention du cache.

Des compteurs natifs opt-in (activés explicitement), limités au processus de mesure,
rapportent les références possédées créées avec succès de CFData, CGDataProvider,
CGFont et CTFont, les unités de libération API confirmées, les résultats incertains
et les octets de copie source CFData sous propriété explicite. Ils ne mesurent ni
`malloc`, ni les caches privés du système, ni la désallocation physique par l'OS.
À une frontière drainée sans réservation, libération en cours ou incertitude
résiduelle, la propriété native explicite restante après abandon des références du
cache appartient exclusivement aux consommateurs survivants. Leurs métriques restent
utilisables après fermeture du domaine. Un défaut de libération conserve toute la
charge résiduelle prudente et son incertitude ; il ne permet pas d'annoncer drainage
confirmé ou mémoire exclusivement consommateur.
