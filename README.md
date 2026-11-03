# Lifelog

An Android app that sends what your phone knows to **your own server**, and
nowhere else: health records from Health Connect, location, which apps you
use (and, if you switch them on, websites, videos, calls and text messages),
and new camera photos. It is the phone half of a personal knowledge vault: a
server that keeps the record and turns it into pages.

It replaces a stack of apps that each did one of these jobs (HC Webhook,
OwnTracks, ActivityWatch, Termux with Termux:API and Termux:Boot, Syncthing)
by sending **their** formats, so a server built for them takes Lifelog
unchanged. See [docs/CONTRACT.md](docs/CONTRACT.md).

## What it promises

- **Off by default.** Every module, and every part of phone use, does nothing
  until you switch it on, on the phone, with the phone in your hand.
- **One server.** It sends to the server you paired it with. Pairing shows the
  server's name and asks first; pairing with a different server turns every
  module off.
- **A permanent notice.** While anything is on, a notification says what is
  sent and to which server. Swipe it away and it comes back.
- **The least that answers the question.** A web page is reduced to its site
  on the phone, never the address. Videos are followed only in the video apps
  the server lists. A text message from a service (a bank, a login code)
  never leaves the phone with its words.
- **Nothing about you in the app.** The address, the tokens and the places
  come from your server when you pair.

It is not on Google Play: Play does not allow reading SMS and the call log
outside a messaging or dialler app, and restricts accessibility services and
usage access. Install the APK, or follow the repository with Obtainium.

## Running beside the apps it replaces

Lifelog can run alongside them for as long as you like, and the server keeps
one copy of everything:

- **Health**: Lifelog and HC Webhook read the same Health Connect records, and
  the server keys a record by its moment and source, so one sent by both is one.
- **Location**: both send OwnTracks messages under the same tracker; each
  reading is its own moment, and a repeat is dropped by the server.
- **Phone use**: Lifelog's pushes carry `X-Sender: lifelog`, and the server
  files them apart from the ActivityWatch script's, showing one sender per day.
- **Photos**: Lifelog's copies go into their own folder, the photo reader skips
  a photo it has already read, and a photo deleted on the phone is deleted
  from the server too, as Syncthing's mirror did.

## Pairing

The server serves a pairing page (in the vault this is built for:
`/lifelog/pair` on the receiver, for the server's owner only). Open it on the
phone and tap **Open in Lifelog**, or paste its code into the app.

## Building

    gradle testDebugUnitTest assembleDebug

A tag `v*` builds a signed release in GitHub Actions once the repository has
four secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. A local release build reads the same four from the
environment (`KEYSTORE_FILE` in place of the base64).
Both number a version by the count of commits, so a release built in either
place is newer than one built before it; Android refuses an update whose
number is lower than the installed one's.

The unit tests also write one example of every payload to
`app/build/contract/`, for the server's own contract test.

## Licence

MIT.
