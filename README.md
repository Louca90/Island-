# Island

Dynamic Island pour Galaxy S24 (One UI 9) : musique (paroles synchronisées, J'aime, aléatoire), notifications empilées avec réponse directe, chrono et minuteur, enregistreur d'écran et dictaphone, appels, alarme, itinéraires, Live Updates (livraisons, VTC, sport), rendez-vous, écouteurs Bluetooth, codes de vérification, charge, verrouillage.

Kotlin + Jetpack Compose, overlay d'accessibilité + NotificationListener.

## Installer
Télécharge le dernier APK dans **Releases**, ou utilise « Vérifier » dans l'app (Mises à jour & sauvegarde).

## Publier une version
1. Monte `versionCode` / `versionName` dans `app/build.gradle.kts`.
2. Pousse sur `main` : GitHub Actions compile et publie l'APK dans la release `vX.Y`.

`app/island.keystore` est une clé de debug (mot de passe `android`) utilisée pour que chaque version s'installe par-dessus la précédente. À remplacer par une vraie clé si l'app est un jour distribuée.

## Secours si les releases GitHub sont en panne
`dist/Island.apk` + `dist/latest.json` sont poussés directement dans le dépôt à chaque version.
Le bouton « Vérifier » de l'app regarde les Releases **et** `dist/`, et prend la version la plus récente.

## Island Music (`music/`)

App à part pour contrôler Spotify via la Web API (Spotify Connect) : volume de l'appareil qui joue (PC, enceinte…),
paroles LRCLIB avec recherche tolérante, file d'attente avec pochettes, choix de l'appareil, relance de Spotify
quand Android l'a tué. APK : `dist/IslandMusic.apk`. Nécessite une app sur developer.spotify.com
(Redirect URI `http://127.0.0.1/callback`) et un compte Premium.
