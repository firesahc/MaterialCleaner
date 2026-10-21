package me.gm.cleaner.client

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig.APPLICATION_ID
import me.gm.cleaner.R
import me.gm.cleaner.client.ui.storageredirect.MountWizard
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.dao.RootPreferences
import me.gm.cleaner.dao.ServiceMoreOptionsPreferences
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.config.getPackageSrZipped
import me.gm.cleaner.core.config.removeRedirectRules
import me.gm.cleaner.core.config.replaceRedirectRules
import me.gm.cleaner.net.OnlineAppCategory
import me.gm.cleaner.net.UpdateChecker
import me.gm.cleaner.net.NOTIFICATION_CHANNEL
import me.gm.cleaner.starter.Starter
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.util.PermissionUtils.notifySafe
import me.gm.cleaner.util.getParcelableExtraCompat

class NotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private fun computeHashCode(packageName: String, action: String): Int =
        31 * packageName.hashCode() + action.hashCode()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return super.onStartCommand(intent, flags, startId)
        if (intent.hasExtra(Intent.EXTRA_INTENT)) {
            /** from [ServerBridgeActivity] */
            onReceive(this, intent.getParcelableExtraCompat(Intent.EXTRA_INTENT)!!)
        } else {
            /** from [android.app.Notification.actions] */
            val name = intent.getStringExtra(NAME)
            when (name) {
                NOTIFICATION_CHANNEL_SRPROMPT -> {
                    val packageInfo =
                        intent.getParcelableExtraCompat<PackageInfo>(Intent.EXTRA_PACKAGE_NAME)!!
                    NotificationManagerCompat.from(this).cancel(
                        computeHashCode(packageInfo.packageName, name)
                    )
                    MainScope().launch(Dispatchers.IO) {
                        val denyList = ServicePreferences.denylist + packageInfo.packageName
                        ServicePreferences.denylist = denyList
                    }
                }

                NOTIFICATION_CHANNEL_SHUTDOWN -> {
                    // 「重启 Cleaner 服务」动作：取消通知并重新拉起服务。
                    // 版本过期通知不带动作按钮（唯一有效动作是重启手机），
                    // 故此处不再受理 NOTIFICATION_CHANNEL_UPDATED。
                    NotificationManagerCompat.from(this).cancel(name.hashCode())
                    MainScope().launch(Dispatchers.IO) {
                        ServerStateMachine.start(StartSource.NOTIFICATION, this@NotificationService)
                    }
                }

                NOTIFICATION_CHANNEL_ADDED_UNDO -> {
                    val packageInfo =
                        intent.getParcelableExtraCompat<PackageInfo>(Intent.EXTRA_PACKAGE_NAME)!!
                    NotificationManagerCompat.from(this).cancel(
                        computeHashCode(packageInfo.packageName, NOTIFICATION_CHANNEL_ADDED)
                    )
                    MainScope().launch(Dispatchers.IO) {
                        val sharedProcessPackages =
                            getSharedProcessPackages(packageInfo).map { it.packageName }
                        val store = ConfiguredPolicyStoreProvider.instance
                        val removeResult = store.updateRedirect(store.snapshots.value.redirect.revision) {
                            it.removeRedirectRules(sharedProcessPackages)
                        }
                        if (!removeResult.success) {
                            Log.e("MC/Policy", "remove redirect failed: ${removeResult.error}")
                            return@launch
                        }
                        if (!removeResult.changed) {
                            return@launch
                        }
                        if (CleanerClient.pingBinder()) {
                            CleanerClient.service?.notifySrChanged()
                        }
                    }
                }

                NOTIFICATION_CHANNEL_ADDED_HIDE -> {
                    val packageInfo =
                        intent.getParcelableExtraCompat<PackageInfo>(Intent.EXTRA_PACKAGE_NAME)!!
                    NotificationManagerCompat.from(this).cancel(
                        computeHashCode(packageInfo.packageName, NOTIFICATION_CHANNEL_ADDED)
                    )
                    MainScope().launch(Dispatchers.IO) {
                        val denyList = ServicePreferences.denylist + packageInfo.packageName
                        ServicePreferences.denylist = denyList
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    fun onReceive(context: Context, intent: Intent) {
        if (Process.myUid().toUserId() > 0) return
        when (intent.action) {
            ACTION_REDIRECTED_TO_INTERNAL,
            ACTION_MEDIA_NOT_FOUND -> {
                val prompt = intent.getStringExtra(Intent.EXTRA_TITLE)!!
                val packageInfo =
                    intent.getParcelableExtraCompat<PackageInfo>(Intent.EXTRA_PACKAGE_NAME)!!
                buildPromptNotification(context, prompt, packageInfo)
            }

            ACTION_LOGCAT_SHUTDOWN -> {
                // 同步 Binder 查询可能阻塞主线程，先做无开销的快路径去重，
                // 再到 IO 线程核对 serverException（启动期豁免 + 恢复后抑制）。
                val now = SystemClock.elapsedRealtime()
                if (now - lastLogcatShutdownNotifyElapsed < LogcatDeathPolicy.NOTIFY_DEDUP_MS) {
                    Log.d("NotificationService", "logcat shutdown notification deduped")
                    return
                }
                MainScope().launch(Dispatchers.IO) {
                    val exception = CleanerClient.getServerException()
                    val at = SystemClock.elapsedRealtime()
                    if (!LogcatDeathPolicy.shouldNotify(
                            exception,
                            lastLogcatShutdownNotifyElapsed,
                            at
                        )
                    ) {
                        Log.d(
                            "NotificationService",
                            "logcat shutdown notification suppressed: serverException=$exception"
                        )
                        return@launch
                    }
                    lastLogcatShutdownNotifyElapsed = at
                    buildLogcatShutdownNotification(context)
                }
            }
            ACTION_MY_PACKAGE_REPLACED -> {
                // 仅保留"包更新后写 source_dir"这一独立职责。
                // "请重启手机"的通知改由 ServerStateMachine 的状态驱动检测发出：
                // 本 action 是包被替换那一刻的一次性事件，App 进程可能正好
                // 被安装器杀死，事件会永久丢失（实测从未送达）。
                if (RootPreferences.isStartOnBoot) {
                    MainScope().launch(Dispatchers.IO) {
                        try {
                            Starter.writeSourceDir(context)
                        } catch (e: Throwable) {
                            Log.e("NotificationService", "Failed to write source dir", e)
                        }
                    }
                }
            }

            ACTION_PACKAGE_ADDED -> if (ServiceMoreOptionsPreferences.applyTemplateOnPackageAdded) {
                val packageInfo =
                    intent.getParcelableExtraCompat<PackageInfo>(Intent.EXTRA_PACKAGE_NAME)!!
                if (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    return
                }
                val wizard = MountWizard(packageInfo)
                val answers = ServiceMoreOptionsPreferences.editMountRulesTemplate
                val rulesByTemplate = wizard.createRules(answers)
                val templatePackages = getSharedProcessPackages(packageInfo).map { it.packageName }
                val store = ConfiguredPolicyStoreProvider.instance
                val putResult = store.updateRedirect(store.snapshots.value.redirect.revision) {
                    it.replaceRedirectRules(rulesByTemplate, templatePackages)
                }
                if (!putResult.success) {
                    Log.e("MC/Policy", "put redirect failed: ${putResult.error}")
                } else {
                    if (putResult.changed) {
                        CleanerClient.service?.notifySrChanged()
                    }
                    buildPackageAddedNotification(context, packageInfo)
                    MainScope().launch {
                        OnlineAppCategory.fetch(context, packageInfo).onSuccess { appTypeMarks ->
                            appTypeMarks ?: return@onSuccess
                            wizard.answerBasedOnRecord(answers, emptyList(), appTypeMarks)

                            if (rulesByTemplate ==
                                ConfiguredPolicyStoreProvider.instance.getPackageSrZipped(packageInfo.packageName)
                            ) {
                                val refreshPackages =
                                    getSharedProcessPackages(packageInfo).map { it.packageName }
                                val store = ConfiguredPolicyStoreProvider.instance
                                val refreshResult = store.updateRedirect(store.snapshots.value.redirect.revision) {
                                    it.replaceRedirectRules(wizard.createRules(answers), refreshPackages)
                                }
                                if (!refreshResult.success) {
                                    Log.e("MC/Policy", "put redirect failed: ${refreshResult.error}")
                                    return@launch
                                }
                                if (!refreshResult.changed) {
                                    return@launch
                                }
                                CleanerClient.service?.notifySrChanged()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildPromptNotification(
        context: Context, prompt: String, packageInfo: PackageInfo
    ) {
        val id = computeHashCode(packageInfo.packageName, NOTIFICATION_CHANNEL_SRPROMPT)
        val intent = Intent(context, NotificationService::class.java).apply {
            putExtra(NAME, NOTIFICATION_CHANNEL_SRPROMPT)
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageInfo)
        }
        val pendingIntent = PendingIntent.getService(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val style = NotificationCompat.BigTextStyle().bigText(prompt)
        val notification = NotificationCompat
            .Builder(context, NOTIFICATION_CHANNEL_SRPROMPT)
            .setContentText(prompt)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.outline_error_outline_24)
            .setColor(context.getColor(R.color.color_primary))
            .setStyle(style)
            .addAction(0, context.getString(R.string.no_prompt_for_this_app), pendingIntent)
            .build()
        NotificationManagerCompat.from(context).run {
            NotificationChannels.create(
                context, NOTIFICATION_CHANNEL_SRPROMPT,
                NotificationChannels.Kind.ACTION, R.string.storage_redirect_prompt,
            )
            notifySafe(context, id, notification)
        }
    }

    private fun buildLogcatShutdownNotification(context: Context) {
        val intent = Intent(context, NotificationService::class.java).apply {
            putExtra(NAME, NOTIFICATION_CHANNEL_SHUTDOWN)
        }
        val pendingIntent = PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            packageManager.getLaunchIntentForPackage(APPLICATION_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat
            .Builder(context, NOTIFICATION_CHANNEL_SHUTDOWN)
            .setContentTitle(context.getString(R.string.service_exception_logcat_shutdown))
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_outline_close_24)
            .setColor(context.getColor(R.color.color_primary))
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.confirm_restart_service), pendingIntent)
            .build()
        NotificationManagerCompat.from(context).run {
            NotificationChannels.create(
                context, NOTIFICATION_CHANNEL_SHUTDOWN,
                NotificationChannels.Kind.FAULT, R.string.service_exception_logcat_shutdown,
            )
            notifySafe(context, NOTIFICATION_CHANNEL_SHUTDOWN.hashCode(), notification)
        }
    }

    private fun buildPackageAddedNotification(context: Context, packageInfo: PackageInfo) {        val undoIntent = Intent(context, NotificationService::class.java).apply {
            putExtra(NAME, NOTIFICATION_CHANNEL_ADDED_UNDO)
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageInfo)
        }
        val undoPendingIntent = PendingIntent.getService(
            context, computeHashCode(packageInfo.packageName, NOTIFICATION_CHANNEL_ADDED_UNDO),
            undoIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hideIntent = Intent(context, NotificationService::class.java).apply {
            putExtra(NAME, NOTIFICATION_CHANNEL_ADDED_HIDE)
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageInfo)
        }
        val hidePendingIntent = PendingIntent.getService(
            context, computeHashCode(packageInfo.packageName, NOTIFICATION_CHANNEL_ADDED_HIDE),
            hideIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat
            .Builder(context, NOTIFICATION_CHANNEL_ADDED)
            .setContentTitle(
                context.getString(
                    R.string.applied_template_to_package,
                    AppLabelCache.getPackageLabel(packageInfo)
                )
            )
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_outline_autorenew_24)
            .setColor(context.getColor(R.color.color_primary))
            .addAction(0, context.getString(R.string.undo), undoPendingIntent)
            .addAction(0, context.getString(R.string.menu_add_to_hide_list), hidePendingIntent)
            .build()
        NotificationManagerCompat.from(context).run {
            NotificationChannels.create(
                context, NOTIFICATION_CHANNEL_ADDED,
                NotificationChannels.Kind.INFO, R.string.apply_template_on_package_added_title,
            )
            notifySafe(
                context,
                computeHashCode(packageInfo.packageName, NOTIFICATION_CHANNEL_ADDED),
                notification
            )
        }
    }

    companion object {
        const val ACTION_REDIRECTED_TO_INTERNAL: String =
            "$APPLICATION_ID.intent.action.ACTION_REDIRECTED_TO_INTERNAL"

        fun notifyUpdateAvailable(context: Context, release: UpdateChecker.ReleaseInfo) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL)
                .setContentTitle(context.getString(R.string.new_version_available, release.tagName))
                .setContentText(context.getString(R.string.click_to_view_release))
                .setSmallIcon(R.drawable.ic_outline_update_24)
                .setColor(context.getColor(R.color.color_primary))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            NotificationManagerCompat.from(context).run {
            NotificationChannels.create(
                context, NOTIFICATION_CHANNEL,
                NotificationChannels.Kind.INFO, R.string.update_available_channel_name,
            )
            notifySafe(context, NOTIFICATION_CHANNEL.hashCode(), notification)
        }
        }

        const val ACTION_MEDIA_NOT_FOUND: String =
            "$APPLICATION_ID.intent.action.ACTION_MEDIA_NOT_FOUND"
        private const val NOTIFICATION_CHANNEL_SRPROMPT: String =
            "storage_redirect_prompt"

        const val ACTION_LOGCAT_SHUTDOWN: String =
            "$APPLICATION_ID.intent.action.ACTION_LOGCAT_SHUTDOWN"
        private const val NOTIFICATION_CHANNEL_SHUTDOWN: String = "shutdown"

        const val ACTION_MY_PACKAGE_REPLACED: String =
            "$APPLICATION_ID.intent.action.ACTION_MY_PACKAGE_REPLACED"
        private const val NOTIFICATION_CHANNEL_UPDATED: String = "self_updated"

        const val ACTION_PACKAGE_ADDED: String =
            "$APPLICATION_ID.intent.action.ACTION_PACKAGE_ADDED"
        private const val NOTIFICATION_CHANNEL_ADDED: String = "package_added"
        private const val NOTIFICATION_CHANNEL_ADDED_UNDO: String = "package_added_undo"
        private const val NOTIFICATION_CHANNEL_ADDED_HIDE: String = "package_added_hide"

        private const val NAME: String = "action"

        /**
         * 发出"已安装更新，请重启手机"通知。
         *
         * 由 ServerStateMachine 的版本过期状态检测调用（**状态驱动**）：
         * 包被替换那一刻的事件会因 App 进程被安装器杀死而永久丢失
         * （实测该 action 从未送达），不能作为唯一提醒来源。
         * 状态驱动路径按版本组合去重，同一次不一致只提示一次。
         *
         * 不提供动作按钮：版本过期的唯一有效动作是**重启手机**（Hook 住在
         * Zygote 里，重启服务不换版本），由用户自行执行；给出无效动作
         * （重启服务）会让用户误以为问题已解决。
         */
        fun showSelfUpdatedNotification(context: Context) {
            val notification = NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL_UPDATED)
                .setContentTitle(context.getString(R.string.service_need_reboot))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_outline_update_24)
                .setColor(context.getColor(R.color.color_primary))
                .build()
            NotificationManagerCompat.from(context).run {
                NotificationChannels.create(
                    context, NOTIFICATION_CHANNEL_UPDATED,
                    NotificationChannels.Kind.ACTION, R.string.service_need_reboot,
                )
                notifySafe(context, NOTIFICATION_CHANNEL_UPDATED.hashCode(), notification)
            }
        }

        /**
         * logcat 真死通知的上次弹出时刻（elapsedRealtime）。
         * 看门狗自动重启后 server 会再次广播，去重窗口内不再重复打扰；
         * 判定逻辑见 [LogcatDeathPolicy.shouldNotify]。
         */
        @Volatile
        internal var lastLogcatShutdownNotifyElapsed: Long = 0L
    }
}
