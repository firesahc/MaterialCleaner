package me.gm.cleaner.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoragePolicyEditTransactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `多包暂存单次提交`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyEditTransaction(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        tx.putRedirect(listOf("/c" to "/d"), listOf("p2"))
        tx.putReadOnly(listOf("/ro"), listOf("p1"))

        assertTrue(store.getPackageSrZipped("p1").isEmpty())
        assertTrue(tx.commit())

        assertEquals(listOf("/a" to "/b"), store.getPackageSrZipped("p1"))
        assertEquals(listOf("/c" to "/d"), store.getPackageSrZipped("p2"))
        assertEquals(listOf("/ro"), store.getPackageReadOnly("p1"))
    }

    @Test
    fun `提交后不可复用`() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val tx = StoragePolicyEditTransaction(store)
        tx.putRedirect(listOf("/a" to "/b"), listOf("p1"))
        assertTrue(tx.commit())
        try {
            tx.putRedirect(listOf("/x" to "/y"), listOf("p2"))
            assertFalse("复用应抛", true)
        } catch (e: IllegalStateException) {
            assertTrue(true)
        }
    }
}
