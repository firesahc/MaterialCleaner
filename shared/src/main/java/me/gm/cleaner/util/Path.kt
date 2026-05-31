package me.gm.cleaner.util

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

@JvmOverloads
fun Path.listDirectoryEntriesSafe(glob: String = "*"): List<Path> =
    try {
        listDirectoryEntries(glob)
    } catch (e: Exception) {
        emptyList()
    }
