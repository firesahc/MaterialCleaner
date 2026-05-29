package me.gm.cleaner.client

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import me.gm.cleaner.dao.SecurityHelper

class BinderProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        SecurityHelper.init(context!!)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.d("MC/Test", "BinderProvider.call: method=$method")
        Log.d("CleanerTest", "BinderProvider.call: method=$method, arg=$arg")
        if (extras == null) return null
        val reply = Bundle()
        if (METHOD_SEND_BINDER == method) {
            handleSendBinder(extras)
        }
        return reply
    }

    private fun handleSendBinder(extras: Bundle) {
        val binder = extras.getBinder(EXTRA_BINDER)
        Log.i("MC/Test", "handleSendBinder: currentPingBinder=${CleanerClient.pingBinder()}, hasBinder=${binder != null}")
        Log.i("CleanerTest", "handleSendBinder: pingBinder=${CleanerClient.pingBinder()}, binder=$binder")
        if (CleanerClient.pingBinder()) return
        if (binder == null) return
        Log.i("MC/Test", "handleSendBinder: passing binder to CleanerClient")
        CleanerClient.onBinderReceived(binder)
        Log.i("CleanerTest", "handleSendBinder: binder received successfully")
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
