package me.gm.cleaner.runtime.server

import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule

object RuntimeRedirectPolicyFactory {

    fun build(userIds: List<Int>): RedirectPolicySnapshot {
        val now = System.currentTimeMillis()
        val rules = LinkedHashMap<String, Map<Int, List<RedirectRule>>>()

        for (packageName in ServicePreferences.srPackages) {
            val userRules = LinkedHashMap<Int, List<RedirectRule>>()
            for (userId in userIds) {
                val zipped = ServicePreferences.getPackageSrZipped(packageName, userId)
                if (zipped.isNotEmpty()) {
                    userRules[userId] = zipped.map { (source, target) ->
                        RedirectRule(source = source, target = target)
                    }
                }
            }
            if (userRules.isNotEmpty()) {
                rules[packageName] = userRules
            }
        }

        return RedirectPolicySnapshot(
            schemaVersion = 1,
            generation = now,
            createdAt = now,
            storageRedirectRules = rules,
            readOnlyRules = ServicePreferences.getAllReadOnly(),
            denylist = ServicePreferences.denylist.toSet(),
            recordSharedStorage = ServicePreferences.recordSharedStorage,
            recordExternalAppSpecificStorage = ServicePreferences.recordExternalAppSpecificStorage,
            aggressivelyPromptForReadingMediaFiles = ServicePreferences.aggressivelyPromptForReadingMediaFiles,
            upsertRecords = ServicePreferences.upsert,
        )
    }
}
