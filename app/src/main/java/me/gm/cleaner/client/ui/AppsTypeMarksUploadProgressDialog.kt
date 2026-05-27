package me.gm.cleaner.client.ui

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.gm.cleaner.databinding.ProgressDialogBinding
import me.gm.cleaner.util.getSerializableCompat

class AppsTypeMarksUploadProgressDialog : AppCompatDialogFragment() {
    private val viewModel: AppsTypeMarksUploadProgressViewModel by viewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = ProgressDialogBinding.inflate(LayoutInflater.from(requireContext()))
        binding.progress.isIndeterminate = false
        binding.button.setText(android.R.string.cancel)
        binding.button.setOnClickListener {
            dismiss()
        }

        viewModel.progressLiveData.observe(this) { state ->
            when (state) {
                is AppsTypeMarksUploadState.Uploading -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        binding.progress.setProgress(state.progress, true)
                    } else {
                        binding.progress.progress = state.progress
                    }
                    binding.text.text = state.packageName
                }

                is AppsTypeMarksUploadState.Done -> dismiss()
            }
        }
        if (savedInstanceState == null) {
            val appsTypeMarks = requireArguments()
                .getSerializableCompat<ArrayList<Pair<String, String>>>(KEY_VALUES)!!
            viewModel.uploadAppsTypeMarks(appsTypeMarks)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()
    }

    companion object {
        private const val KEY_VALUES: String = "me.gm.cleaner.key.values"

        fun newInstance(appsTypeMarks: ArrayList<Pair<String, String>>): AppsTypeMarksUploadProgressDialog =
            AppsTypeMarksUploadProgressDialog().apply {
                arguments = bundleOf(KEY_VALUES to appsTypeMarks)
            }
    }
}
