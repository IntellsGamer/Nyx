# Nyx — Nullify Your Xploits

A modern, high-performance, **prediction-based anticheat** for Paper 1.21+.

Nyx detects cheats by comparing live player movement against vanilla Minecraft physics instead of relying on lazy "hardcoded" speed limits. It ships with per-check tuning, a Discord webhook, an in-game configuration GUI, and Folia support — all out of the box.

## Features

- **Prediction-based movement analysis** — compares deltas against real vanilla physics (friction, drag, gravity, ice slipperiness, elytra momentum).
- **30+ checks** covering movement, combat, elytra, vehicle, and packet-level exploits.
- **Fly-safe tuning** — elytra glide, firework boosts, and ice momentum are handled generously to avoid false flags.
- **Per-check configuration** — thresholds, violation decay, max violations, sensitivity, and actions (`alert`, `setback`, `kick`, `ban`, custom commands) are all configurable in `config.yml`.
- **Discord webhook alerts** with embedded violation info.
- **In-game cheat detection GUI** (`/nyx gui`) to toggle checks live.
- **Folia supported** — runs on Folia multi-threaded servers.
- **Java 21+** with virtual-thread support for checks.
- Shaded PacketEvents — no runtime dependency to install.

## Requirements

- **Java 21+**
- **Paper** 1.21.x (or a Paper fork: Purpur, Folia, etc.)
- ProtocolLib, Geyser/Floodgate and ViaVersion are optional soft dependencies.

## Installation

1. Drop `Nyx-1.0.1.jar` into your server's `plugins/` folder.
2. Restart (or `/reload` — a restart is recommended).
3. Tune `plugins/Nyx/config.yml` to taste, then run `/nyx reload`.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/nyx` | Show help | — |
| `/nyx alerts` | Toggle alerts on/off for yourself | `nyx.alerts` |
| `/nyx check <player>` | Show a player's violation history | `nyx.admin` |
| `/nyx reload` | Reload the configuration | `nyx.reload` |
| `/nyx setback <player>` | Setback (teleport to last safe position) a player | `nyx.setback` |
| `/nyx exempt <player>` | Toggle a player's exemption from all checks | `nyx.exempt` |
| `/nyx gui` | Open the check-toggling GUI | `nyx.admin` |

Aliases: `/nyx` is also available as `/ac` and `/anticheat`.

## Permissions

- `nyx.admin` — parent permission for all staff tools (default: op)
- `nyx.alerts`, `nyx.reload`, `nyx.setback`, `nyx.exempt`, `nyx.notify`
- `nyx.bypass.*` — bypass every check, or `nyx.bypass.<check>` for a single check (e.g. `nyx.bypass.speed`)

## Checks

**Movement** — `speed`, `fly`, `nofall`, `timer`, `phase`, `jesus`, `boatfly`, `entityspeed`, `entitycontrol`, `boat`

**Elytra** — `elytraa`, `elytrab`, `elytrac`, `extraelytra` (packet spam, impossible speed/height control)

**Combat** — `reach`, `hitbox`, `aimassist`, `autoclicker`, `velocity`, `aimmodulo360`, `attackwhileusing`, `selfinteract`, `multiinteract`, `noswing`, `tridenta`, `tridentb`

**Other** — `badpackets`, `inventorymove`, `fastbreak`, `fastplace`, `fastuse`, `web`

## Configuration

All tuning lives in `plugins/Nyx/config.yml`. Example:

```yaml
checks:
  speed:
    enabled: true
    threshold: 10          # VL at which alert is sent (0 = always)
    decay: 2               # VL removed every second (forgiveness)
    max-violations: 50     # VL at which the highest action triggers
    sensitivity: 0.8       # 0.0 (lenient) – 1.0 (strict)
    actions:
      - "alert"
      - "setback @ 20"
      - "kick @ 50"
```

Supports `alert`, `setback`, `kick`, `ban`, and `cmd:<command>` (with `%player%`, `%check%`, `%vl%` placeholders). Append ` @ <vl>` to delay an action until a violation level is reached.

Also featured: Discord webhook alerts, Geyser/Floodgate Bedrock auto-tuning, creative/spectator and per-world bypass, and performance options (async + virtual threads).

## Building from source

```bash
mvn clean package
```

The shaded jar is output to `target/Nyx-<version>.jar`. The build requires JDK 21 and Maven 3.9+.

## License

Open source. See the [LICENSE](LICENSE) file for details.