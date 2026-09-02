package me.gm.cleaner.core.config

import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectPolicy
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectRule
import me.gm.cleaner.core.storage.redirect.domain.PackageStorageScope
import me.gm.cleaner.core.storage.redirect.domain.ReadOnlyRule
import me.gm.cleaner.core.storage.redirect.domain.RedirectRuleType
import me.gm.cleaner.core.storage.redirect.domain.RuleId
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import me.gm.cleaner.core.storage.redirect.domain.StorageUserScope
import me.gm.cleaner.core.storage.redirect.domain.StorageVolumeScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class ConfigSourceHealth {
    MISSING,
    VALID,
    CORRUPT,
}

data class VersionedRedirectPolicy(
    val revision: String,
    val envelope: StoragePolicyEnvelope,
    val health: ConfigSourceHealth,
    val diagnostics: List<String> = emptyList(),
)

data class VersionedReadOnlyPolicy(
    val revision: String,
    val envelope: StoragePolicyEnvelope,
    val health: ConfigSourceHealth,
    val diagnostics: List<String> = emptyList(),
)

/** 同一读取时刻取得的两个独立配置域；两域仍保持各自 revision 和健康状态。 */
data class ConfiguredPolicySnapshot(
    val redirect: VersionedRedirectPolicy,
    val readOnly: VersionedReadOnlyPolicy,
)

data class PolicyStoreResult(
    val success: Boolean,
    val revision: String?,
    val error: String?,
)

/**
 * 配置策略的唯一读写门面。
 *
 * `expectedRevision` 非空时执行内容级 CAS；传 null 表示由调用方承担并发协调。
 * 读取结果始终携带健康状态，调用方不得把 CORRUPT 当作空策略继续发布。
 */
interface ConfiguredPolicyStore {
    fun readRedirect(): VersionedRedirectPolicy

    fun readReadOnly(): VersionedReadOnlyPolicy

    fun readSnapshot(): ConfiguredPolicySnapshot = ConfiguredPolicySnapshot(
        redirect = readRedirect(),
        readOnly = readReadOnly(),
    )

    fun updateRedirect(
        expectedRevision: String?,
        mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope,
    ): PolicyStoreResult

    fun updateReadOnly(
        expectedRevision: String?,
        mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope,
    ): PolicyStoreResult
}

object ConfiguredPolicyStoreProvider {
    lateinit var instance: ConfiguredPolicyStore
        private set

    fun isInitialized(): Boolean = ::instance.isInitialized

    fun initialize(baseDir: File) {
        instance = FileConfiguredPolicyStore(baseDir)
    }
}

/** 旧 JSON 文件的兼容适配器。它不读取 deny_list，也不改变旧文件格式。 */
class FileConfiguredPolicyStore(
    private val baseDir: File,
) : ConfiguredPolicyStore {
    private val redirectFile: File = baseDir.resolve(STORAGE_REDIRECT_FILE)
    private val readOnlyFile: File = baseDir.resolve(READ_ONLY_FILE)

    @Synchronized
    override fun readRedirect(): VersionedRedirectPolicy {
        val parsed = readJson(redirectFile)
        if (parsed.health == ConfigSourceHealth.MISSING) {
            return VersionedRedirectPolicy(
                revisionOfRedirect(emptyEnvelope()),
                emptyEnvelope(),
                parsed.health,
                parsed.diagnostics,
            )
        }
        val conversion = parseRedirect(parsed.json)
        val envelope = StoragePolicyEnvelope(redirectPolicies = conversion.policies)
        val health = if (parsed.health == ConfigSourceHealth.CORRUPT || conversion.diagnostics.isNotEmpty()) {
            ConfigSourceHealth.CORRUPT
        } else {
            ConfigSourceHealth.VALID
        }
        return VersionedRedirectPolicy(
            revision = revisionOfRedirect(envelope),
            envelope = envelope,
            health = health,
            diagnostics = parsed.diagnostics + conversion.diagnostics,
        )
    }

    @Synchronized
    override fun readReadOnly(): VersionedReadOnlyPolicy {
        val parsed = readJson(readOnlyFile)
        if (parsed.health == ConfigSourceHealth.MISSING) {
            return VersionedReadOnlyPolicy(
                revisionOfReadOnly(emptyEnvelope()),
                emptyEnvelope(),
                parsed.health,
                parsed.diagnostics,
            )
        }
        val conversion = parseReadOnly(parsed.json)
        val envelope = StoragePolicyEnvelope(readOnlyRules = conversion.rules)
        val health = if (parsed.health == ConfigSourceHealth.CORRUPT || conversion.diagnostics.isNotEmpty()) {
            ConfigSourceHealth.CORRUPT
        } else {
            ConfigSourceHealth.VALID
        }
        return VersionedReadOnlyPolicy(
            revision = revisionOfReadOnly(envelope),
            envelope = envelope,
            health = health,
            diagnostics = parsed.diagnostics + conversion.diagnostics,
        )
    }

    @Synchronized
    override fun updateRedirect(
        expectedRevision: String?,
        mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope,
    ): PolicyStoreResult {
        val current = readRedirect()
        return update(expectedRevision, current.revision, current.health) {
            val updated = normalizeRedirectEnvelope(mutation(current.envelope))
            requireRedirectOnly(updated)
            writeRedirect(updated)
            revisionOfRedirect(updated)
        }
    }

    @Synchronized
    override fun updateReadOnly(
        expectedRevision: String?,
        mutation: (StoragePolicyEnvelope) -> StoragePolicyEnvelope,
    ): PolicyStoreResult {
        val current = readReadOnly()
        return update(expectedRevision, current.revision, current.health) {
            val updated = normalizeReadOnlyEnvelope(mutation(current.envelope))
            requireReadOnlyOnly(updated)
            writeReadOnly(updated)
            revisionOfReadOnly(updated)
        }
    }

    private fun update(
        expectedRevision: String?,
        currentRevision: String,
        health: ConfigSourceHealth,
        operation: () -> String,
    ): PolicyStoreResult {
        if (health == ConfigSourceHealth.CORRUPT) {
            return PolicyStoreResult(false, currentRevision, "配置源损坏: 无法更新")
        }
        if (expectedRevision != null && expectedRevision != currentRevision) {
            return PolicyStoreResult(false, currentRevision, "revision 冲突")
        }
        return try {
            PolicyStoreResult(true, operation(), null)
        } catch (e: Exception) {
            // 写入失败时磁盘仍保留 currentRevision；把它返回给调用方，
            // 便于诊断和下一次 CAS 重试，避免用 null 制造未知版本。
            PolicyStoreResult(false, currentRevision, e.message ?: e.javaClass.simpleName)
        }
    }

    private data class JsonRead(val json: JSONObject?, val health: ConfigSourceHealth, val diagnostics: List<String>)

    private fun readJson(file: File): JsonRead {
        if (!file.exists()) return JsonRead(null, ConfigSourceHealth.MISSING, emptyList())
        return try {
            val text = FileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) }
            JsonRead(JSONObject(text), ConfigSourceHealth.VALID, emptyList())
        } catch (e: Exception) {
            JsonRead(null, ConfigSourceHealth.CORRUPT, listOf("${file.name}: ${e.message ?: "无法解析 JSON"}"))
        }
    }

    private data class RedirectConversion(
        val policies: List<OrderedRedirectPolicy>,
        val diagnostics: List<String>,
    )

    private fun parseRedirect(json: JSONObject?): RedirectConversion {
        if (json == null) return RedirectConversion(emptyList(), listOf("storage_redirect 缺少 JSON 正文"))
        val policies = mutableListOf<OrderedRedirectPolicy>()
        val diagnostics = mutableListOf<String>()
        val packages = json.keys().asSequence().toList().sorted()
        packages.forEach { packageName ->
            try {
                val array = json.getJSONArray(packageName)
                val rules = mutableListOf<OrderedRedirectRule>()
                val occurrences = mutableMapOf<String, Int>()
                for (index in 0 until array.length()) {
                    val pair = array.getJSONArray(index)
                    if (pair.length() < 2) throw IllegalArgumentException("规则必须包含 source 和 target")
                    val source = normalizePath(
                        pair.getString(0),
                        "redirect[$packageName][$index].source",
                    )
                    val target = normalizePath(
                        pair.getString(1),
                        "redirect[$packageName][$index].target",
                    )
                    val type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP
                    val occurrenceKey = "$source\u0000$target"
                    val occurrence = occurrences.getOrDefault(occurrenceKey, 0)
                    occurrences[occurrenceKey] = occurrence + 1
                    rules += OrderedRedirectRule(
                        ruleId = RuleId(stableRuleId("redirect", packageName, occurrence, source, target)),
                        type = type,
                        source = source,
                        target = target,
                        orderIndex = index,
                    )
                }
                if (rules.isNotEmpty()) {
                    policies += OrderedRedirectPolicy(
                        scope = PackageStorageScope(packageName, StorageUserScope.AllUsers),
                        rules = rules,
                    )
                }
            } catch (e: Exception) {
                diagnostics += "redirect[$packageName]: ${e.message ?: "无效规则"}"
            }
        }
        return RedirectConversion(policies, diagnostics)
    }

    private data class ReadOnlyConversion(val rules: List<ReadOnlyRule>, val diagnostics: List<String>)

    private fun parseReadOnly(json: JSONObject?): ReadOnlyConversion {
        if (json == null) return ReadOnlyConversion(emptyList(), listOf("read_only 缺少 JSON 正文"))
        val rules = mutableListOf<ReadOnlyRule>()
        val diagnostics = mutableListOf<String>()
        json.keys().asSequence().toList().sorted().forEach { packageName ->
            try {
                val paths = json.getJSONArray(packageName)
                val occurrences = mutableMapOf<String, Int>()
                for (index in 0 until paths.length()) {
                    val path = normalizePath(
                        paths.getString(index),
                        "readOnly[$packageName][$index]",
                    )
                    val occurrence = occurrences.getOrDefault(path, 0)
                    occurrences[path] = occurrence + 1
                    rules += ReadOnlyRule(
                        ruleId = RuleId(stableRuleId("readonly", packageName, occurrence, path, path)),
                        scope = PackageStorageScope(packageName, StorageUserScope.AllUsers),
                        visiblePath = path,
                    )
                }
            } catch (e: Exception) {
                diagnostics += "readOnly[$packageName]: ${e.message ?: "无效规则"}"
            }
        }
        return ReadOnlyConversion(rules, diagnostics)
    }

    private fun writeRedirect(envelope: StoragePolicyEnvelope) {
        val json = JSONObject()
        envelope.redirectPolicies.forEach { policy ->
            require(policy.scope.users is StorageUserScope.AllUsers) { "旧格式不支持特定用户范围" }
            require(policy.scope.volume == StorageVolumeScope.PRIMARY_EXTERNAL) {
                "旧格式不支持非主外部存储卷"
            }
            val rules = JSONArray()
            policy.rules.forEach { rule -> rules.put(JSONArray(listOf(rule.source, rule.target))) }
            json.put(policy.scope.packageName, rules)
        }
        writeUtf8Atomically(redirectFile, json.toString())
    }

    private fun writeReadOnly(envelope: StoragePolicyEnvelope) {
        val grouped = linkedMapOf<String, MutableList<String>>()
        envelope.readOnlyRules.forEach { rule ->
            require(rule.scope.users is StorageUserScope.AllUsers) { "旧格式不支持特定用户范围" }
            require(rule.scope.volume == StorageVolumeScope.PRIMARY_EXTERNAL) {
                "旧格式不支持非主外部存储卷"
            }
            grouped.getOrPut(rule.scope.packageName) { mutableListOf() }.add(rule.visiblePath)
        }
        val json = JSONObject()
        grouped.toSortedMap().forEach { (packageName, paths) -> json.put(packageName, JSONArray(paths)) }
        writeUtf8Atomically(readOnlyFile, json.toString())
    }

    private fun writeUtf8Atomically(file: File, content: String) {
        val parent = file.parentFile ?: baseDir
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) throw IOException("无法创建目录: ${parent.path}")
        val tmp = File(parent, "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw IOException("配置文件系统不支持原子替换: ${file.path}", error)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun emptyEnvelope() = StoragePolicyEnvelope()

    private fun normalizeRedirectEnvelope(envelope: StoragePolicyEnvelope): StoragePolicyEnvelope {
        requireRedirectOnly(envelope)
        return envelope.copy(
            redirectPolicies = envelope.redirectPolicies.map { policy ->
                policy.copy(
                    rules = policy.rules.map { rule ->
                        val source = normalizePath(rule.source, "redirect.${rule.ruleId.value}.source")
                        val target = normalizePath(rule.target, "redirect.${rule.ruleId.value}.target")
                        rule.copy(
                            type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
                            source = source,
                            target = target,
                        )
                    },
                )
            },
        )
    }

    private fun normalizeReadOnlyEnvelope(envelope: StoragePolicyEnvelope): StoragePolicyEnvelope {
        requireReadOnlyOnly(envelope)
        return envelope.copy(
            readOnlyRules = envelope.readOnlyRules.map { rule ->
                rule.copy(visiblePath = normalizePath(rule.visiblePath, "readOnly.${rule.ruleId.value}"))
            },
        )
    }

    private fun requireRedirectOnly(envelope: StoragePolicyEnvelope) {
        require(envelope.readOnlyRules.isEmpty()) { "redirect 更新不能包含 readOnlyRules" }
        require(envelope.denyAllRules.isEmpty()) { "本阶段不支持写入 denyAllRules" }
        require(envelope.promptSuppressions.isEmpty() && envelope.packageExclusions.isEmpty()) {
            "本阶段不支持写入其他策略类型"
        }
        require(envelope.legacyWizardDrafts.isEmpty() && envelope.legacyWizardTemplate == null) {
            "本阶段不支持写入 Wizard 数据"
        }
        require(envelope.legacyQuarantines.isEmpty() && envelope.migrationMetadata == null) {
            "本阶段不支持写入迁移元数据"
        }
    }

    private fun requireReadOnlyOnly(envelope: StoragePolicyEnvelope) {
        require(envelope.redirectPolicies.isEmpty()) { "read-only 更新不能包含 redirectPolicies" }
        require(envelope.denyAllRules.isEmpty()) { "本阶段不支持写入 denyAllRules" }
        require(envelope.promptSuppressions.isEmpty() && envelope.packageExclusions.isEmpty()) {
            "本阶段不支持写入其他策略类型"
        }
        require(envelope.legacyWizardDrafts.isEmpty() && envelope.legacyWizardTemplate == null) {
            "本阶段不支持写入 Wizard 数据"
        }
        require(envelope.legacyQuarantines.isEmpty() && envelope.migrationMetadata == null) {
            "本阶段不支持写入迁移元数据"
        }
    }

    private fun revisionOfRedirect(envelope: StoragePolicyEnvelope): String {
        val canonical = buildString {
            append("domain=redirect|schema=").append(envelope.schemaVersion).append('\n')
            envelope.redirectPolicies.sortedBy { it.scope.packageName }.forEach { policy ->
                append("redirect|").append(policy.scope.packageName).append('|')
                    .append(policy.scope.volume).append('|')
                    .append(policy.scope.users.canonicalName()).append('\n')
                policy.rules.forEach { rule ->
                    append(rule.type).append('|').append(rule.source.length).append(':').append(rule.source)
                        .append('|').append(rule.target.length).append(':').append(rule.target).append('\n')
                }
            }
        }
        return sha256(canonical)
    }

    private fun revisionOfReadOnly(envelope: StoragePolicyEnvelope): String {
        val canonical = buildString {
            append("domain=read-only|schema=").append(envelope.schemaVersion).append('\n')
            // package 名称排序只消除 JSON 对象键顺序的不确定性；同一包内严格保留
            // 旧数组顺序，否则“保留顺序”的兼容转换会被 revision 计算再次抹掉。
            envelope.readOnlyRules
                .withIndex()
                .sortedWith(compareBy<IndexedValue<ReadOnlyRule>> { it.value.scope.packageName }
                    .thenBy { it.index })
                .forEach { indexed ->
                    val rule = indexed.value
                    append("readonly|").append(rule.scope.packageName).append('|')
                        .append(rule.scope.volume).append('|')
                        .append(rule.scope.users.canonicalName()).append('|')
                        .append(rule.visiblePath.length).append(':').append(rule.visiblePath).append('\n')
                }
        }
        return sha256(canonical)
    }

    private fun stableRuleId(kind: String, packageName: String, index: Int, source: String, target: String): String {
        val value = "$kind|$packageName|$index|${source.length}:$source|${target.length}:$target"
        return "$kind-${sha256(value).take(24)}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun StorageUserScope.canonicalName(): String = when (this) {
        StorageUserScope.AllUsers -> "all"
        is StorageUserScope.SpecificUser -> "user:$userId"
    }

    /** 在旧格式进入领域正文时做一次稳定的 POSIX 词法规范化。 */
    private fun normalizePath(path: String, label: String): String {
        val raw = path.trim()
        require(raw.isNotEmpty()) { "$label 不能为空" }
        require(raw.startsWith('/')) { "$label 必须是绝对路径" }
        require('\u0000' !in raw) { "$label 不能包含 NUL" }

        val segments = ArrayDeque<String>()
        raw.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private companion object {
        const val STORAGE_REDIRECT_FILE = "storage_redirect"
        const val READ_ONLY_FILE = "read_only"
    }
}
