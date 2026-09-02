package me.gm.cleaner.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.gm.cleaner.R
import me.gm.cleaner.app.BaseFragment
import me.gm.cleaner.app.ConfirmationDialog
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.databinding.MountAppPickerFragmentBinding
import me.gm.cleaner.util.buildStyledTitle
import me.gm.cleaner.util.colorAccent
import me.gm.cleaner.util.fitsSystemWindowInsets
import me.gm.cleaner.util.fixEdgeEffect
import me.gm.cleaner.util.overScrollIfContentScrollsPersistent
import me.gm.cleaner.util.submitListKeepPosition
import me.gm.cleaner.widget.ThemedTabBorderSwipeRefreshLayout

class MountAppPickerFragment : BaseFragment() {
    private val viewModel: MountAppPickerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConfiguredPolicyStoreProvider.instance.snapshots.collect {
                    viewModel.updateAppsRuleCount()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val binding = MountAppPickerFragmentBinding.inflate(inflater)
        setAppBar(binding.root)

        val adapter = AppListAdapter(
            fragment = this,
            navDestinationId = R.id.mount_app_picker_fragment,
            navAction = { model ->
                MountAppPickerFragmentDirections
                    .mountAppPickerToStorageRedirectAction(model.packageInfo)
            }
        ).apply {
            stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        val list = binding.list
        list.adapter = adapter
        list.layoutManager = GridLayoutManager(requireContext(), 1)
        list.setHasFixedSize(true)
        list.fixEdgeEffect()
        list.overScrollIfContentScrollsPersistent()
        list.fitsSystemWindowInsets(savedInstanceState == null)
        binding.listContainer.setOnRefreshListener {
            viewModel.loadApps()
        }
        if (viewModel.isDone) {
            binding.progress.hide()
        }

        viewModel.uninstalledPackagesLiveData.observe(viewLifecycleOwner) { mutableUninstalledPackages ->
            if (mutableUninstalledPackages.isNotEmpty()) {
                val uninstalledPackages = mutableUninstalledPackages.toList()
                (mutableUninstalledPackages as MutableList).clear()
                ConfirmationDialog
                    .newInstance(
                        resources.getQuantityString(
                            R.plurals.uninstalled_package, uninstalledPackages.size,
                            uninstalledPackages.joinToString(getString(R.string.delimiter))
                        )
                    )
                    .apply {
                        addOnPositiveButtonClickListener {
                            ServicePreferences.removeStorageRedirect(uninstalledPackages)
                            ServicePreferences.removeReadOnly(uninstalledPackages)
                            val denyList = ServicePreferences.denylist - uninstalledPackages.toSet()
                            ServicePreferences.denylist = denyList
                        }
                    }
                    .show(childFragmentManager, null)
            }
        }
        viewModel.appsFlow.asLiveData().observe(viewLifecycleOwner) { apps ->
            when (apps) {
                is AppListState.Done -> {
                    val hideProgress = Runnable {
                        binding.progress.hide()
                        binding.listContainer.isEnabled = true
                        binding.listContainer.isRefreshing = false
                    }
                    if (binding.listContainer.isRefreshing) {
                        adapter.submitListKeepPosition(list, apps.list, hideProgress)
                    } else {
                        adapter.submitList(apps.list, hideProgress)
                    }
                }

                else -> {}
            }
        }
        ServicePreferences.preferencesChangeLiveData.observe(viewLifecycleOwner) {
            viewModel.updateAppsRuleCount()
        }

        return binding.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.applist_toolbar, menu)
        val menuView = menu.findItem(R.id.menu_view).subMenu!!
        MenuCompat.setGroupDividerEnabled(menuView, true)
        super.onCreateOptionsMenu(menu, inflater)
        when (ServicePreferences.sortBy) {
            ServicePreferences.SORT_BY_NAME ->
                menu.findItem(R.id.menu_sort_by_name).isChecked = true

            ServicePreferences.SORT_BY_UPDATE_TIME ->
                menu.findItem(R.id.menu_sort_by_update_time).isChecked = true
        }
        menu.findItem(R.id.menu_rule_count).isChecked = ServicePreferences.ruleCount
        menu.findItem(R.id.menu_mount_state).isChecked = ServicePreferences.mountState
        menu.findItem(R.id.menu_hide_system_app).isChecked = ServicePreferences.isHideSystemApp
        menu.findItem(R.id.menu_hide_disabled_app).isChecked = ServicePreferences.isHideDisabledApp
        menu.findItem(R.id.menu_hide_no_storage_permissions).isChecked =
            ServicePreferences.isHideNoStoragePermissionApp
        arrayOf(
            menu.findItem(R.id.menu_header_sort), menu.findItem(R.id.menu_header_hide)
        ).forEach {
            it.title = requireContext().buildStyledTitle(
                it.title!!,
                androidx.appcompat.R.attr.textAppearancePopupMenuHeader,
                requireContext().colorAccent
            )
        }

        // SearchView setup — manual, not via BaseServiceSettingsFragment
        val searchItem = menu.findItem(R.id.menu_search)
        if (viewModel.isSearching) {
            searchItem.expandActionView()
        }
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                viewModel.isSearching = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.isSearching = false
                return true
            }
        })
        val searchView = searchItem.actionView as SearchView
        searchView.setQuery(viewModel.queryText, false)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                viewModel.queryText = query
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.queryText = newText
                return false
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_by_name -> {
                item.isChecked = true
                ServicePreferences.sortBy = ServicePreferences.SORT_BY_NAME
            }

            R.id.menu_sort_by_update_time -> {
                item.isChecked = true
                ServicePreferences.sortBy = ServicePreferences.SORT_BY_UPDATE_TIME
            }

            R.id.menu_rule_count -> {
                val ruleCount = !item.isChecked
                item.isChecked = ruleCount
                ServicePreferences.ruleCount = ruleCount
            }

            R.id.menu_mount_state -> {
                val mountState = !item.isChecked
                item.isChecked = mountState
                ServicePreferences.mountState = mountState
            }

            R.id.menu_hide_system_app -> {
                val isHideSystemApp = !item.isChecked
                item.isChecked = isHideSystemApp
                ServicePreferences.isHideSystemApp = isHideSystemApp
            }

            R.id.menu_hide_disabled_app -> {
                val isHideDisabledApp = !item.isChecked
                item.isChecked = isHideDisabledApp
                ServicePreferences.isHideDisabledApp = isHideDisabledApp
            }

            R.id.menu_hide_no_storage_permissions -> {
                val isHideNoStoragePermissionApp = !item.isChecked
                item.isChecked = isHideNoStoragePermissionApp
                ServicePreferences.isHideNoStoragePermissionApp = isHideNoStoragePermissionApp
            }

            R.id.menu_refresh -> {
                requireView().findViewById<ThemedTabBorderSwipeRefreshLayout>(R.id.list_container)
                    .isRefreshing = true
                viewModel.loadApps()
            }

            R.id.menu_diagnostics_archive -> {
                exportDiagnosticsArchiveAndShare(requireContext())
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        val appBarLayout = requireActivity().findViewById<AppBarLayout>(R.id.toolbar_container)
        val list = requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list) ?: return
        appBarLayout.setLiftOnScrollTargetView(list)
    }
}
