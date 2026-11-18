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
| D8 | CI builds every push; tags publish `ravine.apk` to GitHub Releases | No computer needs to stay on; the Shield installs from a fixed URL |
| D9 | A stable release key from GitHub secrets (`RAVINE_KEYSTORE_*`) | Veil's debug-key signing makes each build machine's APK unable to update another's |
| D10 | Never disable or uninstall system packages, including the stock launcher | Inherited from Veil's install rules: debloating boot-looped a TV |
| D11 | Local Nuvio history is the default Continue Watching source, recorded by `MediaListener` in the background | Ravine is stopped while Nuvio plays, so the on-screen tracker never sees the end of an episode. Needs notification access (granted over ADB) |
| D12 | Home starts with focus on the app dock, so the wallpaper is what you see first | Show artwork appears only when you move up into Continue Watching |
| D13 | Instrument Serif (OFL) is the display face: clock and wallpaper titles. Manrope stays for UI text | The editorial/vintage direction in §2 |
| D14 | Themes: Ravine (forest + brass, default), Canyon (bark + terracotta), Chavez (night blue). Veil's themes stay selectable | Text colour is warm "bone" instead of cool white |
| D15 | The Shield's display mode is **never** changed by Ravine or its setup: the owner keeps the interface in HDR / Dolby Vision mode because that's tuned for video. Ravine compensates with "Picture depth" (Standard / Rich / Deep, default Deep): a contrast and brightness filter on the backdrop plus an even darkening layer | In HDR mode the TV lifts dark tones, so the interface looked milky |
| D16 | The Home-button helper replaces only the system's *default* home activity (the stock launcher), never other launcher apps | Veil bounced any HOME-capable app, which made Projectivy (and its settings) impossible to open |
| D17 | Ravine Ambient is the screensaver and shows the wallpaper library with the Home captions and Picture depth; music still switches it to lyrics / visualizer | Same visual language awake and asleep |

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
- **Later:** game-day mode (Dodgers, Lakers, Raiders: team collections and scores; this is where Courtside
  plugs in), time-of-day wallpaper moods, TiviMate shortcuts.

## 7. Engineering upgrades over upstream

- Kotlin 1.9 → 2.x with the Compose compiler plugin; current Compose BOM, tv-material and AGP.
- Break up `LauncherState.kt` (629 lines) into repositories: Apps, Wallpapers, Watch (with sources), Media, Settings.
- Screenshot tests (Roborazzi) of Home at 1920×1080 in CI, so design changes can be reviewed without the TV.
- Unit tests for the feed parser, the watch-source merge, and focus/back rules.
- Every build is reproducible from CI; no keys in the repo (`keys.properties` and the keystore come from secrets).

## 8. Build and install

- CI: `.github/workflows/build.yml`. Secrets: `TMDB_KEY` (required for artwork), `SIMKL_CLIENT_ID`,
  `RAVINE_KEYSTORE_BASE64`, `RAVINE_KEYSTORE_PASSWORD`. Variable: `WEATHER_CITY`.
- Newest release on the Shield (via the Downloader app):
  `https://github.com/TheyH8CaMpS/ravine/releases/latest/download/ravine.apk`
- Local: `cd source && ./gradlew assembleRelease` (JDK 17+, Android SDK 34).
- Permissions, backup and undo: follow `AGENT_INSTALL.md` (inherited from Veil), with the Shield notes in §4.
