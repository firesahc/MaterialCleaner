package me.gm.cleaner.net

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

class SimpleCachedURL(val context: Context, val url: URL) {
    constructor(context: Context, spec: String) : this(context, URL(spec))

    private val externalCacheDir: File? = context.externalCacheDir
    private val cacheFile: File? = if (externalCacheDir == null) null
    else File(externalCacheDir, MessageDigest.getInstance("MD5").digest(url.toString().toByteArray()).joinToString("") { "%02x".format(it) })

    fun hasCache(): Boolean = cacheFile?.exists() == true

    fun hasNonNullCache(): Boolean = hasCache() && cacheFile?.readText()?.isNotEmpty() == true

    fun invalidate(): SimpleCachedURL {
        cacheFile?.delete()
        return this
    }

    private fun openStreamInternal(): InputStream = url
        .openConnection()
        .apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
        .getInputStream()

    @Throws(IOException::class)
    fun openStream(): InputStream {
        if (externalCacheDir == null) {
            return openStreamInternal()
        }
        if (!hasCache()) {
            runCatching {
                openStreamInternal()
            }.onSuccess { inputStream ->
                cacheFile!!.createNewFile()
                inputStream.use { input ->
                    cacheFile!!.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure { e ->
                if (e !is FileNotFoundException) {
                    throw e
                }
            }
        }
        return cacheFile!!.inputStream()
    }
}
