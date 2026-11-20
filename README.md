# SimplePod — a simple Android podcast player

A small personal podcast app built with Kotlin, Jetpack Compose, Room, Media3 and WorkManager.

## Features
- **Library**: a grid of the podcasts you're subscribed to. Cover art and names come from each feed.
- **Add podcast** (the button at the bottom right): search by name through the Apple Podcasts directory, or paste an RSS feed URL.
- **Podcast page**: episodes newest first, each showing its date, length, download status and a resume bar.
  - Play/pause, add to or remove from the queue, download or re-download, delete a download, and mark played or unplayed.
  - A **"Keep episodes after playing"** switch for each podcast.
  - **Unsubscribe** in the ⋮ menu.
- **Downloads**:
  - Subscribing downloads the newest episode. After that, every new episode is downloaded automatically, on Wi-Fi only.
  - Feeds are checked every 4 hours, or whenever you pull down to refresh.
  - Downloads you start by hand use any network.
  - An episode that isn't downloaded streams instead.
- **Played**: an episode counts as played when it reaches the end, or when you skip away within 30 seconds of the end.
  - Its file is then deleted, unless "Keep episodes" is on. The episode stays in the list and can be downloaded again.
- **Resume**: the position is saved every 5 seconds and on pause or seek. It's kept even after the file is deleted.
- **Player**:
  - A mini player at the bottom of every screen; tap it for the full player.
  - Controls: play/pause, back 10 s, forward 30 s, a scrub bar, and skip to the next queued episode.
  - The queue appears under the controls. Tap an item to play it, or reorder and remove items.
  - It plays to the end and then moves on to the next queued episode automatically.
- Plays in the background, with controls in the notification and on the lock screen. It pauses when headphones are unplugged.

## Install the prebuilt APK
Copy `app-debug.apk` to your phone and open it. You'll need to allow "Install unknown apps" for your file manager or browser. Or, with USB debugging turned on:

```bash
adb install -r app-debug.apk
```

Requires Android 8.0 (API 26) or newer.

## Download from GitHub Actions
Every push runs the **Build APK** workflow (`.github/workflows/build.yml`), which runs the unit tests and builds a debug APK.
- **Latest build:** open the Actions tab, pick a successful run, and download the `whalepod-debug-apk` artifact. It comes as a zip file containing `app-debug.apk`.
- **Release:** push a tag starting with `v` (for example `git tag v1.0 && git push origin v1.0`). The workflow creates a GitHub Release for that tag and attaches the APK to it.

## Build it yourself
Open this folder in Android Studio and press Run. Or, from the command line, with the Android SDK installed and `ANDROID_HOME` set:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests (RSS parsing, merging refreshed feeds, queue ordering):

```bash
./gradlew testDebugUnitTest
```

## Code layout
| Path | What it does |
| --- | --- |
| `data/db/` | Room entities (`Podcast`, `Episode`, `QueueItem`) and DAOs |
| `data/feed/RssParser.kt` | RSS + iTunes tags parser |
| `data/feed/PodcastSearch.kt` | Apple Podcasts search API client |
| `data/PodcastRepository.kt` | Subscribe, refresh, unsubscribe, mark played (deletes the file) |
| `data/EpisodeMerge.kt` | Merges a refreshed feed without overwriting your playback state |
| `data/EpisodeDownloader.kt`, `work/DownloadWorker.kt` | Downloads (resumable, with a progress notification) |
| `work/RefreshWorker.kt` | Background feed refresh every 4 hours |
| `playback/PlaybackService.kt` | ExoPlayer + MediaSession: saves positions, marks episodes played, advances the queue |
| `playback/PlayerController.kt` | What the UI uses to control playback |
| `ui/` | Compose screens: library, add-podcast sheet, podcast page, mini player, Now Playing |

Downloaded audio is stored in the app's own storage (`Android/data/dev.simplepod/files/episodes`) and is removed if you uninstall the app.
