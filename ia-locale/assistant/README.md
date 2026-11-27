# Assistant — étape 1 : fondations, pont avec Claude, télécommande

Première brique du projet décrit dans [`../VISION.md`](../VISION.md).

## Ce que fait cette version

| Fonction | Détail |
|---|---|
| 🔐 **Sécurité** | Rôles (propriétaire, confiance, enfant, invité, inconnu), autorisations appareil par appareil, validation du propriétaire pour les actions critiques, accès temporaires, **arrêt d'urgence** (engageable à distance, levable **uniquement sur le téléphone**) |
| 📒 **Journal de bord** | Chaque action est enregistrée : qui, quoi, quand, résultat |
| 🌉 **Pont avec Claude** | Le téléphone expose ses fonctions à Claude via **MCP** (`http://<ip>:8765/mcp`), protégé par un jeton secret |
| 📡 **Télécommande infrarouge** | Votre **TV Panasonic** préconfigurée (41 touches, protocole **RC5** : la TX-55LX660E est fabriquée par Vestel). Protocoles **RC5**, **Kaseikyo** (Panasonic classiques), **NEC** et **Samsung** |
| 📺 **TV Android par le Wi-Fi** | Votre TV Panasonic (Android TV) pilotée **sans viser** : appairage unique avec un code affiché sur la TV, toutes les touches, ouverture directe de Netflix, YouTube, Prime Video |
| 📺 **Décodeur TV Orange** | Par le **Wi-Fi** (pas besoin de viser) : recherche automatique, touches, changement de chaîne par numéro, état allumé/veille |
| 🔔 **Notifications** | Claude peut vous envoyer un message sur le téléphone |
| 📱 **État du téléphone** | Batterie, modèle, réseau, infrarouge |

### 🎮 La télécommande universelle (ce que Claude utilise en priorité)

Claude n'a plus à choisir entre Wi-Fi et infrarouge : chaque ordre passe par le **Wi-Fi** si l'appareil
est configuré, et bascule tout seul sur l'**infrarouge** s'il ne répond pas (sans appui en double).

| Outil | Rôle |
|---|---|
| `maison_appareils` | À appeler en premier : appareils, par où ils répondent, état, touches, chaînes et scénarios |
| `touche` | Une touche de la TV ou du décodeur (`appareil` : tv / decodeur, `touche`, `repetitions`) |
| `allumer` / `eteindre` | La TV, le décodeur ou `tout`. En Wi-Fi, allume *ou* éteint vraiment (pas de bascule) ; le décodeur n'est touché que si c'est utile |
| `chaine` | Par `numero` ou par `nom` (TF1, France 5, M6…) |
| `chaine_enregistrer` | Apprend le numéro d'une chaîne pour l'appeler par son nom |
| `volume` | `plus` / `moins` (`crans`), `muet`, ou `regler` à une `valeur` précise |
| `source` | Entrée de la TV : `decodeur`, `hdmi1` à `hdmi4`, `tv` |
| `telecommande_regler` | Sur quelle entrée HDMI est branché le décodeur (1 par défaut) |
| `scenario_lancer` | `regarder_tele`, `mode_film`, `tout_eteindre`, ou un scénario créé |
| `scenario_creer` / `scenario_supprimer` | Claude crée vos propres scénarios (« soirée foot »…), retenus sur le téléphone |

Chaque étape d'un scénario repasse par la sécurité (autorisations, arrêt d'urgence) et s'inscrit au journal.

### Les outils détaillés (gardés en secours)

| Outil | Rôle |
|---|---|
| `ir_lister_appareils` | Liste les appareils et leurs touches |
| `ir_envoyer_touche` | Appuie sur une touche (`appareil`, `touche`, `repetitions`) |
| `ir_regler_volume` | Règle le volume à une valeur précise (`appareil`, `volume`, `descente` facultative) : descend à 0 puis remonte, ≈ un quart de seconde par cran |
| `tv_rechercher` | Trouve la TV Android sur le Wi-Fi |
| `tv_appairer` puis `tv_code` | Appairage unique : la TV affiche un code à 6 caractères |
| `tv_touche` | Touche de la TV par le Wi-Fi (`touche`, `repetitions`) |
| `tv_ouvrir_app` | Ouvre Netflix, YouTube, Prime Video, Disney+ |
| `decodeur_rechercher` | Trouve le décodeur Orange sur le Wi-Fi et retient son adresse |
| `decodeur_touche` | Appuie sur une touche du décodeur (`touche`, `repetitions`) |
| `decodeur_chaine` | Met une chaîne par son numéro (`numero`) |
| `decodeur_etat` | Décodeur allumé ou en veille, programme en cours |
| `telephone_notifier` | Affiche une notification sur le téléphone |
| `telephone_etat` | Donne l'état du téléphone |
| `systeme_journal` | Lit le journal de bord |
| `systeme_arret_urgence` | Fige toutes les actions |

## Organisation

```
assistant/
├── core/          Cœur de l'IA (Kotlin pur) : sécurité, outils, infrarouge, serveur MCP — testé
├── simulator/     Le même cœur sur ordinateur, pour essayer avec Claude sans téléphone
└── android-app/   L'application pour le REDMI Note 15 Pro+ (Android 16)
```

## Essai 1 — sur l'ordinateur, sans téléphone (5 minutes)

Prérequis : Java 17 ou plus récent (par exemple Eclipse Temurin 21) et Claude Code.

Sous **Windows** (PowerShell) :

```powershell
cd ia-locale\assistant
.\gradlew.bat :simulator:installDist
.\simulator\build\install\simulator\bin\simulator.bat
```

Sous Mac ou Linux : `./gradlew :simulator:installDist` puis `simulator/build/install/simulator/bin/simulator`.

Le simulateur affiche une commande `claude mcp add …`. Copiez-la dans un autre terminal, lancez `claude`,
puis demandez par exemple : *« Liste les appareils infrarouges et monte le volume de la TV du salon de 3 crans. »* ou *« Mets le volume de la TV à 17. »*
Le simulateur cherche aussi le décodeur Orange sur le réseau de l'ordinateur : si l'ordinateur est sur le Wi-Fi
de la Livebox, *« Trouve le décodeur et mets la chaîne 2 »* fonctionne déjà pour de vrai.
Les signaux infrarouges s'affichent dans la console du simulateur.

## Essai 2 — sur le téléphone

1. Installez **Android Studio** sur l'ordinateur, puis ouvrez le dossier `ia-locale/assistant`.
2. Sur le Redmi : *Paramètres → À propos du téléphone →* appuyez 7 fois sur **Version de l'OS** pour activer
   le mode développeur, puis *Paramètres supplémentaires → Options pour les développeurs →* activez
   **Débogage USB** et **Installer via USB** (spécifique à Xiaomi).
3. Branchez le téléphone en USB, choisissez la configuration **android-app** et cliquez sur ▶️.
4. Dans l'appli **Assistant** :
   - autorisez les notifications ;
   - testez les touches de la télécommande, téléphone pointé vers la TV ;
   - touchez **Démarrer le pont**, puis **Copier la commande pour Claude**, et collez-la dans un terminal de l'ordinateur.
5. Spécificités HyperOS, pour que le pont reste actif écran éteint : *Paramètres → Applis → Assistant →*
   **Démarrage automatique** activé, et **Économiseur de batterie → Aucune restriction**.

Le téléphone et l'ordinateur doivent être sur le même Wi-Fi. Hors de la maison, installez **Tailscale**
sur les deux. L'appli affiche alors aussi l'adresse Tailscale (elle commence par `100.`) : utilisez-la
à la place de l'adresse Wi-Fi dans la commande, et le trafic sera chiffré.

## Ajouter vos propres appareils

Créez un fichier `appareils.json` au format de
[`appareils_exemple.json`](core/src/main/resources/ia/assistant/core/infrared/appareils_exemple.json) et placez-le
dans le dossier de l'application (*Android Studio → Device Explorer → `/data/data/ia.assistant.android/files/`*).
Les codes de milliers d'appareils sont publiés dans la base libre **Flipper-IRDB** : les lignes `address:` et
`command:` de ses fichiers `.ir` se copient telles quelles (ex. `"80 02 20 00"`).

> La TV Panasonic TX-55LX660E est fabriquée par Vestel : elle répond au protocole RC5 (adresse 1), pas aux codes
> Panasonic habituels. Volume et muet ont été vérifiés sur la TV ; les autres codes viennent de télécommandes Vestel
> de Flipper-IRDB (Telefunken, JVC, Finlux). Si une touche ne réagit pas, signalez-le pour qu'on l'ajuste.
> Les climatiseurs utilisent des protocoles plus longs (l'état complet est envoyé à chaque appui) :
> ils arriveront dans une prochaine étape, avec le Wi-Fi et le Bluetooth.

## La TV Android par le Wi-Fi

La TV Panasonic est une Android TV : elle se pilote par le Wi-Fi avec le même protocole que l'appli Google TV.
1. Dans l'appli, carte **TV Panasonic (Wi-Fi)** : **Rechercher**, puis **Appairer**.
2. Un code à 6 caractères s'affiche sur la TV : saisissez-le. C'est fait une seule fois.

Depuis l'ordinateur (même Wi-Fi), le simulateur sait aussi le faire :

```powershell
.\simulator\build\install\simulator\bin\simulator.bat tv rechercher
.\simulator\build\install\simulator\bin\simulator.bat tv appairer
.\simulator\build\install\simulator\bin\simulator.bat tv touche volume_plus 3
.\simulator\build\install\simulator\bin\simulator.bat tv app youtube
```

## Le décodeur Orange

Le décodeur se pilote par le Wi-Fi grâce à son API locale (port 8080). Le téléphone doit être sur le Wi-Fi
de la Livebox. Touchez **Rechercher le décodeur** dans l'appli, ou demandez-le à Claude : l'adresse est retenue.
Pour éviter qu'elle change, vous pouvez réserver une adresse fixe au décodeur dans l'interface de la Livebox
(http://192.168.1.1, *Réseau → DHCP*).

Par sécurité, l'IA ne contacte que des adresses du réseau local (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`).

## Sécurité : ce qui est en place, ce qui reste à faire

En place :
- jeton secret de 256 bits, comparé à temps constant, révocable depuis l'appli ;
- requêtes venant d'un navigateur refusées (protection contre le rebond DNS) ;
- validations par notification qui exigent un téléphone déverrouillé (Android 12+) et expirent après 60 s ;
- arrêt d'urgence : n'importe quel appareil autorisé peut l'engager, seul le téléphone peut le lever ;
- sauvegarde Android désactivée pour que le jeton ne quitte pas le téléphone.

À faire :
- chiffrement du trafic sur le Wi-Fi local (aujourd'hui en clair hors Tailscale) ;
- empreinte ou visage pour lever l'arrêt d'urgence ;
- gestion des membres de confiance et des enfants depuis l'appli.

## Tests

```bash
./gradlew test
```

28 tests couvrent la sécurité, l'encodage infrarouge, le protocole MCP et le transport HTTP (jeton, refus des navigateurs).
