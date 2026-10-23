package dev.offlinezec.wallet.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder

/**
 * A transaction as an endless stream of QR frames (BC-UR "bytes", fountain-coded): the first
 * [seqLen] frames carry the data once, later ones are XOR mixes of random fragments, so a
 * receiver that misses frames finishes from later ones instead of waiting for a full cycle.
 */
class FrameEncoder(payload: ByteArray, maxFragmentLen: Int = MAX_FRAGMENT_LEN) {
    private val encoder = UREncoder(UR.fromBytes(payload), maxFragmentLen, MIN_FRAGMENT_LEN, 0)

    val seqLen: Int get() = encoder.seqLen

    /** Next frame text, uppercased so the QR uses the compact alphanumeric mode. */
    fun next(): String = encoder.nextPart().uppercase()

    companion object {
        /** 400-byte fragments: a 9 KB payment is 23 frames of QR version ~22. */
        const val MAX_FRAGMENT_LEN = 400
        const val MIN_FRAGMENT_LEN = 10
    }
}

/** Collects frames in any order, with repeats and gaps, until the payload is complete. */
class FrameDecoder {
    private var decoder = URDecoder()

    /** 0..1, for a progress bar. */
    val progress: Double get() = decoder.estimatedPercentComplete

    sealed interface State {
        data object Collecting : State
        class Done(val payload: ByteArray) : State
        class Failed(val error: String) : State
    }

    /** Feeds one scanned frame. Frames that are not ours are ignored. */
    fun receive(frame: String): State {
        val text = frame.trim().lowercase()
        if (!text.startsWith("ur:bytes/")) return State.Collecting
        try {
            decoder.receivePart(text)
        } catch (e: Exception) {
            return State.Collecting // a garbled frame: skip it, later frames make up for it
        }
        val result = decoder.result ?: return State.Collecting
        return if (result.type == ResultType.SUCCESS) {
            try {
                State.Done(result.ur.toBytes())
            } catch (e: Exception) {
                reset()
                State.Failed("bad payload: ${e.message}")
            }
        } else {
            reset()
            State.Failed(result.error ?: "decode failed")
        }
    }

    fun reset() {
        decoder = URDecoder()
    }
}

/**
 * Renders [text] as a black-on-white QR bitmap [size] pixels square. [ec] H leaves room for a
 * logo over the centre (the address QR); animated frames stay at L, the densest.
 */
fun qrBitmap(text: String, size: Int, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.L, margin: Int = 2): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ec,
        EncodeHintType.MARGIN to margin,
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}
