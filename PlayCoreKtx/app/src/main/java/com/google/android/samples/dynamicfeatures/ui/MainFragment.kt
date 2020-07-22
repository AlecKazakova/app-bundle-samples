/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.samples.dynamicfeatures.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.AppUpdateResult.Available
import com.google.android.play.core.ktx.AppUpdateResult.Downloaded
import com.google.android.play.core.ktx.AppUpdateResult.InProgress
import com.google.android.play.core.ktx.AppUpdateResult.NotAvailable
import com.google.android.play.core.ktx.bytesDownloaded
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.startConfirmationDialogForResult
import com.google.android.play.core.ktx.totalBytesToDownload
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.samples.dynamicfeatures.R
import com.google.android.samples.dynamicfeatures.databinding.FragmentMainBinding
import com.google.android.samples.dynamicfeatures.state.ColorSource
import com.google.android.samples.dynamicfeatures.state.EventObserver
import com.google.android.samples.dynamicfeatures.state.INSTALL_CONFIRMATION_REQ_CODE
import com.google.android.samples.dynamicfeatures.state.InstallError
import com.google.android.samples.dynamicfeatures.state.InstallViewModel
import com.google.android.samples.dynamicfeatures.state.InstallViewModelProviderFactory
import com.google.android.samples.dynamicfeatures.state.ModuleStatus
import com.google.android.samples.dynamicfeatures.state.ReviewViewModel
import com.google.android.samples.dynamicfeatures.state.ReviewViewModelProviderFactory
import com.google.android.samples.dynamicfeatures.state.UPDATE_CONFIRMATION_REQ_CODE
import com.google.android.samples.dynamicfeatures.state.UpdateViewModel
import com.google.android.samples.dynamicfeatures.state.UpdateViewModelProviderFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainFragment : Fragment(R.layout.fragment_main) {

    private val splitInstallManager by lazy { SplitInstallManagerFactory.create(requireContext()) }
    private val installViewModel by viewModels<InstallViewModel> {
        InstallViewModelProviderFactory(splitInstallManager)
    }

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(requireContext()) }
    private val updateViewModel by viewModels<UpdateViewModel> {
        UpdateViewModelProviderFactory(appUpdateManager)
    }

    private val reviewManager by lazy { ReviewManagerFactory.create(requireContext()) }
    private val reviewViewModel by viewModels<ReviewViewModel> {
        ReviewViewModelProviderFactory(reviewManager)
    }

    private lateinit var bindings: FragmentMainBinding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindings = FragmentMainBinding.bind(view)
        with(bindings) {
            btnInvokeRandom.setOnClickListener { installViewModel.invokeRandomColor() }
            btnInvokePalette.setOnClickListener { installViewModel.invokePictureSelection() }
            btnUpdate.setOnClickListener {
                updateViewModel.invokeUpdate(this@MainFragment)
            }
        }

        addInstallViewModelObservers()
        addUpdateViewModelObservers()
        addReviewViewModelObservers()
    }

    private fun addInstallViewModelObservers() {
        with(installViewModel) {
            toastMessage.observe(viewLifecycleOwner, EventObserver(::toastAndLog))
            errorMessage.observe(
                    viewLifecycleOwner, EventObserver(::processInstallError)
            )

            pictureModuleStatus.observe(viewLifecycleOwner, Observer { status ->
                updateModuleButton(bindings.btnInvokePalette, status)
            })

            randomColorModuleStatus.observe(viewLifecycleOwner, Observer { status ->
                updateModuleButton(bindings.btnInvokePalette, status)
            })

            userConfirmationRequired.observe(viewLifecycleOwner, Observer { status ->
                status.getContentIfNotHandled()?.let {
                    splitInstallManager.startConfirmationDialogForResult(
                            it.state,
                            this@MainFragment,
                            INSTALL_CONFIRMATION_REQ_CODE
                    )
                }
            })

            destinationClass.observe(viewLifecycleOwner, Observer { status ->
                status.getContentIfNotHandled()?.let {
                    startActivity(Intent().setClassName(it.destPackage, it.activity))
                }
            })
        }
    }

    private fun addUpdateViewModelObservers() {
        with(updateViewModel) {
            updateStatus.observe(
                    viewLifecycleOwner, Observer { updateResult: AppUpdateResult ->
                updateUpdateButton(updateResult)
            })

            toastMessage.observe(viewLifecycleOwner, EventObserver(::toastAndLog))
        }
    }

    private fun addReviewViewModelObservers() {
        reviewViewModel.reviewInfo.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                reviewManager.launchReview(requireActivity(), it)
            }
        }
    }

    private fun updateModuleButton(target: View, status: ModuleStatus) {
        target.isEnabled = status !is ModuleStatus.Unavailable
        with(bindings.moduleState) {
            when (status) {
                ModuleStatus.Available -> {
                    text = getString(R.string.install)
                }
                is ModuleStatus.Installing -> {
                    text = getString(
                            R.string.installing,
                            (status.progress * 100).toInt()
                    )
                }
                ModuleStatus.Unavailable -> {
                    text = getString(R.string.feature_not_available)
                }
                ModuleStatus.Installed -> {
                    text = getString(R.string.start)
                }
                is ModuleStatus.NeedsConfirmation -> {
                    splitInstallManager.startConfirmationDialogForResult(
                            status.state,
                            requireActivity(),
                            INSTALL_CONFIRMATION_REQ_CODE
                    )
                }
            }
        }
    }

    private fun updateUpdateButton(updateResult: AppUpdateResult) {
        when (updateResult) {
            NotAvailable -> bindings.btnUpdate.visibility = View.GONE
            is Available -> {
                with(bindings.btnUpdate) {
                    visibility = View.VISIBLE
                    isEnabled = true
                    text = context.getString(R.string.start_update)
                }
            }
            is InProgress -> {
                with(bindings.btnUpdate) {
                    visibility = View.VISIBLE
                    isEnabled = false
                    val updateProgress =
                            updateResult.installState.bytesDownloaded * 100 /
                                    updateResult.installState.totalBytesToDownload
                    text = context.getString(R.string.downloading_update, updateProgress)
                }
            }
            is Downloaded -> {
                with(bindings.btnUpdate) {
                    visibility = View.VISIBLE
                    isEnabled = true
                    text = context.getString(R.string.press_to_complete_update)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        with(ColorSource) {
            view?.setBackgroundColor(backgroundColor)
            bindings.instructions.setTextColor(textColor)
            // Once the color has been set a review can be requested.
            val reviewInfo = reviewViewModel.reviewInfo.value
            if (reviewInfo != null) {
                lifecycleScope.launch {
                    reviewManager.launchReview(requireActivity(), reviewInfo)
                }
            }
        }
    }

    /** This is needed to handle the result of the manager.startConfirmationDialogForResult
    request that can be made from SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION
    in the listener above. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // TODO might not be needed at all if we just get updates from Flow/LiveData???
        if (requestCode == INSTALL_CONFIRMATION_REQ_CODE) {
            // Handle the user's decision. For example, if the user selects "Cancel",
            // you may want to disable certain functionality that depends on the module.
            if (resultCode == Activity.RESULT_CANCELED) {
//                toastAndLog(getString(R.string.user_cancelled))
            }
        } else if (requestCode == UPDATE_CONFIRMATION_REQ_CODE) {
            // TODO Handle flexible updates by updating the UI
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}

fun MainFragment.toastAndLog(text: String) {
    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    Log.d(TAG, text)
}

fun MainFragment.processInstallError(installError: InstallError) {
    toastAndLog(
            getString(R.string.error_for_module, installError.errorCode, installError.moduleNames)
    )
}

private const val TAG = "DynamicFeatures"
