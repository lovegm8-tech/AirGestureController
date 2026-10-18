package tw.airgesture.transferbeta.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Lossless, uncompressed multi-file transport container. It travels as ONE
 * existing authenticated Nearby FILE payload, so the 30-second multi-receiver
 * admission and one-recipient-at-a-time protocol are unchanged.
 *
 * Each original file's bytes and SHA-256 are verified before the receiver makes
 * its MediaStore entries visible. Do not open ZIP paths as filesystem paths.
 */
object AirGrabBundle {
    const val EXTENSION = ".airgrab"
    const val MAX_FILES = 32
    private const val MANIFEST = "META-INF/AIRGRAB-V1.manifest"
    private const val HEADER = "AIRGRAB-1"
    private const val MAX_MANIFEST_BYTES = 16_384

    data class Source(val name: String, val open: () -> InputStream)
    data class Record(val name: String, val bytes: Long, val sha256: String)

    fun isBundle(name: String): Boolean = name.endsWith(EXTENSION, ignoreCase = true)

    private fun digestHex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

    private fun encodedName(name: String): String =
        Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))

    private fun decodedName(encoded: String): String =
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)

    fun pack(target: File, sources: List<Source>, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(sources.size in 2..MAX_FILES) { "請選取 2 至 $MAX_FILES 個檔案" }
        val names = sources.map {
            TransferPolicy.safeName(it.name) ?: error("檔案名稱無效")
        }
        require(names.all { !isBundle(it) }) { "不能將 AirGrab 傳輸包再次包裝" }
        val records = ArrayList<Record>()
        var total = 0L
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                for ((index, source) in sources.withIndex()) {
                    val name = names[index]
                    progress(index + 1, sources.size)
                    zip.putNextEntry(ZipEntry("files/$index/$name"))
                    var count = 0L
                    val sha = MessageDigest.getInstance("SHA-256")
                    source.open().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            count += n
                            total += n
                            require(count <= TransferPolicy.MAX_BYTES &&
                                total <= TransferPolicy.MAX_BYTES) { "檔案總大小超過限制" }
                            sha.update(buf, 0, n)
                            zip.write(buf, 0, n)
                        }
                    }
                    require(count > 0L) { "不支援空檔案" }
                    zip.closeEntry()
                    records.add(Record(name, count, digestHex(sha)))
                }
                val lines = buildString {
                    append(HEADER).append('\n')
                    for ((index, entry) in records.withIndex())
                        append(index).append('\t').append(encodedName(entry.name))
                            .append('\t').append(entry.bytes).append('\t')
                            .append(entry.sha256).append('\n')
                }.toByteArray(Charsets.UTF_8)
                require(lines.size <= MAX_MANIFEST_BYTES)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(lines)
                zip.closeEntry()
            }
            require(target.length() <= TransferPolicy.MAX_BYTES) {
                "封裝後超過單次傳輸上限"
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** openOutput creates a pending destination. Caller must publish ONLY on success. */
    fun unpack(
        input: InputStream,
        expectedCount: Int,
        openOutput: (String) -> OutputStream,
        progress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Record> {
        require(expectedCount in 2..MAX_FILES)
        val records = ArrayList<Record>()
        var total = 0L
        var manifest: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory)
                if (entry.name == MANIFEST) {
                    require(manifest == null && records.size == expectedCount)
                    val buffer = ByteArray(MAX_MANIFEST_BYTES + 1)
                    var readTotal = 0
                    while (readTotal < buffer.size) {
                        val n = zip.read(buffer, readTotal, buffer.size - readTotal)
                        if (n < 0) break
                        if (n == 0) continue
                        readTotal += n
                    }
                    require(readTotal <= MAX_MANIFEST_BYTES) { "檔案清單過大" }
                    manifest = String(buffer, 0, readTotal, Charsets.UTF_8)
                    zip.closeEntry()
                    require(zip.nextEntry == null) { "傳輸包有多餘資料" }
                    break
                }
                val idx = records.size
                require(idx < expectedCount && idx < MAX_FILES) { "檔案數量不符" }
                val prefix = "files/$idx/"
                require(entry.name.startsWith(prefix))
                val name = entry.name.removePrefix(prefix)
                require(name == TransferPolicy.safeName(name) && name.isNotEmpty())
                require(!name.contains('/') && !name.contains('\\'))
                progress(idx + 1, expectedCount)
                val md = MessageDigest.getInstance("SHA-256")
                var count = 0L
                openOutput(name).use { output ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        val n = zip.read(bytes)
                        if (n < 0) break
                        if (n == 0) continue
                        count += n
                        total += n
                        require(count <= TransferPolicy.MAX_BYTES &&
                            total <= TransferPolicy.MAX_BYTES) { "解包大小超過限制" }
                        md.update(bytes, 0, n)
                        output.write(bytes, 0, n)
                    }
                }
                require(count > 0L) { "不支援空檔案" }
                zip.closeEntry()
                records.add(Record(name, count, digestHex(md)))
            }
        }
        val received = manifest ?: error("缺少傳輸包驗證清單")
        val lines = received.trimEnd('\n').split('\n')
        require(lines.size == expectedCount + 1 && lines.first() == HEADER) {
            "傳輸包版本或檔案數量不符"
        }
        for (i in records.indices) {
            val cells = lines[i + 1].split('\t')
            require(cells.size == 4 && cells[0] == i.toString())
            val fromManifest = Record(
                decodedName(cells[1]),
                cells[2].toLong(),
                cells[3]
            )
            require(fromManifest.name == records[i].name &&
                fromManifest.bytes == records[i].bytes &&
                fromManifest.sha256.matches(Regex("[a-f0-9]{64}")) &&
                fromManifest.sha256 == records[i].sha256) {
                "第 ${i + 1} 個原檔 SHA-256 驗證失敗"
            }
        }
        return records
    }
}
