package io.github.cacack.keginker

import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.probe.ProbeResult
import io.github.cacack.keginker.probe.probeDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface ProbeState {
    data object Idle : ProbeState

    /** Waiting for the user to hold a tag to the phone. */
    data object Waiting : ProbeState

    data object Reading : ProbeState

    data class Done(val result: ProbeResult) : ProbeState
}

/**
 * The "Report a display" flow: [arm], then the next found tag is read, never
 * written. Mirrors [WriteController]; while armed, found tags go here instead.
 */
class ProbeController(private val probe: suspend (FoundTag, vendorQueries: Boolean) -> ProbeResult = ::probeDisplay) {
    private val mutableState = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val state: StateFlow<ProbeState> = mutableState

    /** Whether the next found tag is for the probe. */
    val isWaiting: Boolean get() = mutableState.value == ProbeState.Waiting

    private var vendorQueries = false

    /** Waits for a tag to read, also sending it its vendor's queries if [vendorQueries]. Ignored while one is being read. */
    fun arm(vendorQueries: Boolean = false) {
        if (mutableState.value == ProbeState.Reading) return
        this.vendorQueries = vendorQueries
        mutableState.value = ProbeState.Waiting
    }

    /** Returns to [ProbeState.Idle]. Ignored while a tag is being read; the read is quick. */
    fun cancel() {
        if (mutableState.value != ProbeState.Reading) mutableState.value = ProbeState.Idle
    }

    /** Reads [tag], taking ownership of its transport; ignored unless armed. Returns once the read ends. */
    suspend fun onTag(tag: FoundTag) {
        if (mutableState.value != ProbeState.Waiting) {
            (tag as? FoundTag.Writable)?.transport?.close()
            return
        }
        mutableState.value = ProbeState.Reading
        try {
            mutableState.value = ProbeState.Done(probe(tag, vendorQueries))
        } finally {
            if (mutableState.value == ProbeState.Reading) mutableState.value = ProbeState.Waiting
        }
    }
}
