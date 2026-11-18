# Courtside ↔ Ravine

[Courtside](https://github.com/TheyH8CaMpS/courtside) is the owner's private sports guide: a mobile web app
(React/vinext on a Cloudflare Worker hosted by OpenAI Sites, owner-only access) with schedules, live scores
(ESPN with theScore fallback), game details, fantasy rosters, a TV-guide matcher that maps games to the
channels in the owner's provider guide, and in-app playback through a relay and an optional home-PC gateway.

## Decision (2026-11-18, PROJECT.md D29)

- **Watching always goes straight to TiviMate.** Ravine's game strip opens the "Watch games in" app
  (TiviMate by default) and never routes playback through Courtside. Courtside's streaming path (relay or
  home-PC gateway) has been intermittent; TiviMate's has been reliable.
- **No Courtside APK.** Courtside is built for phones. Wrapping it for the TV (a WebView/TWA) would bring
  its least reliable part, playback, to the Shield, and its Sites sign-in is awkward with a remote.
- **Scores stay on ESPN, read directly by Ravine.** Courtside uses the same ESPN data, so going through
  Courtside adds a hop and a dependency without adding information.
- The `courtside://` deep link from the first game-day build was removed.

## Built: provider channel names (2026-11-18, D30)

Courtside PR [TheyH8CaMpS/courtside#201](https://github.com/TheyH8CaMpS/courtside/pull/201) (issue #200) adds
`GET /api/tv/games`, and Ravine's `CourtsideGuide` uses it to show e.g. "512 · Spectrum SportsNet LA" on the game
strip. It goes live only after the owner merges and deploys #201 and sets the secrets below.

**Setup (once):**
1. In 1Password, generate a random value of 48+ characters as `COURTSIDE_TV_TOKEN` (item `Courtside`).
2. After deploying #201, add it as the Sites runtime secret `COURTSIDE_TV_TOKEN`.
3. On the PC, create `courtside.properties` with `COURTSIDE_URL=` (Courtside's https address),
   `COURTSIDE_TV_TOKEN=` and `COURTSIDE_SITES_BYPASS_TOKEN=` (the existing owner-only bypass value), then:
   `adb push courtside.properties /sdcard/Android/data/app.ravine.launcher/files/` and open Ravine. Ravine moves the
   values into private storage and deletes the file. Delete the PC copy too.
4. The menu's Game day section says "Channel names from Courtside" once it's set up.

To rotate: push a new file. To remove: clear Ravine's data, or unset the Sites secret (Ravine then quietly falls
back to ESPN's network name).

The original proposal follows.

## Where Courtside can still add value (optional, later)

Courtside's unique data is the **guide match**: which channel in the owner's provider guide carries a game
(e.g. a regional sports network listed under the provider's own name). ESPN's broadcast field ("Spectrum
Sports Net") is what Ravine shows today. If TiviMate uses the same provider/playlist as Courtside's catalog,
Ravine could show the provider's exact channel name (and number, if the catalog has one), so the game is
one search away in TiviMate. **Open question for the owner: is it the same provider?**

That needs a small Courtside change, done the Courtside way (one issue → one branch → one PR; owner approves
merge and deploy separately):

1. A read-only endpoint, e.g. `GET /api/tv/games?teams=dodgers,lakers,raiders&date=YYYY-MM-DD`, that returns
   for each game: ESPN event id, start time, and the best matched channel (`name`, `number` if known,
   `confidence`). **No stream URLs, tickets or provider URLs**, ever.
2. Its own authentication: Sites identity headers only work in a browser session, so the TV needs a bearer
   token. The token lives in 1Password (`op://Dev/Courtside/...`), in Sites runtime settings for Courtside,
   and as a GitHub Actions secret (`COURTSIDE_TV_TOKEN`) for Ravine's build. Courtside's AGENTS.md already
   requires "a standalone service must do its own authentication".
3. Ravine adds `CourtsideGuide` next to `EspnSource`: it only fills in the channel. If Courtside is down or
   slow, Ravine silently keeps ESPN's broadcast name: Ravine never depends on Courtside to work.

## Ravine's side (for reference)

- `source/app/src/main/java/dev/veil/launcher/data/GameDay.kt`: `GameSource` (ESPN), `Game`, `GameDay`.
- Teams: Dodgers (MLB 19), Lakers (NBA 13), Raiders (NFL 13), the same three Courtside supports.
