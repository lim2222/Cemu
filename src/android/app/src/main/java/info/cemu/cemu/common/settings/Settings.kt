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
    val gamePadPosition: GamePadPosition = GamePadPosition.RIGHT,
    val isPadVisible: Boolean = false,
    val isPadOnExternalDisplay: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
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
    // Per-game overrides keyed by the launch path of the title. When a game has an entry its
    // layout is used instead of the default one, so that games do not affect each other.
    val inputVisibilityMapByGame: Map<String, Map<OverlayInputConfig, Boolean>> = emptyMap(),
    val inputOverlayRectMapByGame: Map<String, Map<OverlayInputConfig, InputOverlayRect>> = emptyMap(),
) {
    fun resolvedForGame(gameKey: String?): InputOverlaySettings {
        if (gameKey == null) {
            return this
        }

        return copy(
            inputVisibilityMap = inputVisibilityMapByGame[gameKey] ?: inputVisibilityMap,
            inputOverlayRectMap = inputOverlayRectMapByGame[gameKey] ?: inputOverlayRectMap,
        )
    }
}


@Serializable
data class AppSettings(
    val guiSettings: GuiSettings = GuiSettings(),
    val emulationSettings: EmulationSettings = EmulationSettings(),
    val storageSettings: StorageSettings = StorageSettings(),
    val inputOverlaySettings: InputOverlaySettings = InputOverlaySettings(),
    val hotkeySettings: Map<HotkeyAction, HotkeyCombo> = emptyMap(),
)

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        Json.decodeFromString<AppSettings>(input.readBytes().decodeToString())
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(Json.encodeToString(t).encodeToByteArray())
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
