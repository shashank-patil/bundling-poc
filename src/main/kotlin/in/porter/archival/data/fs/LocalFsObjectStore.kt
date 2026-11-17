package `in`.porter.archival.data.fs

import `in`.porter.archival.domain.port.ObjectMetadata
import `in`.porter.archival.domain.port.ObjectStore
import `in`.porter.archival.domain.port.ObjectUnreadable
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32C

/**
 * Stands in for Cloud Storage on the local filesystem.
 *
 * The mapping is deliberately one-for-one so the port stays honest:
 *   stat            -> objects.get, metadata only, no payload read
 *   openRange       -> a generation-pinned ranged GET (RandomAccessFile.seek)
 *   createOnly      -> ifGenerationMatch=0 (StandardOpenOption.CREATE_NEW)
 *   generation      -> microseconds since epoch, as GCS generations are
 *
 * Size, generation and checksum live in a sidecar `.meta` file written when the
 * object is created, so stat() costs no payload read - exactly as GCS metadata
 * is free of the object body.
 */
class LocalFsObjectStore(private val roots: Map<String, Path>) : ObjectStore {

    private fun root(bucket: String): Path =
        roots[bucket] ?: error("no local root configured for bucket '$bucket'")

    private fun resolve(bucket: String, path: String): Path = root(bucket).resolve(path).normalize()

    private fun metaPath(bucket: String, path: String): Path = resolve(bucket, "$path.meta")

    override fun stat(bucket: String, path: String): ObjectMetadata? {
        val file = resolve(bucket, path)
        if (!Files.exists(file)) return null
        val meta = metaPath(bucket, path)
        if (!Files.exists(meta)) return null
        val parts = Files.readString(meta).trim().split(',')
        return ObjectMetadata(
            path = path,
            generation = parts[0].toLong(),
            sizeBytes = parts[1].toLong(),
            crc32c = parts[2],
            md5 = parts[3],
        )
    }

    override fun exists(bucket: String, path: String): Boolean = Files.exists(resolve(bucket, path))

    override fun openRange(
        bucket: String,
        path: String,
        generation: Long,
        offset: Long,
        length: Long,
    ): InputStream {
        val current = stat(bucket, path) ?: throw ObjectUnreadable(bucket, path)
        // A generation-pinned read: if the object was replaced, fail loudly rather
        // than return bytes that disagree with the recorded checksum.
        if (current.generation != generation) {
            throw ObjectUnreadable(
                bucket, path,
                IllegalStateException("generation moved: recorded $generation, found ${current.generation}")
            )
        }
        if (offset + length > current.sizeBytes) {
            throw ObjectUnreadable(
                bucket, path,
                IllegalStateException("range $offset..${offset + length} exceeds size ${current.sizeBytes}")
            )
        }
        return RangeInputStream(resolve(bucket, path), offset, length)
    }

    override fun createOnly(bucket: String, path: String, write: (OutputStream) -> Unit): ObjectMetadata {
        val file = resolve(bucket, path)
        Files.createDirectories(file.parent)

        val crc = CRC32C()
        val md5 = MessageDigest.getInstance("MD5")
        var size = 0L

        // CREATE_NEW is ifGenerationMatch=0: it throws if the object already exists.
        try {
            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { raw ->
                BufferedOutputStream(raw, 1 shl 20).use { out ->
                    val counting = object : OutputStream() {
                        override fun write(b: Int) {
                            out.write(b); crc.update(b); md5.update(b.toByte()); size++
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            out.write(b, off, len); crc.update(b, off, len); md5.update(b, off, len); size += len
                        }
                    }
                    write(counting)
                    counting.flush()
                }
            }
        } catch (t: Throwable) {
            // A half-written object must never survive. GCS discards an
            // abandoned resumable upload for us; on a filesystem we have to do
            // it ourselves, and it has to happen here rather than in the caller:
            // the sidecar is only written on success, so by the time the caller
            // sees the failure there is no metadata left to find the file by.
            runCatching { Files.deleteIfExists(file) }
            throw t
        }

        val meta = ObjectMetadata(
            path = path,
            generation = System.currentTimeMillis() * 1000,
            sizeBytes = size,
            crc32c = encodeCrc32c(crc.value),
            md5 = Base64.getEncoder().encodeToString(md5.digest()),
        )
        writeMeta(bucket, path, meta)
        return meta
    }

    override fun delete(bucket: String, path: String, generation: Long) {
        val current = stat(bucket, path) ?: return
        require(current.generation == generation) {
            "refusing to delete gs://$bucket/$path: generation moved"
        }
        Files.deleteIfExists(resolve(bucket, path))
        Files.deleteIfExists(metaPath(bucket, path))
    }

    fun writeMeta(bucket: String, path: String, meta: ObjectMetadata) {
        val target = metaPath(bucket, path)
        Files.createDirectories(target.parent)
        Files.writeString(target, "${meta.generation},${meta.sizeBytes},${meta.crc32c},${meta.md5}")
    }

    companion object {
        /** GCS reports crc32c as big-endian base64, so the PoC does the same. */
        fun encodeCrc32c(value: Long): String {
            val b = ByteArray(4)
            b[0] = (value ushr 24).toByte()
            b[1] = (value ushr 16).toByte()
            b[2] = (value ushr 8).toByte()
            b[3] = value.toByte()
            return Base64.getEncoder().encodeToString(b)
        }
    }
}

/** Reads exactly [length] bytes starting at [offset]; nothing around them is touched. */
private class RangeInputStream(file: Path, offset: Long, private val length: Long) : InputStream() {
    private val raf = RandomAccessFile(file.toFile(), "r").apply { seek(offset) }
    private var read = 0L

    override fun read(): Int {
        if (read >= length) return -1
        val b = raf.read()
        if (b >= 0) read++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= length) return -1
        val toRead = minOf(len.toLong(), length - read).toInt()
        val n = raf.read(b, off, toRead)
        if (n > 0) read += n
        return n
    }

    override fun close() = raf.close()
}
