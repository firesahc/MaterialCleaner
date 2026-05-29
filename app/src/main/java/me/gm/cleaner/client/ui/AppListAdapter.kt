package me.gm.cleaner.client.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.forEach
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.BaseKtListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.getSharedProcessPackages
import me.gm.cleaner.client.getSharedUserIdPackages
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.databinding.ApplistItemBinding
import me.gm.cleaner.util.buildStyledTitle

class AppListAdapter(private val fragment: AppListFragment) :
    BaseKtListAdapter<AppListModel, AppListAdapter.ViewHolder>(CALLBACK) {
    private val activity: ServiceSettingsActivity =
        fragment.requireActivity() as ServiceSettingsActivity

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ApplistItemBinding.inflate(LayoutInflater.from(parent.context))
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val model = getItem(position)
        Log.i("MC/Test", "onBindViewHolder: position=$position, package=${model.packageInfo.packageName}")
        binding.icon.setImageDrawable(model.packageInfo.applicationInfo.loadIcon(fragment.requireContext().packageManager))
        binding.title.text = model.label
        binding.summary.text = run {
            val summary = mutableListOf<CharSequence>()
            if (model.mountRulesCount > 0) {
                summary += fragment.getString(
                    R.string.enabled_mount_rules_count, model.mountRulesCount
                )
            }
            if (model.readOnlyCount > 0) {
                summary += fragment.getString(R.string.enabled_read_only_count, model.readOnlyCount)
            }
            if (summary.isNotEmpty()) {
                activity.buildStyledTitle(
                    fragment.getString(
                        R.string.enabled,
                        summary.joinToString(fragment.getString(R.string.delimiter))
                    )
                )
            } else {
                model.packageInfo.packageName
            }
        }
        binding.status.text = when (model.mountState) {
            AppListModel.STATE_UNMOUNTED -> null
            AppListModel.STATE_MOUNTED -> activity.buildStyledTitle(
                fragment.getString(R.string.storage_redirect_list_mounted)
            )

            AppListModel.STATE_UNKNOWN -> activity.buildStyledTitle(
                fragment.getString(R.string.storage_redirect_list_unknown)
            )

            AppListModel.STATE_MOUNT_EXCEPTION -> activity.buildStyledTitle(
                fragment.getString(R.string.storage_redirect_list_mount_exception),
                color = activity.getColor(R.color.color_warning)
            )

            else -> throw IndexOutOfBoundsException()
        }
        binding.root.setOnClickListener {
            val navController = fragment.findNavController()
            if (navController.currentDestination?.id == R.id.service_settings_fragment) {
                val direction = ServiceSettingsFragmentDirections
                    .serviceSettingsToStorageRedirectAction(model.packageInfo)
                navController.navigate(direction)
            }
        }
        binding.root.setOnLongClickListener { view ->
            Log.i("MC/Test", "onLongClick: position=$position, package=${model.packageInfo.packageName}, mountRules=${model.mountRulesCount}, readOnly=${model.readOnlyCount}")
            val popupMenu = PopupMenu(activity, view)
            popupMenu.menuInflater.inflate(R.menu.applist_item, popupMenu.menu)
            if (model.mountRulesCount == 0) {
                popupMenu.menu.removeItem(R.id.menu_delete_all_mount_rules)
            }
            if (model.readOnlyCount == 0) {
                popupMenu.menu.removeItem(R.id.menu_delete_all_read_only)
            }
            popupMenu.menu.forEach { menuItem ->
                menuItem.setOnMenuItemClickListener { item ->
                    onContextItemSelected(item, model)
                }
            }
            popupMenu.show()
            true
        }
    }

    private fun onContextItemSelected(item: MenuItem, model: AppListModel): Boolean {
        Log.i("MC/Test", "onContextItemSelected: itemId=${item.itemId}, package=${model.packageInfo.packageName}")
        return when (item.itemId) {
            R.id.menu_delete_all_mount_rules -> {
                Log.i("MC/Test", "Executing: delete all mount rules for ${model.packageInfo.packageName}")
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val sharedProcessPackages = getSharedProcessPackages(model.packageInfo)
                        .map { it.packageName }
                    ServicePreferences.removeStorageRedirect(sharedProcessPackages)
                    CleanerClient.service?.notifySrChanged()
                    if (model.mountState != AppListModel.STATE_UNMOUNTED) {
                        CleanerClient.service?.remount(sharedProcessPackages.toTypedArray())
                    }
                }
                true
            }

            R.id.menu_delete_all_read_only -> {
                Log.i("MC/Test", "Executing: delete all read only for ${model.packageInfo.packageName}")
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val sharedUserIdPackages = getSharedUserIdPackages(model.packageInfo)
                        .map { it.packageName }
                    ServicePreferences.removeReadOnly(sharedUserIdPackages)
                    CleanerClient.service?.notifyReadOnlyChanged()
                }
                true
            }

            else -> false
        }
    }

    class ViewHolder(val binding: ApplistItemBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val CALLBACK = object : DiffUtil.ItemCallback<AppListModel>() {
            override fun areItemsTheSame(
                oldItem: AppListModel, newItem: AppListModel
            ): Boolean = oldItem.packageInfo.packageName == newItem.packageInfo.packageName

            override fun areContentsTheSame(
                oldItem: AppListModel, newItem: AppListModel
            ): Boolean = oldItem == newItem
        }
    }
}
