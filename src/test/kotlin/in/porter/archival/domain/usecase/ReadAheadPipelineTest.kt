package `in`.porter.archival.domain.usecase

import `in`.porter.archival.domain.model.CohortDate
import `in`.porter.archival.domain.port.ObjectMetadata
import `in`.porter.archival.domain.port.ObjectStore
import `in`.porter.archival.domain.port.ObjectUnreadable
import `in`.porter.archival.domain.port.StoredMember
import `in`.porter.archival.domain.model.SourceObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadAheadPipelineTest {

    private val bucket = "src-bucket"

    /** Hands back deterministic bytes, and can be told to fail one path. */
    private class FakeStore(
        private val sizes: Map<String, Int>,
        private val failOn: String? = null,
        private val delayMillis: Long = 0,
    ) : ObjectStore {
        val concurrentReads = AtomicInteger()
        val peakConcurrentReads = AtomicInteger()

        override fun stat(bucket: String, path: String) =
            sizes[path]?.let { ObjectMetadata(path, 1, it.toLong(), "crc", "md5") }

        override fun openRange(
            bucket: String, path: String, generation: Long, offset: Long, length: Long,
        ): InputStream {
            if (path == failOn) throw ObjectUnreadable(bucket, path)
            val now = concurrentReads.incrementAndGet()
            peakConcurrentReads.updateAndGet { maxOf(it, now) }
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis)
                // Each byte is the low byte of the path hash, so the writer can
                // prove it received the right payload for the right member.
                return ByteArrayInputStream(ByteArray(length.toInt()) { path.hashCode().toByte() })
            } finally {
                concurrentReads.decrementAndGet()
            }
        }

        override fun createOnly(bucket: String, path: String, write: (OutputStream) -> Unit) =
            error("not used")

        override fun delete(bucket: String, path: String, generation: Long) = Unit
        override fun exists(bucket: String, path: String) = sizes.containsKey(path)
    }

    private fun member(name: String, length: Int) = StoredMember(
        cohortDate = CohortDate(LocalDate.of(2026, 8, 14)),
        source = SourceObject(bucket, name, 1, length.toLong(), "crc"),
        crn = "CRN$name", docKind = "INVOICE", docFormat = "pdf",
        bundleId = 1, offsetBytes = 0, lengthBytes = length.toLong(), crc32c = "crc",
    )

    @Test
    fun `writer receives every member exactly once, in planned order`() {
        val members = (0 until 200).map { member("obj-$it", 1_000 + it) }
        val store = FakeStore(members.associate { it.source.path to it.lengthBytes.toInt() }, delayMillis = 1)

        val seen = mutableListOf<String>()
        ReadAheadPipeline(store, bucket, fetchConcurrency = 8, queueBytes = 64 * 1024).use { p ->
            p.stream(members) { m, bytes ->
                seen += m.source.path
                assertEquals(m.lengthBytes.toInt(), bytes.size, "payload length must match the member row")
                assertEquals(m.source.path.hashCode().toByte(), bytes[0], "payload belongs to a different member")
            }
        }
        assertEquals(members.map { it.source.path }, seen, "the tar must be written in planned order")
    }

    @Test
    fun `reads actually run concurrently`() {
        val members = (0 until 64).map { member("obj-$it", 1_024) }
        val store = FakeStore(members.associate { it.source.path to 1_024 }, delayMillis = 20)

        ReadAheadPipeline(store, bucket, fetchConcurrency = 8, queueBytes = 1 shl 20).use { p ->
            p.stream(members) { _, _ -> }
        }
        assertTrue(
            store.peakConcurrentReads.get() > 1,
            "expected overlapping reads, saw ${store.peakConcurrentReads.get()}",
        )
    }

    @Test
    fun `the queue stays inside its byte budget`() {
        val budget = 32 * 1024L
        val members = (0 until 300).map { member("obj-$it", 4_096) }
        val store = FakeStore(members.associate { it.source.path to 4_096 }, delayMillis = 1)

        val stats = ReadAheadPipeline(store, bucket, fetchConcurrency = 16, queueBytes = budget).use { p ->
            p.stream(members) { _, _ -> }
        }
        assertTrue(
            stats.peakQueueBytes <= budget,
            "peak queue ${stats.peakQueueBytes} exceeded the ${budget} byte budget",
        )
    }

    @Test
    fun `a member larger than the whole budget still makes progress`() {
        // Admitting it alone is what stops the run deadlocking on its own bound.
        val members = listOf(member("small", 512), member("huge", 1 shl 20), member("after", 512))
        val store = FakeStore(members.associate { it.source.path to it.lengthBytes.toInt() })

        val seen = mutableListOf<String>()
        ReadAheadPipeline(store, bucket, fetchConcurrency = 4, queueBytes = 4_096).use { p ->
            p.stream(members) { m, _ -> seen += m.source.path }
        }
        assertEquals(listOf("small", "huge", "after"), seen)
    }

    @Test
    fun `an unreadable member surfaces as ObjectUnreadable, not a wrapped execution error`() {
        val members = (0 until 20).map { member("obj-$it", 1_024) }
        val store = FakeStore(members.associate { it.source.path to 1_024 }, failOn = "obj-7")

        assertFailsWith<ObjectUnreadable> {
            ReadAheadPipeline(store, bucket, fetchConcurrency = 4, queueBytes = 1 shl 16).use { p ->
                p.stream(members) { _, _ -> }
            }
        }
    }
}
