package io.github.cacack.keginker.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.cacack.keginker.nfc.Transport
import java.io.IOException

/**
 * A tag whose every reply comes from the fuzzer, up to [MAX_REPLY] bytes
 * each. When the input runs out, the tag leaves the field: [transceive]
 * throws, as [Transport] says a lost tag does.
 */
internal class FuzzTransport(private val data: FuzzedDataProvider) : Transport {
    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        if (data.remainingBytes() == 0) throw IOException("Tag lost")
        return data.consumeBytes(data.consumeInt(0, MAX_REPLY))
    }

    private companion object {
        /**
         * Well past the longest reply any command asks for (an ISO-DEP read of
         * 250 bytes plus its status word), so replies longer than asked are tried too.
         */
        const val MAX_REPLY = 1024
    }
}
