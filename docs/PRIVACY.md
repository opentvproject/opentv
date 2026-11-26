# Privacy

OpenTV collects nothing. There is no analytics SDK, no crash reporter, no telemetry, and no
network call to any domain the project controls — because the project controls none.

This page lists **every** network destination the app can reach and what triggers each one, so
"it collects nothing" is checkable rather than a claim. It was last reconciled against the code
in `app/src/main/java/app/opentv/` at v0.11.10 + the privacy hardening pass.

## What is stored, and where

Everything lives in this app's private storage on your device:

- Your provider's address, username and password (plaintext, see below)
- Your SMB/NAS share address and password, if you set one up
- The channel, movie and series catalogue downloaded from your provider
- The guide (EPG)
- Your favourites, hidden channels and resume positions
- A hashed parental PIN (never the PIN itself)

None of it is uploaded to the project. There is no account and no project server to upload it to.

### Android Backup is disabled

`android:allowBackup="false"`, with `fullBackupContent` and `dataExtractionRules` excluding every
domain. Android's own Auto Backup (and the device-to-device transfer on Android 12+) would
otherwise copy this private storage — provider credentials included — to the user's Google Drive.
That is the one route by which those credentials could leave the device without the user asking,
so it is closed.

### Plaintext credentials, and why

The provider password is stored as written, in the app's SQLite database, and the SMB password in
SharedPreferences. Both are protected by Android's per-app sandbox, not by encryption at rest.
OpenTV does not encrypt them because the key would have to live on the same device, which moves
the problem rather than solving it; a proper keystore-backed store is the known next step.
What it does mean: **on a rooted device, or with physical access to an unlocked one, those
credentials are readable.** Treat the box accordingly.

## Every destination the app can contact

| Destination | When | What leaves the device |
|---|---|---|
| **Your provider** (Xtream Codes, M3U URL, or Stalker portal) | On every catalogue/guide/stream fetch | Your credentials, as the protocol requires |
| **Your provider's image hosts** | When artwork loads | Only the image request; usually the provider, sometimes a CDN it chose |
| **`api.github.com`** | Only if you tap *Check for updates* in About | `GET /releases/latest`, User-Agent `OpenTV` |
| **`api.themoviedb.org`, `image.tmdb.org`** | Only if you add your own TMDB API key | The title/year being looked up and **your** key |
| **`epgshare01.online`, `raw.githubusercontent.com`** | Only if a built-in guide is enabled — which now requires you to accept a suggestion | The XMLTV request. No account, no identifier |
| **Stremio add-on URLs you paste** | Only if you add an add-on | The title/IMDb id being resolved |
| **Your NAS (SMB)** | Only if you configure recordings to a NAS | Your SMB credentials |
| **Your own other devices (LAN/Tailscale)** | Only while the web manager or device sync is open | See *Device sync* below |
| **`paypal.com` / `github.com/sponsors`** | Only if you tap the donate button | Nothing; it is an ordinary browser intent |

There is no third-party SDK in the APK beyond the libraries any Android app ships (AndroidX,
Media3/ExoPlayer, OkHttp, Room, SMBJ). None of them phone home on their own in this configuration.

### Guide feeds are opt-in

Earlier builds inferred a region from channel names and **switched the matching free guide on by
itself**, which meant a download from a third-party host the user never chose. Detection now only
produces a suggestion, shown in Settings → TV guide with *Turn it on* / *Not now*. No suggestion,
no request.

### Device sync

Settings → Sync exchanges favourites, hidden channels, resume points and NAS recording metadata
between two devices **on your own network**. Two details worth knowing:

- **Stream URLs never travel.** An Xtream URL contains your username and password, so the bundle
  carries `SHA-256(url)` (truncated to 128 bits) instead. The receiving device resolves those keys
  against its own catalogue. Provider credentials are not in the bundle, hashed or otherwise.
- The transfer is plain HTTP on the LAN, gated by a six-digit code, private-address-only, and
  cancelled after repeated wrong codes. Treat it as "trusted wifi" only.

### The phone/laptop manager

The same shapes of guarantee: a random high port, a short-lived token, requests from non-private
addresses refused, an idle timeout that closes the socket, and `Cache-Control: no-store` on the
page that handles credentials. Your provider password is typed in that page and goes straight to
the device on your LAN.

## Cleartext HTTP

The app allows plain HTTP (`usesCleartextTraffic="true"`). The overwhelming majority of IPTV
panels are HTTP-only; refusing them would make the app useless for most of the people it is for.

Be aware of what that means: **on an HTTP connection, your provider username and password are
sent unencrypted**, and anyone able to observe the network between you and your provider can read
them. This is a property of how these services are built, not of OpenTV. If your provider offers
an HTTPS endpoint, use it.

## Uninstalling

Removing the app removes the database, the preferences and everything in them. Because backup is
disabled, there is no copy to restore.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
