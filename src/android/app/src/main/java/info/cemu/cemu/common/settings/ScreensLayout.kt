package info.cemu.cemu.common.settings

// How the TV and GamePad screens are arranged when both are shown. The order of the two screens
// (which one is on top / on the left) is not part of this: it is controlled by the separate
// "Swap screens" option.
enum class ScreensLayout {
    TOP_DOWN,
    SIDE_BY_SIDE,
    ;

    fun isVertical() = this == TOP_DOWN
}
