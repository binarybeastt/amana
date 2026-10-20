package dev.offlinezec.wallet.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The animated-QR path without a camera: payload -> fountain frames -> QR images -> decoded text
 * -> reassembled payload. Supports P2.G4.T1/T2 (which need real phones for the camera part).
 */
class AnimatedQrTest {
    /** Renders a frame as a QR image and reads it back, as the scanner would; null if unreadable. */
    private fun throughQr(text: String): String? = try {
        readQr(text)
    } catch (_: com.google.zxing.ReaderException) {
        unreadable++
        null // ZXing misses ~1.5% of frames even from a clean image; a camera would too.
    }

    private var unreadable = 0

    private fun readQr(text: String): String {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else -1 }
        val src = RGBLuminanceSource(m.width, m.height, px)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src))).text
    }

    /** Feeds frames until done; [drop] = chance each frame is missed. Returns frames shown. */
    private fun transfer(payload: ByteArray, drop: Double, rnd: Random, viaQr: Boolean): Pair<ByteArray, Int> {
        val enc = FrameEncoder(payload)
        val dec = FrameDecoder()
        var shown = 0
        while (shown < 20 * enc.seqLen) {
            val frame = enc.next()
            shown++
            if (rnd.nextDouble() < drop) continue
            val scanned = (if (viaQr) throughQr(frame) else frame) ?: continue
            when (val st = dec.receive(scanned)) {
                is FrameDecoder.State.Done -> return st.payload to shown
                is FrameDecoder.State.Failed -> error("decoder failed: ${st.error}")
                FrameDecoder.State.Collecting -> {}
            }
        }
        error("not complete after $shown frames")
    }

    @Test
    fun twenty_20kb_transfers_through_real_qr_images_are_intact() {
        val rnd = Random(317)
        repeat(20) {
            val payload = rnd.nextBytes(20 * 1024)
            val (got, shown) = transfer(payload, drop = 0.0, rnd = rnd, viaQr = true)
            assertArrayEquals(payload, got)
            assertTrue("needed $shown frames", shown <= 2 * FrameEncoder(payload).seqLen)
            frames += shown
        }
        println("20 x 20 KB: ${FrameEncoder(ByteArray(20 * 1024)).seqLen} parts each, $frames frames shown in all, $unreadable unreadable")
    }

    private var frames = 0

    @Test
    fun skipped_frames_still_complete() {
        val rnd = Random(9166)
        for (drop in listOf(0.1, 0.3, 0.5)) {
            repeat(50) {
                val payload = rnd.nextBytes(9166)
                val (got, shown) = transfer(payload, drop, rnd, viaQr = false)
                assertArrayEquals(payload, got)
                assertTrue("drop $drop needed $shown frames", shown < 10 * FrameEncoder(payload).seqLen)
            }
        }
    }

    @Test
    fun foreign_and_garbled_frames_are_ignored() {
        val payload = Random(1).nextBytes(9166)
        val enc = FrameEncoder(payload)
        val dec = FrameDecoder()
        assertTrue(dec.receive("utest1notaframe") is FrameDecoder.State.Collecting)
        assertTrue(dec.receive("UR:BYTES/1-23/LPADCSZZZZ") is FrameDecoder.State.Collecting)
        var st: FrameDecoder.State = FrameDecoder.State.Collecting
        while (st !is FrameDecoder.State.Done) st = dec.receive(enc.next())
        assertArrayEquals(payload, st.payload)
    }
}
