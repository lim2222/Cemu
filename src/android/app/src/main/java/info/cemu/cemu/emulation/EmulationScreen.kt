package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.settings.MAX_SCREENS_GAP
import info.cemu.cemu.common.settings.ScreenOrientationMode
import info.cemu.cemu.common.settings.ScreensLayout
import info.cemu.cemu.common.settings.inputOverlayKey
import info.cemu.cemu.common.settings.OverlayInputConfig
import info.cemu.cemu.common.ui.components.SingleSelection
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.controllerTypeToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.EmulatedUSBDevicesDialog
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurface
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView
import info.cemu.cemu.emulation.inputoverlay.overlayInputConfigsForControllerType
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.DEFAULT
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_POSITION
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_SIZE
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun EmulationScreen(
    gamePath: String,
    titleId: Long?,
    setMotionSensorEnabled: (Boolean) -> Unit,
    setInputListeningEnabled: (Boolean) -> Unit,
    onQuit: () -> Unit,
    viewModel: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory, extras = MutableCreationExtras().apply {
            set(EmulationViewModel.LAUNCH_PATH_KEY, gamePath)
            if (titleId != null) {
                set(EmulationViewModel.TITLE_ID_KEY, titleId)
            }
        }),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuitConfirmationDialog by remember { mutableStateOf(false) }
    var inputOverlayInputMode by rememberSaveable { mutableStateOf(DEFAULT) }
    var showEmulatedUSBDevices by remember { mutableStateOf(false) }
    var showInputVisibilityDialog by remember { mutableStateOf(false) }

    val emulationError by viewModel.emulationError.collectAsState()
    val isEmulationInitialized by viewModel.isEmulationInitialized.collectAsState()
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val isInputOverlayVisible by viewModel.isInputOverlayVisible.collectAsState()
    val rawInputOverlaySettings by viewModel.inputOverlaySettings.collectAsState()
    // The emulated controller type of the overlay's controller. It comes from the view model so
    // it also updates when the type is changed from the side menu while the game is running.
    val inputOverlayControllerType by viewModel.controllerType.collectAsState()
    // The layout is stored per game *and* per controller type, so a game played with e.g. a
    // GamePad and a Pro Controller keeps a separate arrangement for each.
    val inputOverlaySettings = remember(rawInputOverlaySettings, inputOverlayControllerType) {
        rawInputOverlaySettings.resolvedForGame(
            gamePath,
            inputOverlayKey(gamePath, inputOverlayControllerType),
        )
    }

    // Display orientation and the TV/GamePad layout. Both are stored per game with a global
    // fallback so a title can deviate from the global default.
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val perGameDisplaySettings by viewModel.perGameDisplaySettings.collectAsState()
    val globalDisplaySettings by viewModel.globalDisplaySettings.collectAsState()

    val screenOrientationMode =
        perGameDisplaySettings?.screenOrientation ?: globalDisplaySettings.screenOrientation

    val screensLayout = if (isPortrait) {
        perGameDisplaySettings?.screensLayoutPortrait
            ?: globalDisplaySettings.screensLayoutPortrait
    } else {
        perGameDisplaySettings?.screensLayoutLandscape
            ?: globalDisplaySettings.screensLayoutLandscape
    }

    val screensGap = perGameDisplaySettings?.screensGap
        ?: globalDisplaySettings.screensGap

    // A portrait/landscape mode may differ from how the activity was created, which makes the
    // window rotate right after launch. Creating the emulation surface during that rotation makes
    // some titles (and the Vulkan renderer) crash, so emulation is only started once the window
    // has actually reached the requested orientation. Once it started, the surfaces are kept
    // alive through later rotations (they are only resized) so those never recreate the
    // SurfaceView.
    val isOrientationSettled = when (screenOrientationMode) {
        ScreenOrientationMode.PORTRAIT -> isPortrait
        ScreenOrientationMode.LANDSCAPE -> !isPortrait
        ScreenOrientationMode.AUTO -> true
    }
    var hasSurfacesBeenCreated by remember { mutableStateOf(isOrientationSettled) }
    LaunchedEffect(isOrientationSettled) {
        if (isOrientationSettled) {
            hasSurfacesBeenCreated = true
        }
    }

    // The game is stretched into a 4:3 area in portrait and a 16:9 area in landscape.
    val targetAspectRatio = if (isPortrait) 4f / 3f else 16f / 9f

    val activity = LocalContext.current as? Activity
    LaunchedEffect(activity, screenOrientationMode) {
        activity?.requestedOrientation = screenOrientationMode.toRequestedOrientation()
    }

    // The surface is 4:3 in portrait and 16:9 in landscape. In landscape that already matches
    // most titles, so the user's fullscreen scaling setting is kept; in portrait the game has to
    // be stretched (non proportionally) to fill the 4:3 area.
    val storedFullscreenScaling = remember { NativeSettings.getFullscreenScaling() }
    LaunchedEffect(isPortrait) {
        NativeSettings.setFullscreenScaling(
            if (isPortrait) {
                NativeSettings.FullscreenScaling.STRETCH
            } else {
                storedFullscreenScaling
            }
        )
    }

    fun closeDrawer() {
        scope.launch {
            drawerState.close()
        }
    }

    suspend fun toggleMenu() {
        drawerState.apply {
            if (isClosed) {
                open()
            } else {
                close()
            }
        }
    }

    BackHandler {
        if (drawerState.isAnimationRunning) {
            return@BackHandler
        }

        scope.launch {
            toggleMenu()
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        setInputListeningEnabled(drawerState.isClosed)
    }

    LaunchedEffect(sideMenuState.areScreensSwapped) {
        NativeEmulation.setSwapScreens(sideMenuState.areScreensSwapped)
    }

    LaunchedEffect(sideMenuState.isExternalScreenRotatedLeft) {
        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)
    }

    LaunchedEffect(Unit) {
        HotkeyManager.actions.collect { action ->
            when (action) {
                HotkeyAction.QUIT -> showQuitConfirmationDialog = true
                HotkeyAction.TOGGLE_MENU -> toggleMenu()
                HotkeyAction.SHOW_EMULATED_USB_DEVICES_DIALOG -> showEmulatedUSBDevices = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState())
                ) {
                    EmulationSideMenuContent(
                        sideMenuState = sideMenuState,
                        controllerType = inputOverlayControllerType,
                        isEmulationInitialized = isEmulationInitialized,
                        screenOrientationMode = screenOrientationMode,
                        onScreenOrientationChanged = { viewModel.setScreenOrientation(it) },
                        screensLayout = screensLayout,
                        screensLayoutLabel = tr("TV / GamePad layout") + " — " +
                            tr(if (isPortrait) "Portrait" else "Landscape"),
                        onScreensLayoutChanged = {
                            if (isPortrait) viewModel.setScreensLayoutPortrait(it)
                            else viewModel.setScreensLayoutLandscape(it)
                        },
                        screensGap = screensGap,
                        onScreensGapChanged = { viewModel.setScreensGap(it) },
                        onControllerTypeChanged = { controllerType ->
                            viewModel.setControllerType(
                                rawInputOverlaySettings.controllerIndex,
                                controllerType,
                            )
                            closeDrawer()
                        },
                        updateState = {
                            viewModel.updateSideMenuState(it)
                            setMotionSensorEnabled(it.isMotionEnabled)
                            NativeEmulation.setReplaceTVWithPadView(it.isTVReplacedWithPad)
                            closeDrawer()
                        },
                        onEditInputOverlay = {
                            snackbarHostState.showMessage(scope, tr("Edit input positions"))
                            inputOverlayInputMode = EDIT_POSITION
                            closeDrawer()
                        },
                        onResetInputOverlay = {
                            viewModel.resetInputOverlayLayout(inputOverlayControllerType)
                            closeDrawer()
                        },
                        onEditVisibleInputs = {
                            showInputVisibilityDialog = true
                            closeDrawer()
                        },
                        isVibrateOnTouchEnabled = inputOverlaySettings.isVibrateOnTouchEnabled,
                        overlayAlpha = inputOverlaySettings.alpha,
                        onVibrateChanged = { viewModel.setInputOverlayVibrateOnTouch(it) },
                        onAlphaChanged = { viewModel.setInputOverlayAlpha(it) },
                        onQuit = {
                            showQuitConfirmationDialog = true
                            closeDrawer()
                        },
                        onShowEmulatedUSBDevices = {
                            showEmulatedUSBDevices = true
                            closeDrawer()
                        },
                    )
                }
            }
        },
    ) {
        if (hasSurfacesBeenCreated) {
            EmulationSurfaces(
                viewModel = viewModel,
                isEmulationInitialized = isEmulationInitialized,
                screensLayout = screensLayout,
                screensGap = screensGap,
                targetAspectRatio = targetAspectRatio,
            )
        }

        InputOverlaySurface(
            isVisible = isInputOverlayVisible,
            inputOverlaySettings = inputOverlaySettings,
            controllerType = inputOverlayControllerType,
            inputMode = inputOverlayInputMode,
            onEditFinished = { viewModel.saveInputOverlayRectangles(inputOverlayControllerType, it) },
        )

        if (inputOverlayInputMode != DEFAULT) {
            EditInputsLayout(
                inputMode = inputOverlayInputMode,
                onFinishClick = {
                    snackbarHostState.showMessage(scope, tr("Exited input edit mode"))
                    inputOverlayInputMode = DEFAULT
                },
                onMoveClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input positions"))
                    inputOverlayInputMode = EDIT_POSITION
                },
                onResizeClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input size"))
                    inputOverlayInputMode = EDIT_SIZE
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    emulationError?.let {
        EmulationErrorDialog(it, onQuit)
    }

    if (!isEmulationInitialized) {
        EmulationLoadingDialog()
    }

    if (showQuitConfirmationDialog) {
        EmulationQuitConfirmationDialog(
            onQuit = onQuit,
            onDismiss = { showQuitConfirmationDialog = false },
        )
    }

    if (showEmulatedUSBDevices) {
        EmulatedUSBDevicesDialog(
            onDismiss = { showEmulatedUSBDevices = false },
        )
    }

    if (showInputVisibilityDialog) {
        InputOverlayVisibilityDialog(
            controllerType = inputOverlayControllerType,
            inputVisibilityMap = inputOverlaySettings.inputVisibilityMap,
            onToggle = { input, visible ->
                viewModel.setInputOverlayVisibility(inputOverlayControllerType, input, visible)
            },
            onDismiss = { showInputVisibilityDialog = false },
        )
    }

    EmulationTextInputDialog()
}

@Composable
private fun EditInputsLayout(
    inputMode: InputOverlaySurfaceView.InputMode,
    onFinishClick: () -> Unit,
    onMoveClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onFinishClick) { Text(tr("Done")) }

            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(enabled = inputMode != EDIT_POSITION, onClick = onMoveClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_move),
                        contentDescription = tr("Move")
                    )
                }

                FilledIconButton(enabled = inputMode != EDIT_SIZE, onClick = onResizeClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_resize),
                        contentDescription = tr("Resize"),
                    )
                }
            }
        }
    }
}

@Composable
private fun InputOverlayVisibilityDialog(
    controllerType: Int,
    inputVisibilityMap: Map<OverlayInputConfig, Boolean>,
    onToggle: (OverlayInputConfig, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val inputs = overlayInputConfigsForControllerType(controllerType)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Done")) }
        },
        title = { Text(tr("Visible Inputs")) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (input in inputs) {
                    CheckboxItem(
                        label = overlayInputLabel(input, controllerType),
                        checked = inputVisibilityMap[input] ?: true,
                        onCheckedChange = { onToggle(input, it) },
                    )
                }
            }
        },
    )
}

private fun overlayInputLabel(input: OverlayInputConfig, controllerType: Int): String = when (input) {
    OverlayInputConfig.BUTTON_A -> "A"
    OverlayInputConfig.BUTTON_B -> "B"
    OverlayInputConfig.BUTTON_X -> "X"
    OverlayInputConfig.BUTTON_Y -> "Y"
    OverlayInputConfig.BUTTON_ONE -> "1"
    OverlayInputConfig.BUTTON_TWO -> "2"
    OverlayInputConfig.BUTTON_C -> "C"
    OverlayInputConfig.BUTTON_Z -> "Z"
    OverlayInputConfig.BUTTON_HOME -> tr("Home")
    OverlayInputConfig.BUTTON_L -> "L"
    OverlayInputConfig.BUTTON_R -> "R"
    OverlayInputConfig.BUTTON_L_STICK_CLICK -> "L3"
    OverlayInputConfig.BUTTON_R_STICK_CLICK -> "R3"
    OverlayInputConfig.BUTTON_PLUS -> "+"
    OverlayInputConfig.BUTTON_MINUS -> "-"
    OverlayInputConfig.BUTTON_ZL -> "ZL"
    OverlayInputConfig.BUTTON_ZR -> "ZR"
    OverlayInputConfig.BUTTON_BLOW_MIC -> tr("Blow mic")
    OverlayInputConfig.JOYSTICK_LEFT -> tr("Left joystick")
    OverlayInputConfig.JOYSTICK_RIGHT ->
        if (controllerType == NativeInput.EmulatedControllerType.WIIMOTE) tr("Nunchuck stick")
        else tr("Right joystick")
    OverlayInputConfig.DPAD -> tr("D-pad")
    OverlayInputConfig.TAIKO_DON_LEFT -> tr("Taiko drum")
    OverlayInputConfig.TAIKO_DON_RIGHT -> tr("Taiko drum")
    OverlayInputConfig.TAIKO_KA_LEFT -> tr("Taiko drum")
    OverlayInputConfig.TAIKO_KA_RIGHT -> tr("Taiko drum")
}

// Controller types that can be selected from the in-game side menu.
private val SwitchableControllerTypes = listOf(
    NativeInput.EmulatedControllerType.VPAD,
    NativeInput.EmulatedControllerType.PRO,
    NativeInput.EmulatedControllerType.CLASSIC,
    NativeInput.EmulatedControllerType.WIIMOTE,
    NativeInput.EmulatedControllerType.TAIKO,
)

// Mirrors the checks done by the input settings so a switch cannot exceed the number of
// emulated controllers the console supports.
private fun isControllerTypeSwitchAllowed(currentType: Int, newType: Int): Boolean {
    if (newType == NativeInput.EmulatedControllerType.DISABLED) {
        return true
    }

    val counts = NativeInput.getControllersCount()
    if (newType == NativeInput.EmulatedControllerType.VPAD) {
        return currentType == NativeInput.EmulatedControllerType.VPAD ||
            counts.vpadCount < NativeInput.MAX_VPAD_CONTROLLERS
    }

    val isAlreadyWpad = currentType != NativeInput.EmulatedControllerType.VPAD &&
        currentType != NativeInput.EmulatedControllerType.DISABLED

    return isAlreadyWpad || counts.wpadCount < NativeInput.MAX_WPAD_CONTROLLERS
}

private fun screenOrientationModeToString(mode: ScreenOrientationMode): String = when (mode) {
    ScreenOrientationMode.PORTRAIT -> tr("Portrait")
    ScreenOrientationMode.AUTO -> tr("Auto")
    ScreenOrientationMode.LANDSCAPE -> tr("Landscape")
}

private fun screensLayoutToString(layout: ScreensLayout): String = when (layout) {
    ScreensLayout.TOP_DOWN -> tr("Top / down")
    ScreensLayout.SIDE_BY_SIDE -> tr("Side by side")
}

@Composable
private fun EmulationSideMenuContent(
    sideMenuState: SideMenuState,
    controllerType: Int,
    isEmulationInitialized: Boolean,
    screenOrientationMode: ScreenOrientationMode,
    onScreenOrientationChanged: (ScreenOrientationMode) -> Unit,
    screensLayout: ScreensLayout,
    screensLayoutLabel: String,
    onScreensLayoutChanged: (ScreensLayout) -> Unit,
    screensGap: Int,
    onScreensGapChanged: (Int) -> Unit,
    onControllerTypeChanged: (Int) -> Unit,
    updateState: (SideMenuState) -> Unit,
    onShowEmulatedUSBDevices: () -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
    onEditVisibleInputs: () -> Unit,
    isVibrateOnTouchEnabled: Boolean,
    overlayAlpha: Int,
    onVibrateChanged: (Boolean) -> Unit,
    onAlphaChanged: (Int) -> Unit,
    onQuit: () -> Unit,
) {
    SingleSelection(
        label = tr("Controller type"),
        choice = controllerType,
        choices = SwitchableControllerTypes,
        choiceToString = { controllerTypeToString(it) },
        enabled = isEmulationInitialized,
        isChoiceEnabled = { isControllerTypeSwitchAllowed(controllerType, it) },
        onChoiceChanged = onControllerTypeChanged,
    )

    SingleSelection(
        label = tr("Orientation"),
        choice = screenOrientationMode,
        choices = ScreenOrientationMode.entries,
        choiceToString = { screenOrientationModeToString(it) },
        onChoiceChanged = onScreenOrientationChanged,
    )

    SingleSelection(
        label = screensLayoutLabel,
        choice = screensLayout,
        choices = ScreensLayout.entries,
        choiceToString = { screensLayoutToString(it) },
        onChoiceChanged = onScreensLayoutChanged,
    )

    Slider(
        label = tr("Screen gap"),
        value = screensGap,
        valueFrom = 0,
        valueTo = MAX_SCREENS_GAP,
        labelFormatter = { "$it%" },
        onValueChange = onScreensGapChanged,
    )

    CheckboxItem(
        label = tr("Enable motion"),
        checked = sideMenuState.isMotionEnabled,
        onCheckedChange = { updateState(sideMenuState.copy(isMotionEnabled = it)) },
    )

    CheckboxItem(
        label = tr("Replace TV with PAD"),
        checked = sideMenuState.isTVReplacedWithPad,
        onCheckedChange = { updateState(sideMenuState.copy(isTVReplacedWithPad = it)) },
    )

    CheckboxItem(
        label = tr("Show PAD"),
        checked = sideMenuState.isPadVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isPadVisible = it)) },
    )

    CheckboxItem(
        label = tr("External PAD screen"),
        checked = sideMenuState.isPadOnExternalDisplay,
        onCheckedChange = { updateState(sideMenuState.copy(isPadOnExternalDisplay = it)) },
        enabled = sideMenuState.isPadVisible,
    )

    CheckboxItem(
        label = tr("Swap screens"),
        checked = sideMenuState.areScreensSwapped,
        onCheckedChange = { updateState(sideMenuState.copy(areScreensSwapped = it)) },
    )

    CheckboxItem(
        label = tr("Rotate external screen left"),
        checked = sideMenuState.isExternalScreenRotatedLeft,
        onCheckedChange = { updateState(sideMenuState.copy(isExternalScreenRotatedLeft = it)) },
        enabled = sideMenuState.isPadOnExternalDisplay,
    )

    TextButtonItem(
        label = tr("Emulated USB Devices"),
        onClick = onShowEmulatedUSBDevices,
    )

    CheckboxItem(
        label = tr("Show input overlay"),
        checked = sideMenuState.isInputOverlayVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isInputOverlayVisible = it)) },
    )

    CheckboxItem(
        label = tr("Vibrate on touch"),
        checked = isVibrateOnTouchEnabled,
        onCheckedChange = onVibrateChanged,
        enabled = sideMenuState.isInputOverlayVisible,
    )

    Slider(
        label = tr("Inputs opacity"),
        value = overlayAlpha,
        valueFrom = 0,
        steps = 16,
        valueTo = 255,
        onValueChange = onAlphaChanged,
        labelFormatter = { it.toString() },
        enabled = sideMenuState.isInputOverlayVisible,
    )

    TextButtonItem(
        label = tr("Edit inputs"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onEditInputOverlay,
    )

    TextButtonItem(
        label = tr("Visible inputs"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onEditVisibleInputs,
    )

    TextButtonItem(
        label = tr("Reset input overlay"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onResetInputOverlay,
    )

    TextButtonItem(
        label = tr("Exit"),
        onClick = onQuit,
    )
}

@Composable
private fun CheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled) { onCheckedChange(!checked) }
            .padding(8.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            fontSize = 16.sp,
        )

        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun TextButtonItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .heightIn(min = 48.dp)
            .wrapContentHeight(align = Alignment.CenterVertically),
        fontSize = 16.sp,
    )
}

@Composable
private fun EmulationSurfaces(
    viewModel: EmulationViewModel,
    isEmulationInitialized: Boolean,
    screensLayout: ScreensLayout,
    screensGap: Int,
    targetAspectRatio: Float,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val mainSurfaceDimensions by viewModel.mainSurfaceDimensions.collectAsState()
    val padSurfaceDimensions by viewModel.padSurfaceDimensions.collectAsState()

    val padDisplay = if (activity != null) rememberPadDisplay(activity) else null
    val isPadVisibleEffective = sideMenuState.isPadVisible && isEmulationInitialized
    val usePadPresentation =
        isPadVisibleEffective && sideMenuState.isPadOnExternalDisplay && padDisplay != null

    val mainTouchListener = remember { CanvasOnTouchListener() }
    val padTouchListener = remember { CanvasOnTouchListener() }
    val padPresentationTouchListener = remember { CanvasOnTouchListener() }

    val isMainTargetingTV = !sideMenuState.areScreensSwapped
    val mainTargetDimensions =
        if (isMainTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val isPadTargetingTV = sideMenuState.areScreensSwapped
    val padTargetDimensions =
        if (isPadTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val rotatePresentationTouch = usePadPresentation && sideMenuState.isExternalScreenRotatedLeft

    LaunchedEffect(isPadTargetingTV, padTargetDimensions, rotatePresentationTouch) {
        padPresentationTouchListener.updateConfiguration(
            isTv = isPadTargetingTV,
            surfaceWidth = padTargetDimensions.width,
            surfaceHeight = padTargetDimensions.height,
            rotateLeft = rotatePresentationTouch,
        )
    }

    DisposableEffect(activity, padDisplay, usePadPresentation, sideMenuState.isExternalScreenRotatedLeft) {
        val activityNonNull = activity ?: return@DisposableEffect onDispose {}
        if (!usePadPresentation) {
            return@DisposableEffect onDispose {}
        }
        val padDisplayNonNull = padDisplay

        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)

        val padPresentation = PadPresentation(
            context = activityNonNull,
            display = padDisplayNonNull,
            rotateLeft = sideMenuState.isExternalScreenRotatedLeft,
            holderCallback = viewModel.padHolderCallback,
            touchListener = padPresentationTouchListener,
        )

        padPresentation.show()

        onDispose { padPresentation.dismiss() }
    }

    ScreensLayoutContainer(
        isVertical = screensLayout.isVertical(),
        gapPercent = screensGap,
        targetAspectRatio = targetAspectRatio,
    ) {
        EmulationSurface(
            holderCallback = viewModel.mainHolderCallback,
            touchListener = mainTouchListener,
            touchIsTv = isMainTargetingTV,
            touchSurfaceWidth = mainTargetDimensions.width,
            touchSurfaceHeight = mainTargetDimensions.height,
            afterInit = { viewModel.initializeEmulation() },
        )

        if (isPadVisibleEffective && !usePadPresentation) {
            EmulationSurface(
                holderCallback = viewModel.padHolderCallback,
                touchListener = padTouchListener,
                touchIsTv = isPadTargetingTV,
                touchSurfaceWidth = padTargetDimensions.width,
                touchSurfaceHeight = padTargetDimensions.height,
            )
        }
    }
}

// Lays out the TV / GamePad surfaces without ever swapping the composable that contains them.
// Switching between a Column and a Row when the orientation or the layout changes would dispose
// and recreate the SurfaceViews, which destroys and recreates their native surfaces and can crash
// the renderer. A single custom layout instead just measures its children differently.
@Composable
private fun ScreensLayoutContainer(
    isVertical: Boolean,
    gapPercent: Int,
    targetAspectRatio: Float,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val containerWidth = constraints.maxWidth
        val containerHeight = constraints.maxHeight
        val count = measurables.size
        if (count == 0) {
            return@Layout layout(containerWidth, containerHeight) {}
        }
        val gap = if (count > 1) {
            (min(containerWidth, containerHeight) * gapPercent / 100f).roundToInt()
        } else {
            0
        }

        // Each screen keeps its aspect ratio. In portrait (vertical) a full width screen may be
        // too tall, so its height is capped; in landscape (side by side) the width is capped.
        val sizes = if (isVertical) {
            val share = (containerHeight - gap * (count - 1)) / count
            val boxWidth = min(containerWidth, (share * targetAspectRatio).roundToInt())
            val boxHeight = (boxWidth / targetAspectRatio).roundToInt()
            List(count) { boxWidth to boxHeight }
        } else {
            val share = (containerWidth - gap * (count - 1)) / count
            val boxHeight = min(containerHeight, (share / targetAspectRatio).roundToInt())
            val boxWidth = (boxHeight * targetAspectRatio).roundToInt()
            List(count) { boxWidth to boxHeight }
        }

        val placeables = measurables.mapIndexed { index, measurable ->
            val (width, height) = sizes[index]
            measurable.measure(Constraints.fixed(width, height))
        }

        layout(containerWidth, containerHeight) {
            if (isVertical) {
                // Stacked from the top so a lone screen is not floating in the middle.
                var y = 0
                placeables.forEach { placeable ->
                    placeable.place(
                        IntOffset(
                            x = (containerWidth - placeable.width) / 2,
                            y = y,
                        )
                    )
                    y += placeable.height + gap
                }
            } else {
                val totalWidth = placeables.sumOf { it.width } + gap * (count - 1)
                var x = (containerWidth - totalWidth) / 2
                placeables.forEach { placeable ->
                    placeable.place(
                        IntOffset(
                            x = x,
                            y = (containerHeight - placeable.height) / 2,
                        )
                    )
                    x += placeable.width + gap
                }
            }
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
private fun EmulationSurface(
    holderCallback: SurfaceHolder.Callback,
    touchListener: CanvasOnTouchListener,
    touchIsTv: Boolean,
    touchSurfaceWidth: Int,
    touchSurfaceHeight: Int,
    rotateLeft: Boolean = false,
    afterInit: () -> Unit = {}
) {
    // The size (and aspect ratio) is decided by ScreensLayoutContainer.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                var firstChange = true

                setOnTouchListener(touchListener)

                holder.addCallback(holderCallback)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        if (firstChange) {
                            afterInit()
                            firstChange = false
                        }
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        },
        update = {
            touchListener.updateConfiguration(
                isTv = touchIsTv,
                surfaceWidth = touchSurfaceWidth,
                surfaceHeight = touchSurfaceHeight,
                rotateLeft = rotateLeft,
            )
        },
    )
}

@Composable
private fun rememberPadDisplay(activity: Activity): Display? {
    val displayManager =
        remember(activity) { activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    var padDisplay by remember { mutableStateOf<Display?>(null) }

    fun updatePadDisplay() {
        padDisplay =
            if (activity.display.displayId == Display.DEFAULT_DISPLAY) {
                DisplayUtils.getExternalDisplay(activity)
            } else {
                DisplayUtils.getInternalDisplay(activity)
            }
    }

    DisposableEffect(displayManager, activity) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updatePadDisplay()
            override fun onDisplayRemoved(displayId: Int) = updatePadDisplay()
            override fun onDisplayChanged(displayId: Int) = updatePadDisplay()
        }

        updatePadDisplay()
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return padDisplay
}

@Composable
private fun EmulationQuitConfirmationDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(tr("Exit confirmation")) },
        text = { Text(tr("Are you sure you want to exit?")) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Yes")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("No")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationLoadingDialog() {
    AlertDialog(
        title = { Text(tr("Initializing emulation")) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        onDismissRequest = {},
    )
}

@Composable
private fun EmulationErrorDialog(error: NativeError, onQuit: () -> Unit) {
    val errorMessage = remember(error) {
        when (error) {
            is NativeError.SystemInitializationError -> tr("Failed to initialize")

            is NativeError.RendererInitializationError ->
                tr("Failed creating renderer: {0}", error.message)

            is NativeError.SurfaceCreationError -> tr("Failed creating surface: {0}", error.message)

            NativeError.GameFilesNotFoundError -> tr("Unable to launch game because the base files were not found.")
            NativeError.NoDiscKeysError -> tr("Could not decrypt title. Make sure that keys.txt contains the correct disc key for this title.")
            NativeError.NoTitleTikError -> tr("Could not decrypt title because title.tik is missing.")
            is NativeError.UnknownTilePrepareError ->
                tr("Unable to launch game\nPath: {0}", error.launchPath)

            NativeError.LaunchingTitleError -> tr("Failed to launch title")
        }
    }
    AlertDialog(
        title = { Text(tr("Error")) },
        text = { Text(errorMessage) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Quit")) } },
        onDismissRequest = {},
    )
}
