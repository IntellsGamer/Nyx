# Nyx — Nullify Your Xploits

A modern, high-performance, **prediction-based anticheat** for Paper 1.21+.

Nyx detects cheats by comparing live player movement against vanilla Minecraft physics instead of relying on lazy "hardcoded" speed limits. It ships with per-check tuning, a Discord webhook, an in-game configuration GUI, and Folia support — all out of the box.

## Features

- **Prediction-based movement analysis** — compares deltas against real vanilla physics (friction, drag, gravity, ice slipperiness, elytra momentum).
- **30+ checks** covering movement, combat, elytra, vehicle, scaffolding, and packet-level exploits.
- **Fly-safe tuning** — elytra glide, firework boosts, and ice momentum are handled generously to avoid false flags.
- **Per-check configuration** — thresholds, violation decay, max violations, sensitivity, and actions (`alert`, `setback`, `kick`, `ban`, custom commands) are all configurable in `config.yml`.
- **Server-velocity buffering** — knockback is buffered and consumed over movement ticks, giving a far more reliable anti-knockback check and fewer false flags.
- **Time-based bans** — bans are never permanent (default 3 days, configurable). Uses **LiteBans** automatically when installed, otherwise Bukkit's native ban list.
- **Global VL persistence** — violation levels, bans, and setback/kick escalation counts are stored in a self-contained **SQLite** database, so cheaters cannot reset to zero by rejoining or by a server restart.
- **Discord webhook alerts** with embedded violation info.
- **In-game cheat detection GUI** (`/nyx gui`) to toggle checks live.
- **Folia supported** — runs on Folia multi-threaded servers.
- **Java 21+** (Java 25 for the 26.x builds) with virtual-thread support for checks.
- Shaded PacketEvents — no runtime dependency to install.

## Requirements

- **Java 21+** (the **26.2** build requires **Java 25**, as Paper 26.x ships Java 25 bytecode)
- **Paper** 1.21.5, **1.21.6**, **1.21.11**, or **26.2** (or a matching Paper fork: Purpur, Folia, etc.) — each version ships a specialized jar pinned to the right Paper API + PacketEvents.
- ProtocolLib, Geyser/Floodgate, ViaVersion and LiteBans are optional soft dependencies.

## Versions

| MC version | Module | Jar | Paper | PacketEvents |
| --- | --- | --- | --- | --- |
| 1.21.5 | `1.21.5/` | `1.21.5/target/Nyx-1.21.5+1.1.2.jar` | `1.21.5-R0.1-SNAPSHOT` | 2.8.0 |
| 1.21.6 | `1.21.6/` | `1.21.6/target/Nyx-1.21.6+1.1.2.jar` | `1.21.6-R0.1-SNAPSHOT` | 2.9.5 |
| 1.21.11 | `1.21.11/` | `1.21.11/target/Nyx-1.21.11+1.1.2.jar` | `1.21.11-R0.1-SNAPSHOT` | 2.11.2 |
| 26.2 | `26.2/` | `26.2/target/Nyx-26.2+1.1.2.jar` | `26.2.build.111-stable` | 2.13.0 |

Each jar is a specialized build — the shared source is compiled against that version's Paper API and PacketEvents, so the packet listeners and checks are verified against the matching PacketEvents release. Ready-to-download binaries are also published to the [GitHub Releases](https://github.com/IntellsGamer/Nyx/releases) page (one release per Minecraft version).

## Installation

1. Pick the jar for your server's Minecraft version (see table above).
2. Drop it into your server's `plugins/` folder.
3. Restart (or `/reload` — a restart is recommended).
4. Tune `plugins/Nyx/config.yml` to taste, then run `/nyx reload`.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/nyx` | Show help | — |
| `/nyx alerts` | Toggle alerts on/off for yourself | `nyx.alerts` |
| `/nyx check <player>` | Show a player's violation history | `nyx.admin` |
| `/nyx reload` | Reload the configuration | `nyx.reload` |
| `/nyx setback <player>` | Setback (teleport to last safe position) a player | `nyx.setback` |
| `/nyx ban <player> [duration] [reason...]` | Manually ban a player (time-based, never permanent) | `nyx.ban` |
| `/nyx unban <player>` | Unban a player and purge all Nyx ban records | `nyx.unban` |
| `/nyx exempt <player>` | Toggle a player's exemption from all checks | `nyx.exempt` |
| `/nyx gui` | Open the check-toggling GUI | `nyx.admin` |

Aliases: `/nyx` is also available as `/ac` and `/anticheat`.

## Permissions

- `nyx.admin` — parent permission for all staff tools (default: op)
- `nyx.alerts`, `nyx.reload`, `nyx.setback`, `nyx.ban`, `nyx.unban`, `nyx.exempt`, `nyx.notify`
- `nyx.bypass.*` — bypass every check, or `nyx.bypass.<check>` for a single check (e.g. `nyx.bypass.speed`)

## Checks

**Movement** — `speed`, `fly`, `nofall`, `timer`, `phase`, `jesus`, `boatfly`, `entityspeed`, `entitycontrol`, `boat`

**Elytra** — `elytraa`, `elytrab`, `elytrac`, `extraelytra` (packet spam, impossible speed/height control)

**Combat** — `killaura`, `reach`, `hitbox`, `aimassist`, `autoclicker`, `velocity`, `aimmodulo360`, `attackwhileusing`, `selfinteract`, `multiinteract`, `noswing`, `tridenta`, `tridentb`

**Other** — `badpackets`, `inventorymove`, `fastbreak`, `fastplace`, `fastuse`, `web`, `scaffold`

## Configuration

All tuning lives in `plugins/Nyx/config.yml`. Example:

```yaml
checks:
  speed:
    enabled: true
    autoban: true          # false = never auto-ban for this check (VL/setback/kick paths)
    setbacks-to-ban: 6     # how many setbacks on the SAME check before a ban (0 = never)
    kicks-to-ban: 3        # how many kicks on the SAME check before a ban (0 = never)
    threshold: 10          # VL at which alert is sent (0 = always)
    decay: 2               # VL removed every second (forgiveness)
    max-violations: 50     # VL at which the highest action triggers
    sensitivity: 0.8       # 0.0 (lenient) – 1.0 (strict)
    actions:
      - "alert"
      - "setback @ 20"
      - "kick @ 50"
      - "ban @ 75"
```

Supports `alert`, `setback`, `kick`, `ban`, and `cmd:<command>` (with `%player%`, `%check%`, `%vl%` placeholders, and `%reason%`/`%time%` on LiteBans ban commands). Append ` @ <vl>` to delay an action until a violation level is reached.

### How auto-bans work

A check can escalate to a **time-based ban** through three independent paths:

1. **High VL** — a `ban @ <very high VL>` action for continuous, sustained cheating.
2. **Repeated setbacks** — the same check setbacks the player `setbacks-to-ban` times; the count decays by 1/second, so only genuine, ongoing cheating accumulates.
3. **Repeated kicks** — the same check kicks the player `kicks-to-ban` times. Less mercy than setbacks: after a few kicks they have clearly been caught repeatedly.

`punishment.ban.autoban-enabled` is the global master switch for all three paths, while per-check `autoban: false` disables them for just that check. The manual `/nyx ban` command is never affected. Setback and kick escalation counts are persisted in SQLite, so **they survive kicks and reconnects** — a player cannot escape a ban by simply logging back in.

### Time-based bans

Bans are always **time-based** (never permanent) and configurable:

```yaml
punishment:
  ban:
    duration: 3d           # s/m/h/d/w units — 30s, 45m, 12h, 3d, 2w
    reason: "§c[Nyx] §fUnfair Advantage §7(%check%)"
    litebans-command: "litebans:ban %player% %time% %reason%"
    litebans-unban-command: "litebans:unban %player%"
```

- If **LiteBans** is installed and enabled, Nyx issues bans through it (rich ban screen) and uses the `litebans-unban-command` when you run `/nyx unban`.
- Otherwise it falls back to Bukkit's native ban list with an expiry — no permanent bans either way.
- The `%time%` placeholder is filled with the parsed duration (e.g. `3d`).

> **Why you need `/nyx unban` instead of `/pardon`:** every ban (native or LiteBans) is mirrored into Nyx's own SQLite database, and Nyx re-applies it the moment the player rejoins. `/nyx unban <player>` removes all of those records (SQLite ban, escalation counts, native ban-list entry, and the LiteBans ban) in one go.

### Persistent storage (SQLite)

Violation levels, ban records, and setback/kick escalation counters are stored in a self-contained `plugins/Nyx/nyx.db` database so a cheater's history survives kicks, reconnects, and server restarts:

```yaml
storage:
  persist-global-violations: true
```

Also featured: Discord webhook alerts, Geyser/Floodgate Bedrock auto-tuning, creative/spectator and per-world bypass, and performance options (async + virtual threads).

## Building from source

The project is a Maven multi-module build — one module per Minecraft version, all sharing the same source in `src/`.

```bash
# Build every supported version
mvn clean package

# Or build a single version
mvn clean package -pl 1.21.6
```

Each shaded jar is output to its module's `target/` folder (e.g. `1.21.6/target/Nyx-1.21.6+1.1.2.jar`). Building the **26.2** module requires **JDK 25** (the 1.21.x modules compile to Java 21 bytecode, but are built with the same JDK). Requires Maven 3.9+.

## License

Open source. See the [LICENSE](LICENSE) file for details.