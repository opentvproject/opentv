# OpenTV roadmap — feature gap analysis

This is an evidence-based plan for what to build next, benchmarked against the apps people
actually compare us to (TiviMate, IPTV Smarters Pro, Sparkle/OTT Navigator, Televizo) and
weighted by real user demand from the TiviMate wishlist forum, IPTV subreddits, and player
surveys. Sources are at the bottom.

The October 2026 sweep of what users complain about — 18 apps' store reviews, 16 projects' issue
trackers, the community forums, and the Android TV platform's own rules — is in
`docs/USER_COMPLAINTS.md`. It re-ordered the list below, and it is where the evidence for each
item lives.

## Where we already are

Table stakes are done: Xtream Codes + M3U, a live EPG guide grid, EPG name-matching against
free XMLTV guides, quality-variant grouping, favourites, categories, Movies + Series with
resume, phone-pairing setup, and a tuned ExoPlayer. Several of those (EPG name-matching,
quality grouping, QR phone-pairing) are things the paid incumbents do *not* do well — so the
list below is mostly about reaching parity on the features users treat as non-negotiable.

**Catch-up / archive is done too** — the feature this document used to lead with. Both
providers are covered: Xtream's `tv_archive` / `tv_archive_duration`, and the Kodi-standard
M3U tags (`catchup`, `catchup-source`, `catchup-days`, `catchup-correction`, `catchup-type`,
`timeshift`) in every mode Kodi defines — `default`, `append`, `shift`, `flussonic`, `xc`,
`vod`. The start time is written on the *provider's* clock, not the device's, because that
is the clock the panel names its archive files with; a panel that reports its timezone has
it remembered, and one that does not can be set by hand.

What is still missing on catch-up is a matter of depth, not of plumbing: the guide grid
starts at the current half-hour, so the past is reached through the channel menu ("Earlier
— watch from start") rather than by scrolling back in the timeline, and the archive is
bounded by the guide's own retention (a day), so a programme from three days ago is not
offered even where the provider still has it.

## What catch-up needed to get right

Across every source this was the single highest-demand missing feature, and OpenTV was
unusually well-placed to build it because it already parses XMLTV start/duration times.
**No new video decoding is involved** — an archived programme plays through the existing
player exactly like a movie. The work was reading the catch-up fields, building the right
URL, and a guide gesture to launch a past programme.

- **Xtream** signals it per channel in `get_live_streams`: `tv_archive` (0/1) and
  `tv_archive_duration` (days). Playback URL:
  `http://host:port/timeshift/USER/PASS/DURATION_MINUTES/YYYY-MM-DD:HH-MM/STREAM_ID.ts`.
- **M3U/XMLTV** providers use the Kodi-standard tags instead — `catchup` (`default` /
  `append` / `shift` / `flussonic` / `xc`), `catchup-source`, `catchup-days`,
  `catchup-correction`, with `{utc}`/`{start}`/`{duration}`/`{offset}` tokens to
  substitute. Supporting these is what makes it work for *everyone's* provider, not just
  this one.

Tuneline's write-up confirms most "easy" players skip catch-up not because it's hard to play,
but because it needs full EPG parsing **and** the provider's URL scheme — and we already have
the EPG parsing.

Two things turned out to matter more than the URL format itself:

- **Whose clock is it?** The Xtream panel names its archive
  `/tv_archive/{stream_id}/YYYY-MM-DD:HH-mm.ts` and looks that name up against *its* own
  timezone. A phone in São Paulo asking a Paris panel for "19:00" gets a recording from
  three hours earlier. The panel's `server_info.timezone` is read at login and kept.
- **How far back does the archive reach?** `tv_archive_duration` and `catchup-days` were
  being stored and never read, so every finished programme offered a link the provider
  would answer with a 404. The depth now rules programmes out, and says so instead of
  failing.

## Ranked gaps

Most of what this table used to list is shipped. What is actually still missing, in order:

| # | Feature | Who has it | Demand | Difficulty |
|---|---------|-----------|--------|-----------|
| 1 | **Deeper catch-up** — the guide showing the past, and a retention window wider than a day | TiviMate, Sparkle | "Missed it" is the whole point of the feature | Medium |
| 2 | **Buffering / decoder / stream settings** for problem streams | TiviMate, Televizo | Power users want knobs for bad streams | Easy-Medium |
| 3 | **Backup / restore** of settings — watch-history sync is shipped, a settings file is not | Televizo, TiviMate (partial) | #1 account request | Easy (local) |
| 4 | **Multi-view / N-up** — picture-in-picture is shipped | TiviMate (PiP), IPTV One (grid) | 63% want dual-sports viewing | Hard — deferred on purpose |

Shipped in the meantime, and no longer gaps: catch-up / archive, recording / DVR with series
rules, programme reminders, parental PIN, external-player hand-off, multiple sources at once,
the in-player audio / subtitle picker, sleep timer, aspect-ratio controls, channel numbers and
last-channel, channel hiding and reordering, unified search, local watch-history sync,
picture-in-picture, and the live transport bar (a scrub bar in the live player, a LIVE button
back to the edge, and a two-minute back buffer that can be rewound into).

Two honest caveats about that last one. The client-side window is **two minutes** and only
works on streams the player can seek in — for MPEG-TS, which is most IPTV, the past is the
provider's archive, and that is the catch-up feature above, not the buffer. And the *pause*
itself freezes the picture without a timeshift: the buffer does not keep growing while
paused, because ExoPlayer stops loading when it is paused. A real client-side timeshift
needs the stream to keep being recorded, which is the DVR the app already has.

Honourable mentions: an "on now" strip, favourites-only zapping, a per-channel catch-up
offset in the app's own UI (the playlist's own `catchup-correction` is honoured), and a
stream health / codec overlay.

### Also queued (from user feedback)

- **Local watch-history sync (device-to-device, no backend).** Viewella synced continue-watching
  and watched state through its servers; we do the same over the user's own LAN by extending the
  existing pairing server. Device A exposes a watch-state bundle behind a short code; device B
  pulls and merges newest-wins per item (resume positions, watched flags, favourites). No
  credentials sync. See "Local sync without a backend" below. Demand: direct r/Viewella ask.
- **Channel search in Live TV.** Search box over the channel list (the repository + query flow
  already exist; this is UI only). Later folds into unified live/VOD/series search.
- **A single Settings hub.** One entry point that gathers Providers, TV guide, Display &
  playback, Channels (hide/reorder), Parental PIN, Sync & backup, Playback/stream, and About —
  replacing today's scattered screens. See "Settings hub" below.

## Recommended next five

Re-ordered after the October 2026 sweep of what users actually complain about — the evidence is
in `docs/USER_COMPLAINTS.md`. The two at the top are our own users' open issues, which is the
cheapest possible justification.

1. ~~**Stop dropping channels that share a `tvg-id` (issue #11).**~~ **Done in 0.12.2.** The
   M3U parser kept the first entry per `tvg-id` and dropped the rest, so a playlist that
   loads fine in TiviMate or Lumora showed fewer channels here — and the quality-variant
   ladder was lost the same way, because variants are grouped by name. A stream's identity is
   now the channel, the quality and the category; a true duplicate is still one channel, and
   the first entry with a given id keeps its plain stream id so favourites and renames
   survive. Verified on the stick: 2 channels → 5.
2. **The guide, fast to move with the remote (issue #2).** Two users, on two different cheap
   sticks (Xiaomi TV Stick 2nd gen, Shield TV), say the same thing: moving around the EPG with
   the d-pad is laggy. It is the most-reported thing our users say, and the fastest guide
   navigation is the complaint every app in the category has. **Improved in 0.12.3** — the guide
   read the clock afresh on every pass, so every arrow press invalidated every visible row; the
   clock now comes from the screen's, and the rows' callbacks are remembered. Measured on a
   stick: ten presses down the guide redrew **77 rows before, 30 after**, the draw halved, and
   the input latency fell from 21 ms to 14 ms — one frame instead of two. **What is left:** the
   preview still re-tunes the stream 700 ms after the highlight settles and the codec takes
   about a second to come up, which is the tail the user feels as a freeze; and the p90 frame is
   still ~75 ms, which the measurements point at the vertical scroll rather than at the
   composition.
3. **A settings file you can export and import.** The LAN sync moves the *state* (positions,
   favourites, hidden channels, recordings); it does not move the *configuration* — the sources,
   the preferences, the recording target. That is what issue #10 asks for, and it is the
   most-liked complaint on the category leader (206 people). A file, and a second device that can
   open it.
4. **The rendition the stick can actually hold, and audio that behaves.** We have the ladder
   from the provider and nobody has ever asked the device: probe `MediaCodecInfo` for the exact
   profile and frame-rate, check `isLowRamDevice()`, and remember the highest rendition that
   holds — then fall *down* the ladder on failure rather than trying a different decoder, which
   is what the platform evidence says to do. With it, audio normalisation/night mode and an
   audio-sync offset with a useful range (the two most-liked audio complaints in the study).
5. **The remaining risky paths** — see the end of `docs/ARCHITECTURE.md`. The Room migrations
   are covered end to end (`DatabaseMigrationTest`, from the oldest shipped schema to the
   current one); what is left is `EpgRepository.sync` against a mid-stream failure,
   `replaceCatalogue` preserving favourites across a refresh, and `XtreamApi` against captured
   real panel responses.

Behind those, and bigger: **the system guide** (publish our channels and EPG into `TvProvider`
through a `TvInputService`, so they appear in Google TV's Live tab beside Pluto and Tubi — the
one integration with real distribution value left in 2026), and **casting** (Media3 `media3-cast`
is the only first-party path, and Play's TV criteria ask for it).

**Deferred on purpose:** full N-up multiview and *server-backed* cross-device sync are both
Hard (concurrent decoders / a backend we deliberately don't run) and shouldn't jump the queue.
Note this is distinct from the **local** LAN sync below, which needs no backend and is shipped.

## Local sync without a backend

The goal is Viewella's cross-device continue-watching **without** the servers that made it die
when Viewella went dark. The app already runs a small on-device HTTP server for phone-pairing;
this reuses it.

- **What syncs:** resume positions, watched/finished flags, and favourites. Keyed by provider
  content id (works when devices share a provider — the household case), falling back to a
  name+url hash. Each record carries an `updatedAtMillis`; merge is newest-wins per item.
- **What does not:** provider credentials. They stay per-device — the one thing worth typing
  twice, and the one thing we never want on the wire.
- **Flow:** device A → Settings → "Sync with another device" shows a short code / QR; device B
  enters it, connects over the LAN, and the two merge in both directions. Explicit and
  deterministic — no background daemon, no discovery magic in v1.
- **Later:** an optional "sync folder" (user's own NAS or cloud drive) for devices that are
  never awake at the same time.

## Settings hub

One screen, grouped, replacing today's scattered entries:

- **Providers** — add / edit / remove sources, per-source User-Agent, re-test a login.
- **TV guide** — the existing EPG feeds screen (provider guide + free XMLTV + custom URL).
- **Display & playback** — theme (system/dark/light), captions default, live guide preview.
- **Channels & categories** — hide, reorder, rename; manage favourites (the "Channel Manager"
  idea, done locally).
- **Parental controls** — PIN, hide adult categories (rides on the category model; also answers
  the r/Viewella "it's just porn now" complaint).
- **Playback & streams** — default aspect/zoom, buffer size, hardware/software decoder,
  hand-off to an external player (VLC / MX) for problem streams.
- **Sync & backup** — local device-to-device sync (above) and export/import a settings file.
- **About** — version + in-app update check, GPL licence, links to repo / issues / privacy, and
  the "built with AI, in the open" note.

## Sources

- TiviMate features — https://tivimate.co.com/app/features/
- TiviMate wishlist (TROYPOINT) — https://troypointinsider.com/t/tivimate-feature-wish-list/153806
- Top IPTV players 2026 (TROYPOINT) — https://troypoint.com/top-iptv-players/
- Xtream catch-up API — https://github.com/worldofiptvcom/xtream-codes-api-documentation
- Catch-up / timeshift explained (Tuneline) — https://tuneline.app/blog/catch-up-tv-timeshift-iptv-explained
- Kodi IPTV Simple catchup tags — https://github.com/kodi-pvr/pvr.iptvsimple/blob/Omega/README.md
- PiP / multiview demand (IPTV One) — https://www.iptv-one.app/en/blog/iptv-pip-guide-v2
- IPTV Smarters Pro features — https://iptvsmarterspro.co.com/tv/
