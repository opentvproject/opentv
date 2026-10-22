# What users actually complain about — the 2026 sweep

A feature is worth building when people ask for it, not when it seems tidy. This is the
evidence behind the next round of work: what the users of IPTV and TV apps complain about, read
from their own words, and what that says we should build.

Four sources, each read in its own terms and each quoted where it is quoted:

| Source | What it is | How it was read |
|---|---|---|
| **Store reviews** | 16 apps — TiviMate, TiviMate Companion, Televizo, Sparkle TV, IPTV Smarters Pro, Perfect Player, OTT Navigator, dream Player, Kodi, MX Player, VLC, Jellyfin TV, YouTube TV, NewPipe, SmartTube, Google's TV remote — plus the Amazon Appstore listings | Play listing pages fetched through several locale codes (Play serves a different review sample per locale), AppBrain's mirror for review bodies and rating histograms, the apps' GitHub trackers where they are not on Play |
| **Issue trackers** | 16 projects — OpenTV itself, Kodi (`xbmc/xbmc`), `pvr.iptvsimple`, `pvr.hts`, `mpv-android`, `mytv-android`, `jellyfin-androidtv`, `jellyfin-android`, `NewPipe`, `SmartTube`, `Wholphin`, `open-tv`, `iptvnator`, `NuvioTV`, `ChaiChaiEmbyTV`, `Dispatcharr` | `gh issue list`/`view`, title keywords counted, then each representative issue read |
| **Community** | Reddit and provider forums — r/IPTV, r/AndroidTV, r/FireTV, r/TiviMate, r/SparklePlayer, TROYPOINT, TIVIFY, IPTVTalk | threads read, quoted at first person |
| **Platform** | Android TV 2025–2026 | Android Developers documentation, AOSP source, `androidx.media` — the rules the platform actually enforces, not a summary of them |

**Evidence strength is marked, and "one review" is not "many".** A theme marked *many* recurs
across several independent apps or trackers; *handful* is 2–4 independent reports; *one* is a
single report, which is still worth reading if it is the only report of a crash.

The raw notes are long and are not in the repository; this is the distillation, with the sources
kept so any claim can be checked.

---

## The short version

1. **The guide is the product, and the guide is what breaks.** Every app in the study is
   polarised on it. Kodi: 32% of its Play ratings are 1★–2★; Sparkle TV 30%; Televizo 26%;
   TiviMate 21%. The complaints are never "the grid is ugly" — they are "the grid says 'no
   information'", "the times are wrong", "it only updates when the app is open", "the past is
   hours out".
2. **A clickable row is not a button, on a TV.** Across nine projects the same complaint appears
   in different words: the remote could not reach the controls. NewPipe closed a dozen TV
   navigation issues into one "TV navigation needs a rethink" thread; mpv-android has a
   20-year-old open issue saying the in-player settings popup is unreachable with the d-pad.
3. **Recording is advertised as a headline and is the least reliable thing in the category.**
   TiviMate recordings that stop after 39 seconds, Sparkle's timeshift overheating a Fire Stick
   until it shuts down, dream Player with no recording at all.
4. **Updates break things, and then the developer is unreachable.** A TiviMate update killed
   playback on Google TV for 101 people; a Sparkle user writes "the developer doesn't respond to
   anything I sent"; TiviMate's support bounced users to a company with no contact details (81
   people agreed).
5. **Ads and monetisation friction are the most-liked complaints in the store reviews** — and
   they are what an open-source app answers for free. (In the communities, though, the ads
   evidence is weak: what is there is developers advertising themselves. The real community
   complaint is *abandonment* — a paid app that stops working when the developer moves on.)
6. **Cross-device continuity does not exist.** The single most-liked complaint on TiviMate
   (206 people) is that there is no way to back up your playlist and channel tweaks. The
   community's version of it: *"Saving the settings in the cloud would be my #1 wishlist
   item"* — and another user's objection to a cloud sync that stores favourites in plaintext on
   somebody else's server.
7. **The two biggest names are gone.** IPTV Smarters Pro (22M downloads) was delisted from Play
   in January 2023; Perfect Player (7.1M installs) in December 2021. Both are now sideloaded
   from third-party sites, and Play is full of clones under their names. That population is
   unserved.
8. **A stream that dies never comes back.** Ten people in one thread, and a developer's
   two-year complaint list: a black screen, no timeout, no error, no recovery. The forums add
   this one, and it is the cheapest big win on the list.

**And the highest-signal evidence of all is our own issue tracker** — see the last section.

---

## What the community adds

The forums were read last, and they corrected two things and added four.

### A stream that dies never comes back

The single most-repeated complaint in the community, and the one that is most clearly a bug
rather than a taste:

> "If a stream stops for a second, it shows the loading icon and it won't reconnect at all.
> Basically you have to change the channel and go back again. This annoying issue is not
> happening on any other applications so far."
> — [r/TiviMate](https://www.reddit.com/r/TiviMate/comments/b29ksl/very_annoying_issue_on_all_versions_of_tivimate)
> (37 entries, ~10 users confirming, pinned by a moderator)

A developer's own two-year complaint list says it more sharply: *"Black screen when a stream
dies. Most players just sit there. No timeout, no clear error, no 'try the next channel' button.
You're staring at the wall."* And TROYPOINT's wishlist asks for it by name: *"if the channel goes
down, it will retry 5 times real quick."*

**We do reconnect — three times, 1.5 s apart — and then we stop and show an error.** The fix
is the same shape as the complaint's: keep going with a backoff, and say *reconnecting* rather
than *failed*. A black screen with no explanation is what people write threads about.

### Two corrections to what the stores said

- **Ads and data use are not a community complaint.** The store reviews made ads the most-liked
  complaint in the whole study (MX Player's 7,234-helpful review). In the communities, the
  evidence is *weak and not established*: what is there is developer positioning ("no ads", "no
  telemetry") and one user's objection to a competitor's cloud sync storing favourites in
  plaintext. So the honest claim for an open-source app is the one we can check — **no ads, no
  server, no account** — and not a claim about other people's ads.
- **Data usage is not a user complaint either.** Not found in the communities. The platform
  research still stands on its own (Data Saver throttles a foreground TV app to 800 Kbps), but
  it is a constraint, not a demand.

### And four things the stores did not have

| What | Evidence | Who answers it |
|---|---|---|
| **The stream's own details** — URL, video and audio bitrate and codec. *"Right now to see the stream URL I open in MXPlayer and then go to settings to see the URL."* | a few threads | Nobody. We have no such panel. |
| **A list of only the channels that have catch-up.** *"I can see a clock icon next to the channels that support catch-up but I don't see how to access it? Is it also possible to have a separate item (list) only with channels that support catch-up?"* | a few threads | Nobody. Our catch-up list is per channel. |
| **A lock on the provider's own screen.** *"When looking at Playlist, using m3u link, it shows my username and password — in turn they can copy it and give it out to other friends."* | a few threads | We have a parental PIN; it does not guard the credentials. |
| **Closing the connection when the app goes away.** A *provider* complains: *"Tivimate notoriously doesn't terminate connections correctly either when flipping channels… or upon exiting the app."* | one thread, but externally visible | We do — the player stops the controller when the app is turned off (0.12.1). |

### And the reason the whole category is distrusted

> "I paid for a 'lifetime' IPTV player, the dev moved on, and it slowly stopped working — sound
> familiar?" … "Most apps, especially IPTV ones, seem to just drop and never be heard about
> again."

That is the argument for the project existing, and it is not something a feature list can fix.
It is why the sync is local, why there is no account, and why the release notes are written like
this.

---

## The complaints, in order of how much they cost the user

### 1. The guide lies

| Complaint | Who says it | Evidence |
|---|---|---|
| EPG shows "no information" on every channel, or only after scrolling | TiviMate, Sparkle, Televizo, TiviMate Companion | *many* — the top thread on r/TiviMate; "The EPG doesn't work right away—you have to scroll through channels first" (Sparkle) |
| EPG times are hours out of sync | Televizo ("off by like 2 hours" in the Archive tab), TiviMate ("normally -24"), Dispatcharr | *many*; the catch-up-offset complaint is the **most-liked** Televizo review (22–23 helpful); the "add an explicit EPG offset setting" request is the most-upvoted issue in the whole sweep (40 reactions) — [Dispatcharr#383](https://github.com/Dispatcharr/Dispatcharr/issues/383) |
| The guide only refreshes while the app is open | TiviMate | *one*, 16 helpful |
| The guide shows one day, and cannot be reloaded | `mytv-android` (#238), `pvr.iptvsimple` (#945, #880) | *handful*; a 9-day guide was asked for and refused as "won't fix" |
| A big EPG crashes the guide loader (`std::bad_alloc`) | `pvr.iptvsimple` | *one*, **64 comments** — the most-commented EPG issue anywhere |
| The guide grid lags when moved with the remote | **OpenTV** (#2), Jellyfin TV, Kodi | *strong* — two users, two cheap sticks (Xiaomi TV Stick 2nd gen, Shield TV) |
| The guide does not match the channels (`tvg-id` vs `tvg-name` vs display name) | `mytv-android`, `pvr.iptvsimple` | *many* — ~100 issues across two trackers, "works in TiviMate but not here" |
| Catch-up/timeshift is broken or degrades quality | `pvr.iptvsimple` (#958, #1013, #760, #319), `pvr.hts` | *strong* — 47 + 9 issues; live drops to SD when catch-up is on; a 68-comment canonical stutter thread |
| "The EPG grid is useless" | IPTV Smarters Pro (delisted) | *one*, 2★ — from the app with 98,830 ratings |

**We already do:** live EPG from the provider and from free XMLTV feeds, name-matching against
XMLTV, catch-up on both provider families, the provider's own timezone, per-channel catch-up
offsets, a guide that survives restarts and refreshes in the background, and the focus now
follows the channel you are on.

**What is still true for us:** the grid's *lag* on a cheap stick is our own issue #2, still open,
with two users on two different sticks. And the guide still starts at the current half-hour, so
the past is reached through the channel menu rather than by scrolling back.

### 2. The remote cannot reach the buttons

The same failure, in nine projects, in different words:

- **The centre button does nothing** — Fire TV-style remotes send `KEYCODE_NUMPAD_ENTER`, not
  `KEYCODE_DPAD_CENTER`, and the app only listens for the latter
  ([mpv-android#963](https://github.com/mpv-android/mpv-android/issues/963),
  [SmartTube#6131](https://github.com/yuliskov/SmartTube/issues/6131)): *"I can't press any UI
  elements with my remote control during video playback."*
- **The d-pad walks past the controls** — [mpv-android#1100](https://github.com/mpv-android/mpv-android/issues/1100)
  (11 comments, "try to go 'up' just closes the OSD"), NewPipe's consolidated
  [TV navigation issue #11197](https://github.com/TeamNewPipe/NewPipe/issues/11197), its
  [unreachable playlist controls #13854](https://github.com/TeamNewPipe/NewPipe/issues/13854) and
  [gesture-only settings #13853](https://github.com/TeamNewPipe/NewPipe/issues/13853), `iptvnator`'s
  [couch mode #1337](https://github.com/4gray/iptvnator/issues/1337), and a Jellyfin TV issue where
  the seek menu swallows left/right
  ([#5510](https://github.com/jellyfin/jellyfin-androidtv/issues/5510)).
- **The d-pad dead-ends** — [Wholphin#1988](https://github.com/damontecres/Wholphin/issues/1988)
  on a *xiaomi box s 2nd gen*, exactly our hardware: focus goes right in the search page and
  never comes back.
- **Focus is lost when a view goes away** — AOSP's `ViewGroup.removeViewInternal()` calls
  `unFocus()` → `rootViewRequestFocus()` → `notifyGlobalFocusCleared()`, so the d-pad goes dead
  globally unless the app asks for focus again
  ([AOSP ViewGroup](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/ViewGroup.java)).
  This is the exact class of bug we just fixed in our own transport bar.
- **A Compose `FocusRequester` crash** on arrow-key navigation
  ([mytv-android#272](https://github.com/mytv-android/mytv-android/issues/272), 11 comments:
  *"FocusRequester is not initialized"* — and it only happens with the remote, not the mouse).

**We just fixed our half of this** (0.12.1): every remote-reachable row takes the focus, the
clickable keeps its own key handling, and a row's modifier chain no longer changes under it.

**What is still open:** the `NUMPAD_ENTER` blind spot is a real risk to check against — Compose
maps `KEYCODE_NUMPAD_ENTER` to `Key.NumPadEnter`, and if an app only listens for
`Key.DirectionCenter` the centre button dies on a Fire TV-style remote. We do not swallow
unhandled keys in the guide and the home screen (the handlers return `false`, so the focused
row's own clickable sees them), and Compose's `clickable` handles `NumPadEnter` itself — but the
player's immersive branch has a catch-all that consumes anything it does not recognise, and that
is exactly the shape of bug this section is about.

### 3. Recording is unreliable, and nobody says how to use it

TiviMate recordings that stop after 39 seconds, 1 minute or 15 minutes; recordings that freeze
on playback; Sparkle's recording only reliable over SMB; dream Player with no recording at all
("tek eksiği kayıklık özelliği" — its only flaw is the recording feature); the discoverability
complaint from [open-tv](https://github.com/Fredolx/open-tv/issues/220) — *"just bought the app…
don't see a list of the commands anywhere. Specifically how do I begin recording?"* (18 comments).
And the hardware cost: Sparkle's timeshift **overheats a Fire Stick until it shuts down** — the
developer's own answer is that timeshift is resource-intensive.

**We already do:** recording to local storage, USB or SMB, series rules, padding, reminders,
playback in-app, and a "from now" start. **Still open:** recording to NFS (issue #19), and the
scheduled-recording discoverability problem — where does the user even find "record this show"?

### 4. Updates break playback, and support is a wall

A TiviMate update froze video on two Google TV boxes (5.3.3); another killed *all* playback on
Google TV (**101 helpful**); a Sparkle user: *"the developer doesn't respond to anything I
sent"*; a Televizo developer to a beta tester: *"that's what you get for running beta"*;
TiviMate's support pointing users at a company with no contact details (**81 helpful**). Users
have learned to distrust the category's apps after updates.

**This is the whole argument for an open-source app with no server of ours** — and it is the
one thing we cannot copy from a competitor. What we *can* do: never break a channel silently,
and tell the user what changed.

### 5. Monetisation and ads

Price rises with no new features; a lifetime purchase that then asks you to pay again to change
your email; double charges; unlocks that do not unlock; subscriptions that cannot be cancelled
in-app; device-binding that prevents a second household account. The category's most-liked
review, anywhere, is about **ads**: MX Player's (7,234 helpful, 1.8M 1★ ratings out of 13.9M).
Televizo's free tier is ad-supported to the point of pop-ups taking over the app.

**We already do:** no ads, no analytics, no account, no server, no telemetry. The one claim worth
making is "no ads inside OpenTV" — never "blocks ads", which we cannot do
([Data Saver on TV](https://developer.android.com/develop/connectivity/network-ops/data-saver)
throttles a foreground app to 800 Kbps, so on a metered link an H.265 stream simply stalls).

### 6. Nothing carries between devices

No watch history (TiviMate), no favourites sync, no backup of playlist customisation
(**206 helpful** — the most-liked complaint on that app), TiviMate's phone-based multi-device
setup removed, Jellyfin's TV app not following the server's theme. And the same request in our
own tracker: [issue #10](https://github.com/opentvproject/opentv/issues/10) — *"sync providers,
playlist, recording folder etc across the local network, or save a settings config on the
network that other devices could open manually?"*

**We already do:** local device-to-device sync over the LAN (positions, favourites, hidden
channels and categories, recordings) with no backend, plus NAS sync, plus per-profile history.
**Still open:** the *source definitions* and an actual **settings file** to export and import —
the thing TiviMate's 206 people are asking for.

### 7. Decoding and audio

E-AC-3 unsupported and subtitle sync breaking on seek (MX Player, 7,234 helpful); DTS-HD
passthrough breakups on a Sony Bravia (Kodi, 17–22 helpful); audio cutting out mid-film (VLC, 37
helpful); 4K/HDR options **silently vanishing** after a SmartTube update across five device
families ([#3675](https://github.com/yuliskov/SmartTube/issues/3675)); HDR dim under AV1; Sparkle's
auto-frame-rate switching killing passthrough; and TiviMate users forced into an external player
because there is no audio normalisation, with the audio-sync offset range too small
(**207 helpful** — the most-liked TV-audio complaint in the study).

**The platform's own guidance is precise about the fallback**: on a cheap MediaTek stick Google's
software AVC decoder cannot hold 1080p50 and forcing it causes dropped-frame bursts, so *the
correct fallback for a broken hardware decoder is a lower-resolution rendition, not a different
decoder* ([media3#2972](https://github.com/androidx/media/issues/2972)). And TVs have **no media
performance class** — the CDD guarantees only H.264 1080p60 and nothing about sustained MPEG-TS
throughput, which is exactly what IPTV serves.

**We already do:** a quality-variant ladder from the provider (4K/FHD/HD/SD), a decoder
preference (hardware first, software first), buffer presets, an audio-track picker, a subtitle
picker and an aspect picker.
**Still open:** the ladder is chosen blind — nothing asks the device what it can actually hold;
there is no audio normalisation or night mode; no audio-sync offset; no playback speed.

### 8. TV lifecycle: the screensaver, the idle timeout, the home screen

YouTube for Android TV does not resume after the screensaver (**71 helpful**): *"When you exit
or the screensaver comes on, it doesn't resume YouTube app. It either freezes or doesn't load
at all."* Playback stops on an inactivity timeout with no opt-out (19 helpful). YouTube lost a
sort order and rearranged the home screen without asking (49 helpful). Jellyfin's scrub bar
jumps back to the start. Sparkle's EPG update ballooned its cache from ~500 MB to ~3 GB. Kodi
bricked itself behind a permission dialog after an OS upgrade.

**We already do:** the picture is released on `ON_STOP` in TV mode, the stream stops with it, and
the app comes back where it was.

### 9. Distribution

TiviMate is not in the Amazon Appstore in most regions, and buying Premium on a Fire TV needs a
separate phone app; the Brazilian Televizo review asks for the Amazon Appstore (**66 helpful**);
SmartTube and NewPipe are off Play entirely (SmartTube because of ad-blocking); Fire TV Stick 4K
Select and newer run **Vega OS**, which cannot run Android apps at all; and users are regularly
told to "sideload from unknown sources" — a support and security burden an open-source project
can own rather than inherit.

**The 2026 platform change that matters:** Google TV now has one unified **Live tab** that
surfaces third-party providers (Pluto, Tubi, Plex, Haystack) in the *same* guide as the built-in
channels, and the CDD requires the system TV app to surface third-party TV Inputs
([source.android.com](https://source.android.com/docs/devices/tv)). A third-party app can
publish its channels and programme data into the system `TvProvider` via the **TV Input
Framework** and appear inside the system guide. Google's own TIF Companion library is OEM-only
(API 21–25), so `TvInputService` has to be implemented directly. This is the one integration with
real distribution value in 2026.

**Also note what is *not* available:** there is no `androidx.media3` DVR API and the
`android.media.tv.tuner.dvr` HAL is a system service, so PVR must stay in-app. `CompanionDeviceManager`
is pairing and presence, not "watch together". HDMI-CEC is `SignatureOrSystem` and unusable by a
third-party app. Dolby Atmos passthrough depends on the TV endpoint and the user's setting, and
`EXTRA_ENCODINGS` misreports E-AC-3 when the TV is PCM-only.

### 10. The Play rubric for TV apps is machine-checkable now

If OpenTV ever goes to Play, this is what it is judged against
([TV app quality guidelines](https://developer.android.com/docs/quality-guidelines/tv-app-quality)):

- **AAB mandatory** (TV-G1).
- **Baseline Profile** for startup and jank (TV-BP).
- From **1 August 2026**: **32- and 64-bit ABIs and 16 KB page-size compliance** (TV-G6) — and
  a 16 KB misaligned `.so` *crashes the app with a segfault*. OpenTV ships **no native
  libraries**, so this one does not bite us; the platform deadline for Android 15+ targets is
  1 February 2027.
- **D-pad navigable**, no Menu-button dependency, d-pad-dismissible ads.
- **TV-NP: pause the video when the user leaves the app** — we do this now.
- Low-RAM memory ceilings (TV-ME).

OpenTV already declares `android.software.leanback` (not required), `android.hardware.touchscreen`
(not required), a `banner` and `CATEGORY_LEANBACK_LAUNCHER`.

---

## What our own users are telling us

The most valuable evidence in the whole sweep is our own issue tracker — 11 issues, 9 open, plus
10 open PRs. Sorted by how many people they cost:

| # | Issue | What it says |
|---|---|---|
| **#2** | *"very slow"* — 4 comments | The guide grid is laggy to move with the remote, reported independently on a **Xiaomi TV Stick 2nd gen** and a **Shield TV**. The most-reported thing our users say. PRs #5, #15 and #17 all chase it. |
| **#11** | *Fewer channels when multiple M3U entries share the same `tvg-id`* | A real data bug, and it is ours: the parser dropped every entry after the first with a given `tvg-id`, so a playlist that loads fine in TiviMate or Lumora showed fewer channels here — **and quality variants were lost the same way**, because the variant ladder is grouped by name, not by `tvg-id`. **Fixed in 0.12.2** — the identity of a stream is now the channel, the quality and the category. |
| **#10** | *Sync settings across local network* | Asked for specifically: "sync providers, playlist, recording folder… or save a settings config on the network that other devices could open manually". We shipped the LAN sync; the **source definitions and a settings file** are the missing part. |
| **#12** | *Interest in phone UI improvements?* | A contributor built a phone-focused fork with a `LayoutClass (TV / PHONE / TABLET)` `CompositionLocal`, a touch shell, portrait browsing and category filters, and asked whether it would be taken upstream. **We just shipped a TV/PHONE mode of our own** — the designs overlap but differ, and this is a live contribution to reconcile. |
| **#18** | *Add a provider from a local file* | Motivated by a real constraint: an old Huawei tablet that cannot reach modern TLS hosts, so a playlist on the device is the only way. |
| **#9** | *Problemas con series* | The series section does not appear for a Stalker provider. |
| **#13** | *Parsing error* | A JioTV M3U loads channels and EPG, but playing a channel gives a parse error. |
| **#3** | *unable to stream* | And a question we cannot answer: "do u have a discord server for this project?" |
| **#19** | *nfs recording* | A small, well-scoped request: record to an NFS share. |
| **#1** | *can u add dark mode?* | Closed — shipped. |

Note what is **not** here: nobody has asked for a multiview, a skin, or a chat feature. The
complaints are about the guide being slow, channels going missing, and finding the recording
button.

---

## What this says we should build

Ordered by (demand × evidence) ÷ effort. The first two are our own users' open issues, so they
are the cheapest to justify.

### Now — small, evidence-backed, and ours

1. ~~**Stop dropping channels that share a `tvg-id` (issue #11).**~~ **Done in 0.12.2.** The
   identity of a stream is now the channel, the quality and the category; a true duplicate is
   still one channel, and the first entry with a given id keeps its plain stream id so the
   favourites and renames a user already has survive. Verified on the stick: a playlist with
   two quality variants and two channels under one id went from **2 channels to 5**.
2. **Make the guide fast to move with the remote (issue #2).** The two users are on different
   cheap sticks, so this is silicon, not code — but "laggy when the d-pad moves" is also what
   a Compose recomposition on every arrow press looks like. Profile the scroll path before
   touching the grid.
3. **A settings file you can export and import (issue #10, TiviMate's 206-helpful complaint).**
   Sources, favourites, hidden channels, preferences, recording target — a file, and a second
   device that can open it. The LAN sync already moves the *state*; this moves the
   *configuration*.
4. **A provider from a local file (issue #18).** Small, and it is the only way out for a device
   that cannot fetch a playlist over modern TLS.
5. **Recording to NFS (issue #19).** We have SMB and USB; NFS is the same shape.
6. **Do not swallow the keys you do not know.** The worst remote bug in this category is an app
   that consumes a key it does not recognise, so the button the viewer is pointing at never
   fires. Our guide and home screen return `false` for unhandled keys; the player's immersive
   branch has a catch-all that does not. Cheap to fix, and it is the difference between a remote
   that works on every stick and one that works on the stick it was tested on.
7. **A stream that dies should come back, and say so.** We retry three times, 1.5 s apart, then
   give up and show an error. The community's most-repeated playback complaint is a black
   screen with no explanation and no recovery: keep retrying with a backoff, and say
   *reconnecting* while it does. The state is already there — it is the wording and the
   persistence that are missing.

### Next — the ones the whole category is bleeding on

7. **Pick the rendition the stick can actually hold.** We already have the ladder from the
   provider; nobody has ever asked the device. Probe `MediaCodecInfo` for the exact
   profile/level/frame-rate, check `isLowRamDevice()`, and remember the highest rendition that
   holds — then fall *down* the ladder on a playback failure instead of trying a different
   decoder. This is what the platform evidence says to do.
8. **Audio that behaves: normalisation/night mode, and an audio-sync offset with a useful
   range.** The two most-liked audio complaints in the study (11 and 207 helpful) are both
   about the level and the offset.
9. **An OSD when you change channel** — what is on, what programme, what time. Asked for by
   name in Televizo, Sparkle and TiviMate reviews, and it is the "set-top box" feeling.
10. **The live programme you are watching, rewound.** "True timeshift" — dragging the bar back
   on the *current* live show — is the most-upvoted request in the analogue project
   ([mytv-android#224](https://github.com/mytv-android/mytv-android/issues/224)), and its author
   says catch-up VOD is substitutable but this is not.

### Later — bigger, and worth doing deliberately

11. **The system guide.** Publish OpenTV's channels and EPG into `TvProvider` through a
    `TvInputService`, so the channels appear in Google TV's Live tab next to Pluto and Tubi.
    It is the one integration with real distribution value left in 2026 — and it is a lot of
    work for an app that is not on Play.
12. **Casting.** Google Cast via Media3 `media3-cast` is the only first-party content-casting
    path, and Play's TV criteria ask for it. Chromecast is asked for by name (43 helpful on
    Televizo; 2★ on Perfect Player). Miracast has no third-party sink API.
13. **Playback speed** (asked for in Televizo reviews and expected everywhere else).
14. **A list of only the channels that have catch-up**, the way the community asks for it:
    a separate item, not a menu inside each channel. The per-channel list already works; the
    thing that is missing is the index.
15. **The stream's own details** — URL, video and audio bitrate and codec — in a panel, because
    the alternative is opening another player to find out. It is also the fastest way to answer
    "why is this channel buffering and that one is not", which is a question users ask each
    other constantly.

### Deliberately not

- **Multiview / N-up.** The demand is real (the ROADMAP's own source puts dual-sports viewing at
  63%; TiviMate's multiview praise is among the most-liked quotes in that app's reviews, 86
  helpful) but it is concurrent decoders on a stick, and the hardware research says a €25 stick
  has no media performance class at all.
- **Server-backed sync.** We have the LAN path; a backend is the thing that killed Viewella —
  and the community's own objection is pointed: *"Cloud sync that asks for my email but then
  stores your favorites in plaintext on someone else's server. No thanks."*
- **Ads, subscriptions, "premium".** The category's users have learned to distrust them, and
  the community's real objection is not the ads but the **abandonment**: an app you paid for
  that stops working when the developer moves on.
- **Blocking other apps' ads.** Not possible, and not our claim to make. The honest claim is
  the one that can be checked: no ads inside OpenTV, no account, no server.

---

## Sources

**Store reviews** — Play listings for `ar.tvplayer.tv`, `ar.tvplayer.companion`,
`com.ottplay.ottplay`, `se.hedekonsult.sparkle`, `com.nst.iptvsmarterstvbox` (delisted),
`com.niklabs.pp` (delisted), `com.ottnavigator.iptvnavigator`, `de.cyberdream.iptv.player`,
`org.xbmc.kodi`, `com.mxtech.videoplayer.ad`, `org.videolan.vlc`, `org.jellyfin.androidtv`,
`com.google.android.youtube.tv`; AppBrain mirrors; iOS App Store review pages for the
IPTV Smarters clones.

**Issue trackers** — `opentvproject/opentv`, `xbmc/xbmc`, `kodi-pvr/pvr.iptvsimple`,
`kodi-pvr/pvr.hts`, `mpv-android/mpv-android`, `mytv-android/mytv-android`,
`jellyfin/jellyfin-androidtv`, `jellyfin/jellyfin-android`, `TeamNewPipe/NewPipe`,
`yuliskov/SmartTube`, `damontecres/Wholphin`, `Fredolx/open-tv`, `4gray/iptvnator`,
`NuvioMedia/NuvioTV`, `Dispatcharr/Dispatcharr`.

**Community** — r/IPTV (banned; r/IPTV_Help_Desk is mostly developers advertising their own
players, and is marked as such), r/AndroidTV, r/FireTV, r/TiviMate, r/SparklePlayer, r/NewPipe,
TROYPOINT (TiviMate wishlist and issue threads, and the A/B comparisons against Sparkle TV),
TIVIFY, IPTVTalk. Reddit's vote API was blocked, so the strength of a claim is judged by thread
and comment counts, and r/IPTV, r/FireTV, r/PerfectPlayer and r/DreamPlayer returned no
readable entries — the community evidence is therefore **thinner on "IPTV apps in general" than
on TiviMate specifically**, and it is marked that way throughout.

**Platform** — [Android TV navigation](https://developer.android.com/training/tv/get-started/navigation),
[ambient mode](https://developer.android.com/training/tv/playback/ambient-mode),
[TV Input Framework](https://developer.android.com/training/tv/tif),
[TV app quality guidelines](https://developer.android.com/docs/quality-guidelines/tv-app-quality),
[16 KB page sizes](https://developer.android.com/guide/practices/page-sizes),
[media3-cast](https://developer.android.com/media/media3/cast),
[Data Saver](https://developer.android.com/develop/connectivity/network-ops/data-saver),
[media performance classes](https://source.android.com/docs/compatibility/17/mpc),
[HDMI-CEC](https://source.android.com/docs/devices/tv/hdmi-cec),
[AOSP `ViewGroup.removeViewInternal`](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/ViewGroup.java),
[androidx/media #2972](https://github.com/androidx/media/issues/2972).
