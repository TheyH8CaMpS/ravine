# Ravine: project record

Ravine is a personal Android TV home screen for one living room: an NVIDIA Shield TV Pro (2019) on a
65" TCL QM5K. It is a fork of [Veil](https://github.com/trbrnt/Veil) (GPL-3.0) by trbrnt.

This file is the source of truth for decisions. **Read it before changing anything, and update it when a
decision changes.** Don't redesign or rewrite what's recorded here without a stated reason.

---

## 1. Target setup (only what affects the launcher)

| | |
|---|---|
| Device | NVIDIA Shield TV Pro 2019 (P2897), Tegra X1+, 3 GB RAM, 16 GB storage |
| OS | Android TV, Android 11 (**API 30**). Matters: see §4 |
| UI canvas | 1920×1080, verified: `wm size` reports physical 3840×2160 with a 1920×1080 override. Video still plays in 4K |
| Input | Shield remote: D-pad, select, back, home, voice. No touch, no keyboard |
| Apps | TiviMate, SmartTube, Nuvio (`com.nuvio.tv`), Podcast Addict |
| Stock home | `com.google.android.tvlauncher` (Android TV). Projectivy Premium currently holds the Home button |

**Backup before Ravine (2026-11-18, via ADB):** screensaver `com.google.android.backdrop/.Backdrop`;
accessibility services: Projectivy's override only; notification listeners: Google TV recommendations only;
Private DNS: not set; default HOME activity `com.google.android.tvlauncher/.MainActivity` (Projectivy takes
the Home button via its accessibility service, not the HOME role). (`cmd role get-role-holders` doesn't exist on
this Android 11 build.) Undo script:
`tools/undo-ravine.ps1`.

The soundbar, HDR, Dolby Vision and colour-space settings are Shield settings, not launcher features.
They're out of scope.

## 2. Design language

- Dark forest greens, earthy browns, charcoal and muted natural tones. Authentic photography, no AI imagery.
- The **wallpaper is the main visual feature.** Show artwork appears only when a Continue Watching card has focus.
- Editorial and vintage feel: a serif display face for the clock and titles alongside Manrope, magazine-style
  photo captions ("Fog in the green valley · V2osk"), and subtle pre-rendered film grain.
- Palette tokens (starting point, tuned on the real TV):
  `charcoal #121412` · `forest #1F2B22` · `moss #4A5D45` · `bark #6B5440` · `bone #E8E2D4` (text).
  The accent may be tinted from the current wallpaper, but stays within these earthy tones.
- Minimal: two rows at most on Home (Continue watching, app dock). Everything else sits behind the menu.

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | Fork Veil rather than building from scratch or extending Projectivy | Veil already solves Home-button takeover, Nuvio deep links, ambient mode, lyrics and visualizer, and was profiled on weak TVs |
| D2 | `applicationId = app.ravine.launcher`; Kotlin package stays `dev.veil.launcher` for now | Installs beside Veil without clashing; keeps upstream fixes easy to merge. Revisit if we stop merging upstream |
| D3 | Keep lyrics and the music visualizer, restyled to the palette | Owner wants them; the Shield's GPU handles them easily |
| D4 | NextDNS privacy pill: hidden by default, still available in settings | Not part of the look; may be wanted later |
| D5 | Wallpapers use the existing `overflight.json` schema (`location`, `title`, `author`, `url_img`) from `TheyH8CaMpS/shield-wallpaper-library`, unchanged | Projectivy + Overflight keep working as a fallback; one feed for both |
| D6 | Wallpapers are cached on disk and decoded at 1920×1080, never 4K | A 4K ARGB bitmap is ~33 MB and crossfades hold two; works offline after the first sync |
| D7 | Watch progress through a `WatchSource` interface: local Nuvio history first, Simkl second, Trakt optional | Trakt free accounts allow one connected app (Nuvio uses it). See §5 |
| D8 | CI builds every push; a commit whose message contains `[release]` publishes `ravine.apk` as the latest GitHub Release (the workflow creates the tag, since Claude sessions can't push tags). versionCode = 100 + CI run number | No computer needs to stay on; the Shield installs from a fixed URL |
| D9 | A stable release key from GitHub secrets (`RAVINE_KEYSTORE_*`) | Veil's debug-key signing makes each build machine's APK unable to update another's |
| D10 | Never disable or uninstall system packages, including the stock launcher | Inherited from Veil's install rules: debloating boot-looped a TV |
| D11 | Local Nuvio history is the default Continue Watching source, recorded by `MediaListener` in the background | Ravine is stopped while Nuvio plays, so the on-screen tracker never sees the end of an episode. Needs notification access (granted over ADB) |
| D12 | Home starts with focus on the app dock, so the wallpaper is what you see first | Show artwork appears only when you move up into Continue Watching |
| D13 | Instrument Serif (OFL) is the display face: clock and wallpaper titles. Manrope stays for UI text | The editorial/vintage direction in §2 |
| D14 | Themes: Ravine (forest + brass, default), Canyon (bark + terracotta), Chavez (night blue). Veil's themes stay selectable | Text colour is warm "bone" instead of cool white |
| D15 | The Shield's display mode is **never** changed by Ravine or its setup: the owner keeps the interface in HDR / Dolby Vision mode because that's tuned for video. Ravine compensates with "Picture depth" (Standard / Rich / Deep, default Deep): a contrast and brightness filter on the backdrop plus an even darkening layer | In HDR mode the TV lifts dark tones, so the interface looked milky |
| D16 | The Home-button helper replaces only the system's *default* home activity (the stock launcher), never other launcher apps | Veil bounced any HOME-capable app, which made Projectivy (and its settings) impossible to open |
| D17 | Ravine Ambient is the screensaver and shows the wallpaper library with the Home captions and Picture depth; music still switches it to lyrics / visualizer | Same visual language awake and asleep |
| D18 | Film grain: a baked 256 px speckle tile (max 8.5% alpha) repeated over the photo on Home and in Ambient; menu toggle, on by default | Vintage/editorial feel at the cost of one flat layer |
| D19 | Dock: 26 dp icons and 10.5 sp labels (Veil 30 / 11.5). Hold OK → **Move**, then Left/Right slide the app and OK/Back finish | Owner asked for smaller icons and an easy way to reorder |
| D20 | Lyrics use the serif display face; earthy two-colour visualizer presets come first (Moss, Redwood, Fog Line, Brass and Pine, Dodger Blue, Silver and Black, Purple and Gold) | One visual language across Home, Ambient and music |
| D21 | The Ravine menu is a "field guide index": the day and date as masthead, four numbered sections (I Wallpaper, II Ambient, III Look, IV Home), one line per setting with its value in small caps; OK cycles. "With music" (Lyrics / Album art / Visualizer) replaces Veil's music-mode chips and lyrics switch. The wordmark is serif "Ravine" with no pill | Owner found the Veil menu generic and the music/Ambient/visualizer settings confusing |
| D22 | Dock apps float on the photo: no pill or outline; focus adds a thin bone ring, a slight lift and the app name | Owner's preference (like their Projectivy setup) |
| D23 | Ambient starts after "Starts after" minutes (default 10; 2/5/10/15/30/never), with or without music | Veil's 30 s with music felt too soon |
| D24 | Screen previews: Robolectric renders Home, the menu and Ambient to PNGs at 1920×1080 (`PreviewShots`, run with `-Ppreviews`), so design changes are checked without the TV | Owner asked for previews instead of photos |
| D25 | Visualizer settings is a layer inside the main window (focus trapped, Back closes), not a Dialog | On the Shield, the live GLSurfaceView in a separate dialog window never got input focus → ANR ("does not have a focused window") and Android killed Ravine. Never put a GLSurfaceView in a Dialog here. Verified fixed on the Shield (build 15) |
| D26 | Lyrics: if no LRCLIB version is within 6 s of the song, use the closest within 25 s as untimed lyrics | Different cuts of the same song (e.g. "the cops are coming") showed no lyrics at all. Verified on the Shield (build 15) |
| D27 | Game-day mode: `GameDay` picks a live game, then a recent final, then the next start within 18 h, for the enabled teams. Strip in the top bar (OK opens "Watch games in", default TiviMate (D29)); team wallpapers from 3 h before the start to ~1 h after the end; score in Ambient. Data from ESPN's public site API behind a `GameSource` interface | Owner's teams; ESPN is free and keyless but unofficial, so it's swappable. Courtside's plug-in contract is `docs/courtside.md` |
| D28 | ESPN team ids are only unique within a league (Lakers and Raiders are both 13): responses are always parsed for the league they were fetched for | Found by a unit test |
| D29 | Games are always watched in TiviMate (the "Watch games in" app): no Courtside deep link, no Courtside APK, scores straight from ESPN. The strip shows ESPN's broadcast channel. Courtside may later supply only the provider channel name through a token-protected, URL-free endpoint (docs/courtside.md) | Owner: Courtside's streaming keeps breaking, TiviMate always works. Courtside is a phone web app with owner-only Sites sign-in |
| D30 | Provider channel names from Courtside's `GET /api/tv/games` (TheyH8CaMpS/courtside#201) when set up; ESPN's network name otherwise. The Courtside address and both tokens are pushed once over ADB (`courtside.properties`), moved into private app storage and the file deleted: never in the APK, repo or CI. Set up on the Shield 2026-11-18 | Same IPTV provider as TiviMate and the owner browses TiviMate's guide; Ravine's APK is public |
| D31 | The game strip is two lines (matchup; time · channel), max 330 dp wide, ellipsized | A long provider channel name pushed it into the centred clock (caught in previews) |
| D32 | Crash and freeze log: uncaught exceptions plus Android 11's ApplicationExitInfo (ANR traces, native crashes) appended to `/sdcard/Android/data/app.ravine.launcher/files/ravine-crashes.txt`, capped ~256 KB. Read with `adb shell cat …` | The visualizer "crash" was an ANR and Android kept no details; now one command shows why |
| D33 | "Update ready" at the top of the menu: checks the latest GitHub Release every 6 h (build number from the tag vs versionCode − 100), downloads `ravine.apk` and opens Android's installer (owner allows "install unknown apps" for Ravine once) | Owner asked for updates without typing the Downloader code |
| D34 | The Yak (Barstool Yak, channel UC8BZMZxcXNJLkVtmR_cd1nA) leads Continue Watching when the day's full show is up: public YouTube feed every 20 min, full show = title ending "| The Yak MM-DD-YY" (not "Best of"), within 40 h; OK opens it in SmartTube (`https://www.youtube.com/watch?v=…`, SmartTube's own link filter); "NEW EPISODE" until opened; hold-OK Remove hides it until the next one. Menu IV: "The Yak daily" | Owner watches the day's Yak stream every weekday after work. Verified on the Shield 2026-11-19 (card shown, opened, removed) |
| D35 | Team visualizer: during a game-day window the visualizer picks the team's preset ~70% of the time (Dodger Blue / Purple and Gold / Silver and Black). Menu V toggle | Owner's choice |
| D36 | Game day fetches the live scoreboard immediately after loading schedules | Schedules carry no live scores: a preview during the real Nov 18 game showed "Lakers 0 · Jazz 0" for the first minute |
| D37 | Previews are fully offline (`Http.offline`, fake GameSource): they never reach ESPN, YouTube, GitHub, TMDB or weather | A preview picked up the live game and the real Yak feed |
| D38 | No "Back to live TV" card. TiviMate publishes no media session (`dumpsys media_session` on the Shield, 2026-11-19, listed only Spotify and Netflix), so Ravine can't know the channel or reopen it. Instead the owner turns on TiviMate's own "start with last channel" option, so the dock's TiviMate icon returns to the last channel | A card without a channel would duplicate the dock icon |
| D39 | Compact Continue Watching cards (56×32 thumbnail, text ≤140 dp, 11/9 sp) and a small hero: show title in serif 15 sp (≈ the "Continue watching" label), logos ≤30 dp, 8 sp eyebrow, 10 sp line | Owner: cards far too wide, title far too big; prefers minimal |
| D40 | Kotlin 2.0.21 with the Compose compiler plugin (was 1.9.24 + composeOptions). Ravine's features live in `state/`: `WallpaperState` (`state.walls`), `GameDayState` (`state.gameDay`), `ShowsState` (`state.shows`), `UpdateState` (`state.updates`); `LauncherState` 980 → 765 lines. Verified: 27 unit tests, and every preview pixel-identical before/after (the Ambient shot differs only by its random photo) | Maintainability; no visible change |
| D41 | Team photos: 15 owner-approved Wikimedia Commons photos added to the feed (Dodgers 5, Lakers 6, Raiders 4; now 35 total). Rules: public domain / CC0 / CC BY / CC BY-SA only, landscape, ≥2400 px wide; Commons thumbnail links (3840 px if the original is that big, else 1920), never originals (rate-limited); caption credit "Photographer · license" plus a credits table with source links in the wallpaper repo's README. The owner reviews a numbered contact sheet before anything is added | Game nights looped one Lakers photo. All 15 verified loading on the Shield 2026-11-19 |
| D42 | "Install updates: Automatic" (default) / "Ask" in menu IV: a new build is checked every 2 h, downloaded in the background, and Android's install screen opens on the next return to Home (once per build; never jumps to Settings on its own). The menu footer shows the installed build | Owner wanted updates to load automatically. Android always needs one Install press for a sideloaded app; only system apps install silently. Verified on the Shield 2026-11-19: 26 → 27 by restart, Home, Install |
| D43 | "Check for updates" in menu IV checks immediately (ignores the 2-hour timer): "Up to date", or straight to download + install screen; a failed check says so instead of claiming up to date | Owner wanted a manual check while Ravine changes often |
| D44 | Film grain has levels: Off / Fine (35%) / Light (60%, default) / Classic (100%, the original). One baked tile drawn at the level's alpha; the old on/off switch maps to Off or Light | Owner: the grain felt strong after a while |
| D45 | OK on the "Ravine" wordmark opens and closes the menu (it still opens on focus; Back still closes) | Owner expected the wordmark to close the menu |

## 4. Platform facts that shape the code (Shield, API 30)

- **No Compose blur on this device.** `Modifier.blur`/`RenderEffect` need API 31 and AGSL shaders need 33.
  Frosted glass stays pre-rendered (as Veil does) or moves to OpenGL ES. **The Shield's GPU is the
  big upgrade over the TVs Veil targeted**, so live effects belong in GL (the visualizer pipeline already
  exists), not in Compose.
- **Home button:** Projectivy relies on an override on the Shield because the stock launcher tends to win
  the Home button. In M3 we first try the official route (`cmd role add-role-holder android.app.role.HOME`)
  and check that it survives a reboot (unverified on this Shield). If it doesn't, Ravine uses Veil's
  accessibility service, which listens only to the Home key. Disabling the stock launcher is ruled out (D10). Projectivy's own Home override must be **off** at the same time,
  or the two fight.
- **Watch Next cards** that Nuvio publishes can't be read by another app without a system-only permission,
  so they aren't usable here.
- Nuvio accepts deep links: `nuvio://tmdb/<movie|series>/<id>`, `nuvio://meta/...`, `nuvio://watch/...`
  (from `DeepLinkParser.kt` in NuvioMedia/NuvioTV).
- Nuvio's media session publishes the title and artwork while playing, which feeds the local watch history (§5).

## 5. Watch progress ("Continue watching")

Nuvio sync (Nuvio's own account) keeps Nuvio installs in step with each other. It's a private backend with
no public API, so Ravine doesn't read it. Instead, Nuvio *also* reports to tracking services, and Ravine
reads from one of those, or from what it sees locally:

1. **Local (no account, default).** Ravine's media listener records what Nuvio plays (title, artwork,
   time), matches it to TMDB, and opens it with a `nuvio://tmdb/...` link. Nuvio resumes the position itself.
   It only knows what's watched on this Shield.
2. **Simkl (recommended, free).** Turn on Simkl in Nuvio → Settings → Tracking, alongside Nuvio sync.
   Ravine signs in to the same Simkl account with its own free client ID and reads what's in progress and
   the next episode. No connected-app limit is known for free accounts. Paused-movie resume points
   (`/sync/playback`) may need Simkl Pro: verify.
3. **Trakt (only with VIP).** Veil's original path. Since July 2026 a free Trakt account allows one
   connected app, and Nuvio takes it. Kept in the code, off by default.

## 6. Roadmap

- **M0: Foundation** ✅ fork, rename, CI APK build, stable signing, this file.
- **M1: Make it Ravine.** *In progress.* Done: themes and bone text, serif clock and captions, wallpaper engine
  (feed, offline copy, daily shuffle, 10-min rotation, prefetch, "Next wallpaper" in the menu), editorial
  caption, dock defaults, ad-blocking pill hidden, local Nuvio history (pulled forward from M2). Still to do:
  film grain, earthy visualizer presets, lyrics restyle, screenshot tests. Original M1 scope: Palette and type, wallpaper engine (feed + cache + collections + captions +
  pan/zoom + grain), app dock (TiviMate, SmartTube, Nuvio, Podcast Addict, All apps), restyled lyrics and
  visualizer with earthy presets, NextDNS hidden. Install alongside Projectivy; nothing changes on the
  Shield's Home button yet.
- **M2: Watching.** `WatchSource` refactor (split `LauncherState.kt`), local Nuvio history, Simkl reader,
  artwork-on-focus. Ambient screensaver uses the wallpaper library.
- **M3: Take over Home.** ✅ 2026-11-18: Home button → Ravine via its accessibility helper (Projectivy
  stays installed as a fallback), Ravine Ambient set as the screensaver. Original plan: ADB session (backup + undo script first), Home-button service on, Projectivy override off.
- **M4: Polish on the Shield.** GL backdrop layer (live blur, grain, vignette), visualizer at a higher
  internal resolution, Coil memory tuned for 3 GB, a baseline profile, in-app "update available" from
  GitHub Releases.
- **Game day** ✅ built (D27); Courtside contract in `docs/courtside.md`.
- **Later:** time-of-day wallpaper moods, Simkl, TiviMate shortcuts, more team photos.

## 7. Engineering upgrades over upstream

- Kotlin 1.9 → 2.x with the Compose compiler plugin; current Compose BOM, tv-material and AGP.
- Break up `LauncherState.kt` (629 lines) into repositories: Apps, Wallpapers, Watch (with sources), Media, Settings.
- Screenshot tests (Roborazzi) of Home at 1920×1080 in CI, so design changes can be reviewed without the TV.
- Unit tests for the feed parser, the watch-source merge, and focus/back rules.
- Every build is reproducible from CI; no keys in the repo (`keys.properties` and the keystore come from secrets).

## 8. Build and install

- **Previews:** `cd source && ./gradlew testDebugUnitTest --tests dev.veil.launcher.preview.PreviewShots -Ppreviews`
  → PNG files in `source/previews/` (git-ignored). The first run downloads Robolectric's 150 MB runtime into
  `source/.robolectric/`. Photos come from `app/src/test/resources/preview/`; app icons are placeholders.

- CI: `.github/workflows/build.yml`. Secrets: `TMDB_KEY` (required for artwork), `SIMKL_CLIENT_ID`,
  `RAVINE_KEYSTORE_BASE64`, `RAVINE_KEYSTORE_PASSWORD`. Variable: `WEATHER_CITY`.
- Updates: automatic from the menu (D42/D43). Fallback: Downloader app, code **2583510** (aftv.news/2583510 → the link below). Releases are
  published by commits containing `[release]`.
- Release link:
  `https://github.com/TheyH8CaMpS/ravine/releases/latest/download/ravine.apk`
- Local: `cd source && ./gradlew assembleRelease` (JDK 17+, Android SDK 34).
- Permissions, backup and undo: follow `AGENT_INSTALL.md` (inherited from Veil), with the Shield notes in §4.
