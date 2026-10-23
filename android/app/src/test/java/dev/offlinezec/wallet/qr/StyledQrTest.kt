package dev.offlinezec.wallet.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

/** The styled address QR (light dots on dark, logo hole) still decodes, read inverted as the app's scanner does. */
class StyledQrTest {
    @Test
    fun decodesInverted() {
        val addr = "utest1599vwucgf5m5ndgj76lepjux43qgahsm028hmdw2fu8nnmqyqv5z6k5c3mfch36w83ugs45q03ev8dqh3l843nkes08nmz0htkcdd3iqdp" +
            "2xk7aqq0cz8x6zsem0rmg7yu53xlxsqfvwzhv4e0pfmy3wrf8vzqj"
        for (size in listOf(400, 720)) {
            val px = styledQrPixels(addr, size)
            val src = RGBLuminanceSource(size, size, px).invert()
            val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), mapOf(DecodeHintType.CHARACTER_SET to "ISO-8859-1"))
            assertEquals(addr, r.text)
        }
    }
}
