package `in`.porter.archival.tools

import `in`.porter.archival.app.Config
import `in`.porter.archival.data.fs.LocalFsObjectStore
import `in`.porter.archival.data.psql.Db
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Generates the corpus the PoC runs against: real PDFs on disk behind the
 * ObjectStore port, and the oms_orders rows whose invoice_pdf_uri points at them
 * in exactly the production URI shape.
 *
 * Everything it writes lands under var/ and is git-ignored.
 */
class SeedTool(
    private val db: Db,
    private val store: LocalFsObjectStore,
    private val config: Config,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Spec(
        val orders: Int,
        val pdfSizeBytes: Int,
        val cohortDate: LocalDate,
        /** Every Nth order also gets a CN/DN that no worklist row points at. */
        val cnDnEvery: Int = 20,
    )

    fun run(spec: Spec) {
        val bucket = config.archival.sourceBucket
        val prefix = config.archival.sourcePrefix
        var orphans = 0

        db.tx { c ->
            c.prepareStatement(
                "INSERT INTO oms_orders (order_id, invoice_pdf_uri, created_at) VALUES (?, ?, ?)"
            ).use { st ->
                // The CRN has to be unique across cohorts, not just within one:
                // seeding a second day must not regenerate the first day's
                // objects, because createOnly is ifGenerationMatch=0 and will
                // rightly refuse to overwrite them.
                val dayOffset = spec.cohortDate.toEpochDay() * 1_000_000L
                repeat(spec.orders) { i ->
                    val crn = "CRN%013d".format(dayOffset + i)
                    // Deterministic render id, so re-seeding produces the same layout.
                    val renderId = UUID.nameUUIDFromBytes(crn.toByteArray()).toString()
                    val pdfPath = "$prefix$renderId/invoice_$crn.pdf"
                    val htmlPath = "$prefix$renderId/invoice_$crn.html"

                    val pdfMeta = store.createOnly(bucket, pdfPath) { out ->
                        out.write(DummyPdf.build(crn, spec.pdfSizeBytes))
                    }
                    store.createOnly(bucket, htmlPath) { out ->
                        out.write(DummyPdf.buildHtml(crn))
                    }

                    if (spec.cnDnEvery > 0 && i % spec.cnDnEvery == 0) {
                        // Deliberately NOT referenced by any order row: this is the
                        // "object no worklist row points at" case from the LLD. It
                        // must survive the whole run untouched.
                        store.createOnly(bucket, "$prefix$renderId/invoice_CN_DN_$crn.pdf") { out ->
                            out.write(DummyPdf.build("$crn-CN", spec.pdfSizeBytes / 4))
                        }
                        orphans++
                    }

                    // The production URI shape: bucket, encoded path and generation.
                    val uri = "https://storage.googleapis.com/download/storage/v1/b/$bucket/o/" +
                        pdfPath.replace("/", "%2F") + "?generation=${pdfMeta.generation}"

                    st.setString(1, crn)
                    st.setString(2, uri)
                    st.setObject(
                        3,
                        spec.cohortDate.atStartOfDay()
                            .plusSeconds((i.toLong() * 86_400L) / maxOf(spec.orders, 1))
                            .atOffset(ZoneOffset.UTC),
                    )
                    st.addBatch()

                    if (i > 0 && i % 500 == 0) {
                        st.executeBatch()
                        log.info("seeded {} / {} orders", i, spec.orders)
                    }
                }
                st.executeBatch()
            }
        }

        log.info(
            "seeded {} orders on {} ({} KB each), plus {} unreferenced CN/DN objects",
            spec.orders, spec.cohortDate, spec.pdfSizeBytes / 1024, orphans,
        )
    }
}
