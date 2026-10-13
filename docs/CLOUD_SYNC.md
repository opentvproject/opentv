# Optional Supabase cloud sync

OpenTV works locally without an account. Settings → Cloud Sync is a separate, opt-in sync
service using a Supabase project you configure. Existing LAN, NAS, and Tailscale workflows
continue to work. No hosted OpenTV backend, provider subscription, or service key is bundled.

## What travels

| Category | Synced content |
| --- | --- |
| Providers | Authorized Xtream name, host, username/password, User-Agent, enabled state, live container, EPG URL, provider order |
| Playlists | Authorized M3U/M3U8 URL, name, EPG URL, User-Agent, enabled state, provider order |
| Favorites | Channel/movie favorites, provider-scoped series favorites, hidden channels and category keys |
| Watch history | Movie/episode resume positions, duration, last watched timestamp, profile identity |
| Profiles | Names, stable identity, renames, deletions (the last remaining local profile is retained) |
| Settings | Theme, channel layout, subtitles, guide preview video/sound, resume-last preference, player resize mode, live/movie/series visibility, channel ordering/custom names/EPG overrides, custom and built-in EPG feed configuration |

The category buttons control both publication and application. On the first connection, existing cloud content wins for matching identities before local-only
items are queued; this prevents a fresh device from publishing default preferences over an
existing account. Disabling a category does not
delete its cloud records; remote records remain in an encrypted inbox until enabled again.
Profiles should be enabled alongside watch history when creating profiles on another device.
Favorite curation follows OpenTV's existing global favorite model; positions are per profile.
Provider sort indices are preserved; this feature does not add a provider reordering editor.

Stalker/MAC authorization, Stremio add-ons, PINs, active profile selection, language, API keys,
SMB credentials, filesystem/USB paths, recording settings, hardware/decoder choices, last
tuned channel, downloaded streams, catalogs, EPG programs, and recordings stay local.
Cloud sync does not upload recordings or recording metadata. Existing NAS sync still shares
accessible NAS recording metadata through its own settings and protocol.

## Deploy your backend

Use a **dedicated project** and review the SQL before deploying. This repository does not
automatically create, select, or migrate a connected project.

1. Create a Supabase project. Enable the Data API for `public`.
2. Apply the versioned SQL in `supabase/migrations/` using the SQL editor, or install the
   [Supabase CLI](https://supabase.com/docs/reference/cli/introduction), explicitly link the
   intended project, and run `supabase db push`. Check `supabase link --help` and `db push --help`
   for your CLI version. Never point a local testing command at production.
3. In Authentication → Providers, enable Email/password. Keep email confirmation enabled;
   configure SMTP for reliable confirmation and recovery delivery. Complete confirmation in
   your email client, then return to OpenTV and sign in. Anonymous sign-ins are not required.
   Configure password policy and appropriate signup/auth rate limits.
4. Keep access-token lifetime reasonably short. The sync write/read RPCs additionally check
   the signed `session_id` against `auth.sessions` and the linked device registry, so local
   logout and unlinking stop subsequent RPC access even before JWT expiration.
5. Realtime → Publications: verify the six content tables and `sync_devices` are enabled in
   `supabase_realtime`. The migration adds them when that publication exists. Realtime is an
   acceleration hint; periodic reconciliation works without it.
6. Copy the **HTTPS project origin** and **publishable key** (`sb_publishable_…`) into
   Settings → Cloud Sync. All devices must use the same origin, including custom-domain choice.
   Never enter a `sb_secret_…`, service-role key, database password, or JWT signing secret.

The migration explicitly grants authenticated reads and RPC execution; it works with the new
Supabase projects that no longer automatically expose SQL-created tables. Every application
table has RLS and an owner-specific SELECT policy. Clients have no direct write grants.
Public RPCs are SECURITY INVOKER wrappers around private, narrowly scoped functions that
check verified user/session/device ownership on every operation. `opentv_private` must remain
outside the Data API's exposed schemas. Do not grant direct application-table writes.

For local validation, `supabase start` uses the checked-in local config, followed by
`supabase test db` to run `supabase/tests/cloud_sync.test.sql`. For a local stack with email
autoconfirm enabled, `python3 tools/test_cloud_api.py --publishable-key YOUR_LOCAL_PUBLIC_KEY`
checks real login, refresh, RPC ownership, unlinking, and logout against loopback endpoints.
It creates temporary users only in that disposable stack; reset the stack afterward. The Android client intentionally
requires HTTPS and a publishable key, so a local HTTP development stack is tested using SQL
and REST clients rather than an insecure release-client exception.

## First device and encryption recovery

1. Enter the project origin, publishable key, nickname, email, and password.
2. Create an account (confirm your email and then sign in), or sign in to an existing account.
3. The first device generates a random 256-bit account encryption key. The backend stores only
   a commitment to it, never the key. **Show / hide recovery key** displays the recovery key.
   Save it privately in a password manager. Screenshots are blocked on this screen.
4. Choose categories, then enable cloud sync. Initial synchronization is queued immediately.

The account password is not the provider-encryption password. Changing or resetting the
Supabase password does not recover the encryption key. On a new installation, sign in and
enter the saved recovery key, or pair with an existing device. Recovery validates the key
against the account/backend commitment before use.

If all authorized devices and the recovery key are lost, encrypted data is unrecoverable.
There is no support backdoor. Export/recreate local provider configuration before resetting
the backend account. A freshly generated replacement key cannot silently overwrite a keyring.

## Pair a second Google TV, Android TV, Fire TV, phone, or tablet

1. Configure the identical backend and sign in to the same account on the new device.
2. Select **Pair this device with an existing device**. It displays a ten-character code,
   valid for ten minutes, with a QR code containing only the lookup code for copying on a phone.
   The new device keeps an ephemeral P-256 private key in memory.
3. On a device that already has the encryption key, enter that code and select
   **Approve another device**.
4. **Compare the ten-character verification code on BOTH screens.** Finish on the new device
   only if they match. A mismatch means you should cancel and restart pairing.
5. The new device validates and saves the decrypted account key, consumes the request exactly
   once, and can then enable sync. Leaving the screen/process may require restarting pairing.

Both devices authenticate independently; pairing does not transfer login passwords, refresh
tokens, or provider credentials. The backend stores ephemeral public keys and an AES-GCM
wrapped encryption key. ECDH and HKDF-SHA256 bind the account, request, and both public keys.
The visual comparison protects against public-key substitution by the relay. The short lookup
code is scoped to an authenticated account, is not a key, and cannot decrypt the wrapped key.

## Scheduling, deletion, and disabling

Local Room invalidations and an explicit preference listener capture encrypted changes into a
SQLite outbox, including while offline. A WorkManager network job retries with exponential
backoff. Realtime hints schedule reconciliation; a 15-minute periodic job and launch recovery
cover missed messages. Android may delay jobs in Doze, standby, or after a force-stop. Opening
OpenTV or selecting **Sync now** resumes reconciliation.

Each item has a stable keyed UUID, a millisecond logical clock, a writer device UUID, and a
schema version. The greater `(clock, writer UUID)` wins. Tombstones use `deleted=true` in the
original table and are retained indefinitely so stale offline devices cannot resurrect old
state. Removing a provider removes that provider's cached catalog on receiving devices;
recording files are unaffected. Missing media is retained in the encrypted inbox and matched
after the ordinary catalog download. A temporary failure never empties local tables.

The server serializes sequence allocation and incremental pulls with an advisory transaction
lock so commit reordering cannot skip a record. Each page holds at most 200 records. Unchanged
items are not repeatedly uploaded. This is suitable for personal accounts; the global lock is
a deliberate first-version scaling limit for large multi-tenant deployments.

Disable cloud sync to stop network jobs and live subscriptions. Local providers and user data
remain usable, and re-enabling reconciles edits. Sign out also unlinks this device, removes the
local session and account key, and leaves local application data. Save the recovery key first.
Removing another linked device stops that session's future sync access. Signing in again with
account credentials creates a new authorized session. Remove/reset account credentials if a
device or password is compromised.

## Security and current limitations

- All content payloads, including names, credentials, stream/playlist/EPG URLs, and preferences,
  use AES-256-GCM with fresh 96-bit nonces. AAD binds owner/backend, table, UUID, clock, writer,
  deletion flag, and schema. Stable identifiers and fingerprints use keyed HMACs, not public
  hashes of sensitive URLs. The backend sees account/device metadata, sizes and edit timing.
- Android Keystore wraps locally persisted sessions and encryption keys. Cloud state is excluded
  from cloud backup and device transfer because Keystore keys are tied to the installation.
  A reinstall requires login and pairing/recovery. Existing on-device provider storage retains
  OpenTV's current storage behavior.
- Device removal cannot erase keys, content, or recordings already copied to that device.
  Automatic rotation/re-encryption of the account key is not implemented. This release supports
  access revocation, not cryptographic erasure of previously shared material.
- Realtime availability is not guaranteed; the durable reconciliation path is authoritative.
  Server outage, deleted auth sessions, and inaccessible encrypted payloads retain local data.
- Keep device clocks accurate. Writes more than five minutes ahead of backend time are rejected;
  playback updates use their saved viewing timestamp, so delayed capture cannot replace newer
  progress. Other edits and deletions use edit/capture time with a deterministic device tie break.
- LAN/NAS sync retain their preexisting additive merge semantics. Explicitly importing an old
  bundle through those workflows can constitute a new local addition after a cloud tombstone.
- Only M3U/M3U8 and Xtream providers are supported in cloud sync. Do not share subscriptions
  beyond their authorization or provider connection limits. Cloud sync grants no playback rights.
- UI strings currently use English fallbacks in the other existing locales. Physical TV D-pad,
  OEM Keystore, and live hosted email delivery should be included in release acceptance testing.

API references checked for this implementation: [API keys](https://supabase.com/docs/guides/getting-started/api-keys),
[Auth sessions](https://supabase.com/docs/guides/auth/sessions),
[Realtime protocol](https://supabase.com/docs/guides/realtime/protocol),
[RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), and
[current breaking changes](https://supabase.com/changelog?types=breaking-change).
The client does not retrieve the removed anonymous OpenAPI endpoint, does not use deprecated
`auth.role()`, and negotiates modern TLS through the existing supported OkHttp version.
