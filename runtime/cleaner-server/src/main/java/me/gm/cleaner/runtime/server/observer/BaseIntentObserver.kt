package me.gm.cleaner.runtime.server.observer

import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.RemoteException
import android.util.ArraySet
import android.util.Log
import com.google.common.collect.Multimaps
import com.google.common.collect.SetMultimap

abstract class BaseIntentObserver : BaseObserver() {
    private val TAG = "BaseIntentObserver"
    private val receivers: SetMultimap<IIntentReceiver, String> =
        Multimaps.newSetMultimap(HashMap()) { ArraySet() }

    fun registerReceiver(
        receiver: IIntentReceiver, filter: IntentFilter, userId: Int, flags: Int
    ) {
        registerReceiverInternal(receiver, filter, userId, flags)
        receivers.putAll(receiver, filter.actionsIterator().asSequence().toList())
    }

    fun unregisterReceiver(receiver: IIntentReceiver) {
        unregisterReceiverInternal(receiver)
        receivers.removeAll(receiver)
    }

    protected open fun unregisterReceiverInternal(receiver: IIntentReceiver) {}

    protected open fun registerReceiverInternal(
        receiver: IIntentReceiver, filter: IntentFilter, userId: Int, flags: Int
    ) {
    }

    protected fun mockBroadcastIntent(intent: Intent) {
        receivers.asMap().forEach { receiver, actions ->
            if (actions.contains(intent.action)) {
                try {
                    receiver.performReceive(intent, 0, null, null, false, false, 0)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Failed to mock broadcast intent", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receivers.keySet().forEach { receiver ->
            unregisterReceiverInternal(receiver)
        }
    }
}
