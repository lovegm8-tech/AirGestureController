package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class MediaFilePolicyTest {
    @Test fun galaxyMp4AndMovSavedAsRealVideoMedia() {
        val mp4 = MediaFilePolicy.destination("20261016_143222.MP4")
        assertEquals(MediaKind.VIDEO, mp4.kind)
        assertEquals("video/mp4", mp4.mime)
        assertEquals("Movies/AirGrab", mp4.folder)
        val mov = MediaFilePolicy.destination("movie.MOV")
        assertEquals("video/quicktime", mov.mime)
        assertEquals(MediaKind.VIDEO, mov.kind)
        assertEquals("video/x-matroska", MediaFilePolicy.destination("video.MKV").mime)
    }
    @Test fun photoTypesRemainDiscoverableImages() {
        assertEquals(MediaKind.IMAGE, MediaFilePolicy.destination("IMG_19.heic").kind)
        assertEquals("image/jpeg", MediaFilePolicy.destination("photo.JPG").mime)
        assertEquals("Pictures/AirGrab", MediaFilePolicy.destination("photo.png").folder)
    }
    @Test fun otherFilesRemainDownloadsWithoutPretendingToBeVideos() {
        val data = MediaFilePolicy.destination("notes.pdf")
        assertEquals(MediaKind.DOWNLOAD, data.kind)
        assertEquals("application/octet-stream", data.mime)
        assertEquals("Download/AirGrab", data.folder)
    }
    @Test fun fileExtensionsMustBeExact() {
        assertEquals(MediaKind.DOWNLOAD, MediaFilePolicy.destination("mp4_backup").kind)
        assertEquals(MediaKind.DOWNLOAD, MediaFilePolicy.destination("clip.mp4.bak").kind)
    }
}
