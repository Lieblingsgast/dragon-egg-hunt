# Dragon Egg Hunt

A server-side Fabric mod that turns the dragon egg into a server-wide hunt, inspired by CraftAttack. Players don't need to install anything.

- **The egg stays in play.** It can't be put into chests, ender chests, shulkers, bundles, item frames, pots or shelves, and it can't be dropped. When the holder logs out or dies (also with keepInventory), the egg is placed as a block where they were. A placed egg has no gravity, can't be moved by pistons, survives explosions and never teleports.
- **Fair pickup.** Only a player who is alone in the zone around the egg (a sphere with a radius of 10 blocks) can take it, by clicking it 20 times (sneak + right click doesn't count, so blocks can be placed against the egg as usual). Progress resets after 3 seconds without a click or when someone else enters the zone.
- **Status above the egg.** A floating text shows how many players are inside the ring, who can take the egg and the click progress. It's only shown to players nearby and is drawn in front of blocks, so it's never cut off (`display.hologram-through-blocks`).
- **Zone display.** Players near the egg see the zone sphere as purple particle lines: one on the ground where the terrain crosses the sphere, and a few horizontal rings in the air that show how high the zone reaches. The egg itself glows purple and can be seen through blocks.
- **Carried = visible.** While someone carries the egg, everyone else sees the holder's exact position in the locator bar and sees them glow purple through blocks.
- **Placed = pulse.** A placed egg shows its direction in the locator bar for 5 seconds every 15 minutes, with a quiet sound from the egg's direction and a short line above the hotbar. Every 2 hours a chat message explains the egg. Players can turn all of it off with `/dragonegg off`.
- **Egg icon.** An optional resource pack shows a pixel egg instead of a dot. Players without the pack see a colored, blinking dot.

## Requirements

- Minecraft 26.3 with Fabric Loader 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25

Polymer (virtual entities), Text Placeholder API, fabric-permissions-api and SnakeYAML are bundled in the jar.

## Quick test (macOS)

```bash
bash run-test-server.sh --rebuild
```

Downloads Java 25 (only if it isn't installed), the Fabric server and Fabric API into this folder, builds the mod, asks you to accept the Minecraft EULA and starts a server on `localhost`. Add `--lan` to join from other devices in your network and `--offline` to join with any name (e.g. a second test account). Nothing is installed system-wide.

## Build

```bash
./gradlew build
```

The jar ends up in `build/libs/`. GitHub Actions builds every push. Publishing a GitHub release with a tag like `v1.0.0` (matching `mod_version` in `gradle.properties`) attaches the jar and the resource pack to it.

## Install

1. Download `dragon-egg-hunt-*.jar` from the [releases](https://github.com/Lieblingsgast/dragon-egg-hunt/releases) and put it into `mods/` together with Fabric API.
2. Start the server once, then edit `config/dragonegghunt.yml`.
3. Set up the resource pack (below) and run `/dragonegg reload`.

## Resource pack

The pack lives in `resourcepack/` and is bundled into the jar. It's sent while players are still on the join loading screen. The client keeps downloaded packs by their hash, so it only downloads the pack again after it changed, but it re-applies it on every join (that's how all server resource packs work).

There are two ways to deliver it:

- `mode: embedded` (default): the mod serves the pack itself on `resource-pack.embedded.port` (default 8164). Set `public-address` to the address players use to join. This needs one additional open TCP port.
- `mode: url`: the pack is downloaded from a link. No extra port needed, so this is the mode for hosted servers. Every release has the pack attached, so the simplest link is the one of the release you run, e.g. `https://github.com/Lieblingsgast/dragon-egg-hunt/releases/download/v1.0.0/dragonegghunt-pack.zip`. You can also upload `config/dragonegghunt-pack.zip` unchanged to any host with a direct download link.

The pack must be exactly the one in the mod jar: the server sends its checksum, and clients reject a pack that doesn't match. So after updating the mod, also switch the link to the new release.

After changing the sprite, rebuild the zip:

```bash
cd resourcepack
zip -X -r -D ../src/main/resources/pack/dragonegghunt-pack.zip pack.mcmeta pack.png assets
```

`tools/make_sprite.py` regenerates the 9×9 sprite and the pack icon (needs Pillow).

## Commands

| Command | Permission | Description |
|---|---|---|
| `/dragonegg on` / `/dragonegg off` | everyone | Show or hide the egg in your locator bar |
| `/dragonegg show-zone on` / `off` | everyone | Show or hide the zone lines, the glow and the text above the egg for yourself, e.g. around your build. For 5 minutes after someone clicks the egg or players fight in its zone, everyone near it sees them anyway. |
| `/dragonegg where` | `dragonegghunt.admin` | Where the egg is right now |
| `/dragonegg tp` | `dragonegghunt.admin` | Teleport to the egg (or to the player carrying it) |
| `/dragonegg pulse` | `dragonegghunt.admin` | Send a pulse right away (for testing) |
| `/dragonegg reload` | `dragonegghunt.admin` | Reload `config/dragonegghunt.yml` |
| `/dragonegg config` | `dragonegghunt.admin` | Show all settings; click one to change it |
| `/dragonegg config <setting> <value>` | `dragonegghunt.admin` | Change a setting, e.g. `/dragonegg config zone-height-rings 4`. Saved to the config file and applied right away. `default` resets it. |

Without a permissions mod (e.g. LuckPerms), `dragonegghunt.admin` means operator level 2.

Lost the egg? `/give @s minecraft:dragon_egg` makes you the holder while its location is unknown.

## License

MIT, see [LICENSE](LICENSE).

## How it works

Most rules use Fabric API events. A few small mixins (`dev.dragonegghunt.mixin`) cover what Fabric has no event for: inventory clicks into containers, dropping items, dying, placing the egg, gravity, teleporting, pistons, explosions and the resource pack answer. They only inject at the start or end of vanilla methods and cancel when the egg is involved.

The locator bar entry is a waypoint sent only to individual clients. The server's own waypoint system doesn't know about it, so it doesn't interfere with the vanilla `locatorBar` game rule:

- carried: a position waypoint at the holder, refreshed every half second
- placed: an azimuth waypoint (direction only, no distance) during each pulse

The text above the egg and the egg's glow are virtual display entities ([Polymer](https://github.com/Patbox/polymer)): they only exist in the packets sent to the players who see them, never in the world.

The egg's state (carried by whom, or placed where) and the players' `/dragonegg off` and `show-zone off` choices are stored in `dragonegghunt.json` in the world folder.
