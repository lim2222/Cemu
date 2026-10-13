package info.cemu.cemu.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.common.settings.MAX_SCREENS_GAP
import info.cemu.cemu.common.settings.ScreenOrientationMode
import info.cemu.cemu.common.settings.ScreensLayout
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.components.SingleSelection
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeSettings

@Composable
fun GeneralSettingsScreen(
    navigateBack: () -> Unit,
    goToGamePathsSettings: () -> Unit,
    goToDataStorageSettings: () -> Unit,
    viewModel: GeneralSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current

    val emulationSettings by viewModel.emulationSettings.collectAsState()
    val guiSettings by viewModel.guiSettings.collectAsState()

    ScreenContent(
        appBarText = tr("General settings"),
        navigateBack = navigateBack,
    ) {
        Button(
            label = tr("Add game path"),
            description = tr("Add the root directory of your game(s). It will scan all directories in it for games"),
            onClick = dropUnlessResumed { goToGamePathsSettings() },
        )
        Button(
            label = tr("Data storage"),
            description = tr("Choose where Cemu stores saves, MLC, settings and cache"),
            onClick = dropUnlessResumed { goToDataStorageSettings() },
        )
        SingleSelection(
            label = tr("Language"),
            choice = guiSettings.language,
            onChoiceChanged = { viewModel.setLanguage(language = it, context) },
            choiceToString = { viewModel.languageToDisplayNameMap[it] ?: it },
            choices = viewModel.languages,
        )
        SingleSelection(
            label = tr("Console language"),
            initialChoice = NativeSettings::getConsoleLanguage,
            onChoiceChanged = NativeSettings::setConsoleLanguage,
            choiceToString = { consoleLanguageToString(it) },
            choices = listOf(
                NativeSettings.ConsoleLanguage.JAPANESE,
                NativeSettings.ConsoleLanguage.ENGLISH,
                NativeSettings.ConsoleLanguage.FRENCH,
                NativeSettings.ConsoleLanguage.GERMAN,
                NativeSettings.ConsoleLanguage.ITALIAN,
                NativeSettings.ConsoleLanguage.SPANISH,
                NativeSettings.ConsoleLanguage.CHINESE,
                NativeSettings.ConsoleLanguage.KOREAN,
                NativeSettings.ConsoleLanguage.DUTCH,
                NativeSettings.ConsoleLanguage.PORTUGUESE,
                NativeSettings.ConsoleLanguage.RUSSIAN,
                NativeSettings.ConsoleLanguage.TAIWANESE,
            ),
        )

        SingleSelection(
            label = tr("Orientation"),
            choice = emulationSettings.screenOrientation,
            onChoiceChanged = { viewModel.setScreenOrientation(it) },
            choiceToString = { screenOrientationModeToString(it) },
            choices = ScreenOrientationMode.entries,
        )

        SingleSelection(
            label = tr("TV / GamePad layout (portrait)"),
            choice = emulationSettings.screensLayoutPortrait,
            onChoiceChanged = { viewModel.setScreensLayoutPortrait(it) },
            choiceToString = { screensLayoutToString(it) },
            choices = ScreensLayout.entries,
        )

        SingleSelection(
            label = tr("TV / GamePad layout (landscape)"),
            choice = emulationSettings.screensLayoutLandscape,
            onChoiceChanged = { viewModel.setScreensLayoutLandscape(it) },
            choiceToString = { screensLayoutToString(it) },
            choices = ScreensLayout.entries,
        )

        Slider(
            label = tr("Screen gap"),
            value = emulationSettings.screensGap,
            valueFrom = 0,
            valueTo = MAX_SCREENS_GAP,
            labelFormatter = { "$it%" },
            onValueChange = { viewModel.setScreensGap(it) },
        )
    }
}

private fun screenOrientationModeToString(mode: ScreenOrientationMode) = when (mode) {
    ScreenOrientationMode.PORTRAIT -> tr("Portrait")
    ScreenOrientationMode.AUTO -> tr("Auto")
    ScreenOrientationMode.LANDSCAPE -> tr("Landscape")
}

private fun screensLayoutToString(layout: ScreensLayout) = when (layout) {
    ScreensLayout.TOP_DOWN -> tr("Top / down")
    ScreensLayout.SIDE_BY_SIDE -> tr("Side by side")
}

private fun consoleLanguageToString(channels: Int): String = when (channels) {
    NativeSettings.ConsoleLanguage.JAPANESE -> tr("Japanese")
    NativeSettings.ConsoleLanguage.ENGLISH -> tr("English")
    NativeSettings.ConsoleLanguage.FRENCH -> tr("French")
    NativeSettings.ConsoleLanguage.GERMAN -> tr("German")
    NativeSettings.ConsoleLanguage.ITALIAN -> tr("Italian")
    NativeSettings.ConsoleLanguage.SPANISH -> tr("Spanish")
    NativeSettings.ConsoleLanguage.CHINESE -> tr("Chinese")
    NativeSettings.ConsoleLanguage.KOREAN -> tr("Korean")
    NativeSettings.ConsoleLanguage.DUTCH -> tr("Dutch")
    NativeSettings.ConsoleLanguage.PORTUGUESE -> tr("Portuguese")
    NativeSettings.ConsoleLanguage.RUSSIAN -> tr("Russian")
    NativeSettings.ConsoleLanguage.TAIWANESE -> tr("Taiwanese")
    else -> throw IllegalArgumentException("Invalid console language: $channels")
}
