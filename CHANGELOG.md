# Changelog

## [3.0.3]

- Fixed `NodeMcItemIcon` sometimes still drawing the old item after its stack changed
- Improved performance on 1.8.9 by no longer querying and rebinding the main framebuffer every frame to find its ID

## [3.0.2]

- Fixed item icons in HUDs showing up blank for their first frame
- Fixed a crash when a HUD element drew text at an invalid position, which VanillaHUD could trigger
- Fixed faded text, like EvergreenHUD's blinking potion effects, not showing on 1.8.9 after the window was resized
- Fixed item icons being rendered again over and over while their scale animates, like VanillaHUD's hotbar pickup bounce
- Added `maxScale` to `McItemIcon` and `NodeMcItemIcon` for icons that animate their scale, so they're rendered once at full resolution and stay sharp

## [3.0.1]

- Fixed the class warm-up stopping with an error on startup when a mod like LibJF also transforms classes

## [3.0.0]

- First release, split out of OneConfig
