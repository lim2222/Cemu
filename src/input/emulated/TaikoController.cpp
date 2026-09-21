#include "input/emulated/TaikoController.h"

#include "input/api/Controller.h"

TaikoController::TaikoController(size_t player_index)
	: WPADController(player_index, kDataFormat_TAIKO)
{
}

uint32 TaikoController::get_emulated_button_flag(uint32 id) const
{
	return s_get_emulated_button_flag(id);
}

// NOTE: exact bit positions are a best-guess based on the real Wii
// TaTaCon hardware protocol (WiiBrew), NOT confirmed against Nintendo's
// actual Wii U SDK bit layout for WPAD_DATA_FORMAT_TAIKO. Needs empirical
// verification via debugger + real game testing.
uint32 TaikoController::s_get_emulated_button_flag(uint32 id)
{
	switch (id)
	{
	case kButtonId_DonLeft:
		return 0x40; // CL - center left (don)
	case kButtonId_DonRight:
		return 0x10; // CR - center right (don)
	case kButtonId_KaLeft:
		return 0x20; // RL - rim left (ka)
	case kButtonId_KaRight:
		return 0x08; // RR - rim right (ka)
	case kButtonId_Plus:
		return kCLButton_Plus;
	case kButtonId_Minus:
		return kCLButton_Minus;	
	}

	return 0;
}

std::string_view TaikoController::get_button_name(ButtonId id)
{
	switch (id)
	{
	case kButtonId_DonLeft: return "Don (Left)";
	case kButtonId_DonRight: return "Don (Right)";
	case kButtonId_KaLeft: return "Ka (Left)";
	case kButtonId_KaRight: return "Ka (Right)";
	case kButtonId_Plus: return "+";
	case kButtonId_Minus: return "-";
	case kButtonId_One: return "1";
	case kButtonId_Two: return "2";
	default:
		return "";
	}
}

bool TaikoController::set_default_mapping(const std::shared_ptr<ControllerBase>& controller)
{
	// No default hardware-controller mapping for now — this controller is
	// intended to be driven by a touch/on-screen overlay.
	return false;
}