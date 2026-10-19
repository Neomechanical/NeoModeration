NeoModeration by Neomechanical filters Minecraft chat. It blocks swearing, slurs, scam links and spam as soon as it is installed, with no account or API key.

## What a new install does

- Blocks messages that contain a word from starter lists of swear words and slurs (English by default, plus Spanish, French, German, Portuguese and Turkish when `locale` is set in `config.yml`). A rule matches whole words only, so "assignment" and "Scunthorpe" stay clean.
- Also matches common disguises of a listed word: spaced or stretched letters, accents, look-alike letters, leetspeak such as `b4dw0rd`, and words split across rapid short messages.
- Blocks a starter list of IP-grabber and scam links, including ones written with `(dot)` or `[.]`.
- Blocks spam: message floods, the same message again and again, messages mostly in capitals and long runs of one character.
- Alerts staff with the `neomoderation.notify` permission (operators by default), showing the player, the message, the reason and the action taken, with a clickable link to the player's case history.
- Logs each detection as a case on your server. Read them with `/nmod cases` and mark them with `/nmod cases resolve` or `dismiss`. The log leaves out the message text unless you turn it on.

A new install never mutes, kicks or bans a player or clears chat. Review the starter lists for your server and change them with `/nmod word`, `/nmod url` and `/nmod allow`.

## First check

1. Put the jar in `plugins/` and restart the server.
2. Run `/nmod test badword`. The preview shows the starter rule flagging and blocking the word, and it never acts on a player.
3. Run `/nmod doctor` to check your setup.

To have local rules alert staff without blocking, run `/nmod mode monitor`.

## Options, all off on a new install

- Star out matched words and links and let the rest of the message through (`moderation.chat.censorLocalDetections` in `config.yml`).
- Block every link and server IP address, including ones written with `(dot)` or `[.]` (`moderation.offline.blockAnyUrl`).
- Check signs, books, anvil renames and chat commands such as `/msg` and `/me` with `/nmod surface`. Each can monitor, censor or block. Command checks also catch a player who sends those commands too fast.
- Send detection alerts to a Discord staff channel webhook (`moderation.alerts.discordWebhookUrl`).
- Add actions after a block with `/nmod action`: clear chat, mute, kick or ban. Mute needs no other plugin.
- Add a strike for each blocked or starred-out message and act when a player reaches a set count (`moderation.strikes`).

## Optional cloud checks

The Neomechanical Moderation API can read each chat message for its meaning, in eight categories: sexual content, hate, harassment, violence, scams, spam, illicit activity and self-harm. It can also check map art for NSFW images when a player holds a filled map or right-clicks one in an item frame.

- Run `/nmod trial` in game or from the console to start a free 14-day trial. It needs no account, and each server can start one trial.
- To use your own workspace, [create an account](https://neomechanical.com/signup?src=neomoderation_modrinth), create an API key with the `events:write` and `usage:read` scopes, and run `/nmod setup <key>`.
- Cloud decisions only alert staff until you run `/nmod cloudmode enforce`. In enforce mode a flagged map is taken from the player holding it.
- `/nmod preset family`, `community` or `minimal` sets how strict each category is.
- If the cloud cannot be reached, chat goes through and local rules keep running. Chat waits at most 2.5 seconds for a cloud answer.
- Each check sends one message, or one map image, with the player's name and UUID to `api.neomechanical.com` over HTTPS. Earlier messages are never sent. `/nmod privacy` shows what your settings send.
- Cloud checks use credits, and `/nmod usage` shows how many are left.

## Privacy and metrics

Without an API key, no chat or map content leaves your server. NeoModeration sends technical metrics to bStats, which hold no chat, player names or API keys. Turn them off in `plugins/bStats/config.yml`. Once at startup, NeoModeration asks GitHub whether a newer version exists. Turn that off with `updateCheck` in `config.yml`.

## Compatibility

NeoModeration runs on Bukkit, Spigot, Paper and Purpur, Minecraft 1.18.2 to 26.2. Release checks run on Paper 1.18.2, 1.19.4, 1.20.6 and 1.21.11. In-game messages are in English, Spanish, French, German, Portuguese or Turkish.

## Links

- [Minecraft guide](https://neomechanical.com/docs/use-cases/minecraft?src=neomoderation_modrinth)
- [Source code](https://github.com/Neomechanical/NeoModeration)
- [Report a problem](https://github.com/Neomechanical/NeoModeration/issues)
