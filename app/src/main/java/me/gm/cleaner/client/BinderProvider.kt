package me.gm.cleaner.client

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.core.config.SecurityHelper

private const val AID_USER_OFFSET = 100000

class BinderProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        SecurityHelper.init(context!!)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.d("MC/Test", "BinderProvider.call: method=$method")
        if (BuildConfig.DEBUG) Log.d("CleanerTest", "BinderProvider.call: method=$method, arg=$arg")
        if (METHOD_SEND_BINDER != method) {
            return Bundle()
        }
        enforceSendBinderCaller(method)
        if (extras != null) {
            handleSendBinder(extras)
        }
        return Bundle()
    }

    private fun enforceSendBinderCaller(method: String) {
        val uid = Binder.getCallingUid()
        if (isAuthorizedSendBinderCaller(uid)) {
            return
        }
        Log.w(
            "MC/Test",
            "Rejected BinderProvider call: method=$method uid=$uid pid=${Binder.getCallingPid()}"
        )
        throw SecurityException("Unauthorized BinderProvider caller: $method")
    }

    private fun isAuthorizedSendBinderCaller(uid: Int): Boolean {
        val appId = uid % AID_USER_OFFSET
        return uid == Process.myUid() ||
                appId == Process.ROOT_UID ||
                appId == Process.SYSTEM_UID ||
                appId == Process.SHELL_UID
    }

    private fun handleSendBinder(extras: Bundle) {
        val binder = extras.getBinder(EXTRA_BINDER)
        Log.i("MC/Test", "handleSendBinder: currentPingBinder=${CleanerClient.pingBinder()}, hasBinder=${binder != null}")
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "handleSendBinder: pingBinder=${CleanerClient.pingBinder()}, binder=$binder")
        if (CleanerClient.pingBinder()) return
        if (binder == null) return
        Log.i("MC/Test", "handleSendBinder: passing binder to CleanerClient")
        CleanerClient.onBinderReceived(binder)
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "handleSendBinder: binder received successfully")
    }

    // no other provider methods
    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        const val METHOD_SEND_BINDER: String = "sendBinder"
        const val EXTRA_BINDER: String = "me.gm.cleaner.intent.extra.BINDER"
    }
}
