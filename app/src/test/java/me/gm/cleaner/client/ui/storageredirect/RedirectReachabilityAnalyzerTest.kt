package me.gm.cleaner.client.ui.storageredirect

import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectReachabilityAnalyzerTest {

    private val corpora = listOf(
        emptyList(),
        listOf("/real/photos" to "/visible/DCIM"),
        listOf("/real/first" to "/visible/A", "/real/last" to "/visible/A"),
        listOf("/real/A" to "/visible/A", "/real/B" to "/real/A/B"),
        listOf("/visible/A" to "/visible/A"),
        listOf("/cache" to "/sdcard", "/data" to "/data"),
    )
    private val paths = listOf(
        "/visible/DCIM/a.jpg",
        "/visible/DCIM2/a.jpg",
        "/visible/A/file.txt",
        "/real/A/file.txt",
        "/sdcard/file.txt",
        "/other/file.txt",
    )
    private val redundant = listOf(
        emptyList(),
        emptyList(),
        listOf(0),
        emptyList(),
        listOf(0),
        listOf(1),
    )
    private val mounted = listOf(
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/real/photos/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/real/last/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/real/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/sdcard/file.txt",
            "/other/file.txt",
        ),
        listOf(
            "/visible/DCIM/a.jpg",
            "/visible/DCIM2/a.jpg",
            "/visible/A/file.txt",
            "/real/A/file.txt",
            "/cache/file.txt",
            "/other/file.txt",
        ),
    )
    private val accessible = listOf(
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
        listOf(
            listOf("/visible/DCIM/a.jpg"),
            listOf("/visible/DCIM2/a.jpg"),
            listOf("/visible/A/file.txt"),
            listOf("/real/A/file.txt"),
            listOf("/sdcard/file.txt"),
            listOf("/other/file.txt"),
        ),
    )

    @Test
    fun `外迁前后行为一致`() {
        corpora.forEachIndexed { ci, rules ->
            assertEquals("corpus=$ci redundant", redundant[ci], RedirectReachabilityAnalyzer.redundantIndices(rules))
            paths.forEachIndexed { pi, path ->
                assertEquals(
                    "corpus=$ci path=$path mounted",
                    mounted[ci][pi],
                    RedirectReachabilityAnalyzer.mountedPath(rules, path),
                )
                assertEquals(
                    "corpus=$ci path=$path accessible",
                    accessible[ci][pi],
                    RedirectReachabilityAnalyzer.accessiblePlaces(rules, path),
                )
            }
        }
    }
}
