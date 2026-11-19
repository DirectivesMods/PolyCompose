# Host hooks

Most mods only need screens and HUD layers. The hooks here are for mods that build their own systems on top of PolyCompose, like a config library or an overlay manager. Every `add` call returns an `AutoCloseable`, and closing it removes the hook.

## Listening to the host

```kotlin
ComposeHost.addListener(object : HostListener {
    override fun onScreenRemoved(screen: Any) { saveDrafts() }
    override fun onFontsChanged() { relayout() }
})
```

`HostListener` also tells you when a scene is created, when the pointer moves over a screen, and when the player switches between keyboard and mouse.

## Taking input while playing

A `GameInputLayer` gets clicks, scrolls and keys before the game sees them. PolyCompose screens pass their keys through these layers too. Return `true` to keep the event from the game:

```kotlin
ComposeHost.addGameInput(object : GameInputLayer {
    override val order = GameInputLayer.ABOVE_HUD_LAYERS
    override fun consumeKey(key: Int, state: KeyState) = editing
})
```

Use `ComposeHost.addScreenInput` with a `ScreenInputInterceptor` to step in on input while a screen is open, for example to capture a key for a keybind picker.

## Overlays

An `OverlayRenderer` draws above everything, screens included, which suits toasts and notifications. Set `showWhenGuiHidden` to keep it visible when the player hides the GUI with F1.

## Blur

`ScreenBlur.draw(graphics)` draws the game's background blur at that point. It's safe to call when another mod has already blurred this frame.

## Offscreen drawing

`PolyOffscreen(width, height)` gives you a render target that isn't on screen. Draw into it with `render { }` (or `renderBeforeUi` and `renderBeforePresent` to pick the point in the frame), then call `readback()` to get the pixels back as a `PolyBitmap`, for things like saving a preview to disk. Close it when you're done.

## When a hook keeps failing

PolyCompose calls your hooks inside a guard. If one keeps throwing (five times in a minute), it gets logged and switched off for the rest of the session instead of breaking every screen. Check the log if a hook stops firing.
