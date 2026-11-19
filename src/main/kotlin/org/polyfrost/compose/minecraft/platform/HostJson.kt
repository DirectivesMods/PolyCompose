package org.polyfrost.compose.minecraft.platform

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import java.io.StringReader

object HostJson {
    @JvmStatic
    internal fun parseOrNull(json: String?): JsonElement? {
        if (json == null) return null
        return try {
            Streams.parse(JsonReader(StringReader(json)))
        } catch (_: JsonParseException) {
            null
        }
    }
}
