package me.gm.cleaner.core.config

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

internal const val PREF_STORAGE_REDIRECT = "storage_redirect"
internal const val READ_ONLY = "read_only"

/**
 * 旧存储策略读写口的兼容适配层（仅剩旧 JSON 格式互操作）。
 *
 * 读写与批量已直迁配置存储；这里只保留分享导出、旧格式导入所需的
 * 原始文件读写。旧格式消费方迁移后删除本适配器。
 */
internal object LegacyStoragePolicyAdapter {
    private const val TAG = "LegacyStoragePolicyAdapter"

    private lateinit var storageRedirectFile: File
    private var storageRedirectCache: JSONObject? = null

    private lateinit var readOnlyFile: File
    private var readOnlyCache: JSONObject? = null

    fun init(filesDir: File) {
        storageRedirectFile = filesDir.resolve(PREF_STORAGE_REDIRECT)
        readOnlyFile = filesDir.resolve(READ_ONLY)
        storageRedirectCache = null
        readOnlyCache = null
    }

    internal fun writeUtf8Atomically(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(file)) {
                throw IOException("rename failed: ${tmpFile.path} -> ${file.path}")
            }
        } catch (e: IOException) {
            tmpFile.delete()
            throw e
        } catch (e: RuntimeException) {
            tmpFile.delete()
            throw e
        }
    }

    // @App
    // @Server
    fun readRawStorageRedirect(): String = storageRedirectFile.readText(Charsets.UTF_8)

    internal fun readStorageRedirect(): JSONObject {
        if (storageRedirectCache == null) {
            storageRedirectCache = try {
                JSONObject(readRawStorageRedirect())
            } catch (e: Exception) {
                if (e !is FileNotFoundException) {
                    Log.w(TAG, "Failed to read storage redirect", e)
                }
                JSONObject()
            }
        }
        return storageRedirectCache!!
    }

    // @App
    // @Server
    fun readRawReadOnly(): String = readOnlyFile.readText(Charsets.UTF_8)

    internal fun readReadOnly(): JSONObject {
        if (readOnlyCache == null) {
            readOnlyCache = try {
                JSONObject(readRawReadOnly())
            } catch (e: Exception) {
                if (e !is FileNotFoundException) {
                    Log.w(TAG, "Failed to read read-only config", e)
                }
                JSONObject()
            }
        }
        return readOnlyCache!!
    }
}
