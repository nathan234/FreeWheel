# InMotion Lorin Protocol Reference

Covers all Lorin-family EUCs.

## Protocol Basics

- **Header**: `[0xAA, 0xAA]`
- **Escape byte**: `0xA5` — doubled in transit (`0xA5 0xA5` = literal `0xA5`)
- **Checksum**: XOR of bytes from flags to end of data
- **Response bit**: Response frames have command byte OR'd with `0x80`
- **XOR encryption key**: `[0xA2, 0x3C, 0xBC, 0x5F, 0x11, 0x4B, 0xA1, 0xD5, 0x42, 0x26, 0xE6, 0x39, 0x9E, 0xB3, 0x2F, 0xD1]`
- **Byte order**: Little-endian for multi-byte fields

## Model IDs

| Model | Series | Type | Full ID | Max Speed | Cells |
|-------|--------|------|---------|-----------|-------|
| V11 | 6 | 1 | 61 | 60 km/h | 20 |
| V11Y | 6 | 2 | 62 | 120 km/h | 20 |
| V12HS | 7 | 1 | 71 | 70 km/h | 24 |
| V12HT | 7 | 2 | 72 | 70 km/h | 24 |
| V12PRO | 7 | 3 | 73 | 70 km/h | 24 |
| V12S | 11 | 1 | 111 | 120 km/h | 20 |
| V13 | 8 | 1 | 81 | 120 km/h | 30 |
| V13PRO | 8 | 2 | 82 | 120 km/h | 30 |
| V14g | 9 | 1 | 91 | 120 km/h | 32 |
| V14s | 9 | 2 | 92 | 120 km/h | 32 |
| E25 (internally V9 series) | 12 | 1 | 121 | 120 km/h protocol limit | 20 |
| P6 | 13 | 1 | 131 | 150 km/h | 56 |

## Real-Time Data Layouts

All models share a common telemetry structure with model-specific byte offsets and field availability.

### Common Fields (all models)

| Field | Type | Units | Storage |
|-------|------|-------|---------|
| voltage | Uint16LE | mV | voltage |
| current | Int16LE | mA (signed) | current |
| speed | Int16LE | km/h × 100 | speed |
| torque | Int16LE | Nm × 100 | torque |
| pwm/outputRate | Int16LE | % × 100 | output |
| motorPower | Int16LE | W | motorPower |
| batteryPower | Int16LE | W | power |
| pitchAngle | Int16LE | ° × 100 | angle |
| rollAngle | Int16LE | ° × 100 | roll |
| mileage | Uint32LE | meters | wheelDistance |
| batteryLevel | Uint16LE | % | batteryLevel |
| speedLimit | Int16LE | km/h × 100 | speedLimit |
| currentLimit | Int16LE | A × 100 | currentLimit |
| mosTemp | Uint8 | °C (+ 80 - 256) | temperature |
| motorTemp | Uint8 | °C (+ 80 - 256) | temperature2 |
| cpuTemp | Uint8 | °C (+ 80 - 256) | cpuTemp |
| imuTemp | Uint8 | °C (+ 80 - 256) | imuTemp |

### Model-Specific Telemetry Extensions

| Field | V11 | V12 | V13 | V14 | P6 |
|-------|-----|-----|-----|-----|-----|
| batteryTemp | ✓ | ✓ | ✓ | ✓ | ✓ |
| boardTemp | ✓ | ✓ | ✓ | ✓ | ✓ |
| lampTemp | ✓ | ✓ | — | — | ✓ |
| fanState | ✓ | — | — | — | — |
| liftedState | ✓ | ✓ | ✓ | ✓ | ✓ |
| chargeState | ✓ | ✓ | ✓ | ✓ | ✓ |
| brakeState | ✓ | ✓ | ✓ | ✓ | ✓ |
| slowDownState | ✓ | ✓ | ✓ | ✓ | ✓ |
| backupBatteryState | ✓ | ✓ | — | — | — |
| dfuState | ✓ | ✓ | — | — | — |
| remainderRange | ✓ | ✓ | ✓ | ✓ | ✓ |
| dynamicSpeedLimit | ✓ | ✓ | ✓ | ✓ | ✓ |
| dynamicCurrentLimit | ✓ | ✓ | ✓ | ✓ | ✓ |
| batteryLevelForRide | ✓ | ✓ | ✓ | ✓ | ✓ |
| estimatedTotalMileage | ✓ | ✓ | ✓ | ✓ | ✓ |
| pitchAimAngle | ✓ | ✓ | — | — | — |
| chargeVoltage | — | — | — | — | ✓ |
| chargeCurrent | — | — | — | — | ✓ |
| tirePressure | — | — | — | — | ✓ |
| batteryMaxCellTemp | — | — | — | — | ✓ |
| bmsTemp | — | — | — | — | ✓ |
| lteSingle | — | — | — | — | ✓ |
| riskBehaviourState | — | — | — | ✓ | — |
| lowTempLowBatteryState | — | — | — | ✓ | — |
| lowBeam/highBeam | — | ✓ | — | — | — |
| pcMode/mcMode | — | ✓ | — | — | — |

### Layout Byte Offsets (minSize for REAL_TIME_INFO)

| Layout | Min Size | Models |
|--------|----------|--------|
| V11_1_4 | 57 | V11 (fw ≥ 1.4) |
| V12 | 60 | V12HS, V12HT, V12PRO |
| V13 | 77 | V13, V13PRO |
| V14 | 78 | V14g, V14s |
| EXTENDED | 78 | V11Y, E25, V12S, P6 |

## Settings by Model

### All Models (BASE_COMMANDS)

| Setting | Cmd ID | Payload | Notes |
|---------|--------|---------|-------|
| Headlight | 0x50 | [0x50, on/off] | Model-dependent sub-payload |
| Lock | 0x31 | [0x31, 0/1] | |
| Power off | 0x81 | [0x81] | Dangerous |
| Calibrate | 0x42 | [0x42] | Dangerous |
| Handle button | 0x2E | [0x2E, !val] | Inverted logic |
| Ride mode | 0x23 | [0x23, mode] | |
| Speaker volume | 0x26 | [0x26, vol] | 0-100 |
| Pedal tilt | 0x22 | [0x22, lo, hi] | Signed short LE ÷ 10 |
| Pedal sensitivity | 0x25 | [0x25, val, 0x64] | 0-100 |
| Max speed | 0x21 | [0x21, lo, hi] | Short LE × 100 |
| Transport mode | 0x32 | [0x32, 0/1] | |
| DRL | 0x2D | [0x2D, 0/1] | V11Y/V12/V13/V14 |
| DRL (alt) | 0x44 | [0x44, 0/1] | E25 (P6 uses 0x4E) |
| Go-home mode | 0x37 | [0x37, 0/1] | V11Y/E25/V12S |
| Fancier mode | 0x24 | [0x24, 0/1] | |
| Mute | 0x2C | [0x2C, !val] | Inverted |
| Light brightness | 0x2B | [0x2B, val] | |
| Standby time | 0x28 | [0x28, lo, hi] | Short LE |

### V11-Specific

| Setting | Cmd ID | Notes |
|---------|--------|-------|
| Fan quiet mode | 0x38 | V11Y only |
| Fan on/off | 0x53 | fw ≥ 1.4 (0x43 for < 1.4) |
| Speeding braking sensitivity | 0x3E | |

### V12-Specific

| Setting | Cmd ID | Notes |
|---------|--------|-------|
| Auto headlight | 0x2F | |
| Screen auto-off | 0x3D | |
| Motor sound sensitivity | 0x38 | Reuses fan quiet mode ID |
| Speed alarms | 0x3E, 0x40, 0x42 | Split riding modes |
| Low beam brightness | — | Part of headlight cmd |
| High beam brightness | — | Part of headlight cmd |
| Auto beam switch speed | — | genSetAutoLowHighBeamSwitchSpeedThrMsg |
| Light effect mode | — | genRequestCurrentLightEffectIdMsg |
| Turn light state | — | |

### V13-Specific

| Setting | Cmd ID | Notes |
|---------|--------|-------|
| Auto headlight | 0x2F | |
| Remainder range estimate | 0x3D | 61 |
| Safe speed limit | 0x44 | 68 |
| Berm angle mode | 0x45 | 69 — **unique to V13** |
| Light effect mode | 0x2D | 45 |
| Fan quiet mode | 0xAF | In settings byte |
| Screen auto-off | 0xBF | In settings byte |

### V14-Specific (extends V13)

| Setting | Cmd ID | Notes |
|---------|--------|-------|
| Two battery mode | 0x48 | 72 — **unique to V14** |
| Speed unit | 0x47 | 71 — **unique to V14** |
| Max speed | 0x21 | Extended: 4-byte payload |
| Play sound | 0xC8 | [0xC8, 4, lo, hi] sound ID |
| All V13 settings | — | Inherited |

### P6-Specific (Extended Protocol)

P6 uses Flag.EXTENDED (0x16) with sub-command 0x21 for most operations.

| Setting | Sub-cmd | Notes |
|---------|---------|-------|
| Safe speed limit | via 0x21 | Extended protocol |
| Screen auto-off | via 0x21 | Extended protocol |
| Logo light brightness | via 0x21 | **Unique to P6** |
| Tail light mode | via 0x21 | Extended protocol |
| Turn signal mode | via 0x21 | **Unique to P6** |
| Charge limit | — | **Unique to P6** |
| Tire pressure monitoring | — | Read-only telemetry |
| LTE signal | — | Read-only telemetry |

## Commands Not Yet Implemented in FreeWheel

These command IDs are known from the reverse engineering but not yet wired in the decoder:

| SettingsCommandId | Cmd ID | Models | Source |
|-------------------|--------|--------|--------|
| BERM_ANGLE_MODE | 0x45 | V13 | V13 factory |
| BERM_ANGLE | — | V13 | Needs capture |
| TURNING_SENSITIVITY | — | Unknown | Needs capture |
| ONE_PEDAL_MODE | — | Unknown | Needs capture |
| SPEEDING_BRAKING_MODE | — | V11, V12 | V11: 0x3E |
| SPEEDING_BRAKING_ANGLE | — | V11, V12 | Needs capture |
| SOUND_WAVE | — | V11, V12 | Needs capture |
| SOUND_WAVE_SENSITIVITY | — | V12 | Needs capture |
| SAFE_SPEED_LIMIT | 0x44 | V13 | V13 factory |
| BACKWARD_OVERSPEED_ALERT | — | Unknown | Needs capture |
| TAIL_LIGHT_MODE | — | V12, V13, V14 | Needs capture |
| TURN_SIGNAL_MODE | — | P6, V14? | P6 extended |
| LOGO_LIGHT_BRIGHTNESS | — | P6 | P6 extended |
| LIGHT_EFFECT | — | V12 | V12 factory |
| LIGHT_EFFECT_MODE | 0x2D | V13 | V13 factory |
| TWO_BATTERY_MODE | 0x48 | V14 | V14 factory |
| LOW_BATTERY_SAFE_MODE | — | V11, V13 | In settings |
| SPIN_KILL | — | Unknown | Needs capture |
| CRUISE | — | Unknown | Needs capture |
| LOAD_DETECT | — | V11 | In settings |
| CHARGE_LIMIT | — | P6 | P6 extended |
| SPEED_UNIT | 0x47 | V14 | V14 factory |

## BMS Information

### Cell Counts by Model

| Model | Cells | Battery Packs |
|-------|-------|---------------|
| V11/V11Y | 20 | 1 |
| V12 family | 24 | 1 |
| V13/V13PRO | 30 | 2 |
| V14g/V14s | 32 | Up to 4 |
| E25 | 20 | 2 |
| V12S | 20 | 1 |
| P6 | 56 | 1 |

### General Battery Summary (`flag 0x14`, command `0x05`)

The response contains two independent pack summaries. Standard Lorin packs are seven
bytes each: `Uint16LE voltage`, `Int16LE current`, `Int8 temperature` (add 40 °C), and
two status bytes. Two additional signed 16-bit aggregate charging fields follow the
standard pair, for 18 bytes total.

E25 uses its manufacturer-specific 14-byte layout: each seven-byte pack contains
`Uint16LE voltage`, separate `Int16LE chargeCurrent` and `Int16LE dischargeCurrent`, and
one status byte. FreeWheel normalizes this to positive discharge current with
`dischargeCurrent - chargeCurrent`.

### BMS Commands (FullBMSFeature models: V11Y, V13, V14)

| Command | Purpose |
|---------|---------|
| genRequestBMSRealTimeInfoMsg | Real-time battery data |
| genRequestBMSFixedInfoMsg | Fixed battery info |
| genRequestBMSDateMsg | BMS date/timestamp |
| genRequestBMSCellsVoltageInfoMsg | Per-cell voltages |
| genRequestBMSLogCountMsg | BMS log entry count |
| genRequestBMSLogMsg(index) | Read BMS log entry |

### V14 BMS Extensions

- `V14BMSWarning.largeVoltageDiffOnCharge` — large cell voltage difference during charging
- `V14BMSProtection.largeVoltageDiffOnCharge` — protection trigger for same
- Quad battery monitoring (`_battery1` through `_battery4`)

## Statistics

All models share the same statistics structure:

### Total Statistics
- mileage (Uint32LE, meters)
- energy (Int32LE)
- powerOnTime (Int32LE)
- recovery (Int32LE)
- rideTime (Int32LE)

### Per-Ride History
- index, date, maxSpeed, maxPower, maxMotorPower
- maxMosTemp, maxMotorTemp, maxBatteryTemp, maxBoardTemp
- mileage, energy, recovery, rideTime, powerOnTime

## Firmware Version Variants

Several models change their protocol layout based on firmware version:

| Model | Version | Changes |
|-------|---------|---------|
| V11 | < 1.4 | Old protocol, different byte layout |
| V11 | ≥ 1.4 | Standard layout, fan cmd 0x53 |
| V11 | ≥ 1.31 | Additional real-time state fields |
| V11 | ≥ 1.37 | Extended settings, different speed limit encoding |
| V12 | < 1.4.24 | V12CmdV1 |
| V12 | ≥ 1.4.24 | V12CmdV2 |
| V13 | < 2.0.22 | Default settings layout |
| V13 | ≥ 2.0.22 | V1.4 settings variant |
| V13 | ≥ 2.0.23 | V1.5 settings variant |

## Protocol Families (Non-Lorin)

For completeness, InMotion has three protocol generations. FreeWheel's LorinDecoder handles Lorin only. The older protocols are handled by InMotionDecoder.

| Family | Models | Protocol |
|--------|--------|----------|
| Elephant | V5 (early) | Simplest, fixed-length frames |
| EzCan | V5, V8, V10 | Mid-tier, model sub-variants |
| Lorin | V11+, P6 | Newest, model-specific layouts, capability-driven |

## V6/E-Series (Lorin variants)

These smaller wheels use the Lorin protocol but with reduced feature sets:

| Model | Settings Count | Unique Features |
|-------|---------------|-----------------|
| V6 | 20 | Minimal (no fan, no DRL) |
| E10 | 20 | Same as V6 |
| E20 | 20 | Same as V6 |
| E25 | Dedicated 42-byte payload | Dual battery, dedicated status packing, USB mode |

The manufacturer schema adds fields including `maxChargeBatteryLevel`,
`maxDcOutputBatteryLevel`, `usbMode`, `drlState`, `autoLightState`, `lightBrightness`,
automatic-light thresholds/brightness, and `autoScreenOff`. FreeWheel currently surfaces
the subset represented by `WheelSettings.Lorin`.
