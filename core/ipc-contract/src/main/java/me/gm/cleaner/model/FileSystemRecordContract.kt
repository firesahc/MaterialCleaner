package me.gm.cleaner.model

object FileSystemRecordContract {
    const val DATABASE_NAME: String = "filesystem.db"

    const val PRUNE_DELETE_ALL: Int = 0
    const val PRUNE_DELETE_APP_SPECIFIC: Int = -1
    const val PRUNE_UNINSTALLED: Int = -2
    const val PRUNE_DISTINCT: Int = -3
    const val PRUNE_QUERIED: Int = -4
}
