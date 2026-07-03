package me.gm.cleaner.runtime.server.observer

import android.content.IIntentReceiver
import android.content.IntentFilter
import android.os.RemoteException
import android.util.Log
import api.SystemService

class IntentReceiver : BaseIntentObserver() {
    private val TAG = "IntentReceiver"

    override fun registerReceiverInternal(
        receiver: IIntentReceiver, filter: IntentFilter, userId: Int, flags: Int
    ) {
        try {
            SystemService.registerReceiver(null, null, receiver, filter, null, userId, flags)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to register receiver via AMS", e)
        }
    }

    override fun unregisterReceiverInternal(receiver: IIntentReceiver) {
        try {
            SystemService.unregisterReceiver(receiver)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to unregister receiver via AMS", e)
        }
    }
}
