# Contributing to OpenTV

Everyone is welcome here, including — especially — people who have never contributed to an
open-source project before. If you are fixing the bug that has been annoying you, you are
exactly the person this project is for.

## The one hard rule

**Do not copy code from any other IPTV app, and do not decompile one.**

OpenTV is a clean-room implementation. If any decompiled or copied code enters this repository,
the project loses the legal footing that makes it safe for anyone to use, fork and distribute —
which is the whole reason it exists. Pull requests that appear to contain code lifted from a
closed-source app will be closed.

You may absolutely:

- use another app as a *user* and describe how it behaves
- read public API documentation, XMLTV specs, or the Xtream Codes request format
- port ideas, layouts and interaction patterns

You may not:

- run an APK through a decompiler and paste the output
- copy source you obtained from a private repository or leak

## Reporting a bug

The most valuable bug report in this project names a **device**. Most failures here are
device-specific — a particular box, a particular Android version, a particular provider. Please
include:

- Device and Android version (e.g. "Ugoos AM9 Pro, Android 14"; "Fire TV Stick 4K, Fire OS 7")
- OpenTV version
- Source type: Xtream login or M3U playlist
- What happened, and what you expected
- Logs if you can get them: `adb logcat -s OpenTV:* EpgRepository:* SyncWorker:*`

**Never paste your provider's URL, username or password into an issue.** Redact them. They are
credentials for a service you pay for, and issues are public forever.

## Setting up

```bash
git clone https://github.com/OWNER/opentv.git
cd opentv
./gradlew test          # fast, no device needed
./gradlew assembleDebug
```

JDK 17+ and Android SDK API 35. Android Studio handles both.

Testing against a real provider is the hard part of working on this project. If you do not have
one, the parsers, the database layer and the UI can all be worked on with unit tests and
synthetic data — and those are where most of the interesting bugs are.

## Pull requests

- One change per PR. A PR that fixes a bug *and* restyles three screens is hard to review and
  hard to revert.
- Add a test if the change is testable. Parsers, URL handling, time arithmetic and database
  merge logic all are.
- Run `./gradlew test` before pushing.
- Explain the *why* in the description. The code says what it does.
- **New files carry the licence header.** Every source file starts with:

  ```kotlin
  /*
   * This file is part of OpenTV.
   * Copyright (C) 2026 The OpenTV Contributors
   * Licensed under the GNU General Public License v3.0 or later.
   */
  ```

  Do not remove it from the files you touch. GPL §5(b) asks the notices to be kept, and the
  header is how the copyright holder is recorded.

Comments should explain reasoning, not restate the line below them. Look at
`EpgRepository` or `PlayerController` for the house style: the interesting comments are the ones
explaining why an obvious implementation was rejected.

## The licence

OpenTV is **GPL-3.0-or-later** ([LICENSE](LICENSE)), and this repository is a fork of
[upstream](https://github.com/opentvproject/opentv) — see [NOTICE](NOTICE). What that means for
a contribution:

- **Your contribution is licensed GPL-3.0-or-later**, the same as everything around it. That is
  what makes it possible for the project to be forked, changed and shipped by anyone.
- **The licence cannot be made more restrictive.** Not in a file, not in a PR template, not in a
  build script. GPL §10 forbids additional restrictions on the source.
- **Nothing copyleft and nothing proprietary goes in.** A dependency under a copyleft licence
  (GPL, AGPL, LGPL) or a snippet from a closed-source app changes the terms of the whole
  project — and the second one ends it. Apache-2.0, MIT and BSD are all fine; the dependency
  list is in `gradle/libs.versions.toml` and the licensing in `NOTICE`.
- **If you fork it, keep the notices.** The copyright headers stay, `LICENSE` stays, and if you
  publish builds, the source of *those* builds has to be reachable from them. The About screen's
  "Source code" link is not decoration: it is the GPL §6(b) offer of the corresponding source.
- **Forking is not a betrayal.** The licence guarantees it, permanently. Upstream's own view is
  in the last paragraph of this file.

One thing that is *not* a licence matter, and is easy to get wrong: the app name and logo are
upstream's. The GPL lets you copy and change the code; it does not let you imply the upstream
endorses your fork.

## Good first issues

- Device-specific playback bugs — if you own the device, you are uniquely able to fix it
- Test coverage for `EpgRepository.sync` failure paths (see the end of `docs/ARCHITECTURE.md`)
- Accessibility: content descriptions, focus order, caption styling
- Translations

## Things that need discussion before you build them

Open an issue first for these. They are not refusals — they are decisions with consequences
that outlive the PR:

- **Anything requiring a server we have to run.** See `docs/ARCHITECTURE.md`. The short version:
  a hosting bill that grows with users is how this category of app dies.
- **Recording and catch-up.** Wanted, but large. Worth agreeing the shape first.
- **Replacing the hand-rolled Xtream JSON parsing with typed models.** There is a specific
  reason it is written that way; read the class docs before proposing it.

## Code of conduct

Be decent. Assume the person you are replying to is doing their best with less context than
you have. Frustration with a *piece of software* is fine and often justified; taking it out on
a person is not.

Maintainers are volunteers. Nobody here owes anyone a fix by a deadline. Equally, if a
maintainer goes quiet, that is what forking is for — the licence guarantees you that option,
permanently, and using it is not a betrayal. It is the design working.
