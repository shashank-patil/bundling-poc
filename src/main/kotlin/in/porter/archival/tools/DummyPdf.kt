package `in`.porter.archival.tools

import java.io.ByteArrayOutputStream

/**
 * A real, openable PDF padded to a target size, so the demo can extract a
 * bundle with `tar -xf` and actually open what comes out. Offsets in the xref
 * table are computed as the file is built, so the result is structurally valid.
 */
object DummyPdf {

    fun build(crn: String, targetBytes: Int): ByteArray {
        val body = "BT /F1 16 Tf 60 780 Td (Tax Invoice $crn) Tj ET\n" +
            "BT /F1 10 Tf 60 750 Td (Porter - bundling PoC sample document) Tj ET"

        val objects = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R" +
                "/Resources<</Font<</F1 5 0 R>>>>>>",
            "<</Length ${body.length}>>stream\n$body\nendstream",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>",
        )

        val out = ByteArrayOutputStream(targetBytes + 4096)
        val offsets = IntArray(objects.size + 1)

        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

        write("%PDF-1.4\n%âãÏÓ\n")
        objects.forEachIndexed { i, obj ->
            offsets[i + 1] = out.size()
            write("${i + 1} 0 obj\n$obj\nendobj\n")
        }

        // Padding as a PDF comment: legal anywhere, and it is what gets the file
        // to a realistic invoice size without changing how it renders.
        val overhead = out.size() + 160 + objects.size * 20
        if (targetBytes > overhead) {
            val filler = targetBytes - overhead
            write("% ")
            val chunk = "0123456789abcdef".repeat(64)
            var written = 0
            while (written < filler) {
                val take = minOf(chunk.length, filler - written)
                write(chunk.substring(0, take))
                written += take
            }
            write("\n")
        }

        val xrefAt = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (i in 1..objects.size) write("%010d 00000 n \n".format(offsets[i]))
        write("trailer\n<</Size ${objects.size + 1}/Root 1 0 R>>\nstartxref\n$xrefAt\n%%EOF\n")

        return out.toByteArray()
    }

    fun buildHtml(crn: String): ByteArray =
        """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><title>Tax Invoice $crn</title></head>
        <body><h1>Tax Invoice $crn</h1>
        <p>Porter - bundling PoC sample document. The HTML twin written beside every invoice PDF.</p>
        </body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
}
