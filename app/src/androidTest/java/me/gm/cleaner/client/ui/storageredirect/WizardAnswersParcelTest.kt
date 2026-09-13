package me.gm.cleaner.client.ui.storageredirect

import android.os.Parcel
import androidx.core.os.ParcelCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.gm.cleaner.util.toBase64String
import me.gm.cleaner.util.toParcelable
import me.gm.cleaner.widget.recyclerview.DiffArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #4 回归：WizardAnswers Parcel 读写必须对称，
 * 且能兼容漏写 q4 的旧 Base64。
 */
@RunWith(AndroidJUnit4::class)
class WizardAnswersParcelTest {

    private fun answersWithContent(): WizardAnswers {
        val answers = WizardAnswers(q1 = true, q2 = true, q3 = true, q4 = true, rootDirName = "sdcard")
        answers.accessiblePlacesLiveData.value =
            DiffArrayList(listOf("/storage/emulated/0/Music", null))
        answers.mountRulesLiveData.value =
            DiffArrayList(listOf("/sdcard/Download/App" to "/storage/emulated/0/Download", null to null))
        answers.inaccessiblePlacesLiveData.value =
            DiffArrayList(listOf("/storage/emulated/0/Android", null))
        return answers
    }

    @Test
    fun roundTrip_keepsAllFields() {
        val original = answersWithContent()
        val restored: WizardAnswers = original.toBase64String().toParcelable()
        assertEquals(original, restored)
        assertEquals(original.hashCode(), restored.hashCode())
        assertEquals("sdcard", restored.rootDirName)
        assertEquals(
            listOf("/storage/emulated/0/Music"),
            restored.accessiblePlaces(),
        )
        assertEquals(
            listOf("/sdcard/Download/App" to "/storage/emulated/0/Download"),
            restored.mountRules(),
        )
    }

    @Test
    fun equals_detectsListChange() {
        val a = answersWithContent()
        val b: WizardAnswers = a.toBase64String().toParcelable()
        assertEquals(a, b)
        b.updateMountRules {
            // updateMountRules 用 postValue，instrumented 环境主线程外需等待；
            // 这里直接改 value 保证同步。
            val cur = b.mountRulesLiveData.value!!
            cur.add(cur.size - 1, "/a" to "/b")
            b.mountRulesLiveData.value = cur
        }
        // postValue 可能是异步，这里再同步补一次确保判定稳定
        Thread.sleep(500)
        val c: WizardAnswers = b.toBase64String().toParcelable()
        assertNotEquals(a.mountRules(), c.mountRules())
    }

    @Test
    fun legacy5Bool_compatible_q4DefaultsFalse() {
        // 手工按旧格式（q1,q2,q3,q11,q12，无 q4）编码，模拟线上脏数据
        val parcel = Parcel.obtain()
        try {
            ParcelCompat.writeBoolean(parcel, true) // q1
            ParcelCompat.writeBoolean(parcel, true) // q2
            ParcelCompat.writeBoolean(parcel, true) // q3
            ParcelCompat.writeBoolean(parcel, false) // q11
            ParcelCompat.writeBoolean(parcel, true) // q12
            parcel.writeStringList(listOf("/storage/emulated/0/Music", null))
            parcel.writeStringList(listOf("/sdcard/Download/App", null))
            parcel.writeStringList(listOf("/storage/emulated/0/Download", null))
            parcel.writeStringList(listOf(null))
            val bytes = parcel.marshall()
            val raw = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val restored: WizardAnswers = raw.toParcelable()
            assertEquals(false, restored.q4)
            assertEquals("files", restored.rootDirName)
            assertEquals(listOf("/storage/emulated/0/Music"), restored.accessiblePlaces())
            assertEquals(
                listOf("/sdcard/Download/App" to "/storage/emulated/0/Download"),
                restored.mountRules(),
            )
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun newFormatWithoutRootDirName_defaultsToFiles() {
        // 旧新版格式：6 个 bool + 3 个 StringList，但没有末尾的 rootDirName。
        // 升级后首次读取应兜底回 files，且不抛异常。
        val parcel = Parcel.obtain()
        try {
            ParcelCompat.writeBoolean(parcel, true) // q1
            ParcelCompat.writeBoolean(parcel, false) // q2
            ParcelCompat.writeBoolean(parcel, false) // q3
            ParcelCompat.writeBoolean(parcel, false) // q4
            ParcelCompat.writeBoolean(parcel, true) // q11
            ParcelCompat.writeBoolean(parcel, false) // q12
            parcel.writeStringList(listOf(null))
            parcel.writeStringList(listOf(null to null).unzip().first)
            parcel.writeStringList(listOf(null to null).unzip().second)
            parcel.writeStringList(listOf(null))
            val bytes = parcel.marshall()
            val raw = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val restored: WizardAnswers = raw.toParcelable()
            assertEquals("files", restored.rootDirName)
            assertEquals(true, restored.q1)
        } finally {
            parcel.recycle()
        }
    }
}
