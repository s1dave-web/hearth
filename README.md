# Hearth

A calm, remote-friendly home screen for Android TV (built on an NVIDIA SHIELD) with live **Home Assistant** tiles.

- **Home Assistant tiles** update instantly over the WebSocket API. Press OK to toggle lights, plugs, fans and blinds, run scenes and scripts, or open a camera full screen. Hold OK for brightness, colour (on RGB lights), heating set-point, blind and volume controls. Locks are view-only.
- **Favourites bar** along the bottom (3–6 apps). Press down for **All apps**. Hold OK on any app to add/remove it from favourites, move it, or hide it.
- **Header** with greeting, clock, date, weather (from your Home Assistant weather entity) and a green/red network indicator.
- **Wallpapers**: night sky, aurora, desert dunes, misty lake, Christmas, solid colours, or a **Bing Photo of the day** slideshow.
- **Set up from your phone**: Settings shows a QR code for a small page on the TV where you paste a Home Assistant long-lived token, pick and order tiles, rename them, and back up / restore your settings to a file (the token is never included in the backup).
- Configurable tiles per row, apps per row and favourites count. A single gliding highlight, like the launcher it was styled after.

## Install

Download the APK from [Releases](../../releases) and sideload it (e.g. with the Downloader app or `adb install`). Then make it your home screen:

```bash
adb shell cmd package set-home-activity app.hearth.tv/.ui.HomeActivity
```

Pressing Home will otherwise ask which home app to use each time.

## Set up Home Assistant

1. In Home Assistant: your profile → **Security** → **Long-lived access tokens** → **Create token**.
2. On the TV: **Settings → Home Assistant**, scan the QR code with your phone.
3. Enter your Home Assistant address *as the TV can reach it* (e.g. `http://192.168.1.20:8123`), paste the token, **Connect**, then choose your tiles and **Save to TV**.

The setup page is only reachable on your local network and every request needs the 6-digit code shown on the TV. The token stays on the TV (`allowBackup=false`) and is never sent back out by the page.

## Build

Requires the Android SDK (API 37) and JDK 17+ (Android Studio's bundled JBR works).

```bash
./gradlew assembleDebug
```

Release builds are signed with a key kept **outside** the repo: put `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and the keystore in `~/.hearth-signing/` (or point `HEARTH_SIGNING_DIR` at it), then `./gradlew assembleRelease`. Without it the release APK is unsigned.

## Licence

MIT, see [LICENSE](LICENSE). Third-party components: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
