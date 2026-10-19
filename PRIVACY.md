# NeoModeration Privacy & Data Flow

This page lists what NeoModeration keeps on your server and what it sends when
cloud checks are on. Run `/nmod privacy` in-game for a live summary of the
active configuration.

## Local moderation, by default

With no API key configured, **no chat or map content leaves your server**:

- Banned/allowed word and URL rules run entirely in-process.
- No accounts, no telemetry about chat content, and no network calls for moderation.

## What the cloud receives (only when you add an API key)

When you enable cloud moderation with `/nmod setup <apiKey>`, each checked chat
message (or scanned map image) is sent over HTTPS to the configured endpoint
(default `https://api.neomechanical.com/v1/events`). The request contains:

| Field | Content |
|---|---|
| `actor.externalId` | The player's Minecraft UUID |
| `actor.username` / `displayName` | The player's name |
| `content.text` | The chat message being checked |
| `content.attachments` | For map-art scans only: the rendered map image |
| `metadata.platformPolicy.thresholds` | Your per-category thresholds |
| `context` | `scopeType: minecraft_server`, tag `chat` |

Messages already flagged by your local rules are **not** sent to the cloud;
local rules run first.

## Retention and training

Every request explicitly sets:

- `persistence: "no_store"`: the platform does not retain the content.
- `learning: { enabled: false, mode: "off" }`: content is never used for model training.
- `includeAnalysisDetails: false`: no extended analysis payloads are returned or kept.

## Timeouts and failure policy

Chat is never delayed more than **2.5 seconds** waiting for the cloud,
regardless of configuration. If the cloud is unreachable the configured
`moderation.chat.failOpen` policy decides whether chat passes (default) or
blocks, and a circuit breaker pauses cloud calls for 60 seconds after repeated
errors; local rules keep running throughout.

## Staff alerts

Alerts shown to staff (`neomoderation.notify`) include a short preview of the
flagged message. Set `moderation.alerts.includeMessage: false` to redact
content from staff chat while keeping the alerts.

## Plugin usage metrics

NeoModeration uses [bStats](https://bstats.org/plugin/bukkit/NeoModeration/32542)
with standard technical metrics and 14 plugin-specific aggregate charts. These
report local/cloud modes, cloud health, configuration state, platform family and
detection count buckets; they include no message text or individual detections.
Its standard technical payload includes
the online player count; server software/version; plugin version; Java version;
operating-system type; CPU architecture; country; and a pseudonymous server
identifier used for deduplication and anti-abuse. It never includes player
names, chat content, commands, world data, or your Neomechanical API key.

Like any web request, bStats receives the source IP address. Its current
[privacy policy](https://bstats.org/privacy-policy) says that IP addresses are
held for rate limiting for up to 60 minutes and then discarded; aggregated
technical metrics may be retained. Disable all bStats reporting in
`plugins/bStats/config.yml`.
