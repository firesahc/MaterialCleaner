package me.gm.cleaner.client.ui

import android.app.Dialog
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.gm.cleaner.R
import me.gm.cleaner.databinding.AppPickerDialogBinding
import me.gm.cleaner.util.overScrollIfContentScrollsPersistent
import me.gm.cleaner.util.submitListKeepPosition
import java.util.function.Consumer
import java.util.function.Supplier

class AppPickerDialog : AppCompatDialogFragment() {
    private val viewModel: AppPickerViewModel by viewModels()
    private val pendingPreActions: MutableList<Runnable> = mutableListOf()
    private val pendingPostActions: MutableList<Runnable> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            viewModel.init(pendingPreActions, pendingPostActions)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = AppPickerDialogBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_hide_pick_apps)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.onPositiveButtonClickListeners.forEach { listener ->
                    listener.accept(viewModel.checkedApps)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val adapter = AppPickerAdapter(viewModel)
        val list = binding.listContainer.recyclerView
        list.adapter = adapter
        list.layoutManager = GridLayoutManager(requireContext(), 1)
        list.overScrollIfContentScrollsPersistent()

        binding.menuSelectAll.setOnClickListener {
            viewModel.checkedApps += viewModel.showingApps
                .map { it.packageInfo }
        }
        binding.menuInvertSelection.setOnClickListener {
            val partition = viewModel.showingApps.partition { it.isChecked }
            viewModel.checkedApps = viewModel.checkedApps -
                    partition.first.map { it.packageInfo }.toSet() +
                    partition.second.map { it.packageInfo }
        }
        binding.menuUnselectAll.setOnClickListener {
            viewModel.checkedApps -= viewModel.showingApps
                .map { it.packageInfo }
        }

        binding.filterEdit.doAfterTextChanged { viewModel.filterText = it.toString() }

        viewModel.appsFlow.asLiveData().observe(this) { apps ->
            adapter.submitListKeepPosition(list, apps) {
                if (apps.isNotEmpty()) {
                    binding.progress.hide()
                }
            }
        }
        return dialog
    }

    private fun handlePreAction(action: Runnable) {
        if (!isAdded) {
            pendingPreActions += action
        } else {
            action.run()
        }
    }

    private fun handlePostAction(action: Runnable) {
        if (!isAdded) {
            pendingPostActions += action
        } else {
            action.run()
        }
    }

    /** The supplied listener is called when the user confirms a valid selection.  */
    fun addOnPositiveButtonClickListener(onPositiveButtonClickListener: Consumer<Set<PackageInfo>>) =
        handlePostAction {
            viewModel.onPositiveButtonClickListeners.add(onPositiveButtonClickListener)
        }

    /**
     * Removes a listener previously added via [AppPickerDialog.addOnPositiveButtonClickListener].
     */
    fun removeOnPositiveButtonClickListener(onPositiveButtonClickListener: Consumer<Set<PackageInfo>>) =
        handlePostAction {
            viewModel.onPositiveButtonClickListeners.remove(onPositiveButtonClickListener)
        }

    /**
     * Removes all listeners added via [AppPickerDialog.addOnPositiveButtonClickListener].
     */
    fun clearOnPositiveButtonClickListeners() = handlePostAction {
        viewModel.onPositiveButtonClickListeners.clear()
    }

    fun setAllAppsSupplier(allAppsSupplier: Supplier<List<PackageInfo>>) = handlePreAction {
        viewModel.allAppsSupplier = allAppsSupplier
    }

    fun setSelectionApps(checkedApps: Set<PackageInfo>) = handlePostAction {
        viewModel.checkedApps = checkedApps
    }

    fun setSelection(checkedApps: Set<String>) = handlePostAction {
        viewModel.checkedApps = checkedApps.asSequence()
            .map { packageName ->
                viewModel.allApps.firstOrNull { packageName == it.packageName }
                    ?: PackageInfo().also { it.packageName = packageName }
            }
            .toSet()
    }
}
