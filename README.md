# PolyCompose

PolyCompose is a library mod that other mods use to draw their screens, menus and HUDs. You don't need to do anything with it yourself. Install it when a mod you use asks for it, and keep it updated.

- Play with a controller in any menu built on PolyCompose, as long as you have [Controlify](https://modrinth.com/mod/controlify) installed. Text fields open Controlify's on-screen keyboard (1.21.1+)
- Lots of HUDs from different mods cost very little when nothing on them is changing
- Works alongside the mods in OneClient, including ImmediatelyFast, Gnetum, Argentum and Flashback
- Press F9 to see how much time the UI takes each frame. You can use `/polycompose profiler` to record and export a capture when reporting lag

## Compatibility

PolyCompose replaces the Compose Multiplatform mod, so remove that one if you have it. OneConfig needs to be version 1.9.0 or newer; older versions ship their own copy and won't load next to it.

If menus from PolyCompose mods ever break your game, launch with `-Dpolycompose.ui.disable` to turn them off while you report the problem.

## Supported versions

- Minecraft 1.8.9 on Ornithe.
- Minecraft 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11 and 26.1-26.3 on Fabric.

PolyCompose needs Fabric Language Kotlin. The Fabric builds also need Fabric API, and the 1.8.9 build needs OSL and Pylon.

## Support

- Report bugs and ask questions on the [Polyfrost Discord](https://polyfrost.org/discord).
- Check [Polyfrost Status](https://status.polyfrost.org/) if a Polyfrost service is down.
