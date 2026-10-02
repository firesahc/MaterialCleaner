package me.gm.cleaner.core.config

import android.util.Log
import me.gm.cleaner.core.storage.redirect.domain.ReadOnlyRule
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.regex.Pattern

internal const val PREF_STORAGE_REDIRECT = "storage_redirect"
internal const val READ_ONLY = "read_only"

/**
 * 旧存储策略读写口的兼容适配层。
 *
 * P6 内部分拆：[ServicePreferences] 仅保留普通偏好、名单文件与运行开关，
 * 所有 storage_redirect/read-only 双轨读写与批量暂存收拢至此。
 * 调用方仍经 [ServicePreferences] 门面，行为不变；下一步再将调用方直迁到
 * [ConfiguredPolicyStore] 后删除本适配器。
 *
 * 线程模型沿用门面约束：本对象方法本身不同步，调用方须持有
 * [ServicePreferences] 监视器（门面委托方法均 `@Synchronized`）。
 */
internal object LegacyStoragePolicyAdapter {
    private const val TAG = "LegacyStoragePolicyAdapter"

    private lateinit var storageRedirectFile: File
    private var inBatch: Boolean = false
    private var storageRedirectCache: JSONObject? = null
    private var pendingRedirectPolicy: StoragePolicyEnvelope? = null

    private lateinit var readOnlyFile: File
    private var readOnlyCache: JSONObject? = null
    private var pendingReadOnlyPolicy: StoragePolicyEnvelope? = null

    fun init(filesDir: File) {
        storageRedirectFile = filesDir.resolve(PREF_STORAGE_REDIRECT)
        readOnlyFile = filesDir.resolve(READ_ONLY)
        storageRedirectCache = null
        readOnlyCache = null
        inBatch = false
        pendingRedirectPolicy = null
        pendingReadOnlyPolicy = null
        readStorageRedirect()
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

    // STORAGE REDIRECT
    // @App
    fun putStorageRedirect(rawRules: List<Pair<String, String>>, packageNames: List<String>) {
        if (rawRules.isEmpty()) {
            removeStorageRedirect(packageNames)
            return
        }
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope = { current ->
                current.replaceRedirectRules(rawRules, packageNames)
            }
            if (inBatch) {
                pendingRedirectPolicy = try {
                    mutation(
                        pendingRedirectPolicy ?: ConfiguredPolicyStoreProvider.instance
                            .readRedirect().envelope,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stage redirect policy batch", e)
                    return
                }
                return
            }
            val result = ConfiguredPolicyStoreProvider.instance.updateRedirect(null, mutation)
            if (!result.success) {
                Log.e(TAG, "Failed to update redirect policy: ${result.error}")
                return
            }
            if (result.changed) {
                invalidateSrCache()
            }
            return
        }
        val rules = JSONArray()
        rawRules.forEach { rules.put(JSONArray(it.toList())) }
        val all = readStorageRedirect()
        packageNames.forEach { all.put(it, rules) }
        writeStorageRedirect(all)
    }

    // @App
    fun removeStorageRedirect(packageNames: List<String>) {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope = { current ->
                current.removeRedirectRules(packageNames)
            }
            if (inBatch) {
                pendingRedirectPolicy = mutation(
                    pendingRedirectPolicy ?: ConfiguredPolicyStoreProvider.instance
                        .readRedirect().envelope,
                )
                return
            }
            val result = ConfiguredPolicyStoreProvider.instance.updateRedirect(null, mutation)
            if (!result.success) {
                Log.e(TAG, "Failed to remove redirect policy: ${result.error}")
                return
            }
            if (result.changed) {
                invalidateSrCache()
            }
            return
        }
        val all = readStorageRedirect()
        packageNames.forEach { all.remove(it) }
        writeStorageRedirect(all)
    }

    // @App
    fun getUninstalledSrPackages(installedPackages: Set<String>): List<String> {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val packages = ConfiguredPolicyStoreProvider.instance.readRedirect().envelope.redirectPolicies
                .map { it.scope.packageName }.toSet()
            return (packages - installedPackages).toList()
        }
        val packages = readStorageRedirect().keys().asSequence()
        return (packages - installedPackages).toList()
    }

    // @App
    // @Server
    val srPackages: Set<String>
        get() = if (ConfiguredPolicyStoreProvider.isInitialized()) {
            ConfiguredPolicyStoreProvider.instance.readRedirect().envelope.redirectPolicies
                .map { it.scope.packageName }.toSet()
        } else {
            readStorageRedirect().keys().asSequence().toSet()
        }

    // @App
    // @Server
    val srRulesCount: Int
        get() {
            if (ConfiguredPolicyStoreProvider.isInitialized()) {
                return ConfiguredPolicyStoreProvider.instance.readRedirect().envelope.redirectPolicies
                    .sumOf { it.rules.size }
            }
            var count = 0
            val all = readStorageRedirect()
            all.keys().forEach {
                count += all.getJSONArray(it).length()
            }
            return count
        }

    // @App
    // @Server
    fun getPackageSrCount(packageName: String): Int {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            return ConfiguredPolicyStoreProvider.instance.readRedirect().envelope.redirectPolicies
                .firstOrNull { it.scope.packageName == packageName }?.rules?.size ?: 0
        }
        val all = readStorageRedirect()
        if (all.has(packageName)) {
            return all.getJSONArray(packageName).length()
        }
        return 0
    }

    // @App
    // @Server
    fun getPackageSr(packageName: String, userId: Int): Pair<List<String>, List<String>> {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val rules = ConfiguredPolicyStoreProvider.instance.readRedirect().envelope.redirectPolicies
                .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
            return rules.map { getPathAsUserQuickly(it.source, userId) } to
                rules.map { getPathAsUserQuickly(it.target, userId) }
        }
        val source = mutableListOf<String>()
        val target = mutableListOf<String>()
        val all = readStorageRedirect()
        if (all.has(packageName)) {
            val rules = all.getJSONArray(packageName)
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONArray(i)
                source.add(getPathAsUserQuickly(rule.getString(0), userId))
                target.add(getPathAsUserQuickly(rule.getString(1), userId))
            }
        }
        return source to target
    }

    // @App
    // @Server
    fun getPackageSrZipped(packageName: String, userId: Int = 0): List<Pair<String, String>> {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            return ConfiguredPolicyStoreProvider.instance.readRedirect().envelope.redirectPolicies
                .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
                .map { getPathAsUserQuickly(it.source, userId) to getPathAsUserQuickly(it.target, userId) }
        }
        val list = mutableListOf<Pair<String, String>>()
        val all = readStorageRedirect()
        if (all.has(packageName)) {
            val rules = all.getJSONArray(packageName)
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONArray(i)
                list.add(
                    Pair(
                        getPathAsUserQuickly(rule.getString(0), userId),
                        getPathAsUserQuickly(rule.getString(1), userId)
                    )
                )
            }
        }
        return list
    }

    // @Server
    fun invalidateSrCache() {
        storageRedirectCache = null
    }

    // @App
    fun beginBatchOperation() {
        check(!inBatch) { "批量配置操作不能嵌套" }
        inBatch = true
        pendingRedirectPolicy = null
        pendingReadOnlyPolicy = null
    }

    // @App
    fun endBatchOperation() {
        inBatch = false
        pendingRedirectPolicy?.let { pending ->
            if (ConfiguredPolicyStoreProvider.isInitialized()) {
                val result = ConfiguredPolicyStoreProvider.instance.updateRedirect(null) { pending }
                if (result.success && result.changed) {
                    invalidateSrCache()
                } else {
                    if (!result.success) {
                        Log.e(TAG, "Failed to commit redirect policy batch: ${result.error}")
                    }
                }
            } else {
                Log.w(TAG, "ConfiguredPolicyStore 未初始化，跳过重定向批量提交")
            }
            Unit
        }
        pendingReadOnlyPolicy?.let { pending ->
            if (ConfiguredPolicyStoreProvider.isInitialized()) {
                val result = ConfiguredPolicyStoreProvider.instance.updateReadOnly(null) { pending }
                if (result.success && result.changed) {
                    invalidateReadOnlyCache()
                } else {
                    if (!result.success) {
                        Log.e(TAG, "Failed to commit read-only policy batch: ${result.error}")
                    }
                }
            } else {
                Log.w(TAG, "ConfiguredPolicyStore 未初始化，跳过只读批量提交")
            }
            Unit
        }
        pendingRedirectPolicy = null
        pendingReadOnlyPolicy = null
    }

    private fun writeStorageRedirect(json: JSONObject) {
        storageRedirectCache = json
        if (inBatch) {
            return
        }
        try {
            writeUtf8Atomically(storageRedirectFile, json.toString())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write storage redirect", e)
        }
    }

    // @App
    // @Server
    fun readRawStorageRedirect(): String = storageRedirectFile.readText(Charsets.UTF_8)

    private fun readStorageRedirect(): JSONObject {
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

    private fun getPathAsUserQuickly(path: String, userId: Int): String = if (userId == 0) {
        path
    } else {
        getPathAsUser(path, userId)
    }

    // READ ONLY
    // @App
    fun putReadOnly(rawRules: List<String>, packageNames: List<String>) {
        if (rawRules.isEmpty()) {
            removeReadOnly(packageNames)
            return
        }
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope = { current ->
                current.replaceReadOnlyRules(rawRules, packageNames)
            }
            if (inBatch) {
                pendingReadOnlyPolicy = try {
                    mutation(
                        pendingReadOnlyPolicy ?: ConfiguredPolicyStoreProvider.instance
                            .readReadOnly().envelope,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stage read-only policy batch", e)
                    return
                }
                return
            }
            val result = ConfiguredPolicyStoreProvider.instance.updateReadOnly(null, mutation)
            if (!result.success) {
                Log.e(TAG, "Failed to update read-only policy: ${result.error}")
                return
            }
            if (result.changed) {
                invalidateReadOnlyCache()
            }
            return
        }
        val rules = JSONArray(rawRules)
        val all = readReadOnly()
        packageNames.forEach { all.put(it, rules) }
        writeReadOnly(all)
    }

    // @App
    fun removeReadOnly(packageNames: List<String>) {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope = { current ->
                current.removeReadOnlyRules(packageNames)
            }
            if (inBatch) {
                pendingReadOnlyPolicy = mutation(
                    pendingReadOnlyPolicy ?: ConfiguredPolicyStoreProvider.instance
                        .readReadOnly().envelope,
                )
                return
            }
            val result = ConfiguredPolicyStoreProvider.instance.updateReadOnly(null, mutation)
            if (!result.success) {
                Log.e(TAG, "Failed to remove read-only policy: ${result.error}")
                return
            }
            if (result.changed) {
                invalidateReadOnlyCache()
            }
            return
        }
        val all = readReadOnly()
        packageNames.forEach { all.remove(it) }
        writeReadOnly(all)
    }

    // @App
    fun getUninstalledReadOnlyPackages(installedPackages: Set<String>): List<String> {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            val packages = ConfiguredPolicyStoreProvider.instance.readReadOnly().envelope.readOnlyRules
                .map { it.scope.packageName }.toSet()
            return (packages - installedPackages).toList()
        }
        val packages = readReadOnly().keys().asSequence()
        return (packages - installedPackages).toList()
    }

    // @App
    fun getPackageReadOnly(packageName: String, userId: Int = 0): List<String> {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            return ConfiguredPolicyStoreProvider.instance.readReadOnly().envelope.readOnlyRules
                .filter { it.scope.packageName == packageName }
                .map { getPathAsUserQuickly(it.visiblePath, userId) }
        }
        val all = readReadOnly()
        if (!all.has(packageName)) {
            return emptyList()
        }
        return all.getJSONArray(packageName).toList().map { path ->
            getPathAsUserQuickly(path, userId)
        }
    }

    // @Server
    fun getAllReadOnly(): Map<String, List<String>> {
        if (ConfiguredPolicyStoreProvider.isInitialized()) {
            return ConfiguredPolicyStoreProvider.instance.readReadOnly().envelope.readOnlyRules
                .groupBy { it.scope.packageName }
                .mapValues { (_, rules) -> rules.map(ReadOnlyRule::visiblePath) }
        }
        val ret = mutableMapOf<String, List<String>>()
        val all = readReadOnly()
        all.keys().forEach { ret[it] = all.getJSONArray(it).toList() }
        return ret
    }

    // @Server
    fun invalidateReadOnlyCache() {
        readOnlyCache = null
    }

    private fun writeReadOnly(json: JSONObject) {
        readOnlyCache = json
        try {
            writeUtf8Atomically(readOnlyFile, json.toString())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write read-only config", e)
        }
    }

    // @App
    // @Server
    fun readRawReadOnly(): String = readOnlyFile.readText(Charsets.UTF_8)

    private fun readReadOnly(): JSONObject {
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

private val APP_DATA_DIR_PATHS: Pattern by lazy {
    Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/)?([^/]+)?(/.*)?")
}

private fun getPathAsUser(path: String, userId: Int): String {
    val matcher = APP_DATA_DIR_PATHS.matcher(path)
    if (!matcher.matches()) {
        return path
    }
    val builder = StringBuilder()
    for (i in 1..matcher.groupCount()) {
        val group = matcher.group(i) ?: continue
        if (group.all { it.isDigit() }) {
            builder.append(userId)
        } else {
            builder.append(group)
        }
    }
    return builder.toString()
}

private fun JSONArray.toList(): ArrayList<String> {
    val list = ArrayList<String>(length())
    for (i in 0 until length()) {
        list.add(getString(i))
    }
    return list
}
