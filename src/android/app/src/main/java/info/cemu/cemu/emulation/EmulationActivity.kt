package info.cemu.cemu.emulation

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import info.cemu.cemu.BuildConfig
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.android.inputevent.isFromPhysicalController
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.ui.components.ActivityContent
import info.cemu.cemu.common.ui.localization.TranslatableContent
import info.cemu.cemu.emulation.input.ControllerCallbacks
import info.cemu.cemu.emulation.input.ControllerMotionHandler
import info.cemu.cemu.emulation.input.DeviceControllerCallbacks
import info.cemu.cemu.emulation.input.DeviceMotionHandler
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.input.InputHandler
import info.cemu.cemu.emulation.input.NativeInputDeviceListener
import info.cemu.cemu.common.settings.ScreenOrientationMode
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private class InputDelegateManager(context: Context) {
    private val nativeInputDeviceListener = NativeInputDeviceListener(context)
    private val controllerCallbacks = ControllerCallbacks(context)
    private val controllerMotionHandler = ControllerMotionHandler(context)
    private val deviceControllerCallbacks = DeviceControllerCallbacks(context)
    private val deviceMotionHandler = DeviceMotionHandler(context)

    fun setDeviceMotionEnabled(isListening: Boolean) =
        deviceMotionHandler.setIsListening(isListening)

    fun registerAll() {
        nativeInputDeviceListener.register()
        controllerCallbacks.register()
        controllerMotionHandler.register()
        deviceControllerCallbacks.register()
    }

    fun unregisterAll() {
        nativeInputDeviceListener.unregister()
        controllerCallbacks.unregister()
        controllerMotionHandler.unregister()
        deviceControllerCallbacks.unregister()
    }

    fun onResume(rotation: Int) {
        registerAll()
        deviceMotionHandler.setDeviceRotation(rotation)
        deviceMotionHandler.resumeListening()
    }

    fun onPause() {
        unregisterAll()
        deviceMotionHandler.pauseListening()
    }
}

class EmulationActivity : AppCompatActivity() {
    private lateinit var inputManager: InputDelegateManager
    private var processInputEvents = true
    private var previousFullscreenScaling: Int? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (processInputEvents && InputHandler.onMotionEvent(event)) {
            return true
        }

        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        HotkeyManager.onKeyEvent(event)

        if (processInputEvents && InputHandler.onKeyEvent(event)) {
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.isFromPhysicalController()) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun getGamePath(): String {
        val extras = intent.extras
        val data = intent.data
        var launchPath: String? = null

        if (extras != null) {
            launchPath = extras.getString(EXTRA_LAUNCH_PATH)
        }

        if (launchPath == null && data != null) {
            launchPath = data.toString()
        }

        if (launchPath == null) {
            throw RuntimeException("launchPath is null")
        }

        return launchPath
    }

    private fun getTitleId(): Long? {
        val titleId = intent.getLongExtra(EXTRA_TITLE_ID, -1L)

        return if (titleId >= 0) titleId else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        EmulationSessionState.onSessionStarted(this)
        DisplayUtils.init(this)
        inputManager = InputDelegateManager(this)

        setupHotkeys()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setFullscreen()

        val gamePath = getGamePath()
        val titleId = getTitleId()

        applyInitialOrientation(gamePath)

        // Remember the user's fullscreen scaling. In portrait the emulation surface is a 4:3
        // area, so the game is stretched into it; in landscape it is restored (the surface is
        // 16:9 there, which already matches most titles).
        previousFullscreenScaling = NativeSettings.getFullscreenScaling()

        setContent {
            TranslatableContent {
                ActivityContent {
                    EmulationScreen(
                        gamePath = gamePath,
                        titleId = titleId,
                        setMotionSensorEnabled = inputManager::setDeviceMotionEnabled,
                        onQuit = ::onQuit,
                        setInputListeningEnabled = { processInputEvents = it },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        inputManager.onPause()
    }

    override fun onResume() {
        super.onResume()

        inputManager.onResume(display.rotation)
    }

    override fun onDestroy() {
        previousFullscreenScaling?.let { NativeSettings.setFullscreenScaling(it) }
        EmulationSessionState.onSessionStopped(this)
        super.onDestroy()
    }

    // The stored orientation (per game override, otherwise the global default) is read
    // synchronously so the window does not start in the wrong orientation first.
    private fun applyInitialOrientation(gamePath: String) {
        val mode = try {
            runBlocking {
                val settings = AppSettingsStore.dataStore.data.first()
                settings.displaySettingsByGame[gamePath]?.screenOrientation
                    ?: settings.emulationSettings.screenOrientation
            }
        } catch (_: Exception) {
            ScreenOrientationMode.LANDSCAPE
        }
        requestedOrientation = mode.toRequestedOrientation()
    }

    private fun setupHotkeys() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppSettingsStore.dataStore.data.map { it.hotkeySettings }
                    .distinctUntilChanged()
                    .collect { HotkeyManager.setHotkeyMappings(it) }
            }
        }
    }

    private fun setFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun onQuit() {
        EmulationSessionState.syncSavesToCustomRoot(this)
        finish()
        exitProcess(0)
    }

    companion object {
        const val EXTRA_LAUNCH_PATH: String = BuildConfig.APPLICATION_ID + ".LaunchPath"
        const val EXTRA_TITLE_ID: String = BuildConfig.APPLICATION_ID + ".TitleId"
    }
}

internal fun ScreenOrientationMode.toRequestedOrientation(): Int = when (this) {
    ScreenOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ScreenOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    ScreenOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
