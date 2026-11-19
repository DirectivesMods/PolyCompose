package org.polyfrost.compose.minecraft.preview

import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object PreviewNameTag {
    @JvmField
    var rendering: Boolean = false

    @JvmField
    var show: Boolean = false

    inline fun <T> during(show: Boolean, block: () -> T): T {
        rendering = true
        this.show = show
        try {
            return block()
        } finally {
            rendering = false
            this.show = false
        }
    }
}
