package info.cemu.cemu.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import info.cemu.cemu.common.ui.localization.DEFAULT_LANGUAGE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class EmulationSettings(
    // How the emulation screen is allowed to rotate. Games run full screen, so this is what
    // decides between portrait and landscape.
    val screenOrientation: ScreenOrientationMode = ScreenOrientationMode.LANDSCAPE,
    // TV/GamePad arrangement. Portrait and landscape keep their own layout so rotating the
    // device can switch between top/down and side by side automatically. The order of the two
    // screens is controlled separately by "Swap screens".
    val screensLayoutPortrait: ScreensLayout = ScreensLayout.TOP_DOWN,
    val screensLayoutLandscape: ScreensLayout = ScreensLayout.SIDE_BY_SIDE,
    // Empty space between the TV and GamePad screens, as a percentage of the smaller screen
    // dimension so it looks the same in portrait and landscape.
    val screensGap: Int = DEFAULT_SCREENS_GAP,
    val isPadVisible: Boolean = false,
    val isPadOnExternalDisplay: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
)

const val DEFAULT_SCREENS_GAP: Int = 2
const val MAX_SCREENS_GAP: Int = 20

// Per-game overrides for the display settings above. A null value means "use the global
// default". All fields are optional so adding new ones never breaks stored settings.
@Serializable
data class PerGameDisplaySettings(
    val screenOrientation: ScreenOrientationMode? = null,
    val screensLayoutPortrait: ScreensLayout? = null,
    val screensLayoutLandscape: ScreensLayout? = null,
    val screensGap: Int? = null,
)

@Serializable
data class GuiSettings(
    val language: String = DEFAULT_LANGUAGE,
)

@Serializable
data class StorageSettings(
    val dataRootPath: String? = null,
    val customRootUri: String? = null,
    val mirrorRootPath: String? = null,
    val pendingDeleteDataRootPath: String? = null,
    val isSaveMirrorDirty: Boolean = false,
    val lastSaveSyncAtMillis: Long? = null,
    val lastManualSyncAtMillis: Long? = null,
    @Deprecated("Kept only to decode settings written by older data-storage prototypes.")
    val isMirrorDirty: Boolean = false,
    val lastStorageError: String? = null,
)

@Serializable
data class InputOverlayRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class InputOverlaySettings(
    val isVibrateOnTouchEnabled: Boolean = false,
	val vibrationAmplitude: Int = 128,
    val isOverlayEnabled: Boolean = false,
    val controllerIndex: Int = 0,
    val alpha: Int = 64,
    // Default layout. Edited by the global input overlay settings screen and used by games
    // which have not customized their overlay yet.
    val inputVisibilityMap: Map<OverlayInputConfig, Boolean> = emptyMap(),
    val inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect> = emptyMap(),
    // Per-game overrides keyed by inputOverlayKey(). A title can be played with different
    // controller types (GamePad, Pro Controller, ...) which need their own arrangement, so the
    // key also contains the controller type. Games do not affect each other.
    val inputVisibilityMapByGame: Map<String, Map<OverlayInputConfig, Boolean>> = emptyMap(),
    val inputOverlayRectMapByGame: Map<String, Map<OverlayInputConfig, InputOverlayRect>> = emptyMap(),
    // These are game preferences rather than controller specific, so they are keyed by game only.
    val inputOverlayAlphaByGame: Map<String, Int> = emptyMap(),
    val inputOverlayVibrateByGame: Map<String, Boolean> = emptyMap(),
) {
    fun resolvedForGame(gameKey: String?, overlayKey: String?): InputOverlaySettings {
        if (gameKey == null || overlayKey == null) {
            return this
        }

        return copy(
            inputVisibilityMap = inputVisibilityMapByGame[overlayKey] ?: inputVisibilityMap,
            inputOverlayRectMap = inputOverlayRectMapByGame[overlayKey] ?: inputOverlayRectMap,
            alpha = inputOverlayAlphaByGame[gameKey] ?: alpha,
            isVibrateOnTouchEnabled = inputOverlayVibrateByGame[gameKey] ?: isVibrateOnTouchEnabled,
        )
    }
}

// The controller type is prefixed so the key stays unique even when the game path contains
// separators: the part before the first ':' is always the numeric controller type.
fun inputOverlayKey(gamePath: String, controllerType: Int): String = "$controllerType:$gamePath"


@Serializable
data class AppSettings(
    val guiSettings: GuiSettings = GuiSettings(),
    val emulationSettings: EmulationSettings = EmulationSettings(),
    val storageSettings: StorageSettings = StorageSettings(),
    val inputOverlaySettings: InputOverlaySettings = InputOverlaySettings(),
    val hotkeySettings: Map<HotkeyAction, HotkeyCombo> = emptyMap(),
    // Per-game display overrides (orientation and TV/GamePad layout) keyed by launch path.
    val displaySettingsByGame: Map<String, PerGameDisplaySettings> = emptyMap(),
)

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        json.decodeFromString<AppSettings>(input.readBytes().decodeToString())
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

object AppSettingsStore {
    private lateinit var _dataStore: DataStore<AppSettings>
    val dataStore: DataStore<AppSettings>
        get() = _dataStore

    fun init(context: Context) {
        _dataStore = MultiProcessDataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = null,
            produceFile = { context.dataStoreFile("appSettings.json") },
        )
    }
}
