package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AirGrabBundleTest {
    @Test fun originalPhotosAndDocumentAreRestoredByteForByte() {
        val data = listOf(
            "DSC_001.HEIC" to ByteArray(4096) { (it * 19).toByte() },
            "DSC_002.jpg" to ByteArray(8192) { (it * 7).toByte() },
            "private document.pdf" to "%PDF\noriginal".toByteArray()
        )
        val bundle = File.createTempFile("airgrab-test", AirGrabBundle.EXTENSION)
        try {
            val packProgress = mutableListOf<Int>()
            AirGrabBundle.pack(bundle, data.map { (name, bytes) ->
                AirGrabBundle.Source(name) { ByteArrayInputStream(bytes) }
            }) { index, count ->
                assertEquals(3, count)
                packProgress.add(index)
            }
            assertEquals(listOf(1, 2, 3), packProgress)
            val restored = linkedMapOf<String, ByteArrayOutputStream>()
            val progress = mutableListOf<Int>()
            val records = AirGrabBundle.unpack(bundle.inputStream(), 3, { name ->
                ByteArrayOutputStream().also { restored[name] = it }
            }) { index, count ->
                assertEquals(3, count)
                progress.add(index)
            }
            assertEquals(listOf(1, 2, 3), progress)
            assertEquals(data.map { it.first }, records.map { it.name })
            for ((name, bytes) in data) {
                assertArrayEquals(bytes, restored[name]!!.toByteArray())
            }
            assertTrue(records.all { it.sha256.length == 64 && it.bytes > 0 })
        } finally { bundle.delete() }
    }

    @Test fun duplicateOriginalNamesRemainDistinctEntries() {
        val bundle = File.createTempFile("airgrab-same", AirGrabBundle.EXTENSION)
        try {
            AirGrabBundle.pack(bundle, listOf(
                AirGrabBundle.Source("IMG.jpg") { ByteArrayInputStream(byteArrayOf(1, 2)) },
                AirGrabBundle.Source("IMG.jpg") { ByteArrayInputStream(byteArrayOf(3, 4)) }
            ))
            val collected = mutableListOf<ByteArrayOutputStream>()
            val records = AirGrabBundle.unpack(bundle.inputStream(), 2, {
                ByteArrayOutputStream().also(collected::add)
            })
            assertEquals(2, records.size)
            assertEquals("IMG.jpg", records[0].name)
            assertEquals("IMG.jpg", records[1].name)
            assertArrayEquals(byteArrayOf(1, 2), collected[0].toByteArray())
            assertArrayEquals(byteArrayOf(3, 4), collected[1].toByteArray())
        } finally { bundle.delete() }
    }

    @Test fun countMismatchIsRejected() {
        val bundle = File.createTempFile("airgrab-count", AirGrabBundle.EXTENSION)
        try {
            AirGrabBundle.pack(bundle, listOf(
                AirGrabBundle.Source("a.jpg") { ByteArrayInputStream(byteArrayOf(1)) },
                AirGrabBundle.Source("b.jpg") { ByteArrayInputStream(byteArrayOf(2)) }
            ))
            try {
                AirGrabBundle.unpack(bundle.inputStream(), 3) { ByteArrayOutputStream() }
                fail("wrong manifest count must fail")
            } catch (_: IllegalArgumentException) { }
        } finally { bundle.delete() }
    }

    @Test fun unsafeZipEntriesAreNeverUsedAsPaths() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("files/0/../../secret.jpg"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        try {
            AirGrabBundle.unpack(ByteArrayInputStream(bytes.toByteArray()), 2) {
                fail("no destination may be opened for path traversal")
                ByteArrayOutputStream()
            }
            fail("unsafe path must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsTooManyItemsBeforeReading() {
        val path = File.createTempFile("airgrab-limit", AirGrabBundle.EXTENSION)
        try {
            val sources = (1..33).map { id ->
                AirGrabBundle.Source("$id.jpg") { ByteArrayInputStream(byteArrayOf(1)) }
            }
            try {
                AirGrabBundle.pack(path, sources)
                fail("too many sources")
            } catch (_: IllegalArgumentException) { }
        } finally { path.delete() }
    }
}
