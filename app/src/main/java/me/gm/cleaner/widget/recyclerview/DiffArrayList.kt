/**
 * 窄用专用工具，仅用于以下文件：
 * - MountWizard.kt
 * - StorageRedirectFragment.kt
 *
 * 功能：BaseKtListAdapter 的扩展函数，配合 DiffArrayList.java 使用，
 * 在有 pending updates 时调用 setCurrentList() + consumePendingUpdates()，
 * 否则回退到 submitList()。
 * 不可直接替代为标准库/框架 API。
 */
package me.gm.cleaner.widget.recyclerview;

import androidx.recyclerview.widget.BaseKtListAdapter
import androidx.recyclerview.widget.RecyclerView

fun <T, VH : RecyclerView.ViewHolder> BaseKtListAdapter<T, VH>.submitDiffList(
    list: DiffArrayList<T>, commitCallback: Runnable? = null
) {
    if (list.hasPendingUpdates()) {
        setCurrentList(list.toList()) {
            list.consumePendingUpdates(this)
            commitCallback?.run()
        }
    } else {
        submitList(list.toList(), commitCallback)
    }
}
