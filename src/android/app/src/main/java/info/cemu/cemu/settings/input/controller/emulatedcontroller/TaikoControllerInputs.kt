package info.cemu.cemu.settings.input.controller.emulatedcontroller

import androidx.compose.runtime.Composable
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeInput.TaikoButton

@Composable
fun TaikoControllerInputs(
    onInputClick: (String, Int) -> Unit,
    onInputLongClick: (Int) -> Unit,
    controlsMapping: Map<Int, String>,
) {
    @Composable
    fun InputItemsGroup(
        groupName: String,
        inputIds: List<Int>,
    ) {
        InputItemsGroup(
            groupName = groupName,
            inputIds = inputIds,
            inputIdToString = { taikoButtonIdToString(it) },
            onInputClick = onInputClick,
            onInputLongClick = onInputLongClick,
            controlsMapping = controlsMapping,
        )
    }
    InputItemsGroup(
        groupName = tr("Drum"),
        inputIds = listOf(
            TaikoButton.DON_LEFT,
            TaikoButton.DON_RIGHT,
            TaikoButton.KA_LEFT,
            TaikoButton.KA_RIGHT,
        )
    )
    InputItemsGroup(
        groupName = tr("Buttons"),
        inputIds = listOf(
            TaikoButton.ONE,
            TaikoButton.TWO,
            TaikoButton.PLUS,
            TaikoButton.MINUS,
        )
    )
}

private fun taikoButtonIdToString(buttonId: Int) = when (buttonId) {
    TaikoButton.DON_LEFT -> tr("Don (Left)")
    TaikoButton.DON_RIGHT -> tr("Don (Right)")
    TaikoButton.KA_LEFT -> tr("Ka (Left)")
    TaikoButton.KA_RIGHT -> tr("Ka (Right)")
    TaikoButton.ONE -> "1"
    TaikoButton.TWO -> "2"
    TaikoButton.PLUS -> "+"
    TaikoButton.MINUS -> "-"
    else -> throw IllegalArgumentException("Invalid buttonId $buttonId for Taiko controller type")
}