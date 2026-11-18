# Courtside ↔ Ravine

Courtside is the owner's planned sports app. Ravine's game-day mode is built so Courtside can plug in later
without changes to Ravine's screens. This file is the contract between the two projects.

## What Ravine does today (no Courtside)

- `GameDay` picks the game to show across the enabled teams (Dodgers, Lakers, Raiders): a game in progress,
  then a final from the last hour or so, then the next start within 18 hours.
- Data comes from a `GameSource` (`source/app/src/main/java/dev/veil/launcher/data/GameDay.kt`). The only
  implementation is `EspnSource`, using ESPN's public site API: free, no key, **unofficial** (it can change
  without notice).
- Home shows the game strip in the top bar; game day switches wallpapers to the team's collection; Ambient
  shows the score. OK on the strip opens the "Watch games in" app (TiviMate by default).

## Integration point 1: opening a game (ready now)

When the game strip is selected, Ravine first fires:

```
ACTION_VIEW  courtside://game?league=<mlb|nba|nfl>&espn=<ESPN event id>&team=<dodgers|lakers|raiders>
```

If an installed app handles it, that app opens; otherwise Ravine falls back to the "Watch games in" app.
Ravine's manifest already declares the `courtside` scheme in `<queries>`, so Android 11's package
visibility allows the check.

**Courtside needs:** an activity with

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="courtside" android:host="game" />
</intent-filter>
```

`espn` is ESPN's event id, so Courtside can use it directly or map it to its own ids.

## Integration point 2: Courtside as the data source (later)

Ravine would add a `CourtsideSource : GameSource` and use it whenever Courtside is available, keeping
`EspnSource` as the fallback. Two ways to provide the data; choose one when Courtside's shape is known:

**A. Courtside runs on the Shield**: a read-only `ContentProvider`
(`content://app.courtside.provider/games?from=<epoch ms>&to=<epoch ms>`), one row per game.

**B. Courtside has a backend**: an HTTPS endpoint returning JSON, e.g. `GET /v1/games?teams=dodgers,lakers,raiders&from=…&to=…`.

Either way, each game carries these fields (they map 1:1 onto Ravine's `Game`):

| Field | Type | Example | Notes |
|---|---|---|---|
| `id` | string | `"401910100"` | Stable per game. ESPN's id if you have it |
| `league` | string | `"nba"` | `mlb`, `nba`, `nfl` |
| `team` | string | `"lakers"` | Ravine's team key |
| `opponent` | string | `"Jazz"` | Short name as shown on screen |
| `opponent_abbr` | string | `"UTAH"` | |
| `home` | bool | `true` | Ravine shows "vs" (home) or "at" (away) |
| `start_ms` | long | `1795575600000` | UTC epoch milliseconds |
| `state` | string | `"live"` | `pre`, `live`, `final` |
| `detail` | string | `"4:12 - 3rd"` | Clock / inning / "Final/OT", shown in small caps |
| `our_score` | int? | `54` | Null before the game |
| `their_score` | int? | `50` | |
| `broadcast` | string? | `"Spectrum Sports Net"` | |

Optional extras Ravine could use later: `watch_uri` (a deep link to the exact stream, which would replace
"Watch games in"), `headline` (one line of news for the strip), and team photos to add to the wallpaper
collections.

## Open questions for the owner

1. Is Courtside an Android TV app, a phone app, a website or a backend? That decides A or B.
2. Should Courtside choose where a game is watched (TiviMate channel, SmartTube stream) through `watch_uri`?
3. More teams later? Ravine's `MyTeams` list is the only place to change.
