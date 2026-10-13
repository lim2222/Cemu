package info.cemu.cemu.emulation

import android.view.SurfaceHolder
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.cemu.cemu.common.either.Either
import info.cemu.cemu.common.either.Error
import info.cemu.cemu.common.either.Success
import info.cemu.cemu.common.either.attemptWithContext
import info.cemu.cemu.common.either.bind
import info.cemu.cemu.common.either.mapError
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.EmulationSettings
import info.cemu.cemu.common.settings.InputOverlayRect
import info.cemu.cemu.common.settings.InputOverlaySettings
import info.cemu.cemu.common.settings.OverlayInputConfig
import info.cemu.cemu.common.settings.PerGameDisplaySettings
import info.cemu.cemu.common.settings.ScreenOrientationMode
import info.cemu.cemu.common.settings.ScreensLayout
import info.cemu.cemu.common.settings.inputOverlayAlphaKey
import info.cemu.cemu.common.settings.inputOverlayKey
import info.cemu.cemu.common.settings.inputOverlayOrientationKey
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeEmulation.PrepareTitleResult
import info.cemu.cemu.nativeinterface.NativeException
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SideMenuState(
    val isMotionEnabled: Boolean = false,
    val isTVReplacedWithPad: Boolean = false,
    val isPadOnExternalDisplay: Boolean = false,
    val areScreensSwapped: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
    val isInputOverlayVisible: Boolean = false,
)

class ConditionFlags(
    var isMainConditionMet: Boolean = false, var isPadConditionMet: Boolean = false
) {
    fun get(isMain: Boolean): Boolean {
        return if (isMain) isMainConditionMet else isPadConditionMet
    }

    fun set(isMain: Boolean, value: Boolean) {
        if (isMain) {
            isMainConditionMet = value
        } else {
            isPadConditionMet = value
        }
    }
}

sealed interface NativeError {
    data class SurfaceCreationError(val message: String) : NativeError
    data class RendererInitializationError(val message: String) : NativeError

    object GameFilesNotFoundError : NativeError
    object NoDiscKeysError : NativeError
    object NoTitleTikError : NativeError
    data class UnknownTilePrepareError(val launchPath: String) : NativeError
    data class SystemInitializationError(val message: String) : NativeError

    object LaunchingTitleError : NativeError
}

data class SurfaceDimensions(val width: Int = 1, val height: Int = 1)

class EmulationViewModel(
    private val launchPath: String,
    private val titleId: Long?,
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore
) : ViewModel() {
    private val _emulationError = MutableStateFlow<NativeError?>(null)
    val emulationError = _emulationError.asStateFlow()

    private val _sideMenuState = MutableStateFlow(SideMenuState())
    val sideMenuState = _sideMenuState.asStateFlow()

    // The emulated controller type that is currently applied to the overlay's controller. It can
    // be changed from the in-game side menu, which switches the controller without restarting the
    // title, and the choice is remembered for this title.
    private val _controllerType =
        MutableStateFlow(NativeInput.EmulatedControllerType.DISABLED)
    val controllerType = _controllerType.asStateFlow()

    private var overlayControllerIndex = 0

    private val _mainSurfaceDimensions = MutableStateFlow(SurfaceDimensions())
    val mainSurfaceDimensions = _mainSurfaceDimensions.asStateFlow()

    private val _padSurfaceDimensions = MutableStateFlow(SurfaceDimensions())
    val padSurfaceDimensions = _padSurfaceDimensions.asStateFlow()

    val isInputOverlayVisible =
        sideMenuState.map { it.isInputOverlayVisible }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                false,
            )

    init {
        viewModelScope.launch {
            val settings = dataStore.data.first()
            overlayControllerIndex = settings.inputOverlaySettings.controllerIndex
            _controllerType.value = NativeInput.getControllerType(overlayControllerIndex)
            _sideMenuState.update {
                it.copy(
                    isPadOnExternalDisplay = settings.emulationSettings.isPadOnExternalDisplay,
                    isExternalScreenRotatedLeft = settings.emulationSettings.isExternalScreenRotatedLeft,
                    isInputOverlayVisible = settings.inputOverlaySettings.isOverlayEnabled,
                )
            }
        }
    }

    // Raw settings. The per game/controller type/orientation layout is resolved by the caller
    // with resolvedForGame(launchPath, inputOverlayKey(...), inputOverlayOrientationKey(...)).
    val inputOverlaySettings = dataStore.data
        .map { it.inputOverlaySettings }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            InputOverlaySettings(),
        )

    fun saveInputOverlayRectangles(
        controllerType: Int,
        isPortrait: Boolean,
        inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect>,
    ) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings = it.inputOverlaySettings
                val key = inputOverlayOrientationKey(launchPath, controllerType, isPortrait)
                val gameRectMap =
                    (overlaySettings.inputOverlayRectMapByGame[key] ?: emptyMap()) +
                        inputOverlayRectMap

                it.copy(
                    inputOverlaySettings = overlaySettings.copy(
                        inputOverlayRectMapByGame =
                            overlaySettings.inputOverlayRectMapByGame + (key to gameRectMap),
                    )
                )
            }
        }
    }

    fun setInputOverlayVisibility(
        controllerType: Int,
        isPortrait: Boolean,
        input: OverlayInputConfig,
        visible: Boolean,
    ) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings = it.inputOverlaySettings
                val baseKey = inputOverlayKey(launchPath, controllerType)
                val key = inputOverlayOrientationKey(launchPath, controllerType, isPortrait)
                val gameVisibilityMap =
                    (overlaySettings.inputVisibilityMapByGame[key]
                        ?: overlaySettings.inputVisibilityMapByGame[baseKey]
                        ?: overlaySettings.inputVisibilityMap) + (input to visible)

                it.copy(
                    inputOverlaySettings = overlaySettings.copy(
                        inputVisibilityMapByGame =
                            overlaySettings.inputVisibilityMapByGame + (key to gameVisibilityMap),
                    )
                )
            }
        }
    }

    fun resetInputOverlayLayout(controllerType: Int, isPortrait: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings = it.inputOverlaySettings
                // Only the orientation the user is currently looking at is reset. Buttons can be
                // pushed off screen in one orientation, so resetting to bring them back must not
                // destroy what was already prepared for the other orientation.
                val key = inputOverlayOrientationKey(launchPath, controllerType, isPortrait)

                it.copy(
                    inputOverlaySettings = overlaySettings.copy(
                        inputOverlayRectMapByGame = overlaySettings.inputOverlayRectMapByGame +
                            (key to emptyMap<OverlayInputConfig, InputOverlayRect>()),
                        inputVisibilityMapByGame = overlaySettings.inputVisibilityMapByGame +
                            (key to emptyMap<OverlayInputConfig, Boolean>()),
                    )
                )
            }
        }
    }

    fun setControllerType(controllerIndex: Int, type: Int) {
        if (_controllerType.value == type) {
            return
        }

        // Switch the emulated controller of the running title without restarting it.
        NativeInput.setControllerType(controllerIndex, type)
        _controllerType.value = type
        overlayControllerIndex = controllerIndex

        // Remember the choice for this title so it is applied again on the next launch.
        val id = titleId ?: return
        if (id >= 0) {
            NativeGameTitles.setControllerTypeForTitle(id, controllerIndex, type)
        }
    }

    fun setInputOverlayVibrateOnTouch(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings = it.inputOverlaySettings

                it.copy(
                    inputOverlaySettings = overlaySettings.copy(
                        inputOverlayVibrateByGame =
                            overlaySettings.inputOverlayVibrateByGame + (launchPath to enabled),
                    )
                )
            }
        }
    }

    fun setInputOverlayAlpha(alpha: Int, isPortrait: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings = it.inputOverlaySettings
                val key = inputOverlayAlphaKey(launchPath, isPortrait)

                it.copy(
                    inputOverlaySettings = overlaySettings.copy(
                        inputOverlayAlphaByGame =
                            overlaySettings.inputOverlayAlphaByGame + (key to alpha),
                    )
                )
            }
        }
    }

    fun updateSideMenuState(sideMenuState: SideMenuState) {
        val oldState = _sideMenuState.value
        _sideMenuState.value = sideMenuState

        if (oldState.isPadOnExternalDisplay != sideMenuState.isPadOnExternalDisplay ||
            oldState.isExternalScreenRotatedLeft != sideMenuState.isExternalScreenRotatedLeft
        ) {
            viewModelScope.launch {
                dataStore.updateData {
                    it.copy(
                        emulationSettings = it.emulationSettings.copy(
                            isPadOnExternalDisplay = sideMenuState.isPadOnExternalDisplay,
                            isExternalScreenRotatedLeft = sideMenuState.isExternalScreenRotatedLeft,
                        )
                    )
                }
            }
        }

        if (oldState.isInputOverlayVisible != sideMenuState.isInputOverlayVisible) {
            viewModelScope.launch {
                dataStore.updateData {
                    it.copy(
                        inputOverlaySettings = it.inputOverlaySettings.copy(
                            isOverlayEnabled = sideMenuState.isInputOverlayVisible,
                        )
                    )
                }
            }
        }
    }

    // The GamePad screen is shown based on the current orientation and remembered per game, like
    // the TV/GamePad layout. Missing per-game values fall back to the global default.
    fun setPadVisible(isPortrait: Boolean, visible: Boolean) {
        updatePerGameDisplaySettings {
            if (isPortrait) it.copy(isPadVisiblePortrait = visible)
            else it.copy(isPadVisibleLandscape = visible)
        }
    }

    // The per-game display overrides (orientation and TV/GamePad layout). Missing values fall
    // back to the global defaults which are resolved by the caller.
    val perGameDisplaySettings = dataStore.data.map { it.displaySettingsByGame[launchPath] }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null,
        )

    // The global display defaults, only used as a fallback for values that have no per-game
    // override yet.
    val globalDisplaySettings = dataStore.data.map { it.emulationSettings }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            EmulationSettings(),
        )

    fun setScreenOrientation(mode: ScreenOrientationMode) {
        updatePerGameDisplaySettings { it.copy(screenOrientation = mode) }
    }

    fun setScreensLayoutPortrait(layout: ScreensLayout) {
        updatePerGameDisplaySettings { it.copy(screensLayoutPortrait = layout) }
    }

    fun setScreensLayoutLandscape(layout: ScreensLayout) {
        updatePerGameDisplaySettings { it.copy(screensLayoutLandscape = layout) }
    }

    fun setScreensGap(gap: Int) {
        updatePerGameDisplaySettings { it.copy(screensGap = gap) }
    }

    private fun updatePerGameDisplaySettings(
        transform: (PerGameDisplaySettings) -> PerGameDisplaySettings,
    ) {
        viewModelScope.launch {
            dataStore.updateData {
                val current = it.displaySettingsByGame[launchPath] ?: PerGameDisplaySettings()

                it.copy(
                    displaySettingsByGame =
                        it.displaySettingsByGame + (launchPath to transform(current)),
                )
            }
        }
    }

    val destroyedSurfaces = ConditionFlags()
    var setSurfaces = ConditionFlags()

    private fun updateSurfaceDimensions(isMainCanvas: Boolean, width: Int, height: Int) {
        val newDimensions = SurfaceDimensions(
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
        )
        if (isMainCanvas) {
            _mainSurfaceDimensions.value = newDimensions
        } else {
            _padSurfaceDimensions.value = newDimensions
        }
    }

    private inner class CanvasSurfaceHolderCallback(val isMainCanvas: Boolean) :
        SurfaceHolder.Callback {

        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {}

        override fun surfaceChanged(
            surfaceHolder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            try {
                NativeEmulation.setSurfaceSize(width, height, isMainCanvas)
                updateSurfaceDimensions(isMainCanvas, width, height)

                if (setSurfaces.get(isMainCanvas)) {
                    return
                }

                NativeEmulation.setSurface(surfaceHolder.surface, isMainCanvas)
                val mainSurfaceWasDestroyed = destroyedSurfaces.get(isMain = true)

                if (mainSurfaceWasDestroyed && isMainCanvas) {
                    NativeEmulation.resumeTitle()
                }

                setSurfaces.set(isMainCanvas, true)

                val padSurfaceWasSet = setSurfaces.get(isMain = false)
                if ((!isMainCanvas && !mainSurfaceWasDestroyed) || (isMainCanvas && padSurfaceWasSet)) {
                    NativeEmulation.initializeSurface(isMainCanvas = false)
                }

                destroyedSurfaces.set(isMainCanvas, false)
            } catch (exception: NativeException) {
                _emulationError.value = NativeError.SurfaceCreationError(exception.message!!)
            }
        }

        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            if (setSurfaces.get(isMain = false)) {
                NativeEmulation.clearPadSurface()
                setSurfaces.set(isMain = false, false)
                destroyedSurfaces.set(isMain = false, true)
            }

            if (isMainCanvas) {
                NativeEmulation.pauseTitle()

                setSurfaces.set(isMain = true, false)
                destroyedSurfaces.set(isMain = true, true)
            }
        }
    }

    val mainHolderCallback: SurfaceHolder.Callback = CanvasSurfaceHolderCallback(true)
    val padHolderCallback: SurfaceHolder.Callback = CanvasSurfaceHolderCallback(false)

    private suspend fun initializeSystems() = attemptWithContext(Dispatchers.IO) {
        NativeEmulation.initializeSystems()
    }.mapError { NativeError.SystemInitializationError(it) }

    private suspend fun initializeRenderer() = attemptWithContext(Dispatchers.IO) {
        NativeEmulation.initializeRenderer()
        NativeEmulation.initializeSurface(isMainCanvas = true)
    }.mapError { NativeError.RendererInitializationError(it) }

    private suspend fun prepareTitle(): Either<Unit, NativeError> =
        attemptWithContext(Dispatchers.IO) { NativeEmulation.prepareTitle(launchPath) }
            .fold(
                onSuccess = { result ->
                    when (result) {
                        PrepareTitleResult.SUCCESSFUL -> Success(Unit)
                        PrepareTitleResult.ERROR_GAME_BASE_FILES_NOT_FOUND -> Error(NativeError.GameFilesNotFoundError)
                        PrepareTitleResult.ERROR_NO_DISC_KEY -> Error(NativeError.NoDiscKeysError)
                        PrepareTitleResult.ERROR_NO_TITLE_TIK -> Error(NativeError.NoTitleTikError)
                        else -> Error(NativeError.UnknownTilePrepareError(launchPath))
                    }
                },
                onError = { Error(NativeError.UnknownTilePrepareError(launchPath)) }
            )

    private suspend fun launchTitle() =
        attemptWithContext(Dispatchers.IO) { NativeEmulation.launchTitle() }
            .mapError { NativeError.LaunchingTitleError }

    private val _isEmulationInitialized = MutableStateFlow(false)
    val isEmulationInitialized = _isEmulationInitialized.asStateFlow()
    private var emulationInitializationJob: Job? = null
    fun initializeEmulation() {
        if (_isEmulationInitialized.value || emulationInitializationJob != null) {
            return
        }

        emulationInitializationJob = viewModelScope.launch {
            prepareTitle()
                .bind { initializeSystems() }
                .bind { initializeRenderer() }
                .bind { launchTitle() }
                .onError { _emulationError.value = it }

            _isEmulationInitialized.value = true
            // The per-game controller override is applied while the title is launching, so read
            // the effective type again once emulation is up.
            _controllerType.value = NativeInput.getControllerType(overlayControllerIndex)
        }
    }

    companion object {
        val LAUNCH_PATH_KEY = object : CreationExtras.Key<String> {}
        val TITLE_ID_KEY = object : CreationExtras.Key<Long> {}
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EmulationViewModel(
                    this[LAUNCH_PATH_KEY] as String,
                    this[TITLE_ID_KEY],
                )
            }
        }
    }
}
