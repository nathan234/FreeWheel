package org.freewheel.utils

import android.content.Context
import org.freewheel.AppConfig
import org.freewheel.WheelData
import org.freewheel.core.protocol.*
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Parity tests comparing legacy adapter command bytes to KMP decoder buildCommand() bytes.
 * Each test captures the bytes sent by the legacy adapter and compares byte-for-byte
 * against the bytes produced by the KMP decoder for the same command.
 */
class CommandParityTest {

    private lateinit var data: WheelData
    private val appConfig = mockkClass(AppConfig::class, relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)

    // Legacy adapters
    private lateinit var kingsongAdapter: KingsongAdapter
    private lateinit var gotwayAdapter: GotwayAdapter
    private lateinit var veteranAdapter: VeteranAdapter
    private lateinit var inmotionAdapter: InMotionAdapter
    private lateinit var inmotionV2Adapter: InMotionAdapterV2

    // KMP decoders
    private val kingsongDecoder = KingsongDecoder()
    private val gotwayDecoder = GotwayDecoder()
    private val veteranDecoder = VeteranDecoder()
    private val inmotionDecoder = InMotionDecoder()
    private val inmotionV2Decoder = InMotionV2Decoder()

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single { appConfig }
                single { mockContext }
            })
        }
        data = spyk(WheelData())
        mockkStatic(WheelData::class)
        every { WheelData.getInstance() } returns data
        // Make bluetoothCmd return true so legacy adapters don't bail out
        every { data.bluetoothCmd(any<ByteArray>()) } returns true

        // Mock Handler for Gotway (uses postDelayed for multi-step commands)
        mockkConstructor(android.os.Handler::class)
        every { anyConstructed<android.os.Handler>().postDelayed(any(), any()) } answers {
            // Execute the runnable immediately for testing
            (firstArg<Runnable>()).run()
            true
        }

        kingsongAdapter = KingsongAdapter()
        gotwayAdapter = GotwayAdapter()
        veteranAdapter = VeteranAdapter()
        inmotionAdapter = InMotionAdapter()
        inmotionV2Adapter = InMotionAdapterV2()
    }

    @After
    fun tearDown() {
        unmockkAll()
        stopKoin()
    }

    // ==================== Helper ====================

    /**
     * Capture all byte arrays sent via bluetoothCmd during the given action.
     */
    private fun captureBluetoothCmds(action: () -> Unit): List<ByteArray> {
        val captured = mutableListOf<ByteArray>()
        every { data.bluetoothCmd(capture(captured)) } returns true
        action()
        return captured
    }

    /**
     * Get the first SendBytes data from a buildCommand result.
     */
    private fun firstSendBytes(commands: List<WheelCommand>): ByteArray {
        assertThat(commands).isNotEmpty()
        return (commands[0] as WheelCommand.SendBytes).data
    }

    // ==================== Kingsong Command Parity ====================

    @Test
    fun `Kingsong wheelBeep matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.wheelBeep() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.Beep))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong setLightMode 0 off matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.setLightMode(0) }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.SetLightMode(0)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong setLightMode 1 on matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.setLightMode(1) }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.SetLightMode(1)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong setLightMode 2 strobe matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.setLightMode(2) }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.SetLightMode(2)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong updatePedalsMode matches KMP`() {
        for (mode in 0..2) {
            val legacyBytes = captureBluetoothCmds { kingsongAdapter.updatePedalsMode(mode) }
            val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.SetPedalsMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Kingsong wheelCalibration matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.wheelCalibration() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.Calibrate))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong powerOff matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.powerOff() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.PowerOff))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong updateLedMode matches KMP`() {
        for (mode in 0..5) {
            val legacyBytes = captureBluetoothCmds { kingsongAdapter.updateLedMode(mode) }
            val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.SetLedMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Kingsong updateStrobeMode matches KMP`() {
        for (mode in 0..3) {
            val legacyBytes = captureBluetoothCmds { kingsongAdapter.updateStrobeMode(mode) }
            val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.SetStrobeMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Kingsong requestAlarmSettings matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestAlarmSettingsAndMaxSpeed() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestAlarmSettings))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong requestBms1Serial matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestBms1Serial() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestBmsData(1, 0)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong requestBms2Serial matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestBms2Serial() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestBmsData(2, 0)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong requestBms1MoreData matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestBms1MoreData() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestBmsData(1, 1)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong requestBms2MoreData matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestBms2MoreData() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestBmsData(2, 1)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong requestBms1Firmware matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestBms1Firmware() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestBmsData(1, 2)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong requestBms2Firmware matches KMP`() {
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.requestBms2Firmware() }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(WheelCommand.RequestBmsData(2, 2)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Kingsong setKingsongAlarms matches KMP`() {
        // Use Kingsong's updateKSAlarmAndSpeed via the direct KMP equivalent
        // Legacy: set alarm speeds then call updateKSAlarmAndSpeed
        // We set internal state first then capture
        kingsongAdapter.updateKSAlarm1(30)
        kingsongAdapter.updateKSAlarm2(35)
        kingsongAdapter.updateKSAlarm3(40)

        // Now capture the full command with all values set
        val legacyBytes = captureBluetoothCmds { kingsongAdapter.updateMaxSpeed(45) }
        val kmpBytes = firstSendBytes(kingsongDecoder.buildCommand(
            WheelCommand.SetKingsongAlarms(30, 35, 40, 45)
        ))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    // ==================== Gotway Command Parity ====================

    @Test
    fun `Gotway wheelBeep matches KMP`() {
        val legacyBytes = captureBluetoothCmds { gotwayAdapter.wheelBeep() }
        val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.Beep))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Gotway setLightMode 0 off matches KMP`() {
        val legacyBytes = captureBluetoothCmds { gotwayAdapter.setLightMode(0) }
        val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetLightMode(0)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Gotway setLightMode 1 on matches KMP`() {
        val legacyBytes = captureBluetoothCmds { gotwayAdapter.setLightMode(1) }
        val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetLightMode(1)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Gotway setLightMode 2 strobe matches KMP`() {
        val legacyBytes = captureBluetoothCmds { gotwayAdapter.setLightMode(2) }
        val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetLightMode(2)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Gotway updatePedalsMode matches KMP`() {
        for (mode in 0..3) {
            val legacyBytes = captureBluetoothCmds { gotwayAdapter.updatePedalsMode(mode) }
            val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetPedalsMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Gotway setMilesMode matches KMP`() {
        val legacyMilesBytes = captureBluetoothCmds { gotwayAdapter.setMilesMode(true) }
        val kmpMilesBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetMilesMode(true)))
        assertThat(kmpMilesBytes).isEqualTo(legacyMilesBytes[0])

        val legacyKmBytes = captureBluetoothCmds { gotwayAdapter.setMilesMode(false) }
        val kmpKmBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetMilesMode(false)))
        assertThat(kmpKmBytes).isEqualTo(legacyKmBytes[0])
    }

    @Test
    fun `Gotway setRollAngleMode matches KMP`() {
        for (mode in 0..2) {
            val legacyBytes = captureBluetoothCmds { gotwayAdapter.setRollAngleMode(mode) }
            val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetRollAngleMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Gotway updateAlarmMode matches KMP`() {
        for (mode in 0..3) {
            val legacyBytes = captureBluetoothCmds { gotwayAdapter.updateAlarmMode(mode) }
            val kmpBytes = firstSendBytes(gotwayDecoder.buildCommand(WheelCommand.SetAlarmMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Gotway wheelCalibration matches KMP`() {
        // Legacy sends "c" then delayed "y"
        val legacyBytes = captureBluetoothCmds { gotwayAdapter.wheelCalibration() }
        val kmpCommands = gotwayDecoder.buildCommand(WheelCommand.Calibrate)
        assertThat(kmpCommands.size).isAtLeast(2)
        val kmpFirst = (kmpCommands[0] as WheelCommand.SendBytes).data
        val kmpSecond = (kmpCommands[1] as WheelCommand.SendDelayed).data
        assertThat(kmpFirst).isEqualTo(legacyBytes[0])
        assertThat(kmpSecond).isEqualTo(legacyBytes[1])
    }

    // ==================== Veteran Command Parity ====================

    @Test
    fun `Veteran wheelBeep v1 matches KMP`() {
        // Default mVer is 0 (< 3), so uses "b"
        val legacyBytes = captureBluetoothCmds { veteranAdapter.wheelBeep() }
        val kmpBytes = firstSendBytes(veteranDecoder.buildCommand(WheelCommand.Beep))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Veteran setLightState on matches KMP`() {
        val legacyBytes = captureBluetoothCmds { veteranAdapter.setLightState(true) }
        val kmpBytes = firstSendBytes(veteranDecoder.buildCommand(WheelCommand.SetLight(true)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Veteran setLightState off matches KMP`() {
        val legacyBytes = captureBluetoothCmds { veteranAdapter.setLightState(false) }
        val kmpBytes = firstSendBytes(veteranDecoder.buildCommand(WheelCommand.SetLight(false)))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    @Test
    fun `Veteran updatePedalsMode matches KMP`() {
        for (mode in 0..2) {
            val legacyBytes = captureBluetoothCmds { veteranAdapter.updatePedalsMode(mode) }
            val kmpBytes = firstSendBytes(veteranDecoder.buildCommand(WheelCommand.SetPedalsMode(mode)))
            assertThat(kmpBytes).isEqualTo(legacyBytes[0])
        }
    }

    @Test
    fun `Veteran resetTrip matches KMP`() {
        val legacyBytes = captureBluetoothCmds { veteranAdapter.resetTrip() }
        val kmpBytes = firstSendBytes(veteranDecoder.buildCommand(WheelCommand.ResetTrip))
        assertThat(kmpBytes).isEqualTo(legacyBytes[0])
    }

    // ==================== InMotion V1 Command Parity ====================
    // InMotion V1 uses settingCommand instead of bluetoothCmd, so we compare
    // legacy CANMessage.writeBuffer() directly against KMP buildCommand() output.

    @Test
    fun `InMotion wheelBeep matches KMP`() {
        val legacyBytes = InMotionAdapter.CANMessage.wheelBeep().writeBuffer()
        val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.Beep))
        assertThat(kmpBytes).isEqualTo(legacyBytes)
    }

    @Test
    fun `InMotion setLightState matches KMP`() {
        val legacyOn = InMotionAdapter.CANMessage.setLight(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetLight(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapter.CANMessage.setLight(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetLight(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotion setLedState matches KMP`() {
        val legacyOn = InMotionAdapter.CANMessage.setLed(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetLed(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapter.CANMessage.setLed(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetLed(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotion powerOff matches KMP`() {
        val legacyBytes = InMotionAdapter.CANMessage.powerOff().writeBuffer()
        val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.PowerOff))
        assertThat(kmpBytes).isEqualTo(legacyBytes)
    }

    @Test
    fun `InMotion wheelCalibration matches KMP`() {
        val legacyBytes = InMotionAdapter.CANMessage.wheelCalibration().writeBuffer()
        val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.Calibrate))
        assertThat(kmpBytes).isEqualTo(legacyBytes)
    }

    @Test
    fun `InMotion setHandleButtonState matches KMP`() {
        val legacyOn = InMotionAdapter.CANMessage.setHandleButton(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetHandleButton(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapter.CANMessage.setHandleButton(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetHandleButton(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotion setRideMode matches KMP`() {
        val legacyOn = InMotionAdapter.CANMessage.setRideMode(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetRideMode(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapter.CANMessage.setRideMode(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetRideMode(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotion setSpeakerVolume matches KMP`() {
        for (vol in listOf(0, 5, 10, 50, 100)) {
            val legacyBytes = InMotionAdapter.CANMessage.setSpeakerVolume(vol).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetSpeakerVolume(vol)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotion setMaxSpeed matches KMP`() {
        for (speed in listOf(15, 25, 35, 45)) {
            val legacyBytes = InMotionAdapter.CANMessage.setMaxSpeed(speed).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetMaxSpeed(speed)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotion setPedalTilt matches KMP`() {
        for (angle in listOf(-30, 0, 15, 50)) {
            val legacyBytes = InMotionAdapter.CANMessage.setTiltHorizon(angle).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetPedalTilt(angle)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotion setPedalSensitivity matches KMP`() {
        for (sens in listOf(0, 3, 7, 10)) {
            val legacyBytes = InMotionAdapter.CANMessage.setPedalSensivity(sens).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionDecoder.buildCommand(WheelCommand.SetPedalSensitivity(sens)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    // ==================== InMotion V2 Command Parity ====================
    // InMotion V2 also uses settingCommand pattern. We compare legacy Message
    // static factory output directly against KMP buildCommand() output.

    @Test
    fun `InMotionV2 wheelBeep matches KMP`() {
        // Legacy uses playSound(0x18) for newer wheels
        val legacyBytes = InMotionAdapterV2.Message.playSound(0x18).writeBuffer()
        val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.Beep))
        assertThat(kmpBytes).isEqualTo(legacyBytes)
    }

    @Test
    fun `InMotionV2 setLight matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setLight(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetLight(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapterV2.Message.setLight(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetLight(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotionV2 setLock matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setLock(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetLock(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapterV2.Message.setLock(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetLock(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotionV2 setHandleButton matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setHandleButton(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetHandleButton(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapterV2.Message.setHandleButton(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetHandleButton(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotionV2 setRideMode matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setClassicMode(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetRideMode(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapterV2.Message.setClassicMode(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetRideMode(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotionV2 setSpeakerVolume matches KMP`() {
        for (vol in listOf(0, 5, 50, 100)) {
            val legacyBytes = InMotionAdapterV2.Message.setVolume(vol).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetSpeakerVolume(vol)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotionV2 setDrl matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setDrl(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetDrl(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapterV2.Message.setDrl(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetDrl(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotionV2 setTransportMode matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setTransportMode(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetTransportMode(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)
    }

    @Test
    fun `InMotionV2 setGoHomeMode matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setGoHome(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetGoHomeMode(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)
    }

    @Test
    fun `InMotionV2 setFancierMode matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setFancierMode(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetFancierMode(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)
    }

    @Test
    fun `InMotionV2 setMute matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setMute(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetMute(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)

        val legacyOff = InMotionAdapterV2.Message.setMute(false).writeBuffer()
        val kmpOff = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetMute(false)))
        assertThat(kmpOff).isEqualTo(legacyOff)
    }

    @Test
    fun `InMotionV2 setFanQuiet matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setQuietMode(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetFanQuiet(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)
    }

    @Test
    fun `InMotionV2 setFan matches KMP`() {
        val legacyOn = InMotionAdapterV2.Message.setFan(true).writeBuffer()
        val kmpOn = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetFan(true)))
        assertThat(kmpOn).isEqualTo(legacyOn)
    }

    @Test
    fun `InMotionV2 setLightBrightness matches KMP`() {
        for (brightness in listOf(0, 50, 100, 255)) {
            val legacyBytes = InMotionAdapterV2.Message.setLightBrightness(brightness).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetLightBrightness(brightness)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotionV2 powerOff matches KMP`() {
        val legacyBytes = InMotionAdapterV2.Message.wheelOffFirstStage().writeBuffer()
        val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.PowerOff))
        assertThat(kmpBytes).isEqualTo(legacyBytes)
    }

    @Test
    fun `InMotionV2 wheelCalibration matches KMP`() {
        val legacyBytes = InMotionAdapterV2.Message.wheelCalibration().writeBuffer()
        val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.Calibrate))
        assertThat(kmpBytes).isEqualTo(legacyBytes)
    }

    @Test
    fun `InMotionV2 setPedalTilt matches KMP`() {
        for (angle in listOf(-30, 0, 15, 50)) {
            val legacyBytes = InMotionAdapterV2.Message.setPedalTilt(angle).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetPedalTilt(angle)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotionV2 setPedalSensitivity matches KMP`() {
        for (sens in listOf(0, 3, 7, 10)) {
            val legacyBytes = InMotionAdapterV2.Message.setPedalSensivity(sens).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetPedalSensitivity(sens)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }

    @Test
    fun `InMotionV2 setMaxSpeed matches KMP`() {
        for (speed in listOf(15, 25, 35, 45)) {
            val legacyBytes = InMotionAdapterV2.Message.setMaxSpeed(speed).writeBuffer()
            val kmpBytes = firstSendBytes(inmotionV2Decoder.buildCommand(WheelCommand.SetMaxSpeed(speed)))
            assertThat(kmpBytes).isEqualTo(legacyBytes)
        }
    }
}
