package me.gm.cleaner.client

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.gm.cleaner.BuildConfig

/**
 * Xposed/MediaProvider hooks 服务连接状态的独立观察者。
 *
 * 这是一个独立于 [ServerStateMachine] 的 Flow，因为 Xposed 连接状态
 * 不由 app 控制——LSPosed 负责模块注入，libinline.so 负责 xhook 安装，
 * app 只能被动感知 sMediaProviderService 的生死。
 *
 * 数据源：[HooksBridgeProvider] 中 IMediaProviderHooksService Binder 的
 * DeathRecipient（sMediaProviderService）。
 *
 * true  = IMediaProviderHooksService Binder 存活且已注册到 HooksBridgeProvider
 *         此时 xhook 也已安装完毕（XposedInit 中 initializeXHook() 在
 *         registerHooksCallback() 之前执行）
 * false = Binder 死亡/unlink/未注册
 */
object XposedConnectionState {

    private val _isConnected = MutableStateFlow(false)

    /** 当前 Xposed hooks 连接状态。true=已连接。 */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * 由 [HooksBridgeProvider.setMediaProviderBinder] 在成功接收
     * Xposed 的 IMediaProviderHooksService Binder 后调用。
     */
    fun onConnected() {
        if (!_isConnected.value) {
            if (BuildConfig.DEBUG) Log.i("MC/XposedState", "connected")
            _isConnected.value = true
        }
    }

    /**
     * 由 [HooksBridgeProvider] 中 sMediaProviderService 的 DeathRecipient
     * 在 MediaProvider 进程死亡时调用。
     */
    fun onDisconnected() {
        if (_isConnected.value) {
            if (BuildConfig.DEBUG) Log.w("MC/XposedState", "disconnected (binder died)")
            _isConnected.value = false
        }
    }

    /**
     * 从 HooksBridgeProvider 查询当前 Xposed Binder 状态。
     * 用于 app 进程重启后恢复 XposedConnectionState。
     */
    fun refreshFromProvider() {
        val connected = HooksBridgeProvider.isMediaProviderConnected()
        if (BuildConfig.DEBUG) Log.d("MC/XposedState", "refreshFromProvider: $connected")
        if (connected != _isConnected.value) {
            _isConnected.value = connected
        }
    }
}
