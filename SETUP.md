# Getting this onto GitHub and onto your TV

One-time setup. After this, every tagged release builds itself and appears on the install page
automatically.

> This repository is a fork of [opentvproject/opentv](https://github.com/opentvproject/opentv);
> see [NOTICE](NOTICE). The steps below are the upstream ones, with this fork's URLs — the
> licence obligations that come with publishing builds are in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## 1. Create the repository

On [github.com/new](https://github.com/new):

- **Name:** `opentv`
- **Visibility:** Public — GitHub Actions is free on public repos, and the whole point of the
  project is that people can read it
- **Do not** tick "Add a README", ".gitignore" or "Choose a license" — this repo already has
  all three, and an initialised repo makes the first push awkward

## 2. Push it

From the unzipped `opentv` folder:

```bash
cd opentv
git remote add origin https://github.com/T3lo5/opentv.git
git push -u origin main
```

The repo already has an initial commit, so there is nothing to stage.

If git asks for a password, it wants a **personal access token**, not your account password —
GitHub stopped accepting passwords over HTTPS. Generate one at
**Settings → Developer settings → Personal access tokens → Tokens (classic)** with the `repo`
scope, and paste it when prompted.

## 3. Funding links (optional)

The "Sponsor" button on the repo comes from `.github/FUNDING.yml` — it points at GitHub Sponsors
and a PayPal donate link. Edit that file to change them. Everything else already points at
`T3lo5/opentv`.

The donation links are deliberately still the upstream maintainer's: they wrote the original,
and the licence's whole point is that the work stays theirs.

## 4. Turn on Pages (optional)

**Settings → Pages → Build and deployment → Source: GitHub Actions.**

That's the only setting. Don't pick "Deploy from a branch" — the workflow in
`.github/workflows/pages.yml` handles it.

Your install page will be at:

```
https://t3lo5.github.io/opentv/
```

It goes live a minute or two after the first push.

**This fork does not have Pages enabled**, so `.github/workflows/pages.yml` has nothing to
publish to and the app's "Install page" button points at the **releases** instead:

```
https://github.com/T3lo5/opentv/releases
```

Either turn Pages on and change that link back, or leave it off and use the releases. Both are
fine; the releases are what the app links to today.

## 5. Cut the first release

This is what actually produces an installable APK:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Watch it under the **Actions** tab. It runs the tests, builds the APK, and attaches it to a
GitHub release. The install page picks it up on its own — nothing to edit.

**Expect the first run to fail.** This code has never been compiled; there was no Android SDK
available where it was written. The build log will name the file and line of anything that
needs fixing. Fix, commit, delete and re-push the tag:

```bash
git tag -d v0.1.0 && git push origin :refs/tags/v0.1.0
git tag v0.1.0 && git push origin v0.1.0
```

## 6. Install it on the TV

The releases are where the APK lives:

```
https://github.com/T3lo5/opentv/releases
```

Upstream also has a short address that always redirects to the newest **upstream** APK, so it
never needs changing between releases:

```
opentvproject.github.io/opentv/apk
```

A short code in the **Downloader** app for this fork's own APKs has to be registered with
aftv.news by whoever runs the fork — `6398449` belongs to upstream.

---

## Before you tell anyone about it

Two things worth doing first:

1. **Set up real signing.** See `docs/RELEASING.md`. Right now the APK is signed with the
   Android debug key so it can be installed at all. Once you switch to a real keystore, anyone
   running a debug-signed build has to uninstall and reinstall — trivial while it's just you,
   genuinely annoying once it's a few hundred people.

2. **Test it yourself for a week.** The thing that damaged Viewella's reputation was not bugs;
   every new app has those. It was people paying, then finding nobody was home. You are not
   charging anyone, which removes most of that risk — but arriving on r/Viewella with something
   that falls over on first run still spends goodwill you only get to spend once.
