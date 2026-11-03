# What Lifelog sends

Four routes, each the route of the app Lifelog replaces, so a server built
for those apps takes Lifelog unchanged. Every request goes to the paired
server's `url`, over HTTPS, with that module's token in the path. Examples of
each payload are written by the unit tests to `app/build/contract/`.

## Pairing code

`lifelog://pair?c=<base64url(JSON)>`, or the JSON itself:

```json
{"v": 1, "url": "https://server.example:8443", "tz": "Europe/Prague",
 "tokens": {"health": "…", "location": "…", "usage": "…", "photos": "…"},
 "location": {"tid": "XX", "topic": "owntracks/user/phone",
              "regions": [{"desc": "Home", "lat": 50.0, "lon": 14.4, "rad": 100, "tst": 0}]},
 "video_apps": ["com.google.android.youtube"]}
```

A module whose token is missing cannot be switched on. `tz` decides where a
day of phone use begins and ends.

## Health: `POST {url}/hc/{tokens.health}`

HC Webhook's JSON: `{"timestamp", "app_version", "timezone": "UTC", "sender": "lifelog", <kind>: [records]}`,
kinds `steps`, `distance`, `heart_rate`, `heart_rate_variability`,
`resting_heart_rate`, `respiratory_rate`, `sleep`, `exercise`,
`total_calories`, `active_calories`, `weight`, `oxygen_saturation`,
`skin_temperature`, `mindfulness`, each record with HC Webhook's field names
and a `metadata` object (`data_origin`, `recording_method`, `id`, `device`, …).
Sent every 12 hours by default, from Health Connect's change feed: every
record written or rewritten since the last accepted send, whatever moment it
describes, one request per page of the feed. A time window would miss what
the watch writes late (Health Connect filters a window by a record's start, and
a night's sleep is written hours after it began). On the first send, and
whenever the feed has to start again (the allowed kinds changed, or Health
Connect let it expire), the records are read by time instead: from three days
back on the first send, else from two days before the last good one, at most
one day per request. Deletions are not sent. The receiver must treat a record
sent twice, or sent by HC Webhook as well, as one.

## Location: `POST {url}/owntracks/{tokens.location}`

One OwnTracks message per request (owntracks.org/booklet/tech/json):
`location` every 15 minutes (`lat`, `lon`, `acc`, `alt`, `vac`, `vel`, `cog`,
`batt`, `bs`, `conn`, `tst`, `tid`, `topic`, `m`, `inregions`), and
`transition` on entering or leaving a region. Queued on the phone until the
server answers 2xx.

## Phone use: `POST {url}/activitywatch/{tokens.usage}`

Header `X-Sender: lifelog`. Newline-delimited day snapshots,
`{"day": "YYYY-MM-DD", "events": [...]}`, today and yesterday on every run and
any older changed day of the last 14 (the phone builds the last week).
Events:

| `src` | fields |
|---|---|
| `app` | `ts`, `dur` (seconds), `app` (label), `package` |
| `unlock` | `ts` |
| `web` | `ts`, `dur`, `site` (host only), `title`, `browser` (package), `video` (YouTube id, if any) |
| `media` | `ts`, `dur`, `package`, `title`, `artist`, `state` |
| `call` | `ts` (local, with offset), `dur`, `dir`, `who` |
| `sms` | `ts` (local, with offset), `dir`, `who`, `party` (`person`/`service`), `len`, `text` (a person's only) |

A day is sent whole and the server is expected to file it as a delta, and to
keep each sender's days apart (by `X-Sender`), so that Lifelog and the
ActivityWatch script it replaces can push side by side without one's view of a
day undoing the other's.

## Photos: `POST {url}/photos/{tokens.photos}`

The original image (EXIF intact) as the body, `X-Filename` and `X-Modified`
(milliseconds) as headers. New camera photos only, on an unmetered network.
The answer names the server's copy: `{"stored", "duplicate", "file": "lifelog/<name>"}`.

## Photo deletions: `POST {url}/photos/{tokens.photos}/deleted`

`{"files": ["lifelog/<name>", …]}`, the `file` names the server gave, for the
photos that have left the phone's camera folder (deleted, in the trash, or
moved out), so the server does not keep a photo the phone no longer has. The
answer is `{"deleted": n, "missing": m}`; both mean done. A photo taken back
out of the trash is sent again as a new one.
