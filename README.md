# ModShare

**Automatic mod syncing for NeoForge servers** — like Garry's Mod addon sharing, but for Minecraft.

When a player joins a ModShare server, their client compares its mods with the server's, downloads what is missing, removes mods that would block joining, and restarts the game — no more "you are missing mods" and manually hunting down jars.

*Minecraft 1.21.1 · NeoForge 21.1+ · install on both the server and the client*

## How it works

1. The server runs a small HTTP server and announces its port in the server list ping, so clients need no setup.
2. When the player clicks **Join**, the client fetches the server's mod list and compares it by SHA-256 hash.
3. You see every change in a list and choose for each mod: **Apply**, **Skip** this time or **Always ignore**. Mods the server lists in `hiddenMods` (libraries and the like) are not listed: once you trust the server, they install silently.
4. Downloads are verified against their hash. The changes are applied after the game closes (Windows locks loaded jars), then **Restart game** starts it again and rejoins the server.

### Only the mods that matter

ModShare uses the same rule as NeoForge to decide who may connect: a mod is needed on both sides if it adds synced content (blocks, items, entities, ...) or required network channels.

- **Server-only mods** (backups, permissions, world generation tweaks) are detected and kept off clients.
- **Client-only mods** (Distant Horizons, Sodium, JEI, minimaps, ...) are never removed.

## Installation

1. Put `modshare-<version>.jar` into the `mods` folder of the **server** and of every **client**.
2. Open the HTTP port on the server (default `25580`, see below).
3. Done. Players join as usual.

**Playing with friends without a dedicated server?** Open your singleplayer world to LAN: ModShare starts sharing your mods at that moment. Friends joining over Radmin VPN, ZeroTier, port forwarding and similar need to reach port `25580` of your computer too.

## Configuration

### Server: `config/modshare-server.toml`

| Option | Default | Description |
| --- | --- | --- |
| `httpPort` | `25580` | Port of the download server. Pick any free port your host gives you. |
| `publicUrl` | `""` | Download address if it differs from the server's, e.g. `http://play.example.com:8123` (port forwarding, reverse proxy). |
| `shareMode` | `AUTO` | `AUTO` shares only the mods clients need to join; `ALL` shares every jar. |
| `forceSharedMods` | `[]` | Always share these mods (e.g. recommended client mods of your pack). |
| `hiddenMods` | `[]` | Clients install these silently instead of listing them (e.g. libraries) — only from servers they trust. |
| `excludedMods` | `[]` | Never share these mods; they are not even listed to clients. |

Mod lists accept a mod id (`jei`) or the beginning of a jar file name (`xaeros_minimap`), case-insensitive.

### Client: `config/modshare-client.toml`

| Option | Default | Description |
| --- | --- | --- |
| `trustedServers` | `[]` | Servers allowed to install their hidden mods without asking. Filled in by **Trust and apply**. |
| `ignoredDownloads` | `[]` | Server mods that are never downloaded. Filled in by **Always ignore**. |
| `removeMode` | `AUTO` | `AUTO` removes only mods that would block joining; `ALL` removes everything the server lacks; `NONE` never removes. |
| `keepMods` | `[]` | Mods that are never removed. |
| `backupRemovedMods` | `true` | Move removed mods to `modshare/backup` instead of deleting them. |
| `fallbackHttpPort` | `0` | For proxies (Velocity, BungeeCord) that hide the port from the ping. |
| `timeoutMs` | `3000` | How long to wait for the server before joining normally. |

## Automatic restart

**Restart game** works with:

- **Prism Launcher and its forks** (ElyPrism / PineLauncher, MultiMC): the launcher restarts the instance itself.
- **Other launchers on Windows and Linux**: the game is started again with its original command line.

On macOS, or when the launcher cannot be detected, a **Quit game** button is shown instead — start the game again manually.

## Security

Mods are Java code with full access to your computer. Only trust servers you would download a modpack from.

- Every change is listed for you to choose. Only a server's hidden mods install silently, and only after you trusted that server.
- Downloads are checked against the hashes in the server's list, but the connection itself is plain HTTP — do not trust servers over networks you do not control.
- Remove a server from `trustedServers` to see its hidden mods on the confirmation screen again.

## License and mod redistribution

ModShare is released under the [MIT License](LICENSE) © pryvietmir.

**ModShare redistributes the mods of your server.** Many mods do not allow redistribution. As a server owner, you are responsible for sharing only mods whose licenses permit it, or for keeping your server private.

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`. Requires JDK 21.
