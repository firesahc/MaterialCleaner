package me.gm.cleaner.client.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.OrchestratedLayerStatus
import me.gm.cleaner.client.OrchestratedRuntimeStatus
import me.gm.cleaner.client.ServerState
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.core.config.ServicePreferences

/**
 * 状态卡视图引用：Fragment 在 onCreateView 中组装，onDestroyView 时 detach。
 */
data class StatusCardViews(
    val statusDot: ImageView?,
    val statusTitle: TextView?,
    val statusSubtitle: TextView?,
    val btnToggleServer: TextView?,
    val btnToggleStatusDetails: ImageView?,
    val statusDetails: View?,
    val l1Indicator: ImageView?,
    val l1Text: TextView?,
    val l2Indicator: ImageView?,
    val l2Text: TextView?,
    val l3Indicator: ImageView?,
    val l3Text: TextView?,
    val l4Indicator: ImageView?,
    val l4Text: TextView?,
    val l5Indicator: ImageView?,
    val l5Text: TextView?,
    val statusCause: TextView?,
)

/** 状态卡单层行（ViewState 的一部分，与展示顺序一致）。 */
data class StatusLayerRow(
    val name: String,
    val stateText: String,
    val summary: String,
    val colorRes: Int,
)

/** 状态卡整体 ViewState：Controller 对外吐出的数据，Fragment 只做 collect + 转发。 */
data class StatusCardViewState(
    val title: String,
    val subtitle: String,
    val rows: List<StatusLayerRow>,
    val cause: String?,
    val dotIconRes: Int,
    val dotColorRes: Int,
    val toggleLabel: String,
    val detailsAvailable: Boolean,
    val manuallyStopped: Boolean,
)

/**
 * 状态卡控制器：持有 [CleanerClient.getOrchestratedStatus] 数据源，承接生命周期守卫。
 *
 * 职责边界：
 * - 数据源：后台拉取 orchestratedStatus（IO 线程），回主线程后经守卫渲染；
 * - 守卫：[guard]（Fragment 传入 `{ isAdded }`）与 views 存活检查替代原
 *   `if (!isAdded) return` / `requireContext()`，未通过时直接丢弃；
 * - 文案/色值计算全部委托 [ServiceStatusFormatter]（纯函数），本类只做 View 绑定；
 * - 不持有 Fragment / Context；View 引用经 [attach]/[detach] 与视图生命周期对齐。
 *
 * 外部依赖（Shell.isRoot / srPackages / 手动停止标记）以 lambda 注入，单测可替换。
 */
class StatusCardController(
    private val strings: StatusStrings,
    private val getOrchestratedStatus: () -> OrchestratedRuntimeStatus? = CleanerClient::getOrchestratedStatus,
    private val isRoot: () -> Boolean = { runCatching { Shell.getShell().isRoot }.getOrDefault(false) },
    private val configuredCount: () -> Int = { ServicePreferences.srPackages.size },
    private val isManuallyStopped: () -> Boolean = { ServerStateMachine.isSessionManuallyStopped },
    private val guard: () -> Boolean = { true },
) {
    var orchestratedStatus: OrchestratedRuntimeStatus? = null
        private set

    var isExpanded: Boolean = false

    private var views: StatusCardViews? = null
    private var detailsAvailable = false

    fun attach(v: StatusCardViews) {
        views = v
        renderDetailsExpansion()
    }

    fun detach() {
        views = null
    }

    /** 展开/收起切换：详情不可用时忽略。 */
    fun toggleExpansion() {
        if (!detailsAvailable) return
        isExpanded = !isExpanded
        renderDetailsExpansion()
    }

    /**
     * 后台刷新 orchestratedStatus，回主线程后按给定输入渲染。
     * 调用方（Fragment collect 回调）只负责转发当前 serverState / xposed 状态。
     */
    fun refresh(
        scope: CoroutineScope,
        serverState: ServerState,
        xposedConnected: Boolean,
    ) {
        scope.launch(Dispatchers.IO) {
            val status = runCatching { getOrchestratedStatus() }.getOrNull()
            withContext(Dispatchers.Main) {
                orchestratedStatus = status
                render(serverState, xposedConnected)
            }
        }
    }

    /**
     * 组装 ViewState 并绑定到已 attach 的视图。
     * 守卫未通过（Fragment 不在位 / 视图已销毁）时返回 null 并丢弃。
     */
    fun render(serverState: ServerState, xposedConnected: Boolean): StatusCardViewState? {
        if (!guard()) return null
        val v = views ?: return null
        val state = buildViewState(serverState, xposedConnected)
        bind(v, state)
        return state
    }

    /** 纯组装（不碰 View）：供单测与 render 复用。 */
    fun buildViewState(serverState: ServerState, xposedConnected: Boolean): StatusCardViewState {
        val s = strings
        val configured = configuredCount()
        if (isManuallyStopped()) {
            val (dotIcon, dotColor) =
                R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
            return StatusCardViewState(
                title = ServiceStatusFormatter.stoppedTitle(s),
                subtitle = ServiceStatusFormatter.stoppedSubtitle(s, configured),
                rows = emptyList(),
                cause = null,
                dotIconRes = dotIcon,
                dotColorRes = dotColor,
                toggleLabel = s.get(R.string.btn_start_service),
                detailsAvailable = false,
                manuallyStopped = true,
            )
        }

        val rootAvailable = isRoot()
        val orchestrated = orchestratedStatus.takeIf { serverState == ServerState.RUNNING }
        val toggleLabel = ServiceStatusFormatter.toggleLabel(s, serverState)
        if (orchestrated == null) {
            val layerNames = layerNames()
            val fallbackRows = ServiceStatusFormatter.fallbackRows(s, serverState, rootAvailable, xposedConnected)
            val rows = layerNames.zip(fallbackRows) { name, row ->
                StatusLayerRow(
                    name = name,
                    stateText = ServiceStatusFormatter.stateLabel(s, row.state),
                    summary = row.summary,
                    colorRes = ServiceStatusFormatter.layerColorRes(row.state),
                )
            }
            val (dotIcon, dotColor) = ServiceStatusFormatter.fallbackDotRes(serverState)
            return StatusCardViewState(
                title = s.get(ServiceStatusFormatter.fallbackTitleRes(serverState)),
                subtitle = ServiceStatusFormatter.fallbackSubtitle(s, serverState, configured),
                rows = rows,
                cause = ServiceStatusFormatter.fallbackCause(s, serverState, rootAvailable, xposedConnected),
                dotIconRes = dotIcon,
                dotColorRes = dotColor,
                toggleLabel = toggleLabel,
                detailsAvailable = true,
                manuallyStopped = false,
            )
        }

        val layerNames = layerNames()
        val layers = listOf(
            orchestrated.vfs,
            orchestrated.mediaProviderJavaHook,
            orchestrated.fuseNativeHook,
            orchestrated.dataBus,
            orchestrated.controlPlane,
        )
        val summaries = listOf(
            ServiceStatusFormatter.vfsSummary(s, orchestrated.vfs, configured),
            ServiceStatusFormatter.hookSummary(s, orchestrated.mediaProviderJavaHook),
            ServiceStatusFormatter.nativeSummary(s, orchestrated.fuseNativeHook),
            ServiceStatusFormatter.dataBusSummary(s, orchestrated.dataBus),
            ServiceStatusFormatter.controlPlaneSummary(s, orchestrated.controlPlane),
        )
        val rows = layerNames.zip(layers.zip(summaries)) { name, (layer, summary) ->
            StatusLayerRow(
                name = name,
                stateText = ServiceStatusFormatter.stateLabel(s, layer.state),
                summary = summary,
                colorRes = ServiceStatusFormatter.layerColorRes(layer.state),
            )
        }
        val namedLayers: List<Pair<String, OrchestratedLayerStatus>> = layerNames.zip(layers)
        val (dotIcon, dotColor) = ServiceStatusFormatter.statusIconRes(orchestrated.health)
        return StatusCardViewState(
            title = ServiceStatusFormatter.orchestratedTitle(s, orchestrated.health, configured),
            subtitle = ServiceStatusFormatter.topSummary(s, orchestrated, configured),
            rows = rows,
            cause = ServiceStatusFormatter.firstProblem(s, namedLayers),
            dotIconRes = dotIcon,
            dotColorRes = dotColor,
            toggleLabel = toggleLabel,
            detailsAvailable = true,
            manuallyStopped = false,
        )
    }

    private fun layerNames(): List<String> = listOf(
        strings.get(R.string.runtime_layer_vfs),
        strings.get(R.string.runtime_layer_media_provider_hook),
        strings.get(R.string.runtime_layer_fuse_native),
        strings.get(R.string.runtime_layer_databus),
        strings.get(R.string.runtime_layer_control_plane),
    )

    private fun bind(v: StatusCardViews, state: StatusCardViewState) {
        v.statusTitle?.text = state.title
        v.statusSubtitle?.text = state.subtitle
        v.btnToggleServer?.text = state.toggleLabel

        detailsAvailable = state.detailsAvailable
        renderDetailsExpansion()

        val indicators = listOf(
            v.l1Indicator to v.l1Text,
            v.l2Indicator to v.l2Text,
            v.l3Indicator to v.l3Text,
            v.l4Indicator to v.l4Text,
            v.l5Indicator to v.l5Text,
        )
        if (state.manuallyStopped) {
            v.statusCause?.visibility = View.GONE
        } else {
            indicators.forEachIndexed { index, (indicator, text) ->
                val row = state.rows.getOrNull(index) ?: return@forEachIndexed
                val ctx = text?.context ?: indicator?.context ?: return@forEachIndexed
                indicator?.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, row.colorRes)
                )
                text?.text = ServiceStatusFormatter.rowText(row.name, row.stateText, row.summary)
                text?.setTextColor(ContextCompat.getColor(ctx, row.colorRes))
            }
            v.statusCause?.visibility = if (state.cause == null) View.GONE else View.VISIBLE
            v.statusCause?.text = state.cause
        }

        val dotCtx = v.statusDot?.context ?: v.statusTitle?.context ?: return
        v.statusDot?.setImageResource(state.dotIconRes)
        v.statusDot?.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(dotCtx, state.dotColorRes)
        )
    }

    private fun renderDetailsExpansion() {
        val v = views ?: return
        val showDetails = detailsAvailable && isExpanded
        v.statusDetails?.visibility = if (showDetails) View.VISIBLE else View.GONE
        v.btnToggleStatusDetails?.apply {
            visibility = if (detailsAvailable) View.VISIBLE else View.INVISIBLE
            isEnabled = detailsAvailable
            isClickable = detailsAvailable
            isFocusable = detailsAvailable
            rotationX = if (isExpanded) 180F else 0F
            contentDescription = context.getString(
                if (isExpanded) {
                    R.string.status_details_collapse
                } else {
                    R.string.status_details_expand
                },
            )
        }
    }
}
