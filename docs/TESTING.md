# Testing OpenTV

There is no QA department; there is you, your provider and your telly. This page makes that
count. The app logs everything it does, so a bug report with a log attached is usually a fix
within the day — and a "works for me" without one is usually nothing.

## The quick loop (with a Mac/PC on the same network)

```bash
bash test-run.sh        # reinstalls, launches, records to testrun.log, shows a checklist
```

## What to test after any change

| # | Check | Pass looks like |
|---|-------|-----------------|
| A | Quality grouping | One row per logical channel; "N qualities" badge on grouped ones |
| B | Guide feeds | Gear → enable a guide for your country → "matched N of M" reported |
| C | Now/Next | Matched channels show programme + progress bar, not "No guide information" |
| D | Playback | Channel plays; quality switch top-right; switching mid-stream keeps playing |
| E | **Wrong matches** | No channel shows another channel's programmes — the worst bug this app can have. Report the exact channel name |
| F | State survives | Favourites/hidden survive a refresh and an app restart |
| G | Fast switching | Holding channel-change never wedges playback (one tune when you stop) |

## Recent feature checks

Run the automated unit tests before device checks:

```bash
./gradlew testDebugUnitTest
```

| Area | Check | Pass looks like |
|---|---|---|
| Category settings | Hide/reorder Live TV, Movies and Shows categories, including with multiple providers; leave and reopen settings | Visibility and order persist, source-specific categories stay separate, and hidden entries remain manageable |
| Movies/Shows navigation | Switch provider, All, and individual categories from the left rail | The selected category grid belongs to the selected provider; returning to All restores home shelves |
| My Views | Play a movie/episode, change profiles, use only the remote to focus and clear one card, clear a show, then clear all | Remove buttons have a clear d-pad focus state; history is profile-specific; >95% is Watched; clearing a show removes its episode entries; clearing all is confirmed |
| Next episode | Play an episode with a following episode, then one at the end of a series | Prompt appears in the final minute only when a next episode exists; Play Next advances immediately; Dismiss prevents auto-advance |
| Seeking | Drag the VOD timeline and use remote left/right while it has focus | Drag seeks to the selected position; remote directions seek in 10-second increments |
| External subtitles | Choose SRT, VTT, SSA/ASS or TTML for a movie and an episode | Subtitle loads and playback resumes near the current position; unsupported formats show a message; a device without a file-picker app does not crash |
| Display | Change language, size and color palette; test both Light and Dark | Language reloads correctly; size applies across screens; each palette remains readable in both modes and preferences survive restart |
| Memory | Toggle live preview off, visit Movies/Shows, then navigate away and back | No preview stream runs while disabled; category grids remain responsive; feeds repopulate when returning |

## Reading the log

Interesting lines and what they mean:

- `Matcher: N of M channels have a working guide` — the headline EPG number.
- `Feed 'X' failed: <reason>` — a guide source failed; the same text shows in Guide settings.
- `Source N: ... channels` — catalogue sync result.
- `AndroidRuntime: FATAL` — a crash; everything above it in the log is the story.

## Reporting

Open a GitHub issue with: device + Android version, what you did (checklist letter is
enough), what happened, and the relevant chunk of `testrun.log`. **Strip your provider's
URL/username/password first** — logs deliberately never print credentials, but check what
you paste anyway.
