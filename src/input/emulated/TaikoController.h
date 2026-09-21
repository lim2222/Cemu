#pragma once

#include "input/emulated/WPADController.h"

class TaikoController : public WPADController
{
public:
	enum ButtonId
	{
		kButtonId_None,

		kButtonId_DonLeft,     // 左鼓面 (center left)
		kButtonId_DonRight,    // 右鼓面 (center right)
		kButtonId_KaLeft,      // 左鼓边 (rim left)
		kButtonId_KaRight,     // 右鼓边 (rim right)

		kButtonId_Plus,
		kButtonId_Minus,
		kButtonId_One,
		kButtonId_Two,

		kButtonId_Max,
	};

	TaikoController(size_t player_index);

	Type type() const override { return Type::Taiko; }
	WPADDeviceType get_device_type() const override { return kWAPDevTaiko; }

	uint32 get_emulated_button_flag(uint32 id) const override;
	size_t get_highest_mapping_id() const override { return kButtonId_Max; }
	bool is_axis_mapping(uint64 mapping) const override { return false; }

	glm::vec2 get_axis() const override { return {0.0f, 0.0f}; }
	glm::vec2 get_rotation() const override { return {0.0f, 0.0f}; }
	glm::vec2 get_trigger() const override { return {0.0f, 0.0f}; }

	static uint32 s_get_emulated_button_flag(uint32 id);

	static std::string_view get_button_name(ButtonId id);

	bool is_start_down() const override { return false; }
	bool is_left_down() const override { return is_mapping_down(kButtonId_KaLeft); }
	bool is_right_down() const override { return is_mapping_down(kButtonId_KaRight); }
	bool is_up_down() const override { return is_mapping_down(kButtonId_DonLeft); }
	bool is_down_down() const override { return is_mapping_down(kButtonId_DonRight); }
	bool is_a_down() const override { return is_mapping_down(kButtonId_DonLeft); }
	bool is_b_down() const override { return is_mapping_down(kButtonId_DonRight); }
	bool is_home_down() const override { return false; }

	bool set_default_mapping(const std::shared_ptr<ControllerBase>& controller) override;
};