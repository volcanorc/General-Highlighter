# General Highlighter

A Fabric 1.21.1 client-side mod that highlights selected entities and loaded blocks the client already knows about.

## Behavior

- Press `H` to open the highlighter menu.
- Entity rules default to white (`#FFFFFF`).
- Block rules default to green (`#00FF55`), including chests.
- Dropped item stack rules default to yellow (`#FFFF00`).
- Settings are saved to `config/general-highlighter.json`.
- Entity highlighting updates continuously, so newly tracked entities are included automatically.
- Block highlighting scans loaded client chunks incrementally to avoid freezes.
- Items tab rules match dropped item stacks by the real item id, such as `minecraft:diamond`.

## Build

```powershell
.\gradlew.bat build
```

The mod jar will be under `build/libs`.
