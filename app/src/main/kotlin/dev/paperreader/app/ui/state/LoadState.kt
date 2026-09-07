package dev.paperreader.app.ui.state

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data object Failed : LoadState<Nothing>
}

internal fun <T, R> Flow<T>.asLoadState(
    scope: CoroutineScope,
    transform: (T) -> R,
): StateFlow<LoadState<R>> = map<T, LoadState<R>> { LoadState.Ready(transform(it)) }
    .catch { error ->
        if (error is CancellationException) throw error
        emit(LoadState.Failed)
    }
    .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = LoadState.Loading,
    )
