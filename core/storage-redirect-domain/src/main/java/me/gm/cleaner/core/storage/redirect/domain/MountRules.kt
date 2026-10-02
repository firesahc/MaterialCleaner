package me.gm.cleaner.core.storage.redirect.domain

/**
 * Storage redirect mount rule calculator (transitional legacy).
 *
 * This class is intentionally free of Android, Binder, DataBus and preference
 * dependencies. Production VFS/Hook/deriver now resolve through
 * [OrderedRedirectInterpreter] via [MountPlanDeriver]; this class remains only
 * as the fallback for non-canonical legacy data and UI-independent tests.
 * Reachability analysis moved to the app-side analyzer.
 */
class MountRules {
    private lateinit var ruleZipped: List<Pair<String, String>>
    private lateinit var rule: Pair<List<String>, List<String>>

    constructor(ruleZipped: List<Pair<String, String>>) {
        this.ruleZipped = ruleZipped
    }

    constructor(rule: Pair<List<String>, List<String>>) {
        this.rule = rule
    }

    private fun ensureRuleZipped(): List<Pair<String, String>> {
        if (!::ruleZipped.isInitialized) {
            ruleZipped = rule.first.zip(rule.second)
        }
        return ruleZipped
    }

    private fun ensureRule(): Pair<List<String>, List<String>> {
        if (!::rule.isInitialized) {
            rule = ruleZipped.unzip()
        }
        return rule
    }

    val sources: List<String>
        get() = ensureRule().first

    val targets: List<String>
        get() = ensureRule().second

    fun isEmpty(): Boolean =
        ::ruleZipped.isInitialized && ruleZipped.isEmpty() ||
                ::rule.isInitialized && rule.first.isEmpty()

    val mountPoint: List<String>
        get() {
            val mkdirList = mutableListOf<String>()
            val mountedList = mutableListOf<Pair<String, String>>()
            ensureRuleZipped().forEach { (source, target) ->
                mkdirList.add(getMountedPath(mountedList, target))
                mountedList.add(Pair(source, target))
            }
            return mkdirList
        }

    private fun getMountedPath(ruleZipped: List<Pair<String, String>>, path: String): String {
        val fileSystemLastMatch = ruleZipped.indexOfLast { (_, target) ->
            startsWithPath(path, target)
        }
        if (fileSystemLastMatch == -1) {
            return path
        }

        var mountedPath = path
        ruleZipped.subList(fileSystemLastMatch, ruleZipped.size).forEach { (source, target) ->
            if (startsWithPath(mountedPath, target)) {
                mountedPath = source + mountedPath.substring(target.length)
            }
        }
        return mountedPath
    }

    fun getMountedPath(path: String): String = getMountedPath(ensureRuleZipped(), path)

    companion object {
        fun startsWithPath(path: String, prefix: String): Boolean =
            path == prefix || path.startsWith(ensureTrailingSeparator(prefix))

        private fun ensureTrailingSeparator(path: String): String =
            if (path.endsWith('/')) path else "$path/"
    }
}
