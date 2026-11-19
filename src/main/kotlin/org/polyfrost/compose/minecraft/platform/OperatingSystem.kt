package org.polyfrost.compose.minecraft.platform

object OperatingSystem {
    private val name: String = runCatching { System.getProperty("os.name") }.getOrNull().orEmpty()

    @JvmStatic
    val isLinux: Boolean = name.startsWith("Linux") || name.startsWith("LINUX")

    @JvmStatic
    val isMac: Boolean = name.startsWith("Mac")

    @JvmStatic
    val isWindows: Boolean = name.startsWith("Windows")
}
