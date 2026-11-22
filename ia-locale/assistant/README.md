# Assistant — étape 1 : fondations, pont avec Claude, télécommande infrarouge

Première brique du projet décrit dans [`../VISION.md`](../VISION.md).

## Ce que fait cette version

| Fonction | Détail |
|---|---|
| 🔐 **Sécurité** | Rôles (propriétaire, confiance, enfant, invité, inconnu), autorisations appareil par appareil, validation du propriétaire pour les actions critiques, accès temporaires, **arrêt d'urgence** (engageable à distance, levable **uniquement sur le téléphone**) |
| 📒 **Journal de bord** | Chaque action est enregistrée : qui, quoi, quand, résultat |
| 🌉 **Pont avec Claude** | Le téléphone expose ses fonctions à Claude via **MCP** (`http://<ip>:8765/mcp`), protégé par un jeton secret |
| 📡 **Télécommande infrarouge** | Protocoles **NEC** (LG, beaucoup de décodeurs et ventilateurs) et **Samsung**, depuis l'émetteur IR du Redmi |
| 🔔 **Notifications** | Claude peut vous envoyer un message sur le téléphone |
| 📱 **État du téléphone** | Batterie, modèle, réseau, infrarouge |

### Les outils que Claude voit

| Outil | Rôle |
|---|---|
| `ir_lister_appareils` | Liste les appareils et leurs touches |
| `ir_envoyer_touche` | Appuie sur une touche (`appareil`, `touche`, `repetitions`) |
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

Prérequis : Java 17 ou plus récent, et Claude Code.

```bash
cd ia-locale/assistant
./gradlew :simulator:installDist          # Windows : gradlew.bat :simulator:installDist
simulator/build/install/simulator/bin/simulator
```

Le simulateur affiche une commande `claude mcp add …`. Copiez-la dans un autre terminal, lancez `claude`,
puis demandez par exemple : *« Liste les appareils infrarouges et monte le volume de la TV LG de 3 crans. »*
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
Les codes de milliers d'appareils sont publiés dans des bases libres comme **Flipper-IRDB** et **IRDB**.

> Les codes fournis (TV LG et Samsung) sont des exemples issus des protocoles publics : à vérifier sur vos appareils.
> Les climatiseurs utilisent des protocoles plus longs (l'état complet est envoyé à chaque appui) :
> ils arriveront dans une prochaine étape, avec le Wi-Fi et le Bluetooth.

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

17 tests couvrent la sécurité, l'encodage infrarouge, le protocole MCP et le transport HTTP (jeton, refus des navigateurs).
