package app.raum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-Code als Vektorgrafik: schwarz auf weiß (auch im dunklen Design – Kameras brauchen den Kontrast). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier) {
        drawRect(Color.White)
        val n = matrix.width
        val cell = size.minDimension / n
        val left = (size.width - cell * n) / 2
        val top = (size.height - cell * n) / 2
        for (y in 0 until n) for (x in 0 until n) {
            // leicht überlappend zeichnen, damit keine Haarlinien zwischen Modulen entstehen
            if (matrix[x, y]) drawRect(Color.Black, Offset(left + x * cell, top + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
