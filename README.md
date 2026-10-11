# NeoModeration

Chat **and map-art** moderation for Minecraft. A starter English word list, configured URL rules, and anti-spam run locally without an account. Fresh installs block local matches without muting players or clearing everyone's chat; review the starter list for your server. Optional Neomechanical cloud checks add context-aware chat scanning and NSFW detection on filled maps.

**Control the rollout:** `/nmod test <message>` previews a decision without taking action. Run `/nmod mode monitor` if you want local detections to alert staff without blocking. Cloud judgements start in **monitor mode** even while local rules enforce; enable cloud blocking separately with `/nmod cloudmode enforce` after reviewing them. See exactly what data stays on your server with `/nmod privacy` ([privacy details](docs/PRIVACY.md)).

## Setup

1. Drop `NeoModeration-1.6.5.jar` into `plugins/` and restart.
2. Run `/nmod test badword`. It should show the bundled local rule as **FLAGGED** and the result as **blocked**. This is a dry run: the preview itself never blocks or punishes anyone. Review the starter English word list in `config.yml`; it is a starting policy, not complete coverage. This command previews content rules; rate and repetition checks need live messages.
3. Optional: for context-aware cloud moderation, start an instant 14-day evaluation trial:

```text
/nmod trial
```

Or connect an existing workspace key:

```text
/nmod setup YOUR_KEY
```

4. Run `/nmod doctor` to verify the account/usage API, latency, and exact credit balance. If credits are exhausted, it links directly to the [billing recovery page](https://neomechanical.com/billing?src=neomoderation_credits). Then use `/nmod test hello` to verify the moderation-events path safely.
5. Review cloud detections in staff alerts. When you are ready for cloud decisions to block, run:

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

## Coverage

Beyond chat, all running on local rules (no API key needed):

- **Anti-spam** — message rate, duplicate/similar messages, caps, character floods, command-rate. On by default; tune under `moderation.spam` or disable per-check.
- **Censor** — set `moderation.chat.censorLocalDetections: true` to replace matched words with `****` instead of blocking the whole message.
- **Optional strikes** — disabled on a new install. If enabled after policy review, detections accumulate and decay; the configured ladder can kick at 4. Configure `moderation.strikes`.
- **More surfaces** — signs, books, anvil renames, and `/msg`-style commands. Each independently `off`/`monitor`/`censor`/`block` under `moderation.surfaces` (all off by default).
- **Case history** — `/nmod cases [player]` and `/nmod case <id>` browse a local SQLite log of detections.

## Trust & control

- **Mode control** — `/nmod mode monitor|enforce` controls local rules independently from cloud decisions. `/nmod mode` shows what would have happened since startup.
- **Preview content decisions** — `/nmod test badword` proves the bundled local rule works; `/nmod test <message>` checks local content rules and, if reached, cloud. Rate, repetition, commands and maps need live context.
- **Setup diagnostics** — `/nmod doctor` finds misconfigurations before they bite.
- **Exceptions** — `/nmod allow word|url add <value>` fixes false positives instantly; allowed phrases/links always win over banned rules.
- **Cloud presets** — `/nmod preset family|community|minimal` tunes cloud category thresholds without changing local rules or punishments. Cloud checks require an API key. Each category also accepts a custom threshold (0.05–0.99) in `config.yml`.
- **Staff alerts** — players with `neomoderation.notify` see every detection in-game (message preview optional).
- **Privacy surface** — `/nmod privacy` shows what runs locally, what the cloud receives, and the `no_store`/no-training guarantees.

## What happens on detect

In **enforce** mode, the default is to block the matching message, alert staff and log a case. There is no automatic mute, kick or chat clear on a new install. In **monitor** mode, detections alert staff and log without blocking. Existing servers keep their saved action and strike settings when upgrading.

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

Mute durations: `30s`, `5m`, `1h`, `1d` (or bare seconds).

## Commands

| Command | What it does |
|---------|----------------|
| `/nmod trial [status]` | Start a 14-day cloud evaluation trial or check its status |
| `/nmod setup <apiKey>` | Save a cloud API key; verify it with `/nmod doctor` |
| `/nmod status` | Show current local and cloud protection status |
| `/nmod mode [monitor\|enforce]` | Show or switch local enforcement mode |
| `/nmod cloudmode [monitor\|enforce]` | Show or switch cloud enforcement mode |
| `/nmod test <message>` | Preview how a message would be moderated (never acts) |
| `/nmod doctor` | Diagnose configuration, account API, and event health |
| `/nmod cases [player]` / `/nmod case <id>` | Browse the local detection history |
| `/nmod preset <family\|community\|minimal>` | Tune cloud category thresholds |
| `/nmod word add\|remove\|list [value]` | Manage blocked words |
| `/nmod url add\|remove\|list [value]` | Manage blocked links |
| `/nmod allow word\|url add\|remove\|list` | Manage exceptions (always win) |
| `/nmod action <list\|add\|remove\|reset>` | Configure extra actions (`clear`, `mute`, `kick`, `ban`) |
| `/nmod usage` | Show cloud credits, limits, and requests |
| `/nmod privacy` | Show what data stays local vs. cloud |
| `/nmod key <set\|clear> [apiKey]` | Replace or remove the saved API key |
| `/nmod on` / `/nmod off` | Enable or disable moderation |
| `/nmod reload` | Reload config from disk |

Aliases: `/neomod`, `/nmod`, `/neomoderation`.

## Compatibility

Supports Bukkit, Spigot, Paper, and Purpur on Minecraft 1.18.2 through the
current Paper calendar releases (26.1, 26.2). The automated release matrix
verifies Paper 1.18.2, 1.19.4, 1.20.6, and 1.21.11; Paper 26.2 is verified by
hand (build 112 under Java 25) until the chat harness supports the 26.x
protocol.

Paper marks the whole `1.x` line end-of-life as of 2026-06-15; 26.1 and 26.2 are
the only versions it still supports. NeoModeration runs on both schemes because
it compiles against the stable Bukkit API and reaches newer Paper APIs
reflectively.

## Map-art scanning

With a cloud key, filled maps are scanned for NSFW imagery when a player holds one or
right-clicks an item frame. Verdicts are cached by saved image content and cloud settings;
changed artwork is checked again after its map data is saved. Flagged maps in the
player inventory are removed by default (in monitor mode, staff are alerted instead). Tune it under `moderation.mapArt`
in `config.yml`:

```yaml
moderation:
  mapArt:
    enabled: true
    scanOnHold: true
    scanOnFrameInteract: true
    confiscate: true      # false = warn only, keep the item
    cacheSize: 1000
```

## Permissions

- `neomoderation.admin` — use `/nmod` (ops by default)
- `neomoderation.bypass` — skip checks
- `neomoderation.notify` — receive in-game detection alerts (ops by default)

## Notes

- Without a key, local word/link and anti-spam rules run — no chat or map content leaves your server. bStats reports technical metrics and aggregate configuration/detection buckets (including cloud state); it sends no chat, player names or API keys. Disable reporting in `plugins/bStats/config.yml`.
- With a key, checked chat is sent to `https://api.neomechanical.com/v1/events` with `no_store` retention and training disabled. Details: [docs/PRIVACY.md](docs/PRIVACY.md).
- Mute is built into NeoModeration (no Essentials required).
- Cloud failures and busy workers follow `moderation.chat.failOpen` (true by default). In cloud enforce mode, fail-closed blocks that message without strikes, mutes or other player punishments. Cloud monitor mode allows it; local rules still run.
- Cloud chat uses two workers with no waiting queue. Saturation is reported as busy and follows the same failure policy. Map scans allow two concurrent tasks and deduplicate pending map IDs.
- Servers upgrading from 1.2.x keep enforcing exactly as before; local rules now enforce by default on brand-new installs, while cloud judgements stay in monitor until `/nmod cloudmode enforce`.
- Edit `plugins/NeoModeration/config.yml` for advanced options.

## Maintainers

There is no CI. GitHub Actions is off for this repository, so do not add a workflow or rely on
one. Before every push, run `mvn -B clean verify` with Java 17 to 21 (set `JAVA_HOME` if your
default Java is another version); it builds the plugin and runs every test. Then run the public
integrity check with the maintainers' pattern, which is not kept in this repository:
`PUBLIC_INTEGRITY_PATTERN='<pattern>' PUBLIC_INTEGRITY_BASE_SHA=$(git merge-base origin/main HEAD) bash .github/scripts/public-integrity-check.sh .`
It fails when a new commit message, a file path or a tracked file matches the pattern.

The build, marketplace publishing, review, and post-release verification
process is documented in [docs/RELEASING.md](docs/RELEASING.md).
