# Island

Dynamic Island pour Galaxy S24 (One UI 9) : musique (paroles synchronisées, J'aime, aléatoire), notifications empilées avec réponse directe, chrono et minuteur, enregistreur d'écran et dictaphone, appels, alarme, itinéraires, Live Updates (livraisons, VTC, sport), rendez-vous, écouteurs Bluetooth, codes de vérification, charge, verrouillage.

Kotlin + Jetpack Compose, overlay d'accessibilité + NotificationListener.

## Installer
Télécharge le dernier APK dans **Releases**, ou utilise « Vérifier » dans l'app (Mises à jour & sauvegarde).

## Publier une version
1. Monte `versionCode` / `versionName` dans `app/build.gradle.kts`.
2. Pousse sur `main` : GitHub Actions compile et publie l'APK dans la release `vX.Y`.

`app/island.keystore` est une clé de debug (mot de passe `android`) utilisée pour que chaque version s'installe par-dessus la précédente. À remplacer par une vraie clé si l'app est un jour distribuée.
