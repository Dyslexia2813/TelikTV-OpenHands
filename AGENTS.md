# TelikTV — Android TV player (MXQ Pro 4K)

Minimal Android TV app that auto-plays the live stream of
https://telik.live/zhivaya-priroda.html on launch.

## Stream extraction chain (verified 2026-10)

1. `GET https://telik.live/zhivaya-priroda.html` — sets a session cookie
   (`cae743a7973d0f451f5b1e65eb85e034=...`).
2. `GET https://cdntvmedia.com/educat/zhivaya-priroda.php`
   Required headers: `Referer: https://telik.live/zhivaya-priroda.html`
   **and** `Sec-Fetch-Dest: iframe` (both needed; a plain UA is not enough).
   Returns an iframe pointing at `players/playerjs.php?ch=2025&sp=1`.
3. `GET https://cdntvmedia.com/players/playerjs.php?ch=2025&sp=1`
   Required headers: `Referer: https://cdntvmedia.com/` **and**
   `Sec-Fetch-Dest: iframe`. Returns JS with
   `generatedFile = "https://tvcdnpotok.com/2025/index.m3u8?wmsAuthSign=<token> or https://tvlife.live/2025/index.m3u8?wmsAuthSign=<token>"`
   The token (`wmsAuthSign`) is signed and time-limited — must be re-fetched
   periodically (roughly every few minutes / on 403).
4. `GET` that m3u8 -> HTTP 302 -> `/XXXX/2025/<unix_ts>/index.m3u8` -> HLS
   playlist with 5s `.ts` segments. Segments need no special headers.

Note: requests from datacenter IPs to `cdntvmedia.com` get 403 unless the
`Referer` + `Sec-Fetch-Dest: iframe` combination is sent.

### Token lifetime (measured)

- The `wmsAuthSign` timestamp is `now + 3540s`, i.e. ~59 minutes.
- The 302-resolved session playlist URL (`/XXXX/2025/<ts>/index.m3u8`) stayed
  valid for over 20 minutes of polling.
- Practical consequence: re-resolving on every retry is more than enough; no
  separate token-refresh timer is needed.
- The stream m3u8 and its `.ts` segments need no Referer or UA — ExoPlayer
  plays them directly (it follows the 302 automatically).


## TLS

The stream chain terminates in Let's Encrypt's `ISRG Root X1`.

- Android 7.0 (API 24) does **not** ship that root as a trust anchor — it
  arrived in 7.1.1. On a 7.0 build every HTTPS request fails during the
  handshake and the player shows only a black screen.
- Many TV boxes advertise Android 9/10 while actually running a 7.x build, so
  never assume the platform trust store is complete.
- Fix: `app/src/main/res/raw/isrg_root_x{1,2}.pem` are declared in
  `res/xml/network_security_config.xml` (covers ExoPlayer's
  `HttpURLConnection`), and `TlsTrust` merges them with the platform roots for
  the resolver's own raw sockets.
- `StreamResolver.fetch` uses a raw socket, not `HttpURLConnection`: Android
  may silently drop restricted headers such as `Referer`, which turns an
  expected 200 into a 403 with no other symptom.

## Debugging a black screen

The app was originally mute — every failure ended in a silent retry loop, so a
black screen gave no information. It now shows the reason on screen and logs
under tag `TelikTV`:

```bash
adb logcat -s TelikTV
```

The status text hides only on `onRenderedFirstFrame`, not on `STATE_READY`:
`STATE_READY` means the player has enough data to start, not that pixels
reached the surface, so hiding on it can mask a decode failure.

## Conventions

- Keep the app minimal: single Activity, no menus, black background,
  immersive landscape, wake lock, no splash.
- Prefer native HLS playback (ExoPlayer/MediaPlayer) over WebView to save
  RAM/CPU on weak boxes.
