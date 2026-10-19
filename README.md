# NeoModeration

NeoModeration by Neomechanical moderates chat and map art on Minecraft servers. Starter lists of swear words and slurs, scam-link rules, server-ad blocking and anti-spam run on your server with no account. A new install blocks what those rules match, tells the player why, alerts staff and logs a case. It never mutes, kicks or bans anyone unless you set that up. Optional Neomechanical cloud checks read chat for its meaning and check map art.

`/nmod test <message>` previews a decision without acting on anyone. Run `/nmod mode monitor` to have local rules alert staff without blocking. Cloud decisions start in monitor mode even while local rules block; turn cloud blocking on with `/nmod cloudmode enforce` once you have watched them. `/nmod privacy` shows what your settings send ([privacy details](PRIVACY.md)).

## Setup

1. Drop `NeoModeration-1.7.0.jar` into `plugins/` and restart.
2. Run `/nmod test badword`. It shows "Local rules: caught" and "Result: blocked". The preview never blocks or punishes anyone. It checks content rules; message rate, repeats and split messages need live chat.
3. Optional: for cloud moderation, start a free 14-day trial. It needs no account. `/nmod trial` first says what a trial sends and how its decisions work, then offers a button (or `/nmod trial start` in the console) to begin.

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

The local rules catch listed words, links and server ads, including spaced-out, accented and look-alike spellings. What they cannot do is read meaning. Harassment and threats in plain words, encouragement to self-harm, scams that never use a banned word, sexual talk and abuse in languages without a word pack all pass a word list. The cloud reads each message for its meaning in eight categories and checks map art.

The free 14-day trial runs on your own chat, and while it runs its decisions only alert staff, so you can judge them before they block anything. [How the cloud checks Minecraft chat](https://neomechanical.com/docs/use-cases/minecraft?src=neomoderation_github).

## What runs without a key

- Starter lists of swear words and slurs in English, plus the Spanish, French, German, Portuguese or Turkish list that matches `locale` in `config.yml`. `moderation.offline.languagePacks` takes `[auto, es]`, `[all]` or `[]`. A word only matches from the start of a word to the end of one, so "assignment" and "Scunthorpe" stay clean. Matching sees through spacing and punctuation ("f uck", "f'uck", "k y s"), accents, look-alike letters, leetspeak and words split across rapid short messages. Everyday words that together spell a listed word stay clean, as in "put a torch here". That counts English and the server's language for every list, and the player's own language for the language packs. Write a word with a space, such as "ass hole", to catch it however it is spaced. A word one language uses every day and another bans, such as French "retard" (late) or the Spanish name Kike, passes on a server whose `locale` is that language. It also passes for a player whose game language it is, when the message is written in that language.
- Ads for other servers are blocked on a new install: public IP addresses, addresses with a port, `play.` and `mc.` style addresses, free hosts such as `aternos.me`, and "join myserver.net". Ordinary links are not affected. Let your own address through with `/nmod allow url add <address>`. An upgraded server keeps this off until you set `moderation.offline.blockServerAds: true`.
- IP grabbers and fake giveaway links from a starter list are blocked, including ones written with `(dot)` or `[.]`.
- Anti-spam blocks message floods, the same message again, character floods and command floods, and lets messages in capitals through in lower case. Spam is only blocked; your actions stay for abuse. Tune it under `moderation.spam`.
- Players whose message, sign, book, item name or command is stopped are told why, in their own game language (`moderation.tellPlayers`).
- `moderation.chat.censorLocalDetections: true` stars out matched words and lets the rest of the message through.
- Strikes are off on a new install. Turn them on under `moderation.strikes` to act when a player reaches a set count.
- `/nmod surface` shows a clickable menu to check signs, books, item names and commands, each set to off, monitor, censor or block (all off by default).
- `/nmod cases [player]` lists detections from a local SQLite log. Open one to resolve or dismiss it, allow the word or mute the player.

## Staff tools

- Players with `neomoderation.notify` see each detection in chat. Those who may open cases can click it for that player's history. During a raid, alerts about one player pause after three in 30 seconds, and all alerts after a dozen in 10 seconds or 30 in a minute. A pause ends after a quiet minute or five minutes; then one line says how many were missed. The console and case history still get every one. `moderation.alerts.includeMessage: false` hides the message text.
- `/nmod discord <webhook URL>` sends the same alerts to a Discord channel, batched so a raid cannot flood it, with mentions turned off. `/nmod discord test` checks it, and `/nmod doctor` says when Discord refuses alerts.
- `/nmod mute <player> [duration]` and `/nmod unmute <player>` mute a player by hand, with no other plugin. A mute covers public chat, private messages (`/msg`, `/tell`, `/r` and any alias the server has for them, such as EssentialsX's `/m`), `/me`, sending `/mail`, `/teammsg`, mcMMO party chat and the rest of `scannedCommands`. Channel chat other plugins add, such as TownyChat's, is not covered.
- `/nmod allow word|url add <value>` fixes a false positive at once. Allowed phrases and links always win.
- `/nmod preset family|community|minimal` sets how strict the cloud is without touching local rules or actions. Each category also takes its own threshold (0.05 to 0.99) in `config.yml`.
- `/nmod doctor` lists every setting that needs attention.
- Moderators with `neomoderation.moderate` get help, status, test, privacy, cases, mute and unmute; settings and the cloud key stay with `neomoderation.admin`.

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

Mute durations: `30s`, `5m`, `1h`, `1d` (or bare seconds), up to 30 days. Kick and ban reasons you have not set yourself read in each player's own language.

## Commands

| Command | What it does |
|---------|----------------|
| `/nmod help [2]` | The clickable command menu |
| `/nmod trial [start\|status]` | Explain the free 14-day cloud trial and start it, or see how it is going |
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

Supports Spigot, Paper and Purpur on Minecraft 1.18.2 through the current
Paper releases, 26.2 and 26.3, and Folia. Every check runs before each release,
with real players in game, on Paper 1.18.2, 1.19.4, 1.20.6, 1.21.11, 26.1.2,
26.2 and 26.3, Folia 1.21.11 and Spigot 1.21.11. A blocked line never
reaches chat and leaves a case, the kick action runs, capitals arrive in lower
case and "bad'word" is caught. A French player's "quel retard ce train" and a
Swedish player's "slut på järn" pass, an operator gets the staff alert, a muted
player's `/msg` is refused and an operator's menus arrive. On 26.2 and 26.3 the test players join through
ViaBackwards, because the test client speaks up to 26.1.

Paper marks the whole `1.x` line end-of-life as of 2026-06-15 and supports 26.2
and 26.3 today. NeoModeration runs on both schemes because it compiles against
the stable Bukkit API and reaches newer Paper and Folia APIs reflectively.

## Map-art checks

With a cloud key, a filled map is checked when a player holds it, puts it in an
item frame or right-clicks one in a frame. The picture is read from the server's
memory, so a map drawn a moment ago is checked at once; each picture is checked
once, and drawing on it makes it a new picture. In enforce cloud mode a flagged
map is taken from the player holding it, and a flagged map already hanging in a
frame is taken down without blaming the player who found it. In monitor mode,
staff are alerted instead. Each player can have 30 new pictures checked an hour,
so nobody can use up your credits by drawing maps. Tune it under
`moderation.mapArt` in `config.yml`:

```yaml
moderation:
  mapArt:
    enabled: true
    scanOnHold: true
    scanOnFrameInteract: true
    confiscate: true      # false = alert staff and leave the map
    cacheSize: 1000
    checksPerPlayerPerHour: 30   # 0 = no limit
```

## Permissions

- `neomoderation.admin`: every `/nmod` command (ops by default); includes `neomoderation.moderate`
- `neomoderation.moderate`: `/nmod help`, `status`, `test`, `privacy`, `cases`, `mute` and `unmute` (ops by default)
- `neomoderation.notify`: receive in-game detection alerts (ops by default)
- `neomoderation.bypass`: skip checks

## Notes

- Without a key or a Discord webhook, local rules and anti-spam run and no chat or map content leaves your server. bStats reports technical metrics and aggregate configuration and detection buckets (including cloud state); it sends no chat, player names or API keys. Turn reporting off in `plugins/bStats/config.yml`.
- With a key, checked chat is sent to `https://api.neomechanical.com/v1/events` with `no_store` retention and training turned off. Details: [PRIVACY.md](PRIVACY.md).
- Cloud failures and a busy cloud follow `moderation.chat.failOpen` (true by default). In cloud enforce mode, fail-closed blocks that message without strikes, mutes or other punishment. In cloud monitor mode it goes through; local rules still run.
- In cloud monitor mode (the trial's default) a message goes through at once and is checked in the background. In enforce mode it waits for the answer, at most 2.5 seconds. Eight cloud requests run at once with a short queue behind them. Map checks run two at a time and never check the same map twice at once.
- Keys and webhook URLs typed into `/nmod setup`, `/nmod key set` and `/nmod discord` are kept out of the server log.
- Edit `plugins/NeoModeration/config.yml` for advanced options.

## Maintainers

There is no CI. GitHub Actions is off for this repository, so do not add a workflow or rely on
one. Before every push, run `mvn -B clean verify` with Java 17 to 21 (set `JAVA_HOME` if your
default Java is another version); it builds the plugin and runs every test. Then run the public
integrity check with the maintainers' pattern, which is not kept in this repository:
`PUBLIC_INTEGRITY_PATTERN='<pattern>' PUBLIC_INTEGRITY_BASE_SHA=$(git merge-base origin/main HEAD) bash .github/scripts/public-integrity-check.sh .`
It fails when a new commit message, a file path or a tracked file matches the pattern.

The maintainers keep the release procedure outside this repository.
