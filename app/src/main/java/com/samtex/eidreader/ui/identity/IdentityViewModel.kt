package com.samtex.eidreader.ui.identity

import androidx.lifecycle.ViewModel
import com.samtex.eidreader.data.scan.IdentityUiState
import com.samtex.eidreader.data.scan.ScanController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class IdentityViewModel @Inject constructor(private val controller: ScanController) : ViewModel() {
    val state: StateFlow<IdentityUiState> = controller.identity

    fun reveal(show: Boolean) = controller.revealIdentity(show)
    fun revealPortrait(show: Boolean) = controller.revealPortrait(show)
    fun cancel() = controller.cancelIdentityRead()
    fun endSession() = controller.endIdentitySession()
}
