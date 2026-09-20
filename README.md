# Wander

Choose, test and move an Android phone's reported location – a development tool for testing
location-based apps on a device you own. Built for a Galaxy Fold (works folded and unfolded).

It uses Android's official **mock location** mechanism (Developer options → *Select mock location app*),
so there is no root, no VPN and no pairing involved.

## Features

- Search for places, type `lat, lon`, or tap the map.
- Fixed mock location; "Jump here" to move it while a session is running.
- Simulated walks along real footpaths (walk / jog / cycle / drive speeds) with pause, resume,
  reverse, and redirect to a new destination mid-walk.
- Favourites and history, stored on the phone only.
- **Restore real location** from the app or from the ongoing notification.
- Cleans up after an interrupted session on next launch and offers to resume it.
- Follows the system light/dark theme.

## Setup on the phone

1. Settings → About phone → Software information → tap **Build number** 7 times.
2. Settings → Developer options → **Select mock location app** → Wander.

The app walks you through both steps on first launch.

## Network use

Map tiles and place search come from OpenStreetMap (tile server, Nominatim); walking routes from
`routing.openstreetmap.de`. Nothing else leaves the phone, and there are no analytics.

## Responsible use

While a session is active **every** app on the phone sees the mock location. Restore the real location
before relying on navigation, emergency, safety or location-sharing features. Apps can tell that a
location is mocked (`Location.isMock()`), which is by design – this is a testing tool, not a disguise.

## Building

Builds run on GitHub Actions (`.github/workflows/android.yml`): every push produces an APK, every `v*` tag a
signed release. Signing uses the four `KEYSTORE_*` / `KEY_*` repository secrets.
