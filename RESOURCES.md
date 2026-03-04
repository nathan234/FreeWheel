# Resources

A curated collection of references for EUC protocol development, open-source hardware, and the broader movement toward open PEV (personal electric vehicle) ecosystems. See also [docs/protocol-quality-assessment.md](docs/protocol-quality-assessment.md) and [docs/reference-protocol.md](docs/reference-protocol.md).

The goal of FreeWheel is to **democratize EUC connectivity** and help move the industry toward open-source hardware and firmware, similar to what VESC did for electric skateboards.

## Protocol Documentation

No EUC manufacturer publishes official BLE protocol specs. Everything below is reverse-engineered by the community.

### Community Protocol Docs

- [Gotway/KingSong Protocol Reverse-Engineering](https://forum.electricunicycle.org/topic/870-gotwaykingsong-protocol-reverse-engineering/) -- The foundational forum thread where esaj and others documented Gotway and KingSong BLE packet formats byte-by-byte.
- [Ninebot One BLE Protocol (9BMetrics)](https://www.gorina.es/9BMetrics/protocol.html) -- Paco Gorina's documentation of the Ninebot One protocol, including message format, command/response tables, and variable addresses.
- [etransport/ninebot-docs Wiki](https://github.com/etransport/ninebot-docs/wiki/protocol) -- Community wiki documenting the Ninebot serial/BLE protocol with command formats, register maps, and checksums.
- [InMotion Bluetooth Protocol Documentation](https://forum.electricunicycle.org/topic/12684-inmotion-bluetooth-protocol-documentation/) -- Forum thread discussing InMotion's BLE protocol structure.
- [Unraveling Ninebot One E+ BLE Protocol](https://forum.electricunicycle.org/topic/2686-unraveling-ninebot-one-e-ble-protocol-success/) -- The community's successful reverse engineering of the Ninebot One E+ protocol.
- [Ninebot BLE Protocol (M365)](https://github.com/CamiAlfa/M365-BLE-PROTOCOL) -- Ninebot BLE protocol as used between MiHome and Ninebot/Xiaomi scooters. Shares the same protocol family as Ninebot EUCs.

### Source Code as Protocol Reference

The most complete and up-to-date protocol documentation is the decoder source code in open-source apps:

- **FreeWheel KMP decoders** (`core/src/commonMain/.../protocol/`) -- KingsongDecoder, GotwayDecoder, VeteranDecoder, NinebotDecoder, NinebotZDecoder, InMotionDecoder, InMotionV2Decoder. The most current implementations covering all major brands.
- [EUC-Dash-ESP32](https://github.com/Pickelhaupt/EUC-Dash-ESP32) -- ESP32-based dashboard with KingSong protocol implementation in C/C++. Useful as an independent cross-reference.
- [eucWatch](https://github.com/enaon/eucWatch) -- JavaScript-based protocol implementations for KingSong, InMotion, Ninebot, Begode, and Leaperkim on NRF52xx smartwatches.
- [9BMetrics (iOS)](https://github.com/fgorina/9BMetrics) -- Open-source iOS app with Ninebot, KingSong, and Gotway protocol implementations.

## BLE Reference

### Service UUIDs Used by EUCs

| UUID | Used By | Notes |
|------|---------|-------|
| `0000FFE0-...` | KingSong, Begode/Gotway, Veteran, Ninebot, InMotion V1 | Not an official Bluetooth SIG assignment -- originates from the HM-10 BLE module's default config. De facto standard in Chinese BLE hardware. |
| `0000FFE1-...` | (characteristic on FFE0) | Read/write/notify characteristic used for all data exchange on the above wheels. |
| `0000FFF0-...` | Begode (newer firmware) | Additional service on some Begode wheels (MTen5+, etc.). |
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | InMotion V2+, NinebotZ | Nordic UART Service (NUS). |
| `6E400002-...` | (RX characteristic on NUS) | Write characteristic for sending commands. |
| `6E400003-...` | (TX characteristic on NUS) | Notify characteristic for receiving data. |

### BLE Specifications

- [Bluetooth Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/) -- Official Bluetooth SIG page for assigned UUIDs, company identifiers, and GATT definitions.
- [Nordic UART Service (NUS)](https://docs.nordicsemi.com/bundle/ncs-latest/page/nrf/libraries/bluetooth/services/nus.html) -- Official Nordic docs for the UART service used by InMotion V2+ and NinebotZ.
- [HM-10 BLE Module Datasheet](https://people.ece.cornell.edu/land/courses/ece4760/PIC32/uart/HM10/DSD%20TECH%20HM-10%20datasheet.pdf) -- The module that established 0xFFE0/0xFFE1 as the de facto standard for Chinese BLE peripherals.
- [How to Choose a UUID for Custom BLE Services](https://novelbits.io/uuid-for-custom-services-and-characteristics/) -- Explains 16-bit assigned vs 128-bit vendor-specific UUIDs.

## Open Source EUC Apps

| App | Platform | License | Notes |
|-----|----------|---------|-------|
| [FreeWheel (this project)](https://github.com/nathan234/FreeWheel) | Android + iOS | GPL-3.0 | KMP shared decoders, all major brands |
| [EUC World](https://github.com/slastowski/EucWorldAndroid) | Android | Source available | Forked from FreeWheel, most feature-rich Android app. Online service components are proprietary. |
| [9BMetrics](https://github.com/fgorina/9BMetrics) | iOS + Watch | GPL-3.0 | Ninebot, KingSong, Gotway. Includes protocol documentation. |
| [WheelDash](https://github.com/blkfribourg/WheelDash) | Garmin/Amazfit | Open source | Standalone watch app, no phone required |
| [EUC-Dash-ESP32](https://github.com/Pickelhaupt/EUC-Dash-ESP32) | ESP32 (T-Watch) | Open source | Hardware BLE dashboard |
| [eucWatch](https://github.com/enaon/eucWatch) | NRF52xx watches | Open source | JS-based, supports all brands |
| [EUCSpeedo](https://github.com/ihatechoosingusernames/EUCSpeedo) | ESP32 (T-Wristband) | Open source | Wearable speedometer |
| [Electric-Unicycle-Interface](https://github.com/T-vK/Electric-Unicycle-Interface) | Arduino | Open source | Arduino library for EUC serial/BLE |
| DarknessBot | iOS/Android | Proprietary | Most popular closed-source EUC app. [Forum thread](https://forum.electricunicycle.org/topic/6578-darknessbot-ios-app/). |

## Open Source EUC Hardware

### Complete Builds

- [EGG Electric Unicycle (wiki)](https://github.com/EGG-electric-unicycle/documentation/wiki) -- The most documented open-source EUC build: controller schematics, BOM, firmware, 3D-printable shell, BLE protocol docs. Uses STM32F103 controller.
- [EGG Firmware](https://github.com/EGG-electric-unicycle/EGG_OpenSource_EBike_firmware) -- FOC motor control firmware for the EGG unicycle. GPL-3.0.

### Controller Boards and Firmware

- [Open Source EUC Controller Board (forum)](https://forum.electricunicycle.org/topic/2146-euc-open-source-controller-board/) -- Multi-page community discussion on designing an open-source controller.
- [Open-Source EUC Motherboard (forum)](https://forum.electricunicycle.org/topic/12500-open-source-euc-motherboard/) -- Continuation of the above with newer designs.
- [Where Are We With Open Source Controller? (forum)](https://forum.electricunicycle.org/topic/27140-where-are-we-with-open-source-controller/) -- 2022 state-of-the-art discussion.
- [Hoverboard Firmware Hack](https://github.com/lucysrausch/hoverboard-firmware-hack) -- Open-source replacement firmware for STM32F103 hoverboard controllers, which are also used in many generic EUCs.
- [Hoverboard Firmware Hack (FOC)](https://github.com/palahniukvovan/hoverboard-firmware-hack-FOC) -- FOC variant with improved motor control.
- [OpenSourceEBike](https://opensourceebike.github.io/) -- Modular open-source electronics and firmware for ebikes/escooters, with components applicable to EUCs.

### Battery Management Systems

- [foxBMS](https://foxbms.org/) -- Advanced open-source BMS platform, OSHWA-certified. Supports any cell count.
- [diyBMS](https://github.com/stuartpittaway/diyBMS) -- Affordable DIY BMS for lithium cells with ESP32 monitoring.

## VESC and Open Source Motor Controllers

The [VESC project](https://vesc-project.com/) is the most successful open-source motor controller platform. It transformed DIY esk8 from proprietary to open-source and is now expanding into self-balancing vehicles.

### Core VESC

- [VESC Firmware (bldc)](https://github.com/vedderb/bldc) -- Motor control firmware. FOC and sensored/sensorless BLDC. GPL-3.0.
- [VESC Hardware](https://github.com/vedderb/bldc-hardware) -- Open-source PCB design files. CC BY-SA 4.0.
- [VESC Tool](https://github.com/vedderb/vesc_tool) -- Configuration tool (desktop + mobile).

### VESC Balance App (EUC/Onewheel Self-Balancing)

- [Balance App PR #99](https://github.com/vedderb/bldc/pull/99) -- Mitchlol's PR adding self-balancing support to VESC firmware, designed for DIY EUCs and onewheels. Now merged into mainline.
- [Balance App Forum](https://vesc-project.com/forum/16) -- Dedicated forum with tuning guides and build logs for DIY EUCs and onewheels.
- [Balance App Documentation](https://www.vesc-project.com/node/2689) -- Official configuration parameters.
- [Open Source Onewheel / VESC Guide](https://spinningmag.net/articles/vesc-guide) -- Guide showing how VESC has expanded from esk8 into self-balancing PEVs.

## The esk8 Precedent

The electric skateboard community's move from proprietary to open-source is the model for where EUC should go:

- [Benjamin Vedder's VESC Announcement (2015)](http://vedder.se/2015/01/vesc-open-source-esc/) -- The blog post that launched VESC and transformed DIY esk8.
- [VESC Open Source Philosophy](https://onestopboardshop.com/pages/vesc-open-source) -- Overview of the CC BY-SA 4.0 license and what it means for hardware derivatives (Flipsky, Focbox, etc.).
- [esk8 Builders Forum](https://forum.esk8.news/) -- The primary DIY esk8 community, active discussions on VESC builds and open-source remotes.
- [GB Remote Lite (esk8.news)](https://forum.esk8.news/t/gb-remote-lite-open-source-vesc-remote/89620) -- Example of community-driven open hardware: a complete VESC remote designed and manufactured by the community.
- [DIY Onewheel on the Cheap (Hackaday)](https://hackaday.com/2022/01/03/diy-onewheel-on-the-cheap/) -- VESC balance app applied to a real self-balancing build.

## Community

- [Electric Unicycle Forum](https://forum.electricunicycle.org) -- Primary English-language EUC community. Sub-forums for each brand and DIY.
- [Endless Sphere](https://endless-sphere.com/sphere/threads/my-opensource-firmware-for-electric-unicycles.87699/) -- Broader DIY EV forum; Casainho's thread on open-source EUC firmware.
- [EUC Apps and Projects (forum)](https://forum.electricunicycle.org/topic/22558-euc-apps-and-projects/) -- Overview of all known EUC apps and development projects.
- [FOSS EUC App Discussion (forum)](https://forum.electricunicycle.org/topic/34395-foss-free-and-open-source-app/) -- 2023 thread on the need for a fully FOSS EUC app.
- [ScooterHacking.org](https://scooterhacking.org/) -- Ninebot/Xiaomi scooter firmware modification community. Relevant because Ninebot scooters share the same protocol family as Ninebot EUCs.
- [NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto) -- Implementation of Ninebot's encryption protocol used in newer devices.

## Where EUC Stands vs esk8

**What exists today:**
- Protocol decoders cover all major brands across multiple open-source apps
- VESC has a balance app capable of running a DIY EUC
- The EGG project proved a fully open-source EUC build is possible
- Several open-source BMS options work for EUC battery packs

**What's missing:**
- No production-ready open-source EUC controller (VESC balance app works, but no one has commercialized a VESC-based EUC board the way Flipsky did for esk8)
- No manufacturer-published protocol specifications (everything is reverse-engineered)
- DarknessBot (the dominant iOS app) is closed-source
- No open-source equivalent to manufacturer firmware (unlike scooters where ScooterHacking has custom firmware builders)
