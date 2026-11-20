# HUD layers

A `HudLayer` is a piece of Compose UI drawn on the HUD. Make one, then call `draw` from wherever your mod already draws its HUD:

```kotlin
val fps = HudLayer("mymod:fps") {
    Text("${Minecraft.getInstance().fps} FPS", color = Color.White)
}

fun onHudRender(graphics: GuiGraphicsExtractor) {
    fps.draw(graphics)
}
```

On 1.8.9 `draw()` takes no arguments. It returns `false` when the layer drew nothing, for example when the UI is turned off or the layer is hidden.

The id only has to be unique and readable. It shows up in logs and in the profiler.

## Settings on a layer

- `visible` hides the layer without throwing away its state
- `scale` makes it bigger or smaller on top of the GUI scale
- `order` decides which layer draws on top when two overlap (higher draws later)
- `acceptsInput` lets the layer take mouse and keyboard input. Turn it off for layers that only display things
- `isolated` gives the layer its own scene (see below)
- `options`, passed when you create the layer, sets its theme, motion and input handling (see [hooks](hooks.md))

Call `close()` when you don't need the layer anymore. A closed layer stops drawing and frees what it used.

## Shared and isolated layers

Layers with the same options share one Compose scene by default. Each layer still draws at its own spot between vanilla HUD elements, and layers drawn back to back get recorded together, so having lots of them costs very little when nothing changes. A layer that keeps throwing gets moved out of the shared scene on its own, so one broken mod can't take everyone else's HUD down with it.

Set `isolated = true` if a layer needs its own scene, for example because it changes every single frame and you don't want it waking the others up. Players can turn sharing off for everything with `-Dpolycompose.hud.shared=false`.

## Performance tips

- Read game state inside the composable through Compose state or `remember`, and let PolyCompose skip redraws when it hasn't changed
- Avoid starting new objects every frame inside the layer's content. It runs whenever the layer redraws
- Press F9 in game to see what each layer costs. You can use `/polycompose profiler capture 10` to record ten seconds and `/polycompose profiler export` to save it
