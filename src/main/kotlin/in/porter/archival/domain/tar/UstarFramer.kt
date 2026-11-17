package `in`.porter.archival.domain.tar

/**
 * USTAR framing: pure arithmetic and byte building, no I/O.
 *
 * The whole archival design rests on one property - the position of every file
 * is a function of (order, exact sizes) alone, so it can be computed before a
 * single byte is read and written once into the member row.
 *
 * USTAR and not GNU or PAX on purpose: on a name that is too long USTAR refuses
 * and throws, where GNU and PAX silently add an extra card that shifts every
 * later offset by 1,024 bytes and would corrupt the index with no error at all.
 */
object UstarFramer {

    const val BLOCK = 512
    const val END_OF_ARCHIVE_BYTES = 1024L

    private const val NAME_LEN = 100
    private const val PREFIX_LEN = 155

    /** Rounds a payload length up to the next 512-byte boundary. */
    fun padTo512(length: Long): Long = ((length + BLOCK - 1) / BLOCK) * BLOCK

    /**
     * Walks a cursor through the archive handing out offsets.
     *
     *   offset = cursor + 512      (the payload sits straight after its header)
     *   cursor = offset + padTo512(length)
     *
     * Returns the offset of each entry in order, and the cursor left after them.
     */
    fun plan(lengths: List<Long>): PlanResult {
        var cursor = 0L
        val offsets = ArrayList<Long>(lengths.size)
        for (length in lengths) {
            require(length >= 0) { "negative length in plan" }
            val offset = cursor + BLOCK
            offsets.add(offset)
            cursor = offset + padTo512(length)
        }
        return PlanResult(offsets = offsets, cursorAfter = cursor)
    }

    data class PlanResult(val offsets: List<Long>, val cursorAfter: Long)

    /**
     * A 512-byte USTAR header. Mode, uid and gid are pinned so that re-packing the
     * same plan produces byte-identical output.
     */
    fun header(name: String, sizeBytes: Long, mtimeEpochSeconds: Long): ByteArray {
        val (namePart, prefixPart) = splitName(name)
        val h = ByteArray(BLOCK)

        writeString(h, 0, NAME_LEN, namePart)
        writeOctal(h, 100, 8, 420L)                 // mode 0644, pinned
        writeOctal(h, 108, 8, 0L)                   // uid, pinned
        writeOctal(h, 116, 8, 0L)                   // gid, pinned
        writeOctal(h, 124, 12, sizeBytes)
        writeOctal(h, 136, 12, mtimeEpochSeconds)
        // 148..155 is the checksum, filled in below
        h[156] = '0'.code.toByte()                  // typeflag: regular file
        writeString(h, 157, 100, "")                // linkname
        writeString(h, 257, 6, "ustar")             // magic, NUL terminated
        h[263] = '0'.code.toByte()                  // version "00"
        h[264] = '0'.code.toByte()
        writeString(h, 265, 32, "root")             // uname, pinned
        writeString(h, 297, 32, "root")             // gname, pinned
        writeOctal(h, 329, 8, 0L)                   // devmajor
        writeOctal(h, 337, 8, 0L)                   // devminor
        writeString(h, 345, PREFIX_LEN, prefixPart)

        writeChecksum(h)
        return h
    }

    /** Two zero blocks close the archive. */
    fun endOfArchive(): ByteArray = ByteArray(END_OF_ARCHIVE_BYTES.toInt())

    /** The zero padding that follows a payload, so the next header lands on a boundary. */
    fun paddingAfter(length: Long): ByteArray = ByteArray((padTo512(length) - length).toInt())

    /**
     * Splits a path into USTAR's name/prefix pair, or throws.
     *
     * Throwing is the point: a silent fallback is what corrupts an index.
     */
    fun splitName(name: String): Pair<String, String> {
        require(name.isNotEmpty()) { "empty entry name" }
        if (name.length <= NAME_LEN) return name to ""

        require(name.length <= NAME_LEN + PREFIX_LEN + 1) {
            "entry name is ${name.length} chars, USTAR allows at most ${NAME_LEN + PREFIX_LEN + 1}: $name"
        }
        // Split at the last slash that leaves both halves within their fields.
        val cut = name.lastIndexOf('/', startIndex = minOf(PREFIX_LEN, name.length - 1))
        require(cut > 0 && name.length - cut - 1 <= NAME_LEN) {
            "entry name cannot be split into USTAR name/prefix on a slash: $name"
        }
        return name.substring(cut + 1) to name.substring(0, cut)
    }

    private fun writeString(target: ByteArray, at: Int, width: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size < width || (bytes.size == width && value.length == width)) {
            "value does not fit in $width bytes: $value"
        }
        bytes.copyInto(target, at, 0, minOf(bytes.size, width))
    }

    /** Octal, right aligned, zero padded, NUL terminated - the USTAR numeric convention. */
    private fun writeOctal(target: ByteArray, at: Int, width: Int, value: Long) {
        val digits = width - 1
        val text = java.lang.Long.toOctalString(value).padStart(digits, '0')
        require(text.length == digits) { "value $value does not fit in $digits octal digits" }
        text.toByteArray(Charsets.US_ASCII).copyInto(target, at)
        target[at + digits] = 0
    }

    /**
     * Checksum is the unsigned sum of every header byte with the checksum field
     * itself read as spaces. Written as six octal digits, NUL, then a space.
     */
    private fun writeChecksum(h: ByteArray) {
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xFF)
        val text = java.lang.Long.toOctalString(sum).padStart(6, '0')
        text.toByteArray(Charsets.US_ASCII).copyInto(h, 148)
        h[154] = 0
        h[155] = ' '.code.toByte()
    }
}
