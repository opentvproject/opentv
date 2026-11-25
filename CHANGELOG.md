# Changelog

## 0.12.0

- **Playback diagnostics: find out why it buffers, and get moved to a stream that doesn't.**
  Every channel you watch is now measured in the background — how long it took to start, how often
  it buffered, how often the provider dropped the connection, whether the picture jumped back and
  replayed, whether the stream arrived with pieces missing. **Settings → Playback diagnostics**
  turns that into plain answers: a neutral internet speed test, a response test of each provider's
  server, a per-provider scorecard, and a verdict that says whose side the problem is on ("your
  internet is fine — this provider is not keeping up"). **Test every copy** downloads a few
  seconds of each copy of a channel across all your providers and ranks them. A **VPN
  comparison** puts the same measurements side by side with your VPN off and on, so you can see
  whether your ISP is throttling the provider (OpenTV does not include a VPN; it measures yours).
  In the player, a **Stats** button shows the live numbers, and when a stream keeps buffering
  OpenTV offers — or, if you turn it on, automatically switches to — the copy of that channel
  with the best record on this device. Everything stays on the device; nothing is uploaded.
- **Optimise streams: open the copy of each channel that actually plays.** Providers carry the
  same channel several times (4K, FHD, HD, SD), often on servers of very different strength.
  **Settings → Playback diagnostics → Optimise streams** tests each copy for a few seconds —
  your favourites, the categories you choose, or everything — and from then on a channel opens on
  the best quality that keeps up, passing over copies that buffer or do not open. It tests one
  stream at a time and pauses whenever you watch or record, so it can also **run every night at
  3 am**. The screen lists every channel that now opens differently and why; **Use the results**
  turns it all off again. What you watch feeds the same record, and the buffering rescue uses it
  to pick a copy that has been measured rather than guessed at.
- **Fix my streams: one button, one answer.** At the top of Playback diagnostics, one press tests
  your internet, each provider's server and every copy of the channels you watch most, moves each
  of those channels onto the copy that plays best, and finishes with a single sentence saying what
  it found and whose side any problem is on.
- **A score out of 100 beside every channel that has been tested or watched.** It is how much
  faster the stream arrives here than it needs to: 80 and over (green) plays with room to spare,
  50–79 (amber) may buffer, under 50 (red) struggles. No score means the channel has not been
  measured yet. Each copy in Playback diagnostics carries the same score.
- **Buffering rescue is now on by default, and you can undo it.** When a channel keeps buffering
  OpenTV moves you to a steadier copy on its own and says so; press OK within ten seconds to go
  straight back. Turn it off under *When a stream struggles* to be asked first instead.
- **OpenTV learns when a copy is weak.** Measurements are now also kept by time of day (morning,
  afternoon, evening, night), so a copy that is fine at lunchtime but falls over on a busy
  evening is passed over in the evening and still used at lunchtime.
- **Weekly provider report card.** Each provider gets a letter, A to E, for the week's actual
  viewing — buffering per hour, how fast channels start, dropped connections — next to how it did
  the week before.
- **Test every copy of any channel, not just the last one you watched.** The copy test now starts
  on the channel you watch most, and **Choose a different channel** searches for any other by
  name. The copy marked **Best** is the highest quality that arrives with room to spare; it used
  to be whichever had the most headroom, which was always the SD one.
- **The speed test now shows what your connection can play.** Under the result is a bar for SD,
  HD, Full HD and 4K: a full green bar means that quality should stream perfectly, amber means it
  fits but may buffer when the connection is shared, red means too slow. The bars climb live
  while the test runs.
- The speed test's response time and jitter are now measured as plain network round trips. They
  used to include the test server's own thinking time, which made a good connection look
  "unsteady".
- The stats overlay no longer says "Unsteady" for the second or two a channel takes to start; it
  says "Starting…" until there is a picture to judge.
- **A channel banner when you zap, and full programme detail on Info.** Changing channel now
  flashes a compact banner — channel number, logo, name, what's on with its times and a progress
  bar, and what's next. It appears the instant you press the key, before the picture loads and
  even if the stream fails, so you can keep zapping without waiting for each channel to come up.
  Press **Info** (or the Info button on the player bar, for remotes without one) to expand it into
  the full synopsis, series/episode and genre. Channel surfing is also debounced now, so holding
  channel-up only opens the channel you settle on instead of starting a stream for every one you
  pass through.
- **Download films and shows for offline viewing, in a new Library.** Open a film or episode and
  choose **Download** — it saves to your recording storage (internal, USB or NAS) and appears in the
  new **Library** tab, which brings your downloaded **Films** (grouped by genre), **TV Shows**
  (grouped by series) and your **Recordings** together in one place — each with artwork, year, genre
  and download date. Downloads run in the background with a progress bar, keep their resume point,
  and the poster is saved too so the Library looks right even fully offline.
- **Smoother NAS playback.** Recordings and downloads stored on a NAS now reuse a single SMB
  connection while you watch, instead of reconnecting on every seek — fixing the constant buffering
  that random-access seeking a movie over the network used to cause.

## 0.11.10

- **Fire TV: app row fix, take two.** v0.11.9 replaced the placeholder artwork but the stick still
  wouldn't list OpenTV. Fire OS reads a sideloaded app's launcher icon straight out of the APK by
  path, and it only looks in `res/drawable…` folders (a known Fire OS 7/8 quirk that leaves apps
  with icons under `res/mipmap…` broken or missing). The icon now lives in `drawable-*dpi` and is
  referenced as `@drawable/ic_launcher`, and the release build keeps real resource paths instead
  of aapt2-shortened ones, so the stick can find it. After updating, uninstall OpenTV, restart the
  stick and install it again from Downloader so the launcher re-reads the APK.

## 0.11.9

- **OpenTV now shows up on the TV home screen.** The app was built with a placeholder vector
  banner and an adaptive-only (XML) launcher icon, which the Fire TV and Google TV launchers
  either rendered as a blank dark tile with no name on it or failed to render at all, so a
  sideloaded install only appeared under Settings → Applications. The APK now ships a proper
  320×180 **banner with the OpenTV wordmark** (Google TV / Android TV home screen) and plain
  **PNG launcher icons at every density** (Fire TV app row, phones), which is what both
  launchers expect. Install the update and the OpenTV tile appears in the apps row — on the
  new Fire TV layout it lands at the end of the row, so hold Select → Move to bring it forward.

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
