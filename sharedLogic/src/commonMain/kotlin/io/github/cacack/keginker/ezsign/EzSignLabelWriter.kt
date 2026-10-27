package io.github.cacack.keginker.ezsign

import io.github.cacack.keginker.LabelDisplay
import io.github.cacack.keginker.diagnostics.ExchangeLog
import io.github.cacack.keginker.nfc.Transport
import io.github.cacack.keginker.probe.DEVICE_INFO
import io.github.cacack.keginker.probe.NDEF_APPLICATION_AID
import io.github.cacack.keginker.probe.selectAid
import io.github.cacack.keginker.waveshare.FailureKind
import io.github.cacack.keginker.waveshare.WriteResult
import io.github.cacack.keginker.waveshare.WriteStep
import io.github.cacack.keginker.waveshare.exchange
import io.github.cacack.keginker.waveshare.runSteps

/** What an ISO-DEP tag's EZ Sign device information says it is, from [identifyEzSign]. */
internal sealed interface EzSignIdentity {
    /** Its device information decoded; [display] is the [LabelDisplay] of its size, if there is one. */
    class Identified(val info: EzSignDeviceInfo) : EzSignIdentity {
        val display: LabelDisplay? = LabelDisplay.entries.firstOrNull {
            it.isEzSign && it.nativeWidth == info.nativeWidth && it.nativeHeight == info.nativeHeight
        }
    }

    /** The tag answered, but not as an EZ Sign display: [reason] says how, in plain English. */
    class NotEzSign(val reason: String) : EzSignIdentity

    /** Communication with the tag broke off. */
    class Failed(val failure: WriteResult.Failure) : EzSignIdentity
}

/**
 * Asks the tag over [transport] what it is: SELECT the NDEF application,
 * then the EZ Sign device-information query, decoded with
 * [parseEzSignDeviceInfo]. Both are reads; every exchange goes in [log].
 * Cancellation propagates.
 */
internal suspend fun identifyEzSign(transport: Transport, log: ExchangeLog): EzSignIdentity {
    var selected = ByteArray(0)
    var deviceInfo = ByteArray(0)
    val sent = runSteps {
        selected = transport.exchange(WriteStep.INIT, selectAid(NDEF_APPLICATION_AID), log = log)
        if (isEzSignOk(selected)) deviceInfo = transport.exchange(WriteStep.INIT, DEVICE_INFO.copyOf(), log = log)
    }
    if (sent is WriteResult.Failure) return EzSignIdentity.Failed(sent)
    if (!isEzSignOk(selected)) return EzSignIdentity.NotEzSign("the tag refused the NDEF application")
    return when (val parse = parseEzSignDeviceInfo(deviceInfo)) {
        is EzSignDeviceInfoParse.Decoded -> EzSignIdentity.Identified(parse.info)
        is EzSignDeviceInfoParse.NotDecoded -> EzSignIdentity.NotEzSign("its device information didn't decode: ${parse.reason}")
    }
}

/**
 * Writes labels to the EZ Sign display [info] describes, over [transport]
 * with [sendEzSignFrame], each block compressed with [lzo1xCompress].
 */
internal class EzSignLabelWriter(
    private val transport: Transport,
    private val info: EzSignDeviceInfo,
) {
    /**
     * Writes [view] (row-major [LabelDisplay.palette] indices,
     * [LabelDisplay.viewWidth] wide), composed for [display], recording every exchange in [log].
     * [onProgress] receives 0–100 and never decreases. Cancellation propagates.
     *
     * A blocking refresh that never answers, whether its timeout ran out or
     * the transport failed, is [FailureKind.REFRESH_UNCONFIRMED]: the panel
     * may have drawn, but the tag never said so.
     */
    suspend fun write(display: LabelDisplay, view: ByteArray, log: ExchangeLog, onProgress: (Int) -> Unit = {}): WriteResult {
        require(display.nativeWidth == info.nativeWidth && display.nativeHeight == info.nativeHeight) {
            "${display.name} is ${display.nativeWidth} × ${display.nativeHeight}; the panel is ${info.nativeWidth} × ${info.nativeHeight}"
        }
        val streams = frameBlocks(packFrame(ezSignLabelPixels(display, view), info)).map(::lzo1xCompress)
        val result = runSteps { transport.sendEzSignFrame(streams, log, onProgress) }
        return if (result is WriteResult.Failure && result.step == WriteStep.REFRESH && result.kind == FailureKind.LOST_CONTACT) {
            result.copy(kind = FailureKind.REFRESH_UNCONFIRMED)
        } else {
            result
        }
    }
}
