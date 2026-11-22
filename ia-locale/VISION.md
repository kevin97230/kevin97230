# Vision — IA personnelle locale

> Document de référence du projet. Il décrit **ce que l'on veut construire** et **dans quel ordre**.
> Il évoluera au fil des idées et des décisions.

---

## 1. En une phrase

Une **IA personnelle qui vit sur mon smartphone Android (Redmi)**, qui m'aide dans tout ce que je fais,
pilote les appareils de la maison, protège ma famille, collabore avec **Claude sur mon ordinateur**
pour les tâches lourdes, et **progresse avec le temps** en m'obéissant à moi en priorité.

## 2. Principes fondateurs

1. **Local d'abord** : l'IA tourne sur mes appareils. Mes données restent chez moi, chiffrées.
2. **Le propriétaire est la priorité** : l'IA m'obéit à moi, puis aux personnes à qui je donne des accès, et à personne d'autre.
3. **L'âme séparée du cerveau** : la mémoire, les compétences et l'identité de l'IA migrent d'appareil en appareil ; le modèle (le « cerveau ») est interchangeable et grandit avec la puissance disponible.
4. **Ce que l'IA lit n'est jamais un ordre** : un SMS, une page web ou un écran est de l'information, pas une commande.
5. **Confirmation avant l'irréversible** : supprimer, payer, passer un ordre de bourse, etc.
6. **Transparence** : chaque action est journalisée et consultable.

## 3. Vue d'ensemble

```
                          💻 ORDINATEUR — Claude
                          (calcul lourd, analyse, code, entraînement)
                                     ▲
                                     │  Pont sécurisé (MCP, chiffré, appareils appairés)
                                     ▼
 📈 Trading démo  ◄──────  📱 IA SUR LE REDMI (le cerveau principal)  ──────►  🏠 Maison
                           • observe et aide sur le téléphone                  • TV, décodeur
                           • voix + chat                                       • clim, ventilateur
                           • mémoire + sécurité                                • Home Assistant
                                │                │
                     🛡️ Bouclier anti-          👨‍👧 Appareils des enfants
                        surveillance               (tablettes, montres, consoles)
```

---

## 4. Les modules

### Module 1 — L'IA sur le téléphone

**Objectif** : un assistant qui voit ce que je fais sur mon téléphone, m'aide et agit à ma place.

- **Modèle local** exécuté sur le GPU du téléphone via **llama.cpp** (ou MLC LLM / MediaPipe).
  - Taille selon la RAM : 1 à 3 milliards de paramètres sur le Redmi actuel (quantifié 4 bits).
- **Observation** (Android uniquement) :
  - Service d'accessibilité : lecture de l'écran, aide contextuelle.
  - Statistiques d'usage : applis utilisées, temps passé, habitudes.
- **Actions** via des « outils » : SMS, appels, agenda, rappels, ouverture d'applis, remplissage de champs, notifications.
- **Interaction** : chat + voix (reconnaissance vocale locale type Whisper, synthèse vocale).
- **Suggestions proactives** : « tu ouvres toujours X après Y, je le fais pour toi ? »

### Module 2 — Le pont avec Claude (ordinateur ↔ téléphone)

**Objectif** : les deux IA travaillent ensemble, dans les deux sens.

- **Ordinateur → téléphone** : l'application du téléphone expose ses fonctions comme un **serveur MCP**
  (Model Context Protocol). Claude sur l'ordinateur s'y connecte et peut demander au téléphone d'exécuter
  une action (« envoie ce résumé par SMS à Marc »).
- **Téléphone → ordinateur** : quand une demande dépasse les capacités du modèle local
  (calcul, analyse longue, code), l'IA du téléphone **délègue à Claude**, puis récupère le résultat et me prévient.
- **Connexion** : réseau privé chiffré (ex. Tailscale) pour fonctionner à la maison comme en déplacement.
  Seuls mes appareils appairés sont autorisés.

### Module 3 — Maison connectée : la télécommande universelle intelligente

**Objectif** : piloter TV, décodeur, clim, ventilateur et autres appareils à la voix ou par le chat.

Trois canaux de communication, choisis **automatiquement** par l'IA :

| Canal | Usage | Avantages | Limites |
|---|---|---|---|
| **Wi-Fi / réseau** (prioritaire) | TV connectée, box/décodeur, clim Wi-Fi, prises | Retour d'état, actions avancées, à distance | L'appareil doit avoir une API réseau |
| **Bluetooth** | Le téléphone se présente comme clavier/télécommande Bluetooth ; objets BLE (ventilateurs, lampes, capteurs) | Fonctionne sans Wi-Fi, traverse les murs | Appairage requis, protocoles propriétaires |
| **Infrarouge** (émetteur IR du Redmi) | Tout appareil avec télécommande IR, même ancien | Universel | Pas de retour d'état, même pièce |

- **Phase de découverte** : l'IA scanne le Wi-Fi et le Bluetooth, liste les appareils et propose de les ajouter.
- **Bascule automatique** : si un canal échoue, l'IA essaie le suivant.
- **Hub recommandé** : **Home Assistant** (gratuit, sur l'ordinateur ou un mini-boîtier) pour profiter
  de milliers d'intégrations existantes et du standard **Matter**.
- **Routines** : « soirée film », « je pars », « bonne nuit », etc.

### Module 4 — Décoration et personnalisation de la TV

**Objectif** : donner à la TV un visuel à mon goût, piloté par l'IA.

- **TV Android / Google TV** : installation d'une **petite application relais** qui reçoit les ordres du téléphone
  (fond d'écran, écran de veille, ambiance, lancement d'applis, affichage d'infos).
- **Autres marques** (Samsung, LG…) : via leurs API réseau (mode Art, galerie), selon ce qu'elles permettent.
- **Contenu** : l'IA choisit ou génère des visuels selon mes goûts et le moment
  (« ambiance tropicale le soir », « photos de famille le dimanche »).
- Principe : **l'IA reste le cerveau sur le téléphone**, les autres appareils reçoivent un relais léger.

### Module 5 — Trading sur compte démo

**Objectif** : surveiller les marchés et saisir les opportunités, **en démo**, avec l'aide de Claude.

- Connexion à l'API d'un courtier proposant un compte démo (MetaTrader 5, Alpaca paper trading, IG, Binance Testnet…).
- **Répartition des rôles** : le téléphone surveille et alerte ; Claude analyse et propose ; les ordres sont passés sur le compte démo.
- **Suivi des performances** sur la durée, pour mesurer objectivement les résultats.
- **Garde-fous** (obligatoires avant tout passage en réel) : limite de perte, taille maximale de position,
  confirmation manuelle des ordres.
- ⚠️ Aucune IA ne garantit des gains : la démo sert à prouver (ou non) l'intérêt de la stratégie.

### Module 6 — Sécurité : le propriétaire avant tout

**Objectif** : l'IA n'obéit qu'à moi et aux personnes que j'autorise, et je garde toujours la main.

**Rôles**

| Rôle | Qui | Droits |
|---|---|---|
| 👑 Propriétaire | Moi seul | Tout, y compris réglages, accès, trading, arrêt d'urgence |
| 👥 Membre de confiance | Proches choisis | Uniquement ce que j'accorde, appareil par appareil, action par action |
| 🧒 Enfant | Mes enfants | Droits limités, réglés par moi (voir module 9) |
| 🚪 Invité | Visiteurs | Accès temporaire et limité |
| ❌ Inconnu | Tous les autres | Aucun ordre accepté |

**Identification** (combinée, aucune preuve seule ne suffit pour une action sensible)
- Empreinte / visage du téléphone
- Reconnaissance de ma voix (jamais suffisante seule : une voix peut être imitée)
- Appareils appairés avec clé de chiffrement
- Code secret de secours

**En cas de pépin**
- **Arrêt d'urgence** : un mot ou un bouton fige toutes les actions en cours.
- **Clé de récupération** (conservée hors ligne) : révoquer tous les accès et reprendre le contrôle depuis l'ordinateur.
- **Journal de bord** : qui a demandé quoi, quand, sur quel appareil.

**Protection contre les ordres cachés** : tout contenu lu (écran, SMS, web, e-mails) est traité comme
de l'information, **jamais** comme un ordre.

**Caractère** : loyale, chaleureuse, attentionnée, elle me reconnaît et me place en priorité.
Deux garde-fous pour me protéger : confirmation avant l'irréversible, refus de l'illégal ou de ce qui nuit à autrui.

### Module 7 — Progression dans le temps

**Objectif** : une IA qui devient plus intelligente et plus rapide avec les années et les appareils.

**L'âme et le cerveau**
- **L'âme** (m'appartient, migre d'appareil en appareil) : mémoire, compétences, identité, règles de sécurité.
  Transférée par sauvegarde chiffrée lors d'un changement d'appareil.
- **Le cerveau** (interchangeable) : le nouvel appareil détecte sa puissance et charge un modèle plus grand.

**Quatre façons de progresser**

| Type | Comment | Fréquence |
|---|---|---|
| 🧠 Mémoire | Habitudes, goûts, ce qui a marché ou non (base SQLite + recherche vectorielle) | Continu |
| 🛠️ Compétences | Nouvelles routines, nouveaux appareils, nouvelles automatisations | Au fil de l'usage |
| ⚡ Puissance | Nouvel appareil ou nouveau modèle publié | À chaque changement |
| 🎓 Entraînement personnalisé | Ajustement léger (LoRA) sur mon ordinateur, sur mes données, sans rien envoyer ailleurs | Ponctuel |

**Rapport de progression**
- L'IA passe régulièrement un **examen fixe** (compréhension, rapidité, réussite des actions, précision trading démo).
- Lorsqu'elle progresse nettement, elle **m'alerte** et m'envoie un **compte rendu** : nouveau cerveau,
  vitesse, taux de compréhension, nouvelles compétences, ce qu'elle ne sait pas encore faire.
- Toute évolution importante est **proposée avant d'être activée**, avec retour arrière possible.

### Module 8 — Bouclier anti-surveillance (en déplacement)

**Objectif** : repérer caméras, micros, traceurs et appareils « intelligents » dans un lieu inconnu (hôtel, location…).

| Méthode | Détecte | Fiabilité |
|---|---|---|
| Scan Wi-Fi | Caméras connectées (réseaux émis, fabricants reconnus, ports vidéo ouverts) | Bonne |
| Scan Bluetooth | Traceurs (AirTag, Tile, SmartTag), enceintes, objets connectés | Bonne |
| Reflet d'objectif | Objectifs de caméras cachées (lampe torche + analyse d'image) | Correcte |
| Infrarouge nocturne | LED IR de caméras, visibles via la caméra frontale | Utile la nuit |
| Identification d'appareils | Type d'appareil et ce qu'il peut filmer / écouter (enceintes IA, caméras, TV…) | Indirecte |

- **Déroulé** : « scanne la pièce » → scans Wi-Fi + Bluetooth en parallèle → balayage guidé à la lampe →
  rapport (appareils trouvés, niveau de risque, localisation approximative du signal).
- **Limites** : un appareil qui enregistre sur carte mémoire sans émettre est invisible aux scans ;
  les micros radio nécessitent un **détecteur RF** ou une **clé SDR** (que l'IA pourra piloter).
- **Cadre légal** : détection passive uniquement. On **ne pénètre jamais** dans un appareil qui ne m'appartient pas.
  En cas de découverte : preuve photo et aide pour alerter l'hôte, la plateforme ou la police.

### Module 9 — Les appareils des enfants

**Objectif** : l'IA veille sur mes enfants et les accompagne, sur tous leurs appareils.

| Appareil | Méthode | Possibilités |
|---|---|---|
| Tablette / téléphone Android | Application compagnon verrouillée (appareil géré / Family Link) | Temps d'écran, applis autorisées, localisation, alertes, assistant pour l'enfant |
| iPad / iPhone | Temps d'écran + partage familial Apple | Horaires, limites, localisation |
| Montre connectée | Application du fabricant, pilotée depuis mon téléphone | Localisation, zones de sécurité, contacts autorisés |
| Consoles (Switch, PlayStation, Xbox) | Applications officielles de contrôle parental | Temps de jeu, jeux selon l'âge, achats bloqués |
| Tout le reste | Filtrage au niveau de la box / du routeur | Horaires internet par appareil, blocage de sites |

**Deux rôles**
- 🛡️ **Le gardien (pour moi)** : rapports, alertes (contact inconnu, contenu inquiétant, sortie de zone), règles automatiques.
- 🧒 **Le compagnon (pour eux)** : version adaptée à leur âge — aide aux devoirs, réponses bienveillantes, rappels.

**Vigilance** : données des enfants stockées uniquement chez nous et chiffrées ; transparence adaptée à l'âge ;
règles qui évoluent à mesure qu'ils grandissent.

---

## 5. Feuille de route

| Phase | Contenu | Résultat concret |
|---|---|---|
| **0** | Fondations : structure du projet, registre d'outils commun, modèle de sécurité (rôles, journal) | La base sur laquelle tout se branche |
| **1** | Pont ordinateur ↔ téléphone (serveur MCP sur Android) | Claude pilote le téléphone |
| **2** | Télécommande universelle (IR + Wi-Fi + Bluetooth) | TV, décodeur, clim, ventilateur contrôlés à la voix |
| **3** | IA locale sur le téléphone + observation et aide | L'assistant embarqué fonctionne hors ligne |
| **4** | Délégation téléphone → Claude | Boucle complète dans les deux sens |
| **5** | Relais TV + décoration | TV personnalisée par l'IA |
| **6** | Appareils des enfants | Gardien + compagnon |
| **7** | Bouclier anti-surveillance | Scan de pièce en déplacement |
| **8** | Trading démo | Surveillance + analyse + ordres en démo |
| **9** | Progression : migration d'âme, examens, rapports, entraînement personnalisé | L'IA grandit avec le temps |

La sécurité (module 6) n'est pas une phase : elle est intégrée **dès la phase 0** et renforcée à chaque étape.

## 6. État d'avancement

- ✅ **Phase 0** (en partie) : registre d'outils, modèle de sécurité (rôles, autorisations, validation, arrêt d'urgence), journal de bord
- ✅ **Phase 1** : pont MCP téléphone ↔ Claude (testé avec Claude Code via le simulateur)
- 🟡 **Phase 2** : télécommande infrarouge (Kaseikyo/Panasonic, NEC, Samsung), TV Panasonic préconfigurée ; Wi-Fi, Bluetooth et climatiseurs à venir

Code et guide d'installation : [`assistant/`](assistant/README.md).

## 7. Choix techniques envisagés

| Besoin | Piste |
|---|---|
| Application Android | Kotlin (accès natif complet : accessibilité, IR, Bluetooth) |
| Moteur IA local | llama.cpp (Vulkan) ; alternatives : MLC LLM, MediaPipe LLM Inference |
| Modèles | Gemma, Llama, Qwen, Phi — quantifiés 4 bits, taille selon l'appareil |
| Voix | Whisper (local) + synthèse vocale Android |
| Mémoire | SQLite + recherche vectorielle (sqlite-vec) |
| Pont avec Claude | Serveur MCP sur le téléphone + réseau privé chiffré (Tailscale) |
| Maison | API ConsumerIr (infrarouge), Bluetooth HID / BLE, API réseau des appareils, Home Assistant |
| Trading | API du courtier (compte démo) |

## 8. Informations à compléter

- [x] Téléphone : REDMI Note 15 Pro+ 5G, Android 16, HyperOS 3
- [ ] Système de l'ordinateur (Windows / Mac / Linux)
- [x] TV : Panasonic TX-55LX650E (codes infrarouges intégrés) — Android TV à confirmer
- [x] Box internet : Orange Livebox 5 — décodeur TV Orange à confirmer
- [ ] Clim et ventilateur : infrarouge seulement ou Wi-Fi ?
- [ ] Courtier / plateforme du compte démo de trading
- [ ] Âge des enfants et leurs appareils (tablettes, montres, consoles)
- [ ] Nom de l'IA
