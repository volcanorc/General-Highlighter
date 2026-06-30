# General Highlighter

A Fabric Minecraft 1.21.1-only client-side mod that highlights selected entities and loaded blocks the client already knows about.

## Downloads

Pick the jar for the branch you want:

| Branch | Flag | Latest jar |
| --- | --- | --- |
| `main` | Stable | [Download general-highlighter-main-1.0.0.jar](https://github.com/volcanorc/General-Highlighter/releases/download/latest-main/general-highlighter-main-1.0.0.jar) |
| `smarthighlight` | Smart Highlight | [Download general-highlighter-smarthighlight-1.0.0.jar](https://github.com/volcanorc/General-Highlighter/releases/download/latest-smarthighlight/general-highlighter-smarthighlight-1.0.0.jar) |

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
- Enabling or changing a block rule triggers an immediate current-chunk scan, then a priority nearby rescan so loaded matches appear quickly.
- Cluster mode draws exposed selected-block borders instead of one large bounding box around air.
- Filled glow modes render translucent whole-block or whole-cluster surfaces without using resource pack reloads.
- The Blocks tab can hide noisy blocks by default, or show them when you want to select things like stone, dirt, water, or lava.
- The Blocks tab can use the current loaded/render-distance chunk range while still only reading chunks the client has already received.
- The Blocks tab shows scan status, cached match count, queued scan sections, and a fast-scan toggle.
- Client block updates invalidate nearby cached block highlights so placing or breaking selected blocks refreshes quickly.
- The Items tab owns dropped item highlighting, including a general `All dropped` toggle and item-specific overrides.
- The Smart tab can register the current mainhand item as a temporary highlight trigger.
- Smart tools match exact styled custom names for renamed/RGB server items, or item id for unnamed items.
- Right-clicking a registered Smart tool resets its temporary highlight timer without blocking normal item use.

## Build

```powershell
.\gradlew.bat build
```

The mod jar will be under `build/libs`.
