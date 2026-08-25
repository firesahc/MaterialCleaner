package me.gm.cleaner.core.common

import android.annotation.SuppressLint
import android.os.Environment
import android.util.Log
import androidx.core.text.isDigitsOnly
import me.gm.cleaner.core.common.AndroidFilesystemConfig.AID_USER_OFFSET
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern
import kotlin.io.path.CopyActionContext
import kotlin.io.path.CopyActionResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.OnErrorResult
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.moveTo
import kotlin.io.path.pathString

object RuntimeFileUtils {
    @Volatile
    private var externalStorageDirInternal: File? = null

    val externalStorageDir: File
        @SuppressLint("SdCardPath")
        get() = externalStorageDirInternal ?: try {
            Environment.getExternalStorageDirectory()
        } catch (e: SecurityException) {
            File("/sdcard").canonicalFile
        }

    fun setExternalStorageDir(dir: File) {
        externalStorageDirInternal = dir
    }

    val externalStorageDirParent: String
        get() = externalStorageDir.parent!!

    val androidDir: File
        get() = externalStorageDir.resolve("Android")

    val androidDataDir: File
        get() = androidDir.resolve("data")

    val androidMediaDir: File
        get() = androidDir.resolve("media")

    val androidObbDir: File
        get() = androidDir.resolve("obb")

    val androidSandboxDir: File
        get() = androidDir.resolve("sandbox")

    fun buildExternalStorageAppDataDirs(packageName: String): File =
        androidDataDir.resolve(packageName)

    val knownAppDirPaths: Pattern by lazy {
        Pattern.compile("(?i)(^/storage/[^/]+/(?:([0-9]+)/)?Android/(?:data|obb)/)([^/]+)(/.*)?")
    }

    fun isKnownAppDirPaths(path: String, packageName: String): Boolean {
        val m = knownAppDirPaths.matcher(path)
        return m.matches() && m.group(3) == packageName
    }

    val appDataDirPaths: Pattern by lazy {
        Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/)?([^/]+)?(/.*)?")
    }

    fun extractUserIdFromPath(path: String, fallbackUserId: Int = 0): Int {
        val m = appDataDirPaths.matcher(path)
        if (m.matches()) {
            return m.group(2)!!.toInt()
        }
        return fallbackUserId
    }

    fun getPathAsUser(path: String, userId: Int): String {
        val m = appDataDirPaths.matcher(path)
        if (!m.matches()) {
            return path
        }
        val sb = StringBuilder()
        for (i in 1..m.groupCount()) {
            val group = m.group(i)
            when {
                group == null -> continue
                group.isDigitsOnly() -> sb.append(userId)
                else -> sb.append(group)
            }
        }
        return sb.toString()
    }

    fun childOf(parent: File, child: File): Boolean = childOf(parent.path, child.path)
    fun childOf(parent: File, child: String): Boolean = childOf(parent.path, child)
    fun childOf(parent: String, child: File): Boolean = childOf(parent, child.path)
    fun childOf(parent: String, child: String): Boolean =
        parent.endsWith(File.separator, true) && child.startsWith(parent, true) ||
                !parent.endsWith(File.separator, true) &&
                child.startsWith(parent + File.separator, true)

    fun startsWith(parent: Path, child: String): Boolean = startsWith(parent.pathString, child)
    fun startsWith(parent: File, child: File): Boolean = startsWith(parent.path, child.path)
    fun startsWith(parent: File, child: String): Boolean = startsWith(parent.path, child)
    fun startsWith(parent: String, child: File): Boolean = startsWith(parent, child.path)
    fun startsWith(parent: String, child: String): Boolean =
        child.equals(parent, true) || parent.equals(File.separator, true) ||
                child.startsWith(parent + File.separator, true)

    @OptIn(ExperimentalPathApi::class)
    fun move(source: Path, target: Path): Boolean {
        try {
            target.createParentDirectories()
        } catch (e: IOException) {
            return false
        }
        try {
            source.moveTo(
                target,
                LinkOption.NOFOLLOW_LINKS,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: NoSuchFileException) {
            return false
        } catch (e: IOException) {
            if (!copy(source, target)) {
                return false
            }
            source.deleteRecursively()
        }
        return true
    }

    @OptIn(ExperimentalPathApi::class)
    @JvmOverloads
    fun copy(
        source: Path, target: Path,
        copyAction: CopyActionContext.(source: Path, target: Path) -> CopyActionResult = { src, dst ->
            src.copyToIgnoringExistingDirectory(dst, false)
        }
    ): Boolean {
        try {
            target.createParentDirectories()
        } catch (e: IOException) {
            return false
        }
        var errorOccurred = false
        try {
            source.copyToRecursively(
                target,
                { source, target, exception ->
                    Log.e(javaClass.name, "$source -> $target\n${exception.stackTraceToString()}")
                    errorOccurred = true
                    OnErrorResult.SKIP_SUBTREE
                },
                false,
                copyAction
            )
        } catch (e: NoSuchFileException) {
            errorOccurred = true
        }
        return !errorOccurred
    }

    fun Int.toUserId(): Int = this / AID_USER_OFFSET
    fun Int.toAppId(): Int = this % AID_USER_OFFSET

    private external fun b(dir: String): Int
    fun rm_dir(dir: String): Int = b(dir)

    private external fun a(dirs: Array<String>, uid: Int): Boolean
    fun auto_prepare_dirs(dirs: Array<String>, uid: Int): Boolean = a(dirs, uid)

    private external fun a(dir: String, uid: Int, isPrivate: Boolean)
    fun switch_owner(dir: String, uid: Int, isPrivate: Boolean) {
        a(dir, uid, isPrivate)
    }

    private external fun b(pid: Int): Int
    fun read_uid(pid: Int): Int = b(pid)

    private external fun a(pid: Int, targets: Array<String>): IntArray?
    fun check_mounts(pid: Int, targets: Array<String>): IntArray? = a(pid, targets)

    private external fun a(
        pid: Int, uid: Int, unmountDataRestriction: Boolean,
        fuseBypass: Boolean, sources: Array<String>, targets: Array<String>
    ): Boolean

    private external fun c(
        pid: Int, uid: Int, unmountDataRestriction: Boolean,
        fuseBypass: Boolean, sources: Array<String>, targets: Array<String>
    ): String

    fun bind_mount(
        pid: Int, uid: Int, unmountDataRestriction: Boolean,
        fuseBypass: Boolean, sources: Array<String>, targets: Array<String>
    ): Boolean = a(pid, uid, unmountDataRestriction, fuseBypass, sources, targets)

    fun bind_mount_result(
        pid: Int, uid: Int, unmountDataRestriction: Boolean,
        fuseBypass: Boolean, sources: Array<String>, targets: Array<String>
    ): BindMountResult = try {
        BindMountResult.fromJson(c(pid, uid, unmountDataRestriction, fuseBypass, sources, targets))
    } catch (e: Exception) {
        BindMountResult(
            success = false,
            stage = "jni_exception",
            errno = 0,
            error = e.message.orEmpty(),
        )
    }

    data class BindMountResult(
        val success: Boolean,
        val stage: String,
        val errno: Int,
        val error: String,
        val failedIndex: Int = -1,
        val source: String = "",
        val target: String = "",
        /** socket wire 协议版本；旧版 native 无此字段时为 0。 */
        val schemaVersion: Int = 0,
        /** 事务阶段数值，见 native MountPhase；-1 表示未知。 */
        val phase: Int = -1,
        /** 事务阶段名（args/zygote_wait/namespace/baseline/rules/report/unknown）。 */
        val phaseName: String = "unknown",
        /** 目标命名空间可能残留部分效果（预留：当前实现恒 false）。 */
        val namespaceDirty: Boolean = false,
        /** 污染处置中已安全终止目标应用（仅 namespaceDirty 时可能为 true）。 */
        val targetTerminated: Boolean = false,
    ) {
        val reason: String
            get() = if (success) {
                "success"
            } else {
                buildList {
                    add("stage=$stage")
                    if (phase >= 0) add("phase=$phaseName")
                    if (errno != 0) add("errno=$errno")
                    if (error.isNotBlank()) add("error=$error")
                    if (failedIndex >= 0) add("index=$failedIndex")
                    if (namespaceDirty) add("dirty=true")
                    // source/target 为挂载规则坐标，仅进入结构化字段与诊断包，
                    // 不再拼接进 reason 以免明文路径进入常规日志。
                }.joinToString(", ")
            }

        companion object {
            fun fromJson(json: String): BindMountResult {
                val root = JSONObject(json)
                return BindMountResult(
                    success = root.optBoolean("success", false),
                    stage = root.optString("stage", ""),
                    errno = root.optInt("errno", 0),
                    error = root.optString("error", ""),
                    failedIndex = root.optInt("failedIndex", -1),
                    source = root.optString("source", ""),
                    target = root.optString("target", ""),
                    schemaVersion = root.optInt("schemaVersion", 0),
                    phase = root.optInt("phase", -1),
                    phaseName = root.optString("phaseName", "unknown"),
                    namespaceDirty = root.optBoolean("namespaceDirty", false),
                    targetTerminated = root.optBoolean("targetTerminated", false),
                )
            }
        }
    }
}
