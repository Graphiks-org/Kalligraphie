# Rapatriement de la gestion des polices — spécification de migration

**Statut :** proposé pour revue  
**Date :** 24 août 2026  
**Dépôt source :** ygdrasil-io/kanvas, branche master, commit 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b

## Objectif

Introduire dans Kalligraphie une gestion de polices OpenType à partir des
sources Kotlin de kanvas/font, avec une première livraison utilisable et
testée sur JVM/Desktop. L’architecture doit préparer une extension Android et
iOS sans faire croire que le portage natif est déjà disponible.

La cible de licence du projet est MIT. L’auteur a confirmé disposer des droits
sur les sources Kanvas à rapatrier et autorise leur réutilisation sous cette
licence.

## Décisions de périmètre

- La migration copie des sources dans ce dépôt ; elle ne dépend ni d’un Git
  submodule ni d’un artefact Kanvas publié.
- La première version est **JVM-first**. Elle n’est pas une promesse de prise
  en charge Android/iOS au premier jalon.
- Les API communes KMP ne dépendent pas directement d’implémentations JVM.
  Les chemins, les scans du système et les flux de fichiers restent derrière
  une frontière de plateforme.
- Tous les composants de font/ sont importés, à l’exception de gpu-api. Le
  renommage est fait pendant l’import, de façon mécanique et vérifiée ; aucune
  compatibilité binaire avec les paquets org.graphiks.kanvas n’est recherchée.
- Les paquets publics cibles partagent la racine io.ygdrasil.kalligraphie,
  avec trois branches : font (core, SFNT, scaler, COLR et façade), glyph
  (représentations et masques), et text (shaping et paragraphes). Cette
  topologie préserve la séparation amont entre font.glyph et glyph et évite
  leurs collisions de noms.
- Le sous-arbre de fixtures requis par les tests amont est importé sous
  font/fixtures, avec son manifeste de provenance et ses licences. Les autres
  rapports Kanvas restent exclus. Toute fixture tierce ajoutée ultérieurement
  doit garder son fichier de licence et une provenance vérifiable (par exemple
  SIL OFL ou Unicode).

## Inventaire amont

Le répertoire font/ amont contient un module agrégateur et huit composants :

| Composant Kanvas | Rôle | Dépendances font principales | Jalon |
| --- | --- | --- | --- |
| core | identité, provenance, diagnostics, sources de données | — | 1 |
| sfnt | lecture des tables SFNT/OpenType | core | 1 |
| colr | tables COLR/CPAL de polices couleur | core, sfnt | 3 |
| scaler | métriques, contours et variations | core, sfnt, colr | 2 |
| text | texte simple, glyphes et frontières de shaping | core, sfnt, scaler | 2 |
| gpu-api | contrats de données entre glyphes et renderer GPU, sans backend GPU | core | différé |
| glyph | représentations de glyphes et routes de rendu | core, text, scaler, colr, gpu-api | 3, sans l’adaptateur GPU |
| font | façade agrégatrice Gradle | tous les précédents sauf COLR direct | 3 |

| Paquet amont | Paquet Kalligraphie |
| --- | --- |
| org.graphiks.kanvas.font... | io.ygdrasil.kalligraphie.font... |
| org.graphiks.kanvas.glyph... | io.ygdrasil.kalligraphie.glyph... |
| org.graphiks.kanvas.text... | io.ygdrasil.kalligraphie.text... |

Les sources de font/src conservent ainsi leur branche
io.ygdrasil.kalligraphie.font.glyph, distincte de
io.ygdrasil.kalligraphie.glyph provenant du sous-module font/glyph.

Les sources sont aujourd’hui compilées comme bibliothèques Kotlin/JVM et
emploient notamment java.nio.file dans la gestion de fichiers. Ce fait interdit
un déplacement brut dans commonMain.

## Architecture cible

    shared/commonMain
        │  API applicative KMP, sans dépendance Java
        ▼
    :font-core (modèle commun) ── :font-core-jvm (fichiers, système, buffers Java)
        ▼
    :font-sfnt
        ▼
    :font-scaler ── :font-text
        ▼
    :font-colr / :font-glyph                     [jalon ultérieur]

    :font-gpu-contracts                           [seulement avec un renderer GPU]
        ▲                              ▲
        │                              │
    adaptateur de glyphes        :renderer-gpu

Le premier jalon peut livrer les modules JVM sous des noms Gradle cohérents
avec ce graphe, même lorsque la partie commune reste volontairement minimale.
Les abstractions KMP seront introduites uniquement autour des capacités qui
ont réellement besoin de plateformes distinctes : lecture de bytes, accès aux
assets et inventaire de polices système.

Le module amont gpu-api ne constitue pas une abstraction de backend GPU. Il
porte des valeurs de transfert et de diagnostic consommées à la fois par
font/glyph et gpu-renderer (atlas, payloads, ordre de rendu et refus). Il ne
doit donc pas être fondu dans font-glyph. Pour Kalligraphie, il est renommé
font-gpu-contracts et reste exclu du premier import. Il ne sera créé que si un
renderer GPU est effectivement ajouté ; ce choix évite de rapatrier une API
inutilisée tout en préservant une frontière saine entre producteur et
consommateur quand ce renderer existera.

## Flux fonctionnel initial

1. L’application fournit des bytes de police ou une ressource JVM.
2. font-core attache identité, hash SHA-256 et provenance.
3. font-sfnt valide le conteneur et expose les tables OpenType nécessaires.
4. font-scaler obtient métriques et contours pour une face sélectionnée.
5. font-text transforme une chaîne simple en glyph IDs et positions.
6. Le rendu consomme les contours ; les scripts complexes, le fallback et les
   emoji non couverts retournent un diagnostic stable au lieu d’un résultat
   silencieusement approximatif.

## Jalons d’exécution

### Jalon 0 — préparation et traçabilité

- Créer un manifeste d’import contenant l’URL, le commit amont, les chemins
  repris et leurs SHA-256.
- Déplacer reports/font/fixtures vers font/fixtures, y compris les fontes de
  test, les valeurs attendues, le manifeste de provenance et les licences.
- Établir une liste d’exclusions explicites : rapports hors fixtures, sources
  de test non requises et tout le module font/gpu-api.
- Ajouter l’historique de migration dans CHANGELOG.md et documenter le
  changement de paquet.

### Jalon 1 — lecture OpenType JVM

- Ajouter les modules Gradle :font-core et :font-sfnt, puis importer les tests
  unitaires correspondants.
- Séparer les méthodes liées à Path, Files et au système hôte dans la source
  JVM ; conserver les modèles et validations déterministes purs.
- Valider une police TTF et une police OTF à partir de bytes en mémoire.

**Sortie :** une face est identifiée de façon déterministe et ses tables SFNT
essentielles sont lisibles sur JVM.

### Jalon 2 — contours et texte simple JVM

- Ajouter :font-scaler puis :font-text en conservant l’ordre de dépendances
  amont.
- Exposer un chemin explicite pour le texte simple : Unicode de base → glyph
  ID → métriques/position → contour.
- Refuser avec des codes testés les cas qui exigent shaping complexe, fallback
  inter-familles ou un comportement propre au système.

**Sortie :** un texte latin simple produit des glyphes positionnés et des
contours déterministes pour le rendu Desktop/JVM.

### Jalon 3 — polices couleur et préparation d’un renderer

- Ajouter :font-colr et les représentations de glyphes sans dépendance GPU
  seulement après la stabilisation des contrats des deux premiers jalons.
- Commencer par COLR/CPAL v0 ; toute prise en charge de COLR v1 doit être
  démontrée par des tests de graphes de peintures et des fixtures licenciées.
- Laisser les adaptateurs provenant de font/glyph vers GPU hors du module de
  glyphes. Si Kalligraphie adopte un renderer GPU, créer alors
  :font-gpu-contracts comme contrat commun entre cet adaptateur et le renderer.
- Ne pas introduire de dépendance du noyau des polices vers Compose ou une API
  GPU particulière.

**Sortie :** les polices couleur disposent de contrats de glyphes indépendants
du renderer. Une intégration GPU reste explicitement différée et ne devient
possible qu’au travers de contrats séparés.

### Jalon 4 — portage KMP progressif

- Extraire des interfaces expect/actual seulement après identification des
  appels JVM réellement bloquants.
- Implémenter Android avec les assets/bytes appropriés ; iOS nécessite une
  implémentation distincte, des tests natifs et ne peut pas réutiliser
  java.nio.
- Ne faire dépendre shared/commonMain que du noyau devenu réellement portable.

**Sortie :** les plateformes deviennent disponibles l’une après l’autre avec
la même API fonctionnelle et des limites documentées.

## Validation et critères d’acceptation

Chaque jalon doit vérifier :

- compilation des modules concernés et de :shared:jvmTest ;
- reprise et adaptation des tests amont pertinents ;
- hash, provenance et erreurs de parsing déterministes ;
- absence de dépendance Java dans commonMain ;
- revue manuelle de toute nouvelle fixture et de sa licence ;
- rtk git diff --check avant intégration.

Le jalon 2 n’est accepté que si un exemple de texte simple rend les mêmes
glyphes et contours sur des exécutions JVM répétées. Les comportements non
pris en charge doivent être explicitement testés et documentés.

## Hors périmètre initial

- HarfBuzz, FreeType, JNI et les shapers système ;
- shaping complexe, ligatures contextuelles complètes et bidi avancé ;
- fallback automatique multi-polices ;
- emoji bitmap/SVG et couverture Unicode complète ;
- atlas de glyphes, SDF/LCD et promesse de rendu GPU cross-platform ;
- migration de rapports et d’infrastructure de preuve Kanvas non nécessaires
  au fonctionnement de Kalligraphie.

## Risques et réponses

| Risque | Réponse |
| --- | --- |
| Copier du JVM dans commonMain casse Android/iOS | Conserver une première implémentation JVM et isoler les adaptateurs de plateforme. |
| Import massif difficile à déboguer | Découper par jalons et faire passer les tests à chaque module. |
| Fixtures de fonte non redistribuables | Importer uniquement font/fixtures, avec provenance et licence ; contrôler chaque ajout ultérieur. |
| Contrats de rendu couplés à Kanvas | Importer d’abord les modèles et le parsing ; intégrer le renderer au jalon 3. |
| Promesse de texte trop large | Définir le texte simple et des diagnostics de refus stables dès le jalon 2. |
