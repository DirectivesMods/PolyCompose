# Host hooks

Most mods only need screens and HUD layers. The hooks here are for mods that build their own systems on top of PolyCompose, like a config library or an overlay manager.

## Options for your mod

Anything that changes how your UI behaves goes in a `ComposeOptions` object you own. Nothing you set there touches another mod's screens.

```kotlin
object MyModOptions : ComposeOptions() {
    override val reducedMotion get() = MyConfig.reduceMotion

    @Composable
    override fun Decorate(owner: Any, content: @Composable () -> Unit) {
        MyTheme { content() }
    }

    override fun onRemoved(owner: Any) { saveDrafts() }
}

class SettingsScreen : ComposeScreen(options = MyModOptions)
val watermark = HudLayer("mymod:watermark", MyModOptions) { Watermark() }
```

The options also set animation speed and the messages shown when the UI can't open, and they get called when a scene is created, on every frame, when the pointer moves, when the player switches between keyboard and mouse, and when the screen or layer goes away. `isCapturingInput`, `consumeEscape` and `consumeMouse` let you take input first, for example to record a key for a keybind picker. Inside your content, `LocalComposeOptions.current` gives you the options in use.

To share a look with other mods, publish your options object and let them pass it. A library can hand out one options object, and every mod that uses it gets the same theme, motion settings and input handling.

## Global hooks

A few things belong to the whole game rather than one mod. These hooks stack, so any number of mods can add their own, and every `add` call returns an `AutoCloseable` that removes it.

`ComposeHost.onFontsChanged { relayout() }` runs after resource packs reload the fonts.

## Taking input while playing

A `GameInputLayer` gets clicks, scrolls and keys before the game sees them. PolyCompose screens pass their keys through these layers too. Return `true` to keep the event from the game:

```kotlin
ComposeHost.addGameInput(object : GameInputLayer {
    override val order = GameInputLayer.ABOVE_HUD_LAYERS
    override fun consumeKey(key: Int, state: KeyState) = editing
})
```

On 1.8.9, PolyCompose screens read the input queue themselves. If your mod posts its own input events, add a `LegacyInputObserver` with `ComposeHost.addLegacyInput` to see the events those screens read.

## Frame rate

`ComposeHost.requestFramerate(fps)` asks for a frame rate cap while the request is open. When several mods ask at once, the highest one wins. Close it as soon as you don't need it.

## Overlays

An `OverlayRenderer` draws above everything, screens included, which suits toasts and notifications. Set `showWhenGuiHidden` to keep it visible when the player hides the GUI with F1.

## Blur

`ScreenBlur.draw(graphics)` draws the game's background blur at that point. It's safe to call when another mod has already blurred this frame.

## Offscreen drawing

`PolyOffscreen(width, height)` gives you a render target that isn't on screen. Draw into it with `render { }` (or `renderBeforeUi` and `renderBeforePresent` to pick the point in the frame), then call `readback()` to get the pixels back as a `PolyBitmap`, for things like saving a preview to disk. Close it when you're done.

## When a hook keeps failing

PolyCompose calls your hooks inside a guard. If one keeps throwing (five times in a minute), it gets logged and switched off for the rest of the session instead of breaking every screen. Check the log if a hook stops firing.
