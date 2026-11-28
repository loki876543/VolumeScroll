# Changelog

## 2026-11-28 — Feed watcher

### Added
- Scheduled GitHub Action (`.github/workflows/zai-fetch.yml`) running `zai-fetch.py` at 07:00 MSK, committing `racing.txt` and `last_seen.json`.
- Watched Atom feeds: `ac-to-rf2` releases and LiveJournal `snake-d-ha` entries (baseline seeded on the first run).
- `racing.txt` rotation: append a timestamped run while under 100 KiB, then overwrite with the next run.

### Changed
- `zai-fetch.py` now uses repo-relative `last_seen.json` and writes all output to `racing.txt`.

## 2026-11-28 — Stats text refinement (second patchset)

### Changed
- Stats tab: removed the "Walked today" duration row; rolling stats now end with the "All time" average.
- Stats tab: the large live/average speed number is replaced by a small inline `avg: <avg>` appended to the records line (`Best today: … · Record: … (s/min) · avg: <avg>`).

### Removed
- Unused `stat_walk_today` string resource and the unused `ForegroundColorSpan` / `StyleSpan` imports in `MainActivity`.

## 2026-11-28 — VolumeScroll (versionCode 20)

### Added
- GitHub Actions workflow (`.github/workflows/build.yml`): JDK 21, Android SDK 36, Gradle setup, debug APK build and artifact upload.
- Per-session 10-step threshold: the walking timer and all speed statistics start at step 10 of each walk; the first nine steps are ignored.
- Once-per-minute speed sampling for the notification display and stored speed history; displayed speed freezes when steps stop instead of decaying.
- New **Speed** tab (fourth tab) with a year of daily average-speed bars; the graph's vertical scale starts at the low warning limit.
- Minute graph: idle stretches longer than one hour collapse into gaps labeled with the start time of the next walking stint; stint starts are labeled instead of clock-hour grid lines.
- One-time recovery import from `loki_log.txt` for installs whose app data was lost (daily steps plus approximate walk time).
- Root-based reinstall script (`scripts/reinstall-with-backup.sh`): tar backup of `/data/data/<pkg>`, uninstall, install, restore.
- New forward-seek player action (jump forward by the configured loop length).

### Changed
- Speed warning defaults are now 110 / 90 steps per minute; sliders allow 50–200 (0 / Off removed).
- Walking time in the notification is printed in minutes only; the Chronometer was removed and a 30-second refresh tick added.
- Step notification channel is silent (`step_counter_silent`, `IMPORTANCE_LOW`, no sound); warning beeps play on the alarm stream at full volume.
- Stats tab rows now show average steps per day for each rolling period.
- Expanded player notification buttons: folder−, rewind, random, forward, folder+. Compact view keeps prev / play-pause / next.
- Player notification layout uses system theme colors instead of a hardcoded dark background.
- CI workflow builds and uploads only the debug APK.

### Fixed
- CI failure caused by `android-actions/setup-android@v3` trying to install the removed legacy `tools` package; the runner's preinstalled `sdkmanager` is used instead.
- Backup script no longer depends on `run-as` (fails with `EINVAL` on this device) or Android Backup Manager (disabled on this device); it uses root directly.
- Downloaded APKs are ignored by git.

### Notes
- Each CI debug build uses a new debug key, so uninstall the previous build before installing a new one.
- The daily-log recovery can restore only steps and approximate walk time for finished days; minute-by-minute data and exact walk times are not recoverable.
- Built artifact from this session: `apks/run-46137911949/app-debug.apk`.
