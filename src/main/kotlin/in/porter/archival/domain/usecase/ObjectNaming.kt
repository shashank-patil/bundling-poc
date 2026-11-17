package `in`.porter.archival.domain.usecase

import `in`.porter.archival.domain.model.DocFormat
import `in`.porter.archival.domain.model.DocKind
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The stored URI already carries bucket, object path and generation, so nothing
 * has to be reconstructed and a member needs exactly one stat call.
 *
 * Shape, straight from production:
 *   https://storage.googleapis.com/download/storage/v1/b/<bucket>/o/<encoded path>?generation=<n>
 */
object ObjectNaming {

    private val URI_PATTERN =
        Regex("""/b/(?<bucket>[^/]+)/o/(?<path>[^?]+)(?:\?generation=(?<generation>\d+))?""")

    data class Parsed(val bucket: String, val path: String, val generation: Long?)

    fun parseUri(uri: String): Parsed? {
        val m = URI_PATTERN.find(uri) ?: return null
        return Parsed(
            bucket = m.groups["bucket"]!!.value,
            path = URLDecoder.decode(m.groups["path"]!!.value, StandardCharsets.UTF_8),
            generation = m.groups["generation"]?.value?.toLong(),
        )
    }

    /**
     * The object name gives us the CRN, the document kind and the format. It does
     * NOT give us the date - which is exactly why the job is driven by a database
     * worklist and not by a bucket listing.
     */
    data class Named(val crn: String, val kind: DocKind, val format: DocFormat)

    private val CN_DN = Regex("""^invoice_CN_DN_(?<crn>CRN[A-Za-z0-9]+)\.(?<ext>pdf|html)$""")
    private val INVOICE = Regex("""^invoice_(?<crn>CRN[A-Za-z0-9]+)\.(?<ext>pdf|html)$""")

    fun nameOf(path: String): Named? {
        val file = path.substringAfterLast('/')
        CN_DN.find(file)?.let {
            return Named(it.groups["crn"]!!.value, DocKind.CN_DN, DocFormat.ofExtension(it.groups["ext"]!!.value))
        }
        INVOICE.find(file)?.let {
            return Named(it.groups["crn"]!!.value, DocKind.INVOICE, DocFormat.ofExtension(it.groups["ext"]!!.value))
        }
        return null
    }

    /** Every invoice PDF has an HTML twin written beside it; both are archived. */
    fun htmlTwinOf(pdfPath: String): String = pdfPath.removeSuffix(".pdf") + ".html"

    /** The tar's entry name keeps the full source path, so `tar -xf` restores the layout. */
    fun entryNameFor(sourcePath: String): String = sourcePath
}
