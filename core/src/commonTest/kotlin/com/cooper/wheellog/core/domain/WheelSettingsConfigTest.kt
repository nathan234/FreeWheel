package com.cooper.wheellog.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WheelSettingsConfigTest {

    // ==================== Section Structure Per Wheel Type ====================

    @Test
    fun `KingSong has 3 sections - Lighting, Ride, Dangerous`() {
        val sections = WheelSettingsConfig.sections(WheelType.KINGSONG)
        assertEquals(3, sections.size)
        assertEquals("Lighting", sections[0].title)
        assertEquals("Ride", sections[1].title)
        assertEquals("Dangerous Actions", sections[2].title)
    }

    @Test
    fun `Gotway has 5 sections - Lighting, Ride, Audio, Safety, Dangerous`() {
        val sections = WheelSettingsConfig.sections(WheelType.GOTWAY)
        assertEquals(5, sections.size)
        assertEquals("Lighting", sections[0].title)
        assertEquals("Ride", sections[1].title)
        assertEquals("Audio", sections[2].title)
        assertEquals("Safety", sections[3].title)
        assertEquals("Dangerous Actions", sections[4].title)
    }

    @Test
    fun `Gotway Virtual matches Gotway sections`() {
        val gotway = WheelSettingsConfig.sections(WheelType.GOTWAY)
        val virtual = WheelSettingsConfig.sections(WheelType.GOTWAY_VIRTUAL)
        assertEquals(gotway.size, virtual.size)
        for (i in gotway.indices) {
            assertEquals(gotway[i].title, virtual[i].title)
            assertEquals(gotway[i].controls.size, virtual[i].controls.size)
        }
    }

    @Test
    fun `Veteran has 3 sections - Lighting, Ride, Dangerous`() {
        val sections = WheelSettingsConfig.sections(WheelType.VETERAN)
        assertEquals(3, sections.size)
        assertEquals("Lighting", sections[0].title)
        assertEquals("Ride", sections[1].title)
        assertEquals("Dangerous Actions", sections[2].title)
    }

    @Test
    fun `NinebotZ has 6 sections - Lighting, Ride, Audio, Wheel Alarms, Speed Limit, Dangerous`() {
        val sections = WheelSettingsConfig.sections(WheelType.NINEBOT_Z)
        assertEquals(6, sections.size)
        assertEquals("Lighting", sections[0].title)
        assertEquals("Ride", sections[1].title)
        assertEquals("Audio", sections[2].title)
        assertEquals("Wheel Alarms", sections[3].title)
        assertEquals("Speed Limit", sections[4].title)
        assertEquals("Dangerous Actions", sections[5].title)
    }

    @Test
    fun `InMotion has 4 sections - Lighting, Ride, Audio, Dangerous`() {
        val sections = WheelSettingsConfig.sections(WheelType.INMOTION)
        assertEquals(4, sections.size)
        assertEquals("Lighting", sections[0].title)
        assertEquals("Ride", sections[1].title)
        assertEquals("Audio", sections[2].title)
        assertEquals("Dangerous Actions", sections[3].title)
    }

    @Test
    fun `InMotionV2 has 10 sections`() {
        val sections = WheelSettingsConfig.sections(WheelType.INMOTION_V2)
        assertEquals(10, sections.size)
        assertEquals("Lighting", sections[0].title)
        assertEquals("Ride", sections[1].title)
        assertEquals("Berm Angle", sections[2].title)
        assertEquals("Braking", sections[3].title)
        assertEquals("Audio", sections[4].title)
        assertEquals("Thermal", sections[5].title)
        assertEquals("Safety", sections[6].title)
        assertEquals("Battery", sections[7].title)
        assertEquals("System", sections[8].title)
        assertEquals("Dangerous Actions", sections[9].title)
    }

    @Test
    fun `Unknown wheel type returns empty sections`() {
        val sections = WheelSettingsConfig.sections(WheelType.Unknown)
        assertTrue(sections.isEmpty())
    }

    @Test
    fun `Ninebot legacy returns empty sections`() {
        val sections = WheelSettingsConfig.sections(WheelType.NINEBOT)
        assertTrue(sections.isEmpty())
    }

    // ==================== Specific Controls Per Wheel Type ====================

    @Test
    fun `KingSong Lighting has Light Mode picker, LED Mode picker, Strobe Mode picker`() {
        val lighting = WheelSettingsConfig.sections(WheelType.KINGSONG)[0]
        assertEquals(3, lighting.controls.size)

        val lightMode = lighting.controls[0] as ControlSpec.Picker
        assertEquals("Light Mode", lightMode.label)
        assertEquals(listOf("Off", "On", "Auto"), lightMode.options)
        assertEquals(SettingsCommandId.LIGHT_MODE, lightMode.commandId)

        val ledMode = lighting.controls[1] as ControlSpec.Picker
        assertEquals("LED Mode", ledMode.label)
        assertEquals(8, ledMode.options.size)

        val strobeMode = lighting.controls[2] as ControlSpec.Picker
        assertEquals("Strobe Mode", strobeMode.label)
        assertEquals(4, strobeMode.options.size)
    }

    @Test
    fun `Gotway has Roll Angle segmented in Ride section`() {
        val ride = WheelSettingsConfig.sections(WheelType.GOTWAY)[1]
        val rollAngle = ride.controls[1] as ControlSpec.Segmented
        assertEquals("Roll Angle", rollAngle.label)
        assertEquals(listOf("Low", "Medium", "High"), rollAngle.options)
        assertEquals(SettingsCommandId.ROLL_ANGLE_MODE, rollAngle.commandId)
    }

    @Test
    fun `Gotway Ride section has 6 controls including Cutout Angle slider 45-90`() {
        val ride = WheelSettingsConfig.sections(WheelType.GOTWAY)[1]
        assertEquals(6, ride.controls.size)
        val cutout = ride.controls[2] as ControlSpec.Slider
        assertEquals("Cutout Angle", cutout.label)
        assertEquals(45, cutout.min)
        assertEquals(90, cutout.max)
        assertEquals("\u00B0", cutout.unit)
        assertEquals(70, cutout.defaultValue)
        assertEquals(SettingsCommandId.CUTOUT_ANGLE, cutout.commandId)
        assertEquals(5, cutout.step)

        val pedalTilt = ride.controls[3] as ControlSpec.Slider
        assertEquals("Pedal Tilt", pedalTilt.label)
        assertEquals(0, pedalTilt.min)
        assertEquals(9, pedalTilt.max)
        assertEquals(SettingsCommandId.PEDAL_TILT, pedalTilt.commandId)

        val weakMag = ride.controls[4] as ControlSpec.Slider
        assertEquals("Weak Magnetism", weakMag.label)
        assertEquals(0, weakMag.min)
        assertEquals(6, weakMag.max)
        assertEquals(SettingsCommandId.WEAK_MAGNETISM, weakMag.commandId)

        val extRoll = ride.controls[5] as ControlSpec.Slider
        assertEquals("Extended Roll Angle", extRoll.label)
        assertEquals(0, extRoll.min)
        assertEquals(9, extRoll.max)
        assertEquals(SettingsCommandId.EXTENDED_ROLL_ANGLE, extRoll.commandId)
    }

    @Test
    fun `Gotway Safety section has Plate Protection toggle and Power Alarm slider`() {
        val safety = WheelSettingsConfig.sections(WheelType.GOTWAY)[3]
        assertEquals("Safety", safety.title)
        assertEquals(2, safety.controls.size)

        val plateProtection = safety.controls[0] as ControlSpec.Toggle
        assertEquals("Plate Protection", plateProtection.label)
        assertEquals(SettingsCommandId.PLATE_PROTECTION, plateProtection.commandId)

        val powerAlarm = safety.controls[1] as ControlSpec.Slider
        assertEquals("Power Alarm", powerAlarm.label)
        assertEquals(50, powerAlarm.min)
        assertEquals(90, powerAlarm.max)
        assertEquals("%", powerAlarm.unit)
        assertEquals(70, powerAlarm.defaultValue)
        assertEquals(SettingsCommandId.POWER_ALARM, powerAlarm.commandId)
    }

    @Test
    fun `Gotway has Beeper Volume slider 1-9 in Audio section`() {
        val audio = WheelSettingsConfig.sections(WheelType.GOTWAY)[2]
        assertEquals("Audio", audio.title)
        assertEquals(1, audio.controls.size)
        val slider = audio.controls[0] as ControlSpec.Slider
        assertEquals("Beeper Volume", slider.label)
        assertEquals(1, slider.min)
        assertEquals(9, slider.max)
    }

    @Test
    fun `NinebotZ has 3 alarm toggles with conditional speed sliders`() {
        val alarms = WheelSettingsConfig.sections(WheelType.NINEBOT_Z)[3]
        assertEquals("Wheel Alarms", alarms.title)
        assertEquals(6, alarms.controls.size) // 3 toggles + 3 sliders

        val toggle1 = alarms.controls[0] as ControlSpec.Toggle
        assertEquals("Alarm 1", toggle1.label)
        assertEquals(SettingsCommandId.ALARM_ENABLED_1, toggle1.commandId)

        val slider1 = alarms.controls[1] as ControlSpec.Slider
        assertEquals("Alarm 1 Speed", slider1.label)
        assertEquals(0, slider1.min)
        assertEquals(60, slider1.max)
        assertEquals(SettingsCommandId.ALARM_ENABLED_1, slider1.visibleWhen)

        val toggle2 = alarms.controls[2] as ControlSpec.Toggle
        assertEquals("Alarm 2", toggle2.label)

        val slider2 = alarms.controls[3] as ControlSpec.Slider
        assertEquals(SettingsCommandId.ALARM_ENABLED_2, slider2.visibleWhen)

        val toggle3 = alarms.controls[4] as ControlSpec.Toggle
        assertEquals("Alarm 3", toggle3.label)

        val slider3 = alarms.controls[5] as ControlSpec.Slider
        assertEquals(SettingsCommandId.ALARM_ENABLED_3, slider3.visibleWhen)
    }

    @Test
    fun `InMotionV2 has Thermal section with Fan and Fan Quiet toggles`() {
        val thermal = WheelSettingsConfig.sections(WheelType.INMOTION_V2)[5]
        assertEquals("Thermal", thermal.title)
        assertEquals(2, thermal.controls.size)

        val fan = thermal.controls[0] as ControlSpec.Toggle
        assertEquals("Fan", fan.label)
        assertEquals(SettingsCommandId.FAN, fan.commandId)

        val fanQuiet = thermal.controls[1] as ControlSpec.Toggle
        assertEquals("Fan Quiet Mode", fanQuiet.label)
        assertEquals(SettingsCommandId.FAN_QUIET, fanQuiet.commandId)
    }

    @Test
    fun `InMotionV2 Dangerous has Lock toggle, Calibrate, Power Off`() {
        val dangerous = WheelSettingsConfig.sections(WheelType.INMOTION_V2)[9]
        assertEquals("Dangerous Actions", dangerous.title)
        assertEquals(3, dangerous.controls.size)

        assertTrue(dangerous.controls[0] is ControlSpec.DangerousToggle)
        assertEquals(SettingsCommandId.LOCK, dangerous.controls[0].commandId)

        assertTrue(dangerous.controls[1] is ControlSpec.DangerousButton)
        assertEquals(SettingsCommandId.CALIBRATE, dangerous.controls[1].commandId)

        assertTrue(dangerous.controls[2] is ControlSpec.DangerousButton)
        assertEquals(SettingsCommandId.POWER_OFF, dangerous.controls[2].commandId)
    }

    @Test
    fun `Veteran Dangerous has Reset Trip`() {
        val dangerous = WheelSettingsConfig.sections(WheelType.VETERAN)[2]
        assertEquals(1, dangerous.controls.size)
        val btn = dangerous.controls[0] as ControlSpec.DangerousButton
        assertEquals("Reset Trip", btn.label)
        assertEquals(SettingsCommandId.RESET_TRIP, btn.commandId)
    }

    @Test
    fun `InMotion Ride has Handle Button, Ride Mode, Max Speed, Pedal Tilt, Pedal Sensitivity`() {
        val ride = WheelSettingsConfig.sections(WheelType.INMOTION)[1]
        assertEquals(5, ride.controls.size)

        assertTrue(ride.controls[0] is ControlSpec.Toggle)
        assertEquals(SettingsCommandId.HANDLE_BUTTON, ride.controls[0].commandId)

        assertTrue(ride.controls[1] is ControlSpec.Toggle)
        assertEquals(SettingsCommandId.RIDE_MODE, ride.controls[1].commandId)

        val maxSpeed = ride.controls[2] as ControlSpec.Slider
        assertEquals(3, maxSpeed.min)
        assertEquals(60, maxSpeed.max)

        val pedalTilt = ride.controls[3] as ControlSpec.Slider
        assertEquals(-8, pedalTilt.min)
        assertEquals(8, pedalTilt.max)
        assertEquals("\u00B0", pedalTilt.unit)

        val sensitivity = ride.controls[4] as ControlSpec.Slider
        assertEquals(4, sensitivity.min)
        assertEquals(100, sensitivity.max)
        assertEquals("%", sensitivity.unit)
    }

    @Test
    fun `InMotionV2 Ride has 11 controls including 7 toggles and 4 sliders`() {
        val ride = WheelSettingsConfig.sections(WheelType.INMOTION_V2)[1]
        assertEquals(11, ride.controls.size)

        val toggles = ride.controls.filterIsInstance<ControlSpec.Toggle>()
        assertEquals(7, toggles.size)
        assertEquals(SettingsCommandId.HANDLE_BUTTON, toggles[0].commandId)
        assertEquals(SettingsCommandId.RIDE_MODE, toggles[1].commandId)
        assertEquals(SettingsCommandId.GO_HOME_MODE, toggles[2].commandId)
        assertEquals(SettingsCommandId.FANCIER_MODE, toggles[3].commandId)
        assertEquals(SettingsCommandId.TRANSPORT_MODE, toggles[4].commandId)
        assertEquals(SettingsCommandId.ONE_PEDAL_MODE, toggles[5].commandId)
        assertEquals(SettingsCommandId.CRUISE, toggles[6].commandId)

        val sliders = ride.controls.filterIsInstance<ControlSpec.Slider>()
        assertEquals(4, sliders.size)
        assertEquals(SettingsCommandId.MAX_SPEED, sliders[0].commandId)
        assertEquals(SettingsCommandId.PEDAL_TILT, sliders[1].commandId)
        assertEquals(SettingsCommandId.PEDAL_SENSITIVITY, sliders[2].commandId)
        assertEquals(SettingsCommandId.TURNING_SENSITIVITY, sliders[3].commandId)
    }

    // ==================== Dangerous Actions Have Confirmation Messages ====================

    @Test
    fun `Calibrate button has confirmation title and message`() {
        val dangerous = WheelSettingsConfig.sections(WheelType.KINGSONG)[2]
        val calibrate = dangerous.controls[0] as ControlSpec.DangerousButton
        assertEquals("Calibrate Wheel", calibrate.confirmTitle)
        assertTrue(calibrate.confirmMessage.contains("flat surface"))
    }

    @Test
    fun `Power Off button has confirmation message`() {
        val dangerous = WheelSettingsConfig.sections(WheelType.KINGSONG)[2]
        val powerOff = dangerous.controls[1] as ControlSpec.DangerousButton
        assertEquals("Power Off", powerOff.confirmTitle)
        assertTrue(powerOff.confirmMessage.contains("power off"))
    }

    @Test
    fun `Lock toggle has confirmation message`() {
        val dangerous = WheelSettingsConfig.sections(WheelType.NINEBOT_Z)[5]
        val lock = dangerous.controls[0] as ControlSpec.DangerousToggle
        assertEquals("Lock Wheel", lock.confirmTitle)
        assertTrue(lock.confirmMessage.contains("Unlock"))
    }

    @Test
    fun `Reset Trip button has confirmation message`() {
        val dangerous = WheelSettingsConfig.sections(WheelType.VETERAN)[2]
        val reset = dangerous.controls[0] as ControlSpec.DangerousButton
        assertEquals("Reset Trip", reset.confirmTitle)
        assertTrue(reset.confirmMessage.contains("trip distance"))
    }

    // ==================== SettingsCommandId readback ====================

    @Test
    fun `readInt returns pedalsMode from WheelState when known`() {
        val state = WheelState(pedalsMode = 1)
        assertEquals(1, SettingsCommandId.PEDALS_MODE.readInt(state))
    }

    @Test
    fun `readInt returns null for pedalsMode when unknown (-1)`() {
        val state = WheelState(pedalsMode = -1)
        assertNull(SettingsCommandId.PEDALS_MODE.readInt(state))
    }

    @Test
    fun `readInt returns lightMode from WheelState`() {
        val state = WheelState(lightMode = 2)
        assertEquals(2, SettingsCommandId.LIGHT_MODE.readInt(state))
    }

    @Test
    fun `readInt returns ledMode from WheelState`() {
        val state = WheelState(ledMode = 5)
        assertEquals(5, SettingsCommandId.LED_MODE.readInt(state))
    }

    @Test
    fun `readInt returns rollAngle from WheelState`() {
        val state = WheelState(rollAngle = 2)
        assertEquals(2, SettingsCommandId.ROLL_ANGLE_MODE.readInt(state))
    }

    @Test
    fun `readInt returns cutoutAngle from FRAME_07 bytes 4-5`() {
        val state = WheelState(cutoutAngle = 70)
        assertEquals(70, SettingsCommandId.CUTOUT_ANGLE.readInt(state))

        val stateUnknown = WheelState(cutoutAngle = -1)
        assertNull(SettingsCommandId.CUTOUT_ANGLE.readInt(stateUnknown))
    }

    @Test
    fun `readInt returns null for write-only commands`() {
        val state = WheelState()
        assertNull(SettingsCommandId.MAX_SPEED.readInt(state))
        assertNull(SettingsCommandId.SPEAKER_VOLUME.readInt(state))
        assertNull(SettingsCommandId.CALIBRATE.readInt(state))
    }

    @Test
    fun `readBool returns LED state from ledMode`() {
        val stateOn = WheelState(ledMode = 3)
        assertEquals(true, SettingsCommandId.LED.readBool(stateOn))

        val stateOff = WheelState(ledMode = 0)
        assertEquals(false, SettingsCommandId.LED.readBool(stateOff))

        val stateUnknown = WheelState(ledMode = -1)
        assertNull(SettingsCommandId.LED.readBool(stateUnknown))
    }

    // ==================== Slider Visibility Gating ====================

    @Test
    fun `NinebotZ alarm speed sliders have visibleWhen set to alarm toggle`() {
        val alarms = WheelSettingsConfig.sections(WheelType.NINEBOT_Z)[3]
        val sliders = alarms.controls.filterIsInstance<ControlSpec.Slider>()
        assertEquals(3, sliders.size)
        assertEquals(SettingsCommandId.ALARM_ENABLED_1, sliders[0].visibleWhen)
        assertEquals(SettingsCommandId.ALARM_ENABLED_2, sliders[1].visibleWhen)
        assertEquals(SettingsCommandId.ALARM_ENABLED_3, sliders[2].visibleWhen)
    }

    @Test
    fun `NinebotZ limited speed slider has visibleWhen set to limited mode`() {
        val speedLimit = WheelSettingsConfig.sections(WheelType.NINEBOT_Z)[4]
        assertEquals("Speed Limit", speedLimit.title)
        val slider = speedLimit.controls[1] as ControlSpec.Slider
        assertEquals(SettingsCommandId.LIMITED_MODE, slider.visibleWhen)
        assertEquals(0, slider.min)
        assertEquals(65, slider.max)
    }

    @Test
    fun `Sliders without visibleWhen have null gating`() {
        val gotwaySections = WheelSettingsConfig.sections(WheelType.GOTWAY)
        val cutoutAngle = gotwaySections[1].controls[2] as ControlSpec.Slider
        assertNull(cutoutAngle.visibleWhen)

        val beeperVolume = gotwaySections[2].controls[0] as ControlSpec.Slider
        assertNull(beeperVolume.visibleWhen)
    }

    // ==================== Gotway Readback vs Write-Only (Begode App BLE Capture) ====================
    // The 0x04 telemetry frame provides readback for most settings.
    // Beeper volume is read from FRAME_00 byte 17 (confirmed via BLE capture).

    @Test
    fun `Gotway settings with readback from 0x04 frame`() {
        // These settings are decoded from the 0x04 telemetry frame
        val state = WheelState(
            pedalsMode = 0,   // Hard (Begode "Strong")
            lightMode = 1,    // On
            ledMode = 0,      // LED0
            rollAngle = 2     // High
        )
        assertEquals(0, SettingsCommandId.PEDALS_MODE.readInt(state))
        assertEquals(1, SettingsCommandId.LIGHT_MODE.readInt(state))
        assertEquals(0, SettingsCommandId.LED_MODE.readInt(state))
        assertEquals(2, SettingsCommandId.ROLL_ANGLE_MODE.readInt(state))
    }

    @Test
    fun `Gotway beeperVolume defaults to unknown before readback`() {
        val state = WheelState()
        assertNull(SettingsCommandId.BEEPER_VOLUME.readInt(state),
            "beeperVolume should be null when -1 (unknown)")
    }

    @Test
    fun `Gotway beeperVolume readback from FRAME_00 byte 17`() {
        val state = WheelState(beeperVolume = 3)
        assertEquals(3, SettingsCommandId.BEEPER_VOLUME.readInt(state))
    }

    @Test
    fun `Gotway settings map covers all Begode app settings`() {
        // Begode app screenshot settings → our WheelSettingsConfig controls
        val sections = WheelSettingsConfig.sections(WheelType.GOTWAY)
        val allCommands = sections.flatMap { s -> s.controls.map { it.commandId } }

        // Settings present in both Begode app and our config
        assertTrue(SettingsCommandId.PEDALS_MODE in allCommands, "Mode setting (Strong/Medium/Soft)")
        assertTrue(SettingsCommandId.ROLL_ANGLE_MODE in allCommands, "Maximum allowed tilt angle")
        assertTrue(SettingsCommandId.CUTOUT_ANGLE in allCommands, "Left and right tilt angle closed")
        assertTrue(SettingsCommandId.LIGHT_MODE in allCommands, "Light Mode")
        assertTrue(SettingsCommandId.LED_MODE in allCommands, "LED setting")
        assertTrue(SettingsCommandId.BEEPER_VOLUME in allCommands, "Volume setting")
        // New Begode extended settings
        assertTrue(SettingsCommandId.PEDAL_TILT in allCommands, "Pedal Tilt (Angle 5)")
        assertTrue(SettingsCommandId.WEAK_MAGNETISM in allCommands, "Weak Magnetism")
        assertTrue(SettingsCommandId.EXTENDED_ROLL_ANGLE in allCommands, "Extended Roll Angle (Angle 10)")
        assertTrue(SettingsCommandId.PLATE_PROTECTION in allCommands, "Plate Protection")
        assertTrue(SettingsCommandId.POWER_ALARM in allCommands, "Power Alarm")
    }

    // ==================== Gotway Light Mode Options Match iOS ====================

    @Test
    fun `Gotway Light Mode has Off, On, Strobe options`() {
        val lighting = WheelSettingsConfig.sections(WheelType.GOTWAY)[0]
        val lightMode = lighting.controls[0] as ControlSpec.Picker
        assertEquals(listOf("Off", "On", "Strobe"), lightMode.options)
    }

    @Test
    fun `Gotway LED Mode has 10 options (0-9)`() {
        val lighting = WheelSettingsConfig.sections(WheelType.GOTWAY)[0]
        val ledMode = lighting.controls[1] as ControlSpec.Picker
        assertEquals(10, ledMode.options.size)
    }

    // ==================== NinebotZ Lighting ====================

    @Test
    fun `NinebotZ Lighting has 4 controls - Headlight, DRL, Tail Light, LED Mode`() {
        val lighting = WheelSettingsConfig.sections(WheelType.NINEBOT_Z)[0]
        assertEquals(4, lighting.controls.size)

        assertTrue(lighting.controls[0] is ControlSpec.Toggle)
        assertEquals("Headlight", (lighting.controls[0] as ControlSpec.Toggle).label)

        assertTrue(lighting.controls[1] is ControlSpec.Toggle)
        assertEquals("DRL", (lighting.controls[1] as ControlSpec.Toggle).label)

        assertTrue(lighting.controls[2] is ControlSpec.Toggle)
        assertEquals("Tail Light", (lighting.controls[2] as ControlSpec.Toggle).label)

        val ledMode = lighting.controls[3] as ControlSpec.Picker
        assertEquals(8, ledMode.options.size) // "Off" + "Type 1" through "Type 7"
        assertEquals("Off", ledMode.options[0])
        assertEquals("Type 1", ledMode.options[1])
    }

    // ==================== InMotionV2 Audio ====================

    @Test
    fun `InMotionV2 Audio has Speaker Volume, Mute, Sound Wave, Sound Wave Sensitivity`() {
        val audio = WheelSettingsConfig.sections(WheelType.INMOTION_V2)[4]
        assertEquals("Audio", audio.title)
        assertEquals(4, audio.controls.size)

        val volume = audio.controls[0] as ControlSpec.Slider
        assertEquals("Speaker Volume", volume.label)
        assertEquals(0, volume.min)
        assertEquals(100, volume.max)

        val mute = audio.controls[1] as ControlSpec.Toggle
        assertEquals("Mute", mute.label)
        assertEquals(SettingsCommandId.MUTE, mute.commandId)

        val soundWave = audio.controls[2] as ControlSpec.Toggle
        assertEquals("Sound Wave", soundWave.label)
        assertEquals(SettingsCommandId.SOUND_WAVE, soundWave.commandId)

        val soundWaveSens = audio.controls[3] as ControlSpec.Slider
        assertEquals("Sound Wave Sensitivity", soundWaveSens.label)
        assertEquals(SettingsCommandId.SOUND_WAVE, soundWaveSens.visibleWhen)
    }

    // ==================== InMotionV2 Lighting ====================

    @Test
    fun `InMotionV2 Lighting has 8 controls including extended lighting features`() {
        val lighting = WheelSettingsConfig.sections(WheelType.INMOTION_V2)[0]
        assertEquals(8, lighting.controls.size)

        // Original 3 controls
        assertTrue(lighting.controls[0] is ControlSpec.Toggle) // Headlight
        assertTrue(lighting.controls[1] is ControlSpec.Toggle) // DRL

        val brightness = lighting.controls[2] as ControlSpec.Slider
        assertEquals("Brightness", brightness.label)
        assertEquals(0, brightness.min)
        assertEquals(100, brightness.max)
        assertEquals("%", brightness.unit)

        // Extended lighting controls
        val autoHeadlight = lighting.controls[3] as ControlSpec.Toggle
        assertEquals("Auto Headlight", autoHeadlight.label)
        assertEquals(SettingsCommandId.AUTO_HEADLIGHT, autoHeadlight.commandId)

        val logoBrightness = lighting.controls[4] as ControlSpec.Slider
        assertEquals("Logo Light Brightness", logoBrightness.label)
        assertEquals(SettingsCommandId.LOGO_LIGHT_BRIGHTNESS, logoBrightness.commandId)

        val tailLight = lighting.controls[5] as ControlSpec.Picker
        assertEquals("Tail Light Mode", tailLight.label)
        assertEquals(listOf("Off", "Highlight", "Hazard"), tailLight.options)

        val turnSignal = lighting.controls[6] as ControlSpec.Picker
        assertEquals("Turn Signal Mode", turnSignal.label)
        assertEquals(5, turnSignal.options.size)

        val lightEffect = lighting.controls[7] as ControlSpec.Toggle
        assertEquals("Light Effects", lightEffect.label)
        assertEquals(SettingsCommandId.LIGHT_EFFECT, lightEffect.commandId)
    }
}
