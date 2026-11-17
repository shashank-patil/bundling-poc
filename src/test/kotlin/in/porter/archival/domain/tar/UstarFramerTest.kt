package `in`.porter.archival.domain.tar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UstarFramerTest {

    @Test
    fun `padding rounds up to the next 512 boundary`() {
        assertEquals(0, UstarFramer.padTo512(0))
        assertEquals(512, UstarFramer.padTo512(1))
        assertEquals(512, UstarFramer.padTo512(512))
        assertEquals(1024, UstarFramer.padTo512(513))
        // The LLD's worked example: 171,488 / 512 = 334.94 -> 335 blocks.
        assertEquals(171_520, UstarFramer.padTo512(171_488))
    }

    @Test
    fun `plan reproduces the offsets quoted in the LLD`() {
        // invoice.pdf of 171,488 bytes, then its HTML twin.
        val plan = UstarFramer.plan(listOf(171_488L, 4_096L))

        assertEquals(512L, plan.offsets[0], "first payload sits straight after its header")
        assertEquals(172_544L, plan.offsets[1], "second payload, after 32 bytes of padding")
        // The second header therefore begins at exactly 172,032.
        assertEquals(172_032L, plan.offsets[1] - UstarFramer.BLOCK)
    }

    @Test
    fun `every planned offset is 512-aligned and strictly increasing`() {
        val lengths = (1..500).map { (it * 7919L) % 300_000 }
        val plan = UstarFramer.plan(lengths)

        var previous = -1L
        plan.offsets.forEachIndexed { i, offset ->
            assertEquals(0, offset % UstarFramer.BLOCK, "offset $i is not 512-aligned")
            assertTrue(offset > previous, "offsets must strictly increase")
            previous = offset
        }
        assertEquals(0, plan.cursorAfter % UstarFramer.BLOCK)
    }

    @Test
    fun `a planned member never overlaps the next header`() {
        val lengths = listOf(1L, 511L, 512L, 513L, 1_048_576L)
        val plan = UstarFramer.plan(lengths)

        for (i in 0 until lengths.size - 1) {
            val endOfPayload = plan.offsets[i] + lengths[i]
            val nextHeader = plan.offsets[i + 1] - UstarFramer.BLOCK
            assertTrue(endOfPayload <= nextHeader, "member $i runs into the next header")
        }
    }

    @Test
    fun `header is one block and its checksum validates`() {
        val h = UstarFramer.header("order-invoices/abc/invoice_CRN1.pdf", 171_488, 1_700_000_000)
        assertEquals(512, h.size)

        val stored = String(h, 148, 8, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        var sum = 0L
        h.forEachIndexed { i, b -> sum += if (i in 148 until 156) ' '.code.toLong() else (b.toInt() and 0xFF).toLong() }
        assertEquals(java.lang.Long.toOctalString(sum).padStart(6, '0'), stored)

        assertEquals("ustar", String(h, 257, 5, Charsets.US_ASCII))
        assertEquals('0'.code.toByte(), h[156], "typeflag must be a regular file")
    }

    @Test
    fun `long paths are split on a slash across prefix and name`() {
        val deep = "order-invoices/" + "x".repeat(120) + "/invoice_CRN2084131969.pdf"
        val (name, prefix) = UstarFramer.splitName(deep)

        assertEquals("invoice_CRN2084131969.pdf", name)
        assertTrue(prefix.length <= 155)
        assertEquals(deep, "$prefix/$name")
    }

    @Test
    fun `an unsplittable name throws rather than shifting every later offset`() {
        // This is the entire reason for choosing USTAR over GNU or PAX.
        assertFailsWith<IllegalArgumentException> {
            UstarFramer.splitName("a".repeat(300))
        }
        assertFailsWith<IllegalArgumentException> {
            UstarFramer.splitName("directory/" + "f".repeat(150))
        }
    }

    @Test
    fun `the same plan always produces byte-identical headers`() {
        val a = UstarFramer.header("order-invoices/a/invoice_CRN7.pdf", 1234, 1_700_000_000)
        val b = UstarFramer.header("order-invoices/a/invoice_CRN7.pdf", 1234, 1_700_000_000)
        assertTrue(a.contentEquals(b), "re-packing must be reproducible")
    }
}
