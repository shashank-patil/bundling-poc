package `in`.porter.archival.domain.usecase

import `in`.porter.archival.domain.port.ObjectStore
import `in`.porter.archival.domain.port.ObjectUnreadable
import `in`.porter.archival.domain.port.StoredMember
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Many readers, one writer, and a queue bounded in bytes.
 *
 * A tar has to be written strictly in offset order, but fetching one object at
 * a time would leave the writer idle for the whole of every round trip. So
 * `fetchConcurrency` readers run ahead of the writer, and the writer drains
 * them in the exact order the Lister planned.
 *
 * The queue is bounded in BYTES rather than rows, because rows say nothing
 * about memory: 20,000 small HTML twins and 20,000 large PDFs are the same
 * number and two very different heaps. With a 64 MiB bound the heap stays flat
 * whatever the bundle size - which is what lets a 6 GiB tar be written inside
 * 512 MB.
 *
 * Why it cannot deadlock: the writer thread alone decides what to submit, and
 * it never submits work it is not already committed to draining in order. If
 * the budget is exhausted it simply stops submitting and waits for the head of
 * the queue, which is always already in flight. A single member larger than the
 * whole budget is allowed through on its own rather than blocking forever.
 */
class ReadAheadPipeline(
    private val store: ObjectStore,
    private val bucket: String,
    private val fetchConcurrency: Int,
    private val queueBytes: Long,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    private val pool = Executors.newFixedThreadPool(fetchConcurrency) { r ->
        Thread(r, "member-reader").apply { isDaemon = true }
    }

    private class InFlight(val member: StoredMember, val future: Future<ByteArray>)

    data class Stats(val peakQueueBytes: Long, val peakInFlight: Int, val membersRead: Int)

    /**
     * Streams every member to [write] in planned order. [write] runs on the
     * caller's thread, so the writer stays single-threaded and the tar is
     * assembled sequentially.
     */
    fun stream(members: List<StoredMember>, write: (StoredMember, ByteArray) -> Unit): Stats {
        val inFlight = ArrayDeque<InFlight>()
        var queued = 0L
        var next = 0
        var peakBytes = 0L
        var peakDepth = 0

        try {
            for (position in members.indices) {
                // Top up the read-ahead window. The first member of an empty
                // queue is always admitted, so an object larger than the whole
                // budget still makes progress instead of stalling the run.
                while (next < members.size) {
                    val candidate = members[next]
                    val admitAnyway = inFlight.isEmpty()
                    val fitsInBudget = queued + candidate.lengthBytes <= queueBytes
                    val belowDepthCap = inFlight.size < fetchConcurrency * 2
                    if (!admitAnyway && !(fitsInBudget && belowDepthCap)) break

                    queued += candidate.lengthBytes
                    inFlight.addLast(InFlight(candidate, pool.submit<ByteArray> { read(candidate) }))
                    next++

                    peakBytes = maxOf(peakBytes, queued)
                    peakDepth = maxOf(peakDepth, inFlight.size)
                    if (admitAnyway && !fitsInBudget) break
                }

                val head = inFlight.removeFirst()
                val bytes = awaitPayload(head)
                write(head.member, bytes)
                queued -= head.member.lengthBytes
            }
        } catch (t: Throwable) {
            inFlight.forEach { it.future.cancel(true) }
            throw t
        }

        log.info(
            "pipeline drained {} members, peak queue {} KiB across at most {} in-flight reads",
            members.size, peakBytes / 1024, peakDepth,
        )
        return Stats(peakBytes, peakDepth, members.size)
    }

    private fun awaitPayload(entry: InFlight): ByteArray =
        try {
            entry.future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: ObjectUnreadable(bucket, entry.member.source.path, e)
        }

    /** A generation-pinned read of exactly the recorded length. */
    private fun read(member: StoredMember): ByteArray {
        val bytes = store.openRange(
            bucket,
            member.source.path,
            member.source.generation,
            0,
            member.lengthBytes,
        ).use { it.readBytes() }

        if (bytes.size.toLong() != member.lengthBytes) {
            throw ObjectUnreadable(
                bucket, member.source.path,
                IllegalStateException("read ${bytes.size} of ${member.lengthBytes} bytes"),
            )
        }
        return bytes
    }

    override fun close() {
        pool.shutdownNow()
    }
}
