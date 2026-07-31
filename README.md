# SignTestPlugin

A Paper plugin that detects configured client mods using the sign/anvil
translation-key side-channel (MC-265322): it seeds a sign with a mod's
translation or keybind key, forces the sign editor open, and reads back
whatever the client echoes when it closes. A resolved (non-raw) echo means
that mod is installed. See `translation-exploit-guard` (the companion
client-side project) for the same vulnerability from the other side.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/signtest <player> <key> [translate\|keybind]` | `signtest.use` | Manual one-off canary check against an online player. Mode defaults to `translate` if omitted. Reports `LEAKED` or `CLEAN` with the raw echoed text. Useful for confirming a mod's key (and which mode actually gets a response) before adding it to `config.yml`. |
| `/signtest reload` | `signtest.use` | Reloads `config.yml` without restarting the server. |
| `/signtest clearoffense <player>` | `signtest.use` | Resets a player's escalation offense count (see `action: escalate` below). Use after they've genuinely removed the flagged mod. Works on offline players. |

## Permissions

| Permission | Default | Effect |
|---|---|---|
| `signtest.use` | op | Run any `/signtest` subcommand. |
| `signtest.bypass` | op | Exempts a player from the automatic join-time check entirely (staff exemption). |
| `signtest.alert` | op | Receive a chat alert whenever the join-time check detects a configured mod, regardless of `action`. |

## What it does automatically (join-time)

If `check-on-join: true` and `mods:` isn't empty, every joining player
(without `signtest.bypass`) gets checked against each configured mod a few
seconds after joining — one mod per sign, run sequentially with a short gap
between each (batching multiple mods' keys onto one sign, or checking them
back-to-back with no gap, both turned out to be their own tell that at
least one protected client reacts to). Checking stops the instant something
matches.

`action` in `config.yml` controls what happens on a match:
- `kick` — disconnect every time, with `kick-message`
- `alert` — just notify staff, no kick
- `escalate` — walk up the `escalation.tiers` ladder (kick, kick again, ban,
  permaban, or however many tiers you configure). Offense counts persist in
  `offenses.yml` across restarts.

## Config.yml highlights

- **`mods:`** — each entry is `name: "key"` (defaults to `translate` mode)
  or `name: { key: "...", mode: translate|keybind }`. `keybind` mode
  (`Component.keybind()`) resolves to the actual bound key rather than a
  display name, and isn't blocked by everything that blocks `translate`
  mode or vice versa — worth trying both for a given mod. There's no
  universal key list; find them yourself (unzip the mod's jar, check
  `assets/<modid>/lang/en_us.json`) or verify ones copied from elsewhere
  with `/signtest <player> <key> [mode]` before trusting them.
- **`escalation.tiers`** — each tier has `action` (kick/ban), `message`
  (supports `&` color codes, multi-line YAML block strings, and
  `{mod}` `{player}` `{duration}` `{offense}` placeholders), and
  `duration-minutes` for ban tiers (`0` = permanent).
- **`canary-timeout-ticks`** — backstop only; the sign editor auto-closes
  and submits on its own almost instantly (see Mechanism below), this just
  covers high-latency edge cases.
- **`between-checks-ticks`** — gap between one mod's check finishing and
  the next starting, join-time only.

## Mechanism

Places a real sign with the target key, sends the block-entity-data packet,
waits one tick, then sends the open-sign-editor packet followed
immediately by `Player#sendBlockChange()` — a **fake, client-only** packet
telling just that one player the position is now air. The real block is
untouched. The client's own sign-editor screen ticks a `canEdit()` check
every frame and auto-closes itself (submitting whatever's in the fields)
the instant it believes its block is gone — so this closes and submits
almost instantly, no player interaction needed.

Signs are opened via manually-sent NMS packets (reflection against
Mojang-mapped names) rather than Bukkit's `Player#openSign(Sign)`, because
that method sends its own block-update and open-editor packets back to
back internally with no way to insert a gap — and opening before the
client has finished applying a preceding text write opens the editor
against stale/empty data.

## Explicitly out of scope

- Run this against your own test/alt accounts, or with the actual consent
  and knowledge of whoever you're checking — not against real players
  without their knowledge, outside of you or staff operating your own
  server with real moderation authority to do so.
- Detection here is inherently an arms race: several clients (Meteor
  Client, per testing) actively detect and defend against this exact
  technique, sometimes selectively (e.g. resolving fine on a genuinely
  player-initiated sign edit but not a server-forced one). A `CLEAN` result
  means "didn't leak this key this way", not "definitely not installed".

## Build notes

- `build.gradle` takes `-Ppaper_api_version=`, `-Pplugin_api_version=`, and
  `-Pjava_version=` project properties (defaults: `1.21.11-R0.1-SNAPSHOT`,
  `1.21`, `21`) — build for a different Paper version without editing the
  file, e.g. `gradlew build -Ppaper_api_version=1.21.4-R0.1-SNAPSHOT
  -Pplugin_api_version=1.21`. Versions 26.x need `-Pjava_version=25` and a
  JDK 25 toolchain (Paper itself requires it from that version on).
- `net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer`
  and `net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer`
  ship with Paper's runtime adventure suite; not needed as explicit
  dependencies on Paper.
