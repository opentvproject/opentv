# OpenTV

**A free, open-source IPTV player for Android TV, Fire TV, phones and tablets.**

No account. No subscription. No server of ours between you and your provider.

[![Build](https://github.com/T3lo5/opentv/actions/workflows/build.yml/badge.svg)](https://github.com/T3lo5/opentv/actions/workflows/build.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![GitHub Sponsors](https://img.shields.io/badge/GitHub-Sponsors-ea4aaa.svg)](https://github.com/sponsors/legionnaireneyland)

---

> **This repository is a fork of [OpenTV](https://github.com/opentvproject/opentv).** It is the same
> project — same licence, same promise, upstream's copyright notices intact — kept alive and
> extended here. Upstream wrote it from scratch; this fork builds on it.
>
> The APKs published from this repository carry the upstream's code, so the GPL applies to them
> as it does to upstream's: the complete corresponding source is this repository, the build is
> public, and the notices travel with the binary. [NOTICE](NOTICE) has the attribution;
> [Licence](#licence) spells out what a fork that ships builds has to do.
>
> The donation links below still go to the upstream maintainer, who wrote the original.

> **Support OpenTV** — it's free and always will be. If it saved you from a dead "lifetime"
> app, you can chip in via **[GitHub Sponsors](https://github.com/sponsors/legionnaireneyland)**
> or **[PayPal](https://www.paypal.com/donate/?business=leetobin1982@gmail.com&item_name=Support+OpenTV&no_recurring=0&currency_code=GBP)**.
> Entirely optional; the app is never paywalled.

## Why this exists

There is a recurring pattern in this corner of the app world. A promising IPTV player appears.
It looks good. It sells a "lifetime" subscription. The lifetime turns out to be shorter than
the buyer's — the developer burns out, or moves on, or simply stops replying, and everyone who
paid is left with an app that no longer works and no way to fix it.

The people affected are not helpless. They are often technical. What they lack is not skill
but *access*: the code is closed, so nobody else can pick it up.

OpenTV is the same category of app built so that cannot happen. The source is public and
GPL-licensed. If the current maintainers vanish tomorrow, anyone can fork it, build it and
keep it alive. That is the entire point.

**OpenTV is not a fork or a decompilation of any existing app.** It is written from scratch.
(This repository is a fork of OpenTV — see the note at the top.)

## What it does

- **Live TV** from Xtream Codes logins and plain M3U/M3U8 playlists
- **A guide that stays put** — XMLTV EPG that survives restarts, refreshes incrementally, and
  never wipes itself
- **Recording (DVR)** — record to the box, a USB drive or your NAS (SMB); schedule from the
  guide; series-link a whole show; play back in-app with full seeking
- **Catch-up / archive** where your provider offers it, plus **programme reminders**
- **Movies and series** with resume and per-profile watch history
- **Favourites, categories, hide channels, search**, picture-in-picture, aspect control, and
  hand-off to an external player
- **Free sync between your own devices** — favourites, watch history and NAS recordings, over
  your wifi or Tailscale, with no server of ours
- **Multiple languages** (fully translated to Spanish), a parental PIN, profiles, and self-updates
- **D-pad first** — designed for a remote, works with a touchscreen
- **One APK** for Android TV, Fire TV, phones and tablets

## Screenshots

The live TV guide — programme grid, favourites, and a live preview:

![OpenTV live TV guide](docs/screenshots/01-guide.png)

| Recordings &amp; reminders | Free cloud sync — your own NAS, no server |
| :---: | :---: |
| ![Recordings and reminders](docs/screenshots/02-recordings.png) | ![NAS cloud sync](docs/screenshots/03-cloud-sync.png) |
| Movies &amp; series | Settings |
| ![Movies](docs/screenshots/04-movies.png) | ![Settings](docs/screenshots/05-settings.png) |

Open-source through and through — the About screen carries the licence, the links, and a donation QR:

![About](docs/screenshots/06-about.png)

## What it deliberately does not do

- **No servers of ours, no web dashboard, no account.** Your provider credentials never leave
  your device, and syncing happens directly between your devices — or through your own NAS —
  never through a machine we run. This is a feature, not a gap — see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the reasoning, and note that "sync your
  channel list to *our* servers" is precisely the feature whose hosting bill makes a one-off
  payment unsustainable.
- **No content.** OpenTV is a player. You bring a service you already pay for. The project has
  no affiliation with any provider and does not help you find one.

## Install

**→ [Releases](https://github.com/T3lo5/opentv/releases)** — the APKs this repository builds,
one per tagged version.

Upstream also keeps its own [install page](https://opentvproject.github.io/opentv/) with
step-by-step instructions for Chromecast with Google TV, Android TV boxes, Fire TV Sticks and
phones.

On a TV, the quickest route is the **[Downloader app](https://www.aftvnews.com/downloader/)**.
The short code `6398449` always points at the newest **upstream** APK; this fork's own APKs are
the ones in the releases above, and a short code for them belongs to whoever runs the fork. On a
phone, the install page is a one-tap download.

Every release is built by GitHub Actions from a tagged commit, and the workflow that built it
is public — you can check the APK against the source, or rebuild it yourself, rather than
trusting anyone's word. The release notes name the exact tag the APK was built from, and the
source of that tag is the corresponding source the GPL asks for.

New to this? [SETUP.md](SETUP.md) walks through getting the repo, the install page and the
first build running.

## Build it yourself

```bash
git clone https://github.com/T3lo5/opentv.git
cd opentv
./gradlew assembleDebug
```

You need JDK 17+ and the Android SDK (API 35). Android Studio will fetch it for you. The debug
APK lands in `app/build/outputs/apk/debug/`.

Run the tests:

```bash
./gradlew test
```

## How it was built

OpenTV was written with **Claude**, Anthropic's AI assistant, working from a human's
direction — the design decisions, the priorities, the "no server, ever" rule, and every
review of what shipped were the maintainer's; Claude did the bulk of the drafting, wiring and
debugging against that direction.

That is relevant here for one specific reason: **it kept the project genuinely clean-room.**
Nothing was decompiled or copied from any existing IPTV app. The code was written from public
specifications (the Xtream Codes request format, the XMLTV and M3U formats, the Kodi catch-up
tags) and from describing how other apps *behave* as a user — never from their source. That is
the legal footing that lets anyone fork this and keep it alive, which is the whole point.

## Contributing

Please do. See [CONTRIBUTING.md](CONTRIBUTING.md).

The project is explicitly looking for people who own devices the maintainers do not. Most
"works for me" bugs in this category are device-specific — an Ugoos box, a first-gen ONN, a
Fire Stick Lite — and a bug report from someone who owns one is worth more than a week of
guessing. If a channel fails on your setup, [open an issue](../../issues/new/choose).

## Supporting the project

OpenTV is free and always will be. There is nothing to buy, no premium tier, and no lifetime
subscription — the failure mode this project was built in response to is a promise nobody
could keep, so we are not making one.

If it saves you money or annoyance and you want to say thanks, you can sponsor the project via
[GitHub Sponsors](https://github.com/sponsors/legionnaireneyland) or
[PayPal](https://www.paypal.com/donate/?business=leetobin1982@gmail.com&item_name=Support+OpenTV&no_recurring=0&currency_code=GBP)
— see [.github/FUNDING.yml](.github/FUNDING.yml). Tips fund nothing except spare-evening development;
nobody is owed a feature for one, and nothing is gated behind one.

## Licence

**GPL-3.0-or-later** ([LICENSE](LICENSE)) — the SPDX identifier is `GPL-3.0-or-later`, which
means version 3 of the GNU General Public License or any later version.

Chosen deliberately. GPL means anyone can take this code, but if they ship it they must ship
their source too. Nobody gets to close it, rebrand it, and sell a lifetime subscription on top
of work the community did for free.

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of
the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If
not, see <https://www.gnu.org/licenses/>.

### What a fork that ships builds has to do

The GPL is not a formality here, and this repository is a fork that publishes APKs — so the
obligations are live, not theoretical:

- **The source of *this* build has to be reachable from the binary.** The "Source code" link on
  the About screen points at this repository, and the release notes of every APK say which tag
  it was built from. A fork that ships an APK and points at somebody else's repository has not
  offered the corresponding source, and the copyleft has quietly stopped holding.
- **The notices travel with the binary.** Every source file keeps its copyright header, the
  licence text is in `LICENSE`, and the About screen carries the notice above.
- **No additional restrictions.** GPL §10 forbids adding conditions to the source — no "not for
  commercial use", no "do not fork", no NDA on the code.
- **You may keep the licence, and you may charge for it.** Selling an APK is allowed. Shipping
  it without the source is not.
- **Using it privately costs you nothing.** The obligations are triggered by *distributing*.
  Installing it on your own stick and keeping it to yourself needs no source release at all.

## Legal

OpenTV is a media player, comparable to VLC. It ships with no channels, no playlists, and no
links to any. What you point it at, and whether you are entitled to, is between you and your
provider.
