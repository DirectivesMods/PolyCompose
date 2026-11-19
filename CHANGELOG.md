# Changelog

## [1.0.0]

- First release, split out of OneConfig
- Mods can draw item icons, player faces and game sprites in Compose.
- Mods can load images from custom sources, show blurred images, and use chroma colours and custom emoji.
- Mods can preview entities and players, and read pixels back from an offscreen canvas.
- The shared HUD scene is on by default, so many HUDs cost less when idle. Turn it off per layer or with `-Dpolycompose.hud.shared=false`.
- Overlays can stay visible when the GUI is hidden. Mods can choose whether the F9 profiler key is registered.
- A mod that keeps failing inside a hook is logged and switched off for the session, instead of breaking the UI.
- Mods can block screen replacement and react to resource reloads.
- Frame-rate limits and glass support come from the host, with a clear reason when glass is unavailable.
- `-Dpolycompose.ui.disable` turns the UI off. `-Doneconfig.ui.disable` still works.
- Compose jars are remapped at build time instead of re-providing Skia classes. This breaks OneConfig older than 1.9.0 and the `compose-multiplatform` mod.
