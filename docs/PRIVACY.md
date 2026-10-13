# Privacy

OpenTV collects nothing. There is no analytics SDK, no crash reporter, no telemetry, and no
network call to any domain the project controls — because the project controls none.

## What is stored, and where

Everything lives in a SQLite database in the app's private storage on your device:

- Your provider's address, username and password
- The channel, movie and series catalogue downloaded from your provider
- The guide (EPG)
- Your favourites, hidden channels and resume positions

Local-only use uploads none of this state. Optional LAN/NAS synchronization uses the peer or
share you configure. If you explicitly enable Supabase Cloud Sync, encrypted user content
and device metadata are sent to your configured Supabase backend. Supabase Auth stores the
account email and manages login sessions. The backend never receives the account encryption
key or plaintext provider payloads. See [cloud sync security and recovery](CLOUD_SYNC.md).

## Who your device talks to

Your provider at the address you entered, configured guide/add-on services, and any sync peers
or Supabase project you opt into. Channel logos and posters are fetched
from whatever URLs your provider puts in its catalogue, which are usually its own servers but
may be third parties — that is your provider's choice, not ours.

## Cleartext HTTP

The app allows plain HTTP (`usesCleartextTraffic="true"`). The overwhelming majority of IPTV
panels are HTTP-only; refusing them would make the app useless to most of the people it is for.

Be aware of what that means: **on an HTTP connection, your provider username and password are
sent unencrypted**, and anyone able to observe the network between you and your provider can
read them. This is a property of how these services are built, not of OpenTV. If your provider
offers an HTTPS endpoint, use it.

## Uninstalling

Removing the app removes local databases, cloud sessions, and locally wrapped key material.
It does not delete encrypted records in a configured Supabase account. Recover those with
a saved recovery key or another authorized device. Delete the backend account/data in your
Supabase project when you want to remove that remote copy.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
