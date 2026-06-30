# General Highlighter

A Fabric Minecraft 1.21.1-only client-side mod that highlights selected entities and loaded blocks the client already knows about.

## Behavior

- Press `H` to open the highlighter menu.
- The menu remembers the last tab, search, selected entry, and scroll position while Minecraft is running.
- Entity rules default to white (`#FFFFFF`).
- Block rules default to green (`#00FF55`), including chests.
- Dropped item stack rules use adaptive item colors by default, with custom hex colors still supported.
- Settings are saved to `config/general-highlighter.json`.
- Entity highlighting updates continuously, so newly tracked entities are included automatically.
- Block highlighting scans loaded client chunks incrementally to avoid freezes.
- Block scans are budgeted by vertical chunk section, so common blocks are less likely to stutter the client.
- Enabling or changing a block rule triggers a priority nearby rescan so loaded matches appear quickly.
- Cluster mode draws exposed selected-block borders instead of one large bounding box around air.
- The Blocks tab can hide noisy blocks by default, or show them when you want to select things like stone, dirt, water, or lava.
- The Blocks tab can use the current loaded/render-distance chunk range while still only reading chunks the client has already received.
- Client block updates invalidate nearby cached block highlights so placing or breaking selected blocks refreshes quickly.
- The Items tab owns dropped item highlighting, including a general `All dropped` toggle and item-specific overrides.

## Build

```powershell
.\gradlew.bat build
```

The mod jar will be under `build/libs`.
