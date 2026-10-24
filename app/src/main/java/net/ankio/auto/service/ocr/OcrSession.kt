package net.ankio.auto.service.ocr

/** Shared across the accessibility service, OCR worker, banners and bill sheets. */
object OcrSession {
    val gate = org.ezbook.server.tools.AutoOcrGate()
}
