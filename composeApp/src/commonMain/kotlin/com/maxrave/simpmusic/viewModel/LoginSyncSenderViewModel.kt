package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncException
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.repository.LoginSyncSenderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android: scans the Desktop's QR, asks the user to trust it, sends the chosen sign-ins. */
class LoginSyncSenderViewModel(
    private val repository: LoginSyncSenderRepository,
) : ViewModel() {
    sealed interface Step {
        data object Loading : Step

        data object NothingSignedIn : Step

        /** [blocked]: denied with "don't ask again", so only the system settings can grant it now. */
        data class NeedsCamera(
            val blocked: Boolean,
        ) : Step

        data object NoCamera : Step

        data object Scanning : Step

        data object Connecting : Step

        data class Trust(
            val desktop: DesktopInfo,
        ) : Step

        data class Choose(
            val desktop: DesktopInfo,
        ) : Step

        data object Sending : Step

        data class Done(
            val desktop: DesktopInfo,
            val services: List<LoginSyncService>,
            val favoritesResult: FavoritesSyncResult? = null,
        ) : Step

        data class Failed(
            val reason: LoginSyncException.Reason,
            val detail: String?,
        ) : Step
    }

    data class UiState(
        val step: Step = Step.Loading,
        val signedIn: List<LoginSyncService> = emptyList(),
        val selected: Set<LoginSyncService> = emptySet(),
        val syncFavorites: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun step(step: Step) = _uiState.update { it.copy(step = step) }

    /** The sheet opened. [hasCamera]: the camera permission is already granted. */
    fun open(hasCamera: Boolean) {
        _uiState.value = UiState()
        viewModelScope.launch {
            val signedIn = repository.signedInServices()
            _uiState.value =
                UiState(
                    step =
                        when {
                            hasCamera -> Step.Scanning
                            else -> Step.NeedsCamera(blocked = false)
                        },
                    signedIn = signedIn,
                    selected = signedIn.toSet(),
                    syncFavorites = true,
                )
        }
    }

    fun onCameraPermission(
        granted: Boolean,
        blocked: Boolean,
    ) = step(if (granted) Step.Scanning else Step.NeedsCamera(blocked))

    fun onCameraUnavailable() = step(Step.NoCamera)

    fun onScanned(text: String) {
        // Any other QR is ignored and the camera keeps looking.
        if (_uiState.value.step != Step.Scanning || !repository.isInvite(text)) return
        step(Step.Connecting)
        viewModelScope.launch {
            step(
                try {
                    Step.Trust(repository.connect(text))
                } catch (e: LoginSyncException) {
                    Step.Failed(e.reason, e.detail)
                },
            )
        }
    }

    fun trust() {
        val trust = _uiState.value.step as? Step.Trust ?: return
        step(Step.Choose(trust.desktop))
    }

    fun toggle(service: LoginSyncService) =
        _uiState.update { it.copy(selected = if (service in it.selected) it.selected - service else it.selected + service) }

    fun toggleFavorites() = _uiState.update { it.copy(syncFavorites = !it.syncFavorites) }

    fun send() {
        val choose = _uiState.value.step as? Step.Choose ?: return
        val services = _uiState.value.selected
        val syncFavs = _uiState.value.syncFavorites
        if (services.isEmpty() && !syncFavs) return
        step(Step.Sending)
        viewModelScope.launch {
            step(
                try {
                    var favResult: FavoritesSyncResult? = null
                    if (syncFavs) {
                        favResult = repository.syncFavorites()
                    }
                    var sentServices: List<LoginSyncService> = emptyList()
                    if (services.isNotEmpty()) {
                        sentServices = repository.send(services)
                    }
                    Step.Done(choose.desktop, sentServices, favResult)
                } catch (e: LoginSyncException) {
                    Step.Failed(e.reason, e.detail)
                },
            )
        }
    }

    fun resyncFavorites() {
        val done = _uiState.value.step as? Step.Done ?: return
        step(Step.Sending)
        viewModelScope.launch {
            step(
                try {
                    val favResult = repository.syncFavorites()
                    Step.Done(done.desktop, done.services, favResult)
                } catch (e: LoginSyncException) {
                    Step.Failed(e.reason, e.detail)
                },
            )
        }
    }

    fun retry(hasCamera: Boolean) {
        repository.disconnect()
        step(if (hasCamera) Step.Scanning else Step.NeedsCamera(blocked = false))
    }

    /** The sheet is gone. A computer still waiting on this phone is put back on its QR. */
    fun close() = repository.disconnect()

    override fun onCleared() = close()
}
