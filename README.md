# NeoModeration

NeoModeration by Neomechanical moderates chat and map art on Minecraft servers. Starter lists of swear words and slurs, scam-link rules, server-ad blocking and anti-spam run on your server with no account. A new install blocks what those rules match, tells the player why, alerts staff and logs a case. It never mutes, kicks or bans anyone unless you set that up. Optional Neomechanical cloud checks read chat for its meaning and check map art.

`/nmod test <message>` previews a decision without acting on anyone. Run `/nmod mode monitor` to have local rules alert staff without blocking. Cloud decisions start in monitor mode even while local rules block; turn cloud blocking on with `/nmod cloudmode enforce` once you have watched them. `/nmod privacy` shows what your settings send ([privacy details](PRIVACY.md)).

## Setup

1. Drop `NeoModeration-1.7.0.jar` into `plugins/` and restart.
2. Run `/nmod test badword`. It shows "Local rules: caught" and "Result: blocked". The preview never blocks or punishes anyone. It checks content rules; message rate, repeats and split messages need live chat.
3. Optional: for cloud moderation, start a free 14-day trial. It needs no account.

```text
/nmod trial
```

Or connect an existing workspace key:

```text
/nmod setup YOUR_KEY
```

4. Run `/nmod doctor` to check the key, the connection and your credits. If credits run out, it links to the [billing page](https://neomechanical.com/billing?src=neomoderation_credits). Then run `/nmod test hello` to check a real cloud request.
5. Watch the cloud's decisions in staff alerts. When you trust them, let them block:

```text
/nmod cloudmode enforce
```

## Why add cloud moderation?

Players do not always type abuse exactly as expected. In Neomechanical's
published 10-string benchmark, replacing letters with digits, adding accents,
or putting punctuation between letters caused a leading free moderation
endpoint to flag 4/10 strings instead of 10/10. Neomechanical's additive
normalisation restored 10/10 for each of those transforms and added no flags
across eight ordinary clean chat lines.

The sample is deliberately small and English-only. [Review the results, method,
and limitations](https://neomechanical.com/r/neomoderation_github?to=obfuscation)
before deciding whether cloud checks fit your server.

## What runs without a key

- **Starter word lists**: English, plus the Spanish, French, German, Portuguese or Turkish list that matches `locale` in `config.yml`. Set `moderation.offline.languagePacks` to `[auto, es]`, `[all]` or `[]` to choose. Matching is whole-word, so "assignment" and "Scunthorpe" stay clean, and it sees through spacing, accents, look-alike letters, leetspeak and words split across rapid short messages.
- **Server ads**: IP addresses, addresses with a port, `play.` and `mc.` style addresses, free hosts such as `aternos.me` and "join myserver.net" are blocked. Ordinary links are not affected. Add your own address with `/nmod allow url add <address>`. Turn it off with `moderation.offline.blockServerAds: false`.
- **Scam links**: IP grabbers and fake giveaways from a starter list, including ones written with `(dot)` or `[.]`.
- **Anti-spam**: message rate, the same message again, capitals, character floods and command rate. Tune it under `moderation.spam`.
- **Player notices**: players whose message, sign, book, item name or command is stopped are told why, in their own game language (`moderation.tellPlayers`).
- **Censor**: set `moderation.chat.censorLocalDetections: true` to star out matched words and let the rest of the message through.
- **Strikes**: off on a new install. Turn them on under `moderation.strikes` to act when a player reaches a set count.
- **Signs, books, item names and commands**: `/nmod surface` shows a clickable menu to set each one to off, monitor, censor or block (all off by default).
- **Case history**: `/nmod cases [player]` lists detections from a local SQLite log. Open one to resolve or dismiss it, allow the word or mute the player.

## Staff tools

- **Alerts**: players with `neomoderation.notify` see each detection in chat; click one for that player's cases. `moderation.alerts.includeMessage: false` hides the message text.
- **Discord**: `/nmod discord <webhook URL>` sends the same alerts to a Discord channel, batched so a raid cannot flood it. `/nmod discord test` checks it.
- **Mute**: `/nmod mute <player> [duration]` and `/nmod unmute <player>`. Mutes are built in; no other plugin is needed.
- **Exceptions**: `/nmod allow word|url add <value>` fixes a false positive at once. Allowed phrases and links always win.
- **Cloud presets**: `/nmod preset family|community|minimal` sets how strict the cloud is without touching local rules or actions. Each category also takes its own threshold (0.05 to 0.99) in `config.yml`.
- **Setup check**: `/nmod doctor` lists every setting that needs attention.

## What happens on a detection

In **enforce** mode, the default is to block the matching message, tell the player why, alert staff and log a case. There is no automatic mute, kick or chat clear on a new install. In **monitor** mode, detections alert staff and are logged without blocking. Servers upgrading keep their saved actions and strikes.

Change actions with:

```text
/nmod action list
/nmod action add clear
/nmod action add mute 10m
/nmod action add kick
/nmod action add ban
/nmod action remove mute
/nmod action reset
```

Mute durations: `30s`, `5m`, `1h`, `1d` (or bare seconds), up to 30 days.

## Commands

| Command | What it does |
|---------|----------------|
| `/nmod help [2]` | The clickable command menu |
| `/nmod trial [status]` | Start a free 14-day cloud trial, or see how it is going |
| `/nmod setup <key>` | Save a cloud API key; check it with `/nmod doctor` |
| `/nmod status` | What is protected right now |
| `/nmod mode [monitor\|enforce]` | Show or switch local rules between alerting and blocking |
| `/nmod cloudmode [monitor\|enforce]` | Show or switch cloud decisions between alerting and blocking |
| `/nmod test <message>` | Preview how a message would be handled (never acts) |
| `/nmod doctor` | Find setup problems |
| `/nmod cases [player\|id\|page <n>]` | Browse, resolve and dismiss detections |
| `/nmod mute <player> [duration]` / `/nmod unmute <player>` | Mute or unmute a player by hand |
| `/nmod discord <webhook URL\|test\|off>` | Send alerts to a Discord channel |
| `/nmod preset <family\|community\|minimal>` | Set how strict the cloud is |
| `/nmod word add\|remove\|list [value]` | Manage banned words |
| `/nmod url add\|remove\|list [value]` | Manage blocked links |
| `/nmod allow word\|url add\|remove\|list [value]` | Manage exceptions (they always win) |
| `/nmod action <list\|add\|remove\|reset>` | Actions after a block (`clear`, `mute`, `kick`, `ban`) |
| `/nmod surface [sign\|book\|anvil\|command\|all] [off\|monitor\|censor\|block]` | Check signs, books, item names and commands |
| `/nmod usage` | Cloud credits, limits and requests |
| `/nmod privacy` | What your settings send, and where |
| `/nmod key <set\|clear> [key]` | Replace or remove the saved key |
| `/nmod on` / `/nmod off` | Turn moderation on or off |
| `/nmod reload` | Reload `config.yml` and the messages |

Aliases: `/neomod`, `/nmod`, `/neomoderation`.

## Languages

Messages ship in English (`en_US`), Spanish (`es_ES`), French (`fr_FR`), German (`de_DE`), Portuguese (`pt_BR`) and Turkish (`tr_TR`). `locale` in `config.yml` sets the console's language, and with `perPlayerLanguage: true` (the default) each player reads messages in their own game language. Edit any text in `plugins/NeoModeration/locale/`; updates replace only the texts you never edited.

## Compatibility

Supports Bukkit, Spigot, Paper and Purpur on Minecraft 1.18.2 through the
current Paper calendar releases (26.1, 26.2). The automated release matrix
verifies Paper 1.18.2, 1.19.4, 1.20.6 and 1.21.11; Paper 26.2 is verified by
hand (build 112 under Java 25) until the chat harness supports the 26.x
protocol.

Paper marks the whole `1.x` line end-of-life as of 2026-06-15; 26.1 and 26.2 are
the only versions it still supports. NeoModeration runs on both schemes because
it compiles against the stable Bukkit API and reaches newer Paper APIs
reflectively.

## Map-art checks

With a cloud key, a filled map is checked when a player holds it, puts it in an
item frame or right-clicks one in a frame. The picture is read from the server's
memory, so a map drawn a moment ago is checked at once; each picture is checked
once, and drawing on it makes it a new picture. In enforce cloud mode a flagged
map is taken from the player holding it, and a flagged map already hanging in a
frame is taken down without blaming the player who found it. In monitor mode,
staff are alerted instead. Tune it under `moderation.mapArt` in `config.yml`:

```yaml
moderation:
  mapArt:
    enabled: true
    scanOnHold: true
    scanOnFrameInteract: true
    confiscate: true      # false = alert staff and leave the map
    cacheSize: 1000
```

## Permissions

- `neomoderation.admin`: use `/nmod` (ops by default)
- `neomoderation.bypass`: skip checks
- `neomoderation.notify`: receive in-game detection alerts (ops by default)

## Notes

- Without a key, local rules and anti-spam run; no chat or map content leaves your server. bStats reports technical metrics and aggregate configuration and detection buckets (including cloud state); it sends no chat, player names or API keys. Turn reporting off in `plugins/bStats/config.yml`.
- With a key, checked chat is sent to `https://api.neomechanical.com/v1/events` with `no_store` retention and training turned off. Details: [PRIVACY.md](PRIVACY.md).
- Cloud failures and a busy cloud follow `moderation.chat.failOpen` (true by default). In cloud enforce mode, fail-closed blocks that message without strikes, mutes or other punishment. In cloud monitor mode it goes through; local rules still run.
- Cloud chat uses two workers with no waiting queue. Map checks run two at a time and never check the same map twice at once.
- Edit `plugins/NeoModeration/config.yml` for advanced options.

## Maintainers

There is no CI. GitHub Actions is off for this repository, so do not add a workflow or rely on
one. Before every push, run `mvn -B clean verify` with Java 17 to 21 (set `JAVA_HOME` if your
default Java is another version); it builds the plugin and runs every test. Then run the public
integrity check with the maintainers' pattern, which is not kept in this repository:
`PUBLIC_INTEGRITY_PATTERN='<pattern>' PUBLIC_INTEGRITY_BASE_SHA=$(git merge-base origin/main HEAD) bash .github/scripts/public-integrity-check.sh .`
It fails when a new commit message, a file path or a tracked file matches the pattern.

The maintainers keep the release procedure outside this repository.
