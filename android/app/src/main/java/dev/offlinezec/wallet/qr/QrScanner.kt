package dev.offlinezec.wallet.qr

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * CameraX analyzer: decodes a QR code from a camera frame's luminance plane and reports its
 * text. Decodes at most [maxPerSecond] frames (the sender shows 5 QR frames a second) and reuses
 * its buffer: allocating a fresh luminance copy per camera frame kept low-memory phones in
 * constant garbage collection.
 */
class QrAnalyzer(private val maxPerSecond: Int = 12, private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1")
    private var buffer = ByteArray(0)
    private var lastMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMs < 1000L / maxPerSecond) return
            lastMs = now
            val plane = image.planes[0]
            val buf = plane.buffer
            val n = buf.remaining()
            if (buffer.size != n) buffer = ByteArray(n)
            buf.get(buffer, 0, n)
            val source = PlanarYUVLuminanceSource(
                buffer, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
            )
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            onText(result.text)
        } catch (_: NotFoundException) {
        } catch (_: ChecksumException) {
        } catch (_: FormatException) {
        } finally {
            reader.reset()
            image.close()
        }
    }
}
