package me.gm.cleaner.core.common.nio

import android.os.Binder
import android.os.Process
import me.gm.cleaner.browser.IProgressListener
import me.gm.cleaner.browser.IRootFileService
import me.gm.cleaner.core.common.RuntimeFileUtils.toAppId
import me.gm.cleaner.model.ParceledBasicFileAttributes
import me.gm.cleaner.model.ParceledCopyOptions
import me.gm.cleaner.model.ParceledException
import me.gm.cleaner.model.ParceledListSlice
import me.gm.cleaner.model.ParceledPath
import me.gm.cleaner.core.common.nio.fs.StructStatFileAttributes
import me.gm.cleaner.core.common.nio.fs.StructStatPath
import java.net.URI
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readAttributes
import kotlin.io.path.toPath

class RootFileService(private val managerAppId: Int) : IRootFileService.Stub() {

    private fun enforceManager(method: String) {
        if (Binder.getCallingPid() == Process.myPid() ||
            Binder.getCallingUid().toAppId() == managerAppId
        ) {
            return
        }
        throw SecurityException(method)
    }

    private fun decode(uriString: String): Path = URI.create(uriString).toPath()

    private fun toParceledAttributes(attrs: StructStatFileAttributes): ParceledBasicFileAttributes =
        ParceledBasicFileAttributes(
            attrs.lastModifiedTime().toMillis(),
            attrs.lastAccessTime().toMillis(),
            attrs.creationTime().toMillis(),
            attrs.isRegularFile,
            attrs.isDirectory,
            attrs.isSymbolicLink,
            attrs.isOther,
            attrs.size(),
            attrs.fileKey()
        )

    override fun readAttributes(
        listener: IProgressListener, file: String, followLinks: Boolean
    ): ParceledBasicFileAttributes {
        enforceManager("RootFileService.readAttributes")
        try {
            val attrs: StructStatFileAttributes = if (!followLinks) {
                StructStatPath.wrap(decode(file)).readAttributes(LinkOption.NOFOLLOW_LINKS)
            } else {
                StructStatPath.wrap(decode(file)).readAttributes()
            }
            return toParceledAttributes(attrs)
        } catch (e: Throwable) {
            listener.onException(ParceledException.create(e))
            throw e
        }
    }

    override fun delete(listener: IProgressListener, file: String) {
        enforceManager("RootFileService.delete")
        try {
            decode(file).deleteExisting()
        } catch (e: Throwable) {
            listener.onException(ParceledException.create(e))
            throw e
        }
    }

    override fun copy(
        listener: IProgressListener, source: String, target: String, options: ParceledCopyOptions
    ) {
        enforceManager("RootFileService.copy")
        try {
            decode(source).copyTo(decode(target), *options.value)
        } catch (e: Throwable) {
            listener.onException(ParceledException.create(e))
            throw e
        }
    }

    override fun move(
        listener: IProgressListener, source: String, target: String, options: ParceledCopyOptions
    ) {
        enforceManager("RootFileService.move")
        try {
            decode(source).moveTo(decode(target), *options.value)
        } catch (e: Throwable) {
            listener.onException(ParceledException.create(e))
            throw e
        }
    }

    override fun newDirectoryStream(
        listener: IProgressListener, dir: String
    ): ParceledListSlice<ParceledPath> = try {
        enforceManager("RootFileService.newDirectoryStream")
        ParceledListSlice(
            decode(dir).listDirectoryEntries().map {
                ParceledPath(
                    it.fileName.toUri().toASCIIString(),
                    toParceledAttributes(
                        StructStatPath.wrap(it).readAttributes(LinkOption.NOFOLLOW_LINKS)
                    )
                )
            }
        )
    } catch (e: Throwable) {
        listener.onException(ParceledException.create(e))
        throw e
    }
}
