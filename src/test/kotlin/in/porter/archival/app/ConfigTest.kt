package `in`.porter.archival.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun `archival yaml loads and reads the way the LLD writes it`() {
        val c = Config.load().archival

        assertEquals("dom-ordersystem-prod-gcs-completed-orders", c.sourceBucket)
        assertEquals("order-invoices/", c.sourcePrefix)
        assertEquals(90, c.cutoffDays)

        // 1GiB and 16MiB in the file, bytes in the config.
        assertEquals(1L shl 30, c.targetBundleBytes)
        assertEquals(16 shl 20, c.uploadChunkBytes)
        assertEquals(64L shl 20, c.queueBytes)

        // 45m in the file.
        assertEquals(45, c.runBudgetMinutes)
        assertEquals(120, c.leaseMinutes)
        assertEquals(5, c.maxAttempts)

        assertEquals("cohort_date", c.partitionKey)
        assertEquals("monthly", c.partitionInterval)
        assertEquals(24, c.livePartitions)
        assertEquals("detach-verify-drop", c.dropPolicy)

        assertEquals(listOf("INVOICE", "CN_DN"), c.docKinds)
        assertTrue("completed-orders-map/" in c.excludedPrefixes, "route maps must never be archived")
    }

    @Test
    fun `engine properties hold only what the LLD does not specify`() {
        val e = Config.load().engine
        assertEquals(4, e.dbPoolSize)
        assertEquals(20000, e.listerPageSize)
    }

    @Test
    fun `size and duration suffixes parse the way the LLD writes them`() {
        assertEquals(6L shl 30, Units.toBytes("6GiB"))
        assertEquals(16L shl 20, Units.toBytes("16MiB"))
        assertEquals(256L shl 10, Units.toBytes("256KiB"))
        assertEquals(1024L, Units.toBytes("1024"))

        assertEquals(45, Units.toMinutes("45m"))
        assertEquals(120, Units.toMinutes("2h"))
        assertEquals(120, Units.toMinutes("120"))

        assertFailsWith<IllegalStateException> { Units.toBytes("6 gigabytes") }
    }

    @Test
    fun `an upload chunk that is not a multiple of 256KiB is refused at boot`() {
        // Firm in the LLD because GCS resumable upload constrains it, so this
        // has to fail loudly rather than at the first upload.
        val e = assertFailsWith<IllegalArgumentException> {
            sampleConfig(uploadChunkBytes = 1_000_000)
        }
        assertTrue(e.message!!.contains("256KiB"))
    }

    @Test
    fun `a queue smaller than one upload chunk is refused`() {
        assertFailsWith<IllegalArgumentException> {
            sampleConfig(queueBytes = 1L shl 20)
        }
    }

    private fun sampleConfig(
        uploadChunkBytes: Int = 16 shl 20,
        queueBytes: Long = 64L shl 20,
    ) = ArchivalConfig(
        sourceBucket = "b", sourcePrefix = "p/", archiveBucket = "a",
        cutoffDays = 90, targetBundleBytes = 1L shl 30, maxBundleMembers = 20_000,
        uploadChunkBytes = uploadChunkBytes, queueBytes = queueBytes,
        fetchConcurrency = 8, runBudgetMinutes = 45, leaseMinutes = 120,
        maxAttempts = 5, verifySampleRatio = 0.0, tarLayoutVersion = 1,
        partitionKey = "cohort_date", partitionInterval = "monthly",
        livePartitions = 24, partitionPremake = 3, dropPolicy = "detach-verify-drop",
        docKinds = listOf("INVOICE"), docFormats = listOf("pdf"),
        excludedPrefixes = emptyList(),
    )
}
