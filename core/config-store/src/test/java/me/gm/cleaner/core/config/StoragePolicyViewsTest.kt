package me.gm.cleaner.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoragePolicyViewsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun seededStore(): FileConfiguredPolicyStore {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        store.updateRedirect(null) {
            it.replaceRedirectRules(
                listOf("/storage/emulated/0/DCIM" to "/storage/emulated/0/Pictures"),
                listOf("com.example"),
            )
        }
        store.updateReadOnly(null) {
            it.replaceReadOnlyRules(listOf("/protected"), listOf("com.example"))
        }
        return store
    }

    @Test
    fun `读取视图与旧门面一致`() {
        val store = seededStore()

        assertEquals(setOf("com.example"), store.srPackages)
        assertEquals(1, store.srRulesCount)
        assertEquals(1, store.getPackageSrCount("com.example"))
        assertEquals(0, store.getPackageSrCount("other"))
        assertEquals(
            listOf("/storage/emulated/0/DCIM" to "/storage/emulated/0/Pictures"),
            store.getPackageSrZipped("com.example"),
        )
        assertEquals(
            listOf("/storage/emulated/10/DCIM" to "/storage/emulated/10/Pictures"),
            store.getPackageSrZipped("com.example", 10),
        )
        assertEquals(
            listOf("/storage/emulated/0/DCIM") to listOf("/storage/emulated/0/Pictures"),
            store.getPackageSr("com.example", 0),
        )
        assertEquals(listOf("/protected"), store.getPackageReadOnly("com.example"))
        assertEquals(listOf("/protected"), store.getPackageReadOnly("com.example", 10))
        assertEquals(listOf("com.example"), store.getUninstalledSrPackages(setOf("other")))
        assertEquals(listOf("com.example"), store.getUninstalledReadOnlyPackages(setOf("other")))
        assertTrue(store.getUninstalledSrPackages(setOf("com.example")).isEmpty())
    }
}
