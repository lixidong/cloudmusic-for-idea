package org.example.musicplugin.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.image.BufferedImage

internal object QrCodeRenderer {

    fun render(content: String, size: Int = 200): BufferedImage {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val config = MatrixToImageConfig(Color.BLACK.rgb, Color.WHITE.rgb)
        return MatrixToImageWriter.toBufferedImage(matrix, config)
    }
}
