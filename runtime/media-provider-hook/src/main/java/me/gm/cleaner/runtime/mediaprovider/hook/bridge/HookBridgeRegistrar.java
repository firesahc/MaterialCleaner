package me.gm.cleaner.runtime.mediaprovider.hook.bridge;

import me.gm.cleaner.core.common.err.ErrorCodes;
import me.gm.cleaner.runtime.mediaprovider.hook.media.MediaProviderHooksService;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public final class HookBridgeRegistrar {
    private static final String TAG = "HookBridgeRegistrar";
    private static final Uri HOOKS_URI = Uri.parse("content://me.gm.cleaner.hooks_bridge");
    private static final String EXTRA_REGISTERED = "registered";
    private static final String METHOD_REGISTER_HOOKS_CALLBACK = "register_hooks_callback";
    private static final String METHOD_GET_HOOKS_SERVICE = "get_hooks_service";
    private static final String EXTRA_BINDER = "binder";

    /**
     * 注册表 Binder 监视状态（Fix 1′）。锁内只维护引用与代次；
     * 跨进程 call 与 link/unlinkToDeath 的阻塞部分在锁外或沿用既有模式
     * （link/unlink 置于锁内，与 HooksBridgeProvider.setMediaProviderBinder
     * 的 synchronized(sMediaProviderLock) 模式一致，均不执行远端代码）。
     */
    private static final Object sWatchLock = new Object();
    private static IBinder sWatchedRegistryBinder;
    private static IBinder.DeathRecipient sRegistryDeathRecipient;
    private static long sWatchGeneration;

    private HookBridgeRegistrar() {
    }

    public static boolean registerHooksCallback(Context context, IBinder binder) {
        NativeHookStatus.INSTANCE.markBridgeRegistering();
        try {
            final Bundle extras = new Bundle();
            extras.putBinder("binder", binder);
            // 显式确认协议：新版 Provider 回传 registered 字段校验真实接入；
            // 旧版无确认字段时保持兼容，仅要求调用不抛异常。
            final Bundle result = context.getContentResolver().call(
                    HOOKS_URI, METHOD_REGISTER_HOOKS_CALLBACK, null, extras);
            final boolean accepted = BridgeRegistrationAckPolicy.isAccepted(
                    result != null,
                    result != null && result.containsKey(EXTRA_REGISTERED),
                    result != null && result.getBoolean(EXTRA_REGISTERED, false));
            if (!accepted) {
                NativeHookStatus.INSTANCE.recordHookError(
                        ErrorCodes.HOOK_JAVA_REG_FAILED,
                        "stage=register_hooks_callback, reason=provider_acknowledged_false");
                NativeHookStatus.INSTANCE.markBridgeFailed(
                        "provider acknowledged registered=false");
                return false;
            }
            NativeHookStatus.INSTANCE.markBridgeRegistered();
            return true;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                throw (VirtualMachineError) e;
            }
            if (e instanceof ThreadDeath) {
                throw (ThreadDeath) e;
            }
            Log.e(TAG, "Failed to register hooks callback", e);
            NativeHookStatus.INSTANCE.recordHookError(
                    ErrorCodes.HOOK_JAVA_REG_FAILED,
                    "stage=register_hooks_callback, failureType=" + e.getClass().getSimpleName());
            NativeHookStatus.INSTANCE.markBridgeFailed(describeThrowable(e));
            return false;
        }
    }

    /** 供 markBridgeFailed 使用的受控异常描述；截断由 NativeHookStatus 统一处理。 */
    private static String describeThrowable(Throwable e) {
        final String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getName();
        }
        return e.getClass().getName() + ": " + message;
    }

    /**
     * 监视注册表 Binder 存活（Fix 1′ 根治）。
     *
     * <p>旧媒体进程孤儿化的根因：它只监听 server 回调死亡，从不监听注册表死亡。
     * App 被划掉只杀死注册表时，无任何事件能触发重注册。本方法让媒体侧持有
     * 注册表 Binder 并监听其死亡，死亡即经既有重试门控重新注册。
     *
     * <p>约束：幂等（同活体复用）；替换时先 unlink 旧监听；死亡回调携带代次，
     * 晚到的旧回调直接丢弃；跨进程 call 在锁外，link/unlink 置于短锁内
     * （不执行远端代码）；返回值仅表示“本次确认监视中”，失败交由调用方
     * 的重试 accounting 驱动。
     *
     * @return true 已确认监视中；false 本次未建立，调用方应按失败重试
     */
    public static boolean watchRegistryBinder(Context context) {
        final IBinder watched;
        synchronized (sWatchLock) {
            watched = sWatchedRegistryBinder;
        }
        // pingBinder 是跨进程往返，必须在锁外。
        if (watched != null && watched.pingBinder()) {
            return true;
        }
        // 可能阻塞（App 进程不存在时会拉起），必须在锁外。
        final IBinder fresh;
        try {
            fresh = fetchRegistryBinder(context);
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                throw (VirtualMachineError) e;
            }
            if (e instanceof ThreadDeath) {
                throw (ThreadDeath) e;
            }
            Log.w(TAG, "Failed to fetch registry binder", e);
            return false;
        }
        if (fresh == null || !fresh.pingBinder()) {
            return false;
        }
        // 竞争分支的存活复核是跨进程调用，必须移出锁：先快照、锁外 ping、再按需加锁更新。
        final IBinder competing;
        synchronized (sWatchLock) {
            competing = (sWatchedRegistryBinder != null && sWatchedRegistryBinder != watched)
                    ? sWatchedRegistryBinder : null;
        }
        final boolean competingAlive = competing != null && competing.pingBinder();
        synchronized (sWatchLock) {
            // 安装期间若已有活体（并发尝试先装好），丢弃本次结果，避免监听堆积。
            // 引用比对即可：同一周期内只有本监视器会写入该字段。
            // 仅当竞争者仍为当前值时采信锁外 ping 结果；期间已变更则按新状态处理。
            if (competing != null && sWatchedRegistryBinder == competing) {
                // 竞争分支必须复核存活：他线程装的 Binder 可能在我们进入本锁前已死亡
                // （死亡回调尚未执行或时序交错），此时若直接返回 true，
                // 将没有有效监视器却又报告成功。已死则清理后继续安装本次结果。
                if (competingAlive) {
                    return true;
                }
                try {
                    sWatchedRegistryBinder.unlinkToDeath(sRegistryDeathRecipient, 0);
                } catch (RuntimeException ignored) {
                }
                sWatchedRegistryBinder = null;
                sRegistryDeathRecipient = null;
            }
            // 锁外 ping 期间若装入未经复核的新竞争者，不在锁内 ping、不直接覆盖：
            // 返回 false 交由调用方重试，下轮在方法入口快照并复核其存活。
            // （不返回 true，避免谎报“已确认监视中”；注册成功 vs 死亡监视成功语义不变。）
            if (sWatchedRegistryBinder != null && sWatchedRegistryBinder != watched) {
                return false;
            }
            if (sWatchedRegistryBinder != null) {
                try {
                    sWatchedRegistryBinder.unlinkToDeath(sRegistryDeathRecipient, 0);
                } catch (RuntimeException ignored) {
                }
                sWatchedRegistryBinder = null;
                sRegistryDeathRecipient = null;
            }
            final long generation = ++sWatchGeneration;
            final IBinder.DeathRecipient recipient = () -> onRegistryBinderDied(generation);
            try {
                fresh.linkToDeath(recipient, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Registry binder died before linkToDeath", e);
                return false;
            } catch (RuntimeException e) {
                Log.w(TAG, "linkToDeath to registry binder failed", e);
                return false;
            }
            sWatchedRegistryBinder = fresh;
            sRegistryDeathRecipient = recipient;
            return true;
        }
    }

    private static IBinder fetchRegistryBinder(Context context) {
        if (context == null) {
            return null;
        }
        final Bundle result = context.getContentResolver().call(
                HOOKS_URI, METHOD_GET_HOOKS_SERVICE, null, null);
        if (result == null) {
            return null;
        }
        final IBinder binder = result.getBinder(EXTRA_BINDER);
        result.clear();
        return binder;
    }

    private static void onRegistryBinderDied(long generation) {
        synchronized (sWatchLock) {
            if (generation != sWatchGeneration) {
                Log.i(TAG, "Stale registry death notification ignored, generation=" + generation);
                return;
            }
            sWatchedRegistryBinder = null;
            sRegistryDeathRecipient = null;
        }
        // 锁外触发：经既有重试门控合并，防止死亡风暴重启退避。
        Log.w(TAG, "Registry binder died, requesting re-register");
        NativeHookStatus.INSTANCE.recordHookError(
                ErrorCodes.HOOK_JAVA_BINDER_DEAD,
                "stage=registry_binder_death, generation=" + generation);
        MediaProviderHooksService.requestReRegister("registry died");
    }
}
