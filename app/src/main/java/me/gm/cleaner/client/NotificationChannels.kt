package me.gm.cleaner.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings

/**
 * 应用通知渠道的统一决策点。
 *
 * 职责边界：只负责"一个通知渠道该有多强的呈现强度"这一决策，
 * 以及据此创建渠道。通知的内容、动作、投递时机都不在此处。
 *
 * 存在理由：此前每个渠道各自手写 IMPORTANCE 与声音/震动配置，出现过
 * "IMPORTANCE_MAX 却显式 setSound(null, null)"（要用户做选择却不发声）、
 * "IMPORTANCE_NONE"（用户永远收不到）与全项目从未开启震动等自相矛盾配置。
 * 声音与震动本质是同一个决策——这条通知要不要打断用户——集中到一处后
 * 不再可能各自漂移。
 *
 * 平台约束：Android 8.0+ 起，已存在渠道的 IMPORTANCE 与声音/震动**不能由
 * 应用修改**，本类的配置只在渠道首次创建时生效。
 */
object NotificationChannels {

    /**
     * 通知类别，决定呈现强度。
     *
     * 判定依据是"**是否需要用户立即行动**"，而非通知的内容形式。
     */
    enum class Kind {
        /** 服务故障，需要用户立即处理（如日志猫退出、服务启动失败）。 */
        FAULT,

        /** 需要用户做出选择或执行强动作（如重定向确认、版本过期需重启手机）。 */
        ACTION,

        /** 用户应知情的事实告知，不需要打断（如包已添加、有新版本）。 */
        INFO,
    }

    /**
     * 创建（或按 [Kind] 重新设定）通知渠道。
     *
     * 渠道 id 必须保持稳定：改 id 会让用户在系统设置里看到新渠道，
     * 且既有渠道的设置不再沿用。
     */
    fun create(context: Context, channelId: String, kind: Kind, nameRes: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            context.getString(nameRes),
            kind.importance(),
        )
        if (kind.sounds()) {
            // 第二个参数为 null 表示沿用系统默认通知音频属性。
            channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null)
        }
        if (kind.vibrates()) {
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 300, 200, 300)
        }
        manager.createNotificationChannel(channel)
    }

    private fun Kind.importance(): Int = when (this) {
        Kind.FAULT -> NotificationManager.IMPORTANCE_HIGH
        Kind.ACTION -> NotificationManager.IMPORTANCE_MAX
        Kind.INFO -> NotificationManager.IMPORTANCE_LOW
    }

    private fun Kind.sounds(): Boolean = this != Kind.INFO

    private fun Kind.vibrates(): Boolean = this != Kind.INFO
}
