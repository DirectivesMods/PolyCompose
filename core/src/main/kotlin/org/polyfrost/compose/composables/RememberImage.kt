package org.polyfrost.compose.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.polyfrost.compose.backend.asComposeImageBitmap
import org.polyfrost.compose.render.ImageLoader
import org.polyfrost.compose.render.ImageSource
import org.polyfrost.compose.render.PolyBitmap

@Stable
public sealed class ImageState {
    public object Loading : ImageState()

    @Immutable
    public class Loaded(public val bitmap: PolyBitmap) : ImageState() {
        public val image: ImageBitmap = bitmap.asComposeImageBitmap()
    }

    @Immutable
    public class Failed(public val error: Throwable) : ImageState()
}

private const val MIN_RETRY_MILLIS = 250L

@Composable
public fun rememberImage(source: ImageSource): ImageState {
    val state = remember(source) {
        mutableStateOf(ImageLoader.cached(source)?.let { ImageState.Loaded(it) } ?: ImageState.Loading)
    }
    LaunchedEffect(state) {
        if (state.value is ImageState.Loaded) return@LaunchedEffect
        while (true) {
            val result = try {
                ImageState.Loaded(ImageLoader.loadAsync(source).awaitImage())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                ImageState.Failed(failure)
            }
            state.value = result
            if (result is ImageState.Loaded) return@LaunchedEffect
            val wait = ImageLoader.retryDelayMillis(source) ?: return@LaunchedEffect
            delay(wait.coerceAtLeast(MIN_RETRY_MILLIS))
        }
    }
    return state.value
}

private suspend fun <T> CompletableFuture<T>.awaitImage(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) continuation.resume(value)
        else continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
    }
    continuation.invokeOnCancellation { cancel(false) }
}
