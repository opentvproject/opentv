# Changelog

## 0.12.4

- **The source code link points at this build.** The About screen pointed at the upstream
  repository — so an APK built from this fork was offering the corresponding source of somebody
  else's code. The GPL asks that anyone given the binary can also get the source it was built
  from, and that link was the one place the promise was made. It points here now, and so do the
  bug tracker, the releases the updater checks, and the release notes of every APK, which name
  the tag the build came from.
- **The licence says what it means.** Every source file said "v3.0 or later"; the About screen
  said "v3.0". Both say the same thing now, and the free-software notice the licence asks for
  is on the About screen and in every set of release notes — along with where the complete
  corresponding source is.
- **This repository says what it is.** The README claimed the project was not a fork, which was
  true of upstream and not of this. It names the upstream now, keeps the attribution, and spells
  out what a fork that ships builds owes. `NOTICE` carries the attribution, the dependency
  licences and the trademark note; `CONTRIBUTING` says what a contribution is licensed under
  and what the header on a new file has to be.
- **And the donations still go to the upstream maintainer**, who wrote the original. The app
  says so now, under the QR code, rather than leaving you to work it out.

## 0.12.3

- **The guide keeps up with the remote.** Moving down the channel list used to redraw every
  programme block on screen, because the guide read the clock afresh on every pass — so every
  arrow press changed a value every row depended on, and every row had to be redone. The clock
  now comes from the screen's, the rows' callbacks are remembered, and the preview's video
  surface is left alone when the player has not changed.
  On a stick, ten presses down the guide redrew **77 rows before, 30 after**; the time to draw
  a frame halved, and a key press is now answered within a frame instead of two.

## 0.12.2

- **The channels you have are the channels you see.** A tvg-id is the guide's name for a
  channel, not a stream, and providers reuse one id for every quality of a channel — and
  sometimes for two channels that have nothing to do with each other. The playlist reader
  treated the id as the stream, so everything after the first entry was quietly dropped: a
  playlist that loaded fine in other players came out with **fewer channels here**, and the
  quality switch had nothing to switch between. The identity of a stream is now the channel,
  the quality and the category, and a true duplicate is still one channel.

## 0.12.1

- **A television is a television.** The app used to guess what it was running on and be wrong
  about it. Now it asks: **TV** or **Phone / tablet**, on the first-run screen and in
  Settings → Display & playback, and the answer is remembered. TV mode browses categories as
  a plain list instead of a timeline, keeps the picture from being held awake so the set can
  be switched off, and drives the whole interface from the d-pad. The phone layout is
  untouched.
- **Turn the television off without stopping the channel.** On a television the app no longer
  holds the picture awake, so the set's own screensaver takes over — and with it, the channel
  stops, the way a television does when it goes dark. A set that keeps talking to an empty room
  is not a set. The viewer who comes back finds the channel still on it.
- **And a button to turn the set off for good.** The player has a **Turn off** button now:
  the stream stops, the app closes, and the box is left idle. It leaves a bookmark behind, so
  opening the app again finds the channel you were on — the way a set-top box does, rather than
  dropping the viewer on the guide.
- **The remote can reach the buttons.** A clickable row is not a focus target, so on a
  television the arrow keys walked straight past the transport bar, the category rail, the
  channel column in the guide and every row of every menu — the only thing OK could press was
  whatever happened to hold the focus, which was play/pause. All of them take the focus now,
  the bar starts on the guide rather than on pause, and up/down stops changing the channel
  while the arrows are walking the bar.
- **And OK actually presses them.** `focusable()` before `clickable()` hides the clickable's
  own key handling from Compose, so pressing OK on a button did nothing at all. The order is
  now the other way round, everywhere — including the channel list in the player, the pickers
  (quality, subtitles, audio, aspect) and the steppers in the recording settings.
- **The buttons that arrive late are buttons too.** The chips that only exist once the guide
  has caught up — rewind, forward, quality — were the ones the arrows walked straight past,
  because the border around a focused button used to be *added* to the button rather than
  drawn on it, and a button whose chain changes under it loses the focus. The border is
  drawn now, and the whole bar — the live player's and the film's — is walkable from end to
  end, right up to the power button.
- **The guide takes the remote.** The up and down arrows walked the preview pane above the
  guide and stopped dead at the guide's first row — the row that had the focus was also lost
  every time the line-up was rebuilt by a sync. The arrows now move between channels (in the
  grid, in a folder's list, and in the player's channel list), the preview follows, and the
  focus is put back where it was after a rebuild.
- **The catch-up list stopped covering the guide.** In the player, the "earlier programmes"
  panel was drawn over the whole guide and took the d-pad with it, so opening the guide
  showed the list instead of the guide. They are separate overlays now, one or the other.
- **The channel list is the whole provider.** "Canais" in the player showed the folder you
  happened to be browsing — a dead end the moment you wanted to change channel. It is every
  channel of the provider now, and the arrows walk it.

## 0.12.0

- **Catch-up for M3U playlists.** Xtream providers have been able to keep an archive for years;
  M3U providers use the Kodi tags (`catchup`, `catchup-source`, `catchup-days`,
  `catchup-correction`, `catchup-type`, `timeshift`) and every mode Kodi defines — `default`,
  `append`, `shift`, `flussonic`, `xc`, `vod`. Both work now. The programmes that already aired
  are listed in the channel menu under **Earlier — watch from start**, and in the player itself,
  over the picture, without leaving what you were watching.
- **Catch-up asks the provider for the right hour.** The Xtream panel names its archive
  `/tv_archive/{stream_id}/YYYY-MM-DD:HH-mm.ts` and looks that name up against *its* timezone —
  so a phone in São Paulo asking a Paris panel for "19:00" was getting a recording from three
  hours earlier. The panel's own timezone is now read at login and kept, and a playlist with no
  panel to ask can have one set by hand.
- **Catch-up knows how far back the archive goes.** The archive depth (`tv_archive_duration`,
  `catchup-days`) was stored and never read, so every finished programme offered a link the
  provider would answer with a 404. The depth now rules programmes out, and says so instead of
  failing. A 404 also says which of the two it is now: a provider whose server is down, or an
  address that is wrong.
- **A live transport bar.** The live player had no way to scrub: play, pause and fifteen-second
  jumps, and the two-minute buffer the player was already keeping was unreachable. There is a bar
  now, a **LIVE** button back to the edge, and — where the stream allows it — the past to rewind
  into. The transport row scrolls, so the last chip is no longer squeezed into a column of
  letters.
- **The guide, in the player.** Watching a channel and wanting to know what else is on is the
  most ordinary thing a viewer does, and making it leave the player to find out was a small
  hostility. The guide is now over the picture (the remote's GUIDE key opens it, as do two new
  buttons), and it can page back to yesterday.
- **Guide history.** How much guide to keep is yours to choose — a day, three or seven. The
  provider is asked for exactly what will be kept, so a programme from last week is still there
  to watch from the archive.
- **Playback knobs.** A buffer size for the box that is short of memory or on a bad line, and
  an escape hatch for the boxes whose hardware decoder stutters or shows a green picture: prefer
  the software decoder instead.
- **A guide that was never there.** A panel serves the guide it generated for one window, and
  answers an empty file — with no error — for any other. The app was asking for a window some
  panels do not have, so their guide was simply absent. The windows are tried in turn now, and
  the first one with a guide in it wins.
- **A few things that were quietly wrong.** The built-in US and Canadian EPG guides pointed at
  files their host had renamed, and answered 404; the settings button fell off the bottom of the
  side rail on a phone in landscape; the provider chips on the first-run screen did the same.

## 0.11.8

- **Edit a saved provider.** Settings → Providers now has an **Edit** button on each provider, so you
  can fix a server address, password, MAC or name in place instead of removing and re-adding it —
  your favourites, hidden channels and per-provider settings are kept.
- **Stalker portals: fewer dropped sessions.** A portal that occasionally rejected a call
  mid-session (channels loading only "sometimes") now gets one automatic re-handshake and retry, so
  an intermittent refusal becomes a silent re-auth rather than an empty channel list or a channel
  that won't tune.
- **Player controls stay clear of the system bar off-TV.** On phones and tablets the bottom control
  bar (subtitles, audio, quality, aspect ratio) could be drawn under the navigation bar and cut off;
  it's now inset above it. No change on Android TV.

## 0.11.7

- **Stalker portals: closer to a real box.** Building on v0.11.6, OpenTV now sends the rest of the
  identity a real MAG set-top box presents at login — the auth token in the session cookie (not just
  the header), a hardware-version hash, a timestamp and the box's API signature — and it probes a
  couple more portal paths. Some Ministra/Stalker panels only return the channel list when they see
  all of this, so lines that connected but showed no channels have a better chance of loading. If a
  portal still comes up empty, please open an issue and say which panel software it runs.

## 0.11.6

- **Stalker portals: full set-top-box identity.** OpenTV now authenticates to Stalker/Ministra
  portals the way a real MAG box does — sending the device serial, `device_id`, a signature and a
  metrics blob derived from your MAC, instead of the MAC on its own. Many portals won't hand over
  the channel list or stream links to a box that only presents a MAC, which is why some lines that
  worked in other apps came up empty in OpenTV. This lets those portals authorise OpenTV. If a
  portal still fails, please open an issue and say which one — it helps a lot.

## 0.11.5

- **Add a provider from your phone or laptop.** The "Manage on phone or laptop" web page now has an
  **Add a provider** section — pick Xtream, M3U or Stalker portal, fill it in with a real keyboard,
  then Test and Add. Channels load straight onto the TV, so you never have to type a server address
  or a MAC on the remote again.
- **Fixed the "camera won't scan it?" web address.** The manage screen was printing that link without
  its access token, so typing it into a browser hit a dead "Not found". It now shows the full working
  URL, and the token is short enough to type.


## 0.11.4

The big DVR release. Recordings behave like Sky Q, you can record straight to a NAS, watch a
recording while it's still taping, and the whole interface now speaks 30 languages.

### Recording & DVR

- **Watch a recording while it's still recording.** Start playing something the moment it begins
  taping — OpenTV reads the growing file straight off disk, so it costs **zero extra connections**
  to your provider. On a single-stream account that's the difference between "wait until it's
  finished" and "watch now". Fast-forward and rewind work within whatever's been taped so far.
- **Record straight to a NAS.** Point recordings at a network share over SMB (Settings →
  Recordings → NAS, or set it up from the phone/laptop web manager) so a cheap box isn't boxed in
  by its own storage — and every OpenTV in the house can reach the same recordings.
- **Single-connection auto-switch.** On a one-stream provider, if a recording is due to start on
  another channel, OpenTV moves the screen onto that recording as it begins — with a **30-second
  warning** first and a *Keep watching* button if you'd rather not. No more "why did my live stream
  just cut out?" (Recordings → Recording behaviour → *Auto-switch when recording starts*.)
- **Clash handling with multi-provider fallback.** Book two overlapping recordings and, if more
  than one of your providers carries that channel, OpenTV records each from a **different provider**
  so neither is cut. On a single provider it flags the clash instead.
- **Recording padding.** Start each recording a minute early and run a few minutes past the listed
  end, so a late kick-off or an overrun isn't clipped. Adjustable per side.
- **Series links show the next episode.** A series link now tells you exactly when it next records.
- **Storage readout.** See how much space recordings use and how much is left.

### Live TV

- **Pause & rewind live TV** *(experimental, opt-in).* Holds the last couple of minutes so you can
  pause and jump back. Off by default — it uses more memory.

### Languages

- **30 languages.** The whole interface is translated into Spanish, French, German, Italian,
  Portuguese, Dutch, Polish, Russian, Turkish, Arabic, Simplified Chinese, Japanese, Korean, Hindi,
  Swedish, Danish, Finnish, Norwegian, Czech, Greek, Romanian, Hungarian, Ukrainian, Indonesian,
  Thai, Vietnamese, Bulgarian, Slovak, Croatian and Persian. Settings → Language (each listed in
  its own name), or leave it on *System* to follow your device.

### Fixes & polish

- Smoother watch-while-recording, especially from a NAS: playback now keeps a buffer behind the
  live edge so a network share doesn't stutter at the write head.
- Back never lands you on a stray live channel or drops you out by accident — coming back from a
  recording returns to your recordings, and Back on the home screen asks before it exits.
- Password fields are masked, with a show/hide toggle.
- Recording stability: NAS write timeouts and reconnect handling, so a quiet network doesn't
  silently freeze a capture.

### Notes

- Everything stays on your device — provider logins never leave the box.
- Free and open source (GPL-3.0): https://github.com/opentvproject/opentv


## 0.10.0

- **Movies & Shows, redesigned.** A Plex-style layout with big artwork, cast & director, "more with
  this cast" rows, and cleaned-up titles (no more "NF -" / "(KR)" junk). Optional: add your own free
  TMDB key in Settings → Metadata and OpenTV fills in any posters, backdrops, cast or synopses your
  provider left blank — the key stays on your device.
- **No more stuck "Loading movies & shows".** The movies/series catalogue is cached, so it loads
  instantly on later launches instead of re-downloading every time — and the live preview no longer
  stutters while it loads.
- **Record to a USB / external drive.** Settings → Recordings → USB, pick a folder on a plugged-in
  drive, and recordings write straight there (no storage permission needed) and play back in-app.
- **Recordings screen overhaul.** Scheduled recordings show *when* they'll record and sit in their
  own "Scheduled" section; failed ones have a **Retry**; each is tagged to a profile; and bookings
  re-arm on every launch so a force-stop or app update can't quietly drop them.

## 0.9.0

- **Manage your channels from a phone or laptop.** A new "Manage on phone or laptop" screen in
  Settings shows a QR code and a link — open it on any device on your wifi and you get a proper web
  page to browse, **rename**, hide, favourite and **reorder** channels with a real keyboard and
  mouse. Changes apply to the TV instantly. Local-only: the TV is the server, nothing touches a
  cloud.
- **Recording keeps going when you leave the app or the box sleeps.** OpenTV can now ask Android to
  exempt it from battery optimisation (Settings → Recording → "Recording in the background"), which
  is what keeps recordings running in standby and when you switch away — swiping OpenTV out of
  recents no longer stops a recording either.
- **Rename channels.** Give a channel your own name; it sticks and survives a guide refresh (from
  the web manager for now).
- **Tidier update notes.** The "update available" prompt shows a clean summary of what's new instead
  of build metadata.

## 0.8.0

- **No more endless "Loading channels".** If a provider fails to load or comes back empty (a wrong
  login, a dead server), the guide now shows a clear error with **Retry** and **Provider setup**
  buttons instead of spinning forever.
- **Set up on a phone without the QR dance.** Installing on a phone now shows the login form
  directly so you can type your details in; the QR code is only offered on a TV, where typing is the
  painful part.
- **Paste into the setup fields.** The URL, username and password fields — and the on-screen
  keyboard — now have a **Paste** button, so a copied Xtream line drops straight in.
- **MPEG-TS or HLS, your choice.** Each Xtream provider now has a **Stream format** toggle. If your
  panel only serves `.ts` and channels wouldn't play, switch to MPEG-TS and they will. Defaults to
  HLS, so nothing changes unless you need it.
- **Parental controls scrolls.** The hidden-categories list is reachable to the bottom now, however
  many categories your provider has.
- **A real Channel Manager.** Settings → Channels is now a browsable, two-pane manager: pick a
  category on the left, see its channels on the right, hide or favourite each one — including
  hidden channels so you can bring them back. With more than one provider, a **source filter** keeps
  them separate instead of merged into one list.

## 0.3.0

- **Profiles.** Add local profiles (just a name — no accounts) from the person icon in the top
  menu, and switch between them. Each profile keeps its own resume points and watched state, so
  your half-watched film doesn't show as watched on someone else's profile. Existing resume points
  become the default "Me" profile's — nothing is lost.
- **Continue Watching.** Movies and Shows now open with a "Continue watching" shelf for the active
  profile — pick up your last film or episode where you left off, with a progress bar on each.
- **Local sync (no servers).** Settings → Sync copies your continue-watching between two OpenTV
  devices over your own wifi: one device shares behind a six-digit code, the other receives. It's
  local-only — nothing leaves the house — the household answer to the cloud sync that died with
  Viewella.

- **Proper player controls.** A single control bar slides up from the bottom with play/pause,
  rewind/forward (where the stream allows it), and pickers for **subtitles, audio track, quality
  and aspect ratio**. It hides after a few seconds and any remote button brings it back —
  nothing is ever left painted permanently over the picture.
- **Captions that actually show.** Subtitles are now chosen from the real tracks in the stream
  (and can be turned off), instead of a blind on/off that often left the renderer enabled but
  nothing on screen. Audio-track switching works the same way for multi-language streams.
- **Page through the guide.** Earlier / Now / Later buttons above the guide move the timeline
  forward and back, so you can see what's on later this evening with a remote.
- **Live preview in the guide.** The highlighted channel now plays, muted, inside the preview
  pane. It uses a single player that stops the instant you go full-screen or leave the guide —
  no second decoder — and it can be turned off in Display & playback for older boxes.
- **Channel manager.** Settings → Channels: search for a channel and hide it from the guide (or
  favourite it). Hidden channels drop out of Live TV and search but stay here, greyed, so you can
  bring them back. Hiding covers every quality variant of a channel at once.
- **Parental controls.** Set a 4-digit PIN and mark categories as hidden — they drop out of the
  guide, All channels and search until you unlock them (and with a PIN set, the parental screen
  itself is locked). In Settings → Parental controls.
- **Unified search with an on-screen keyboard.** Search now has its own screen with a d-pad
  keyboard (no more "type on your phone") and covers **channels, movies and shows** together, with
  live results as you type. Reachable from the top menu.
- **Loading indicators for Movies and Shows.** Both now show a spinner while the catalogue is
  syncing instead of a premature "nothing here".
- **Settings and Search in the top menu.** Both now live top-right, next to Live TV / Movies /
  Shows, instead of being buried behind the guide's icons.
- **A single Settings screen.** One Settings hub — Providers (add / remove / re-test sources), TV
  guide, Display & playback, Parental controls, and About (version, a manual update check,
  licence and links).
- **Dark / light / follow-system.** A new appearance setting. TV still defaults to dark; pick
  Dark or Light to force it on any device.
- **Quality picker only when there's a choice.** No more "Quality: Standard" on single-quality
  channels; the picker appears only when a channel actually has multiple qualities.
- New **Display & playback** settings screen (the sliders icon in the guide).

## 0.2.0

- **Guide detail pane** — highlighting a channel now shows its logo, what's on now with
  start/end times and a live progress bar, a synopsis when the guide carries one, and what's
  on next. Press to watch full-screen.
- **Live progress fill** on the current programme in the guide grid, and a focus highlight on
  the highlighted channel.
- **Fix: never asks for your provider again.** A cold launch could briefly decide "first run"
  before your saved sources had loaded and drop you on the setup screen — it now waits for the
  sources to load before deciding what to show.
- **Fix: no second video decoder on the guide**, which was locking up low-end boxes (a
  Chromecast could freeze). The detail pane is logo + guide info; the one decoder lives
  full-screen.

## 0.1.0

First public release.

- Xtream Codes and M3U/M3U8 sources
- Streaming XMLTV guide parsing with incremental, non-destructive sync
- Live TV playback on Media3/ExoPlayer with IPTV-tuned retry behaviour
- Movies and series catalogue
- Favourites, categories, search
- In-app self-update for sideloaded installs
- Single APK for Android TV, Fire TV, phones and tablets
