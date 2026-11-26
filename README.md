# whalepod — a simple Android podcast player

A small personal podcast app built with Kotlin, Jetpack Compose, Room, Media3 and WorkManager.

## Features
- **Navigation**: a bar at the bottom switches between **Library**, **In progress** and **Downloads**.
- **In progress**: every episode you've started but not finished, across all podcasts, most recently played first, with time left and a progress bar.
- **Downloads**: downloads under way, waiting or failed (with a retry option) at the top, then finished downloads, most recently downloaded first, with how much space they use.
  - Tap an episode in either view to open its podcast, or use ▶ and ⋮ to play it, queue it, manage its download or mark it played.
- **Library**: a grid of the podcasts you're subscribed to. Cover art and names come from each feed.
- **Add podcast** (the button at the bottom right): search by name through the Apple Podcasts directory, or paste an RSS feed URL.
- **Podcast page**: episodes newest first, each showing its date, length, download status and a resume bar.
  - Play/pause, add to or remove from the queue, download or re-download, delete a download, and mark played or unplayed.
  - A **"Keep episodes after playing"** switch for each podcast.
  - **Unsubscribe** in the ⋮ menu.
- **Combined feeds**: some shows have a free feed and a paid or ad-free one. On the podcast page, ⋮ → **Add another feed** lets you paste the paid feed's URL, or pick a show you already subscribe to, and the feeds become one library entry.
  - When an episode is in both feeds (same ID, or the same title within 7 days, ignoring markers like "(Ad-Free)"), only the copy from the **preferred** feed is shown. The added feed is preferred by default; change it in the **Feeds** section.
  - Listening history, queue entries and downloads move to the copy that's shown.
  - Episodes from the added feed are labelled with a short feed name, e.g. "Ad-Free".
  - The ✂ button next to a feed splits it back into its own podcast, keeping its episodes and history.
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
- **Back up and move**: in the ⋮ menu on the main screen.
  - **Export subscriptions (OPML)** writes the standard subscription list that any podcast app can read.
  - **Back up everything** writes a JSON file that also holds played status, resume positions, the queue and combined feeds. Private feed URLs are included, so keep the file private.
  - **Import OPML or backup** reads either kind of file. A backup restores straight from the file, so it doesn't need to fetch every feed first, and it merges with what's already on the phone without losing newer progress.
  - Downloaded audio isn't part of the backup; episodes stream or can be downloaded again.

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
| `data/backup/` | OPML export/import and full JSON backup/restore |
| `data/Duplicates.kt` | Matches the same episode across a podcast's feeds |
| `data/EpisodeDownloader.kt`, `work/DownloadWorker.kt` | Downloads (resumable, with a progress notification) |
| `work/RefreshWorker.kt` | Background feed refresh every 4 hours |
| `playback/PlaybackService.kt` | ExoPlayer + MediaSession: saves positions, marks episodes played, advances the queue |
| `playback/PlayerController.kt` | What the UI uses to control playback |
| `ui/` | Compose screens: library, add-podcast sheet, podcast page, mini player, Now Playing |
| `ui/lists/` | The In progress and Downloads views |

Downloaded audio is stored in the app's own storage (`Android/data/dev.whalepod/files/episodes`) and is removed if you uninstall the app.
