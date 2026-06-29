# General Highlighter

A Fabric 1.21.1 client-side mod that highlights selected entities and loaded blocks the client already knows about.

## Behavior

- Press `H` to open the highlighter menu.
- Entity rules default to white (`#FFFFFF`).
- Block rules default to green (`#00FF55`), including chests.
- Settings are saved to `config/general-highlighter.json`.
- Entity highlighting updates continuously, so newly tracked entities are included automatically.
- Block highlighting scans loaded client chunks incrementally to avoid freezes.

## Build

```powershell
.\gradlew.bat build
```

The mod jar will be under `build/libs`.
