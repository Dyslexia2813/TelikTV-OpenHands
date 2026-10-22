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

### The two hostnames are mirrors, not qualities

`tvcdnpotok.com` and `tvlife.live` serve byte-identical segments (same md5 for
the same segment), so switching between them changes resilience, not picture
quality. Both are tried automatically; the D-pad left/right override just
picks which one is attempted first.

### There is only one quality level

The playlist is a media playlist with no `EXT-X-STREAM-INF`, so there are no
quality variants to select. Probed and 404: `master.m3u8`, `2025/index_hd`,
`2025_720`, `2025/1080`, `2025_hd`, `hd/2025`. Changing the User-Agent changes
nothing (VLC's UA gets 404; a browser UA and a "PeersTV-like" UA get the same
247-byte playlist). Measured stream: H.264 Main@4.0, 1920x1080 progressive
4:2:0, roughly 3-5 Mbit/s VBR. Softness on motion is a source limitation.

Do not try to extract streams from the PeersTV app. It is a separate licensed
service (ООО «Инетра») with its own CDN and auth; bypassing it is out of scope.


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

## Distributing the APK

The APK must be served from somewhere that outlives the session. A local
`python3 -m http.server` behind the session URL works only while the session
is alive; when the environment resets, `/tmp` is wiped and the session URL
starts returning 502 Bad Gateway.

Publish to GitHub Releases instead:

```bash
curl -X POST -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/repos/<owner>/<repo>/releases \
  -d '{"tag_name":"v1.2","target_commitish":"main","name":"...","body":"..."}'
curl -X POST -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@app/build/outputs/apk/release/app-release.apk" \
  "https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets?name=TelikTV-1.2.apk"
```

Build outputs under `/workspace/...` survive an environment reset; `/tmp` does
not.

### Rebuilding after an environment reset

JDK and the Android SDK are not part of the image and are lost on reset. The
Gradle distribution under `/workspace/tools/gradle-8.7` does survive. To
restore a build:

```bash
sudo -n apt-get update -qq
sudo -n apt-get install -y -qq openjdk-21-jdk-headless unzip

sudo -n mkdir -p /opt/android-sdk/cmdline-tools
curl -sSL -o /tmp/cmdtools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -o /tmp/cmdtools.zip -d /tmp/cmdtools-extract
sudo -n cp -r /tmp/cmdtools-extract/cmdline-tools \
  /opt/android-sdk/cmdline-tools/latest
sudo -n chown -R "$(id -un):$(id -gn)" /opt/android-sdk

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"

/workspace/tools/gradle-8.7/bin/gradle :app:assembleRelease --no-daemon
```

Note `/opt` is root-owned, so the SDK needs `sudo` to create; the `chown`
afterwards lets Gradle write into it.

`StreamResolver` is free of Android imports, so it can be compiled and run on
a desktop JVM to verify the resolution chain without a device:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
$JAVA_HOME/bin/javac -d /tmp/rt live/telik/tv/StreamResolver.java
```

## Conventions

- Keep the app minimal: single Activity, no menus, black background,
  immersive landscape, wake lock, no splash.
- Prefer native HLS playback (ExoPlayer/MediaPlayer) over WebView to save
  RAM/CPU on weak boxes.
