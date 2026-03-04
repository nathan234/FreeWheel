import SwiftUI
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/ScanScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Auto-reconnect overlay (Android: separate AutoConnectContent; iOS: inline in ScanView)
//  2. Header with app name
//  3. Scan button with pulse animation
//  4. Device list: "My Wheels" section (saved devices)
//  5. Device list: "New Devices" section (unsaved, swipe-to-delete to forget)
//  6. Empty state with demo mode button
//  Note: iOS has additional simulator-specific test UI buttons

struct ScanView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @State private var scanPulse = false

    private var hasDevices: Bool {
        !wheelManager.discoveredDevices.isEmpty
    }

    private var connectingAddress: String? {
        wheelManager.connectionState.connectingAddress
    }

    private var failedAddress: String? {
        wheelManager.connectionState.failedAddress
    }

    var body: some View {
        if wheelManager.isAutoConnecting {
            VStack(spacing: 16) {
                Spacer()
                ProgressView()
                    .scaleEffect(1.5)
                Text(ScanLabels.shared.RECONNECTING)
                    .font(.title3)
                    .foregroundColor(.secondary)
                Button(CommonLabels.shared.CANCEL) {
                    wheelManager.disconnect()
                }
                .foregroundColor(.red)
                .padding(.top, 8)
                Spacer()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemGroupedBackground))
        } else {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Text(ScanLabels.shared.APP_NAME)
                        .font(.largeTitle)
                        .fontWeight(.bold)
                    Spacer()
                }
                .padding()

                // Hide scan button while connecting
                if connectingAddress == nil {
                    scanButton
                        .onChange(of: wheelManager.isScanning) { scanning in
                            if scanning {
                                scanPulse = true
                            } else {
                                scanPulse = false
                            }
                        }
                }

                if hasDevices {
                    deviceList
                } else {
                    emptyState
                }
            }
            .background(Color(.systemGroupedBackground))
        }
    }

    private var scanButton: some View {
        let isScanning = wheelManager.isScanning
        let size: CGFloat = hasDevices ? 100 : 160

        return Button(action: toggleScan) {
            ZStack {
                Circle()
                    .fill(isScanning ? Color.red : Color.blue)
                    .frame(width: size, height: size)
                    .shadow(color: (isScanning ? Color.red : Color.blue).opacity(0.4), radius: 12, y: 4)

                if isScanning {
                    Circle()
                        .stroke(Color.red.opacity(0.3), lineWidth: 3)
                        .frame(width: size + 20, height: size + 20)
                        .scaleEffect(scanPulse ? 1.3 : 1.0)
                        .opacity(scanPulse ? 0.0 : 0.6)
                        .animation(.easeOut(duration: 1.5).repeatForever(autoreverses: false), value: scanPulse)
                }

                VStack(spacing: hasDevices ? 4 : 8) {
                    Image(systemName: isScanning ? "stop.fill" : (hasDevices ? "arrow.clockwise" : "antenna.radiowaves.left.and.right"))
                        .font(.system(size: hasDevices ? 24 : 40, weight: .medium))
                    Text(isScanning ? CommonLabels.shared.CANCEL : (hasDevices ? ScanLabels.shared.RESCAN : ScanLabels.shared.SCAN))
                        .font(hasDevices ? .body : .title2)
                        .fontWeight(.semibold)
                }
                .foregroundColor(.white)
            }
        }
        .buttonStyle(.plain)
        .padding(.vertical, hasDevices ? 12 : 0)
        .animation(.easeInOut(duration: 0.3), value: hasDevices)
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()

            if wheelManager.isScanning {
                Text(ScanLabels.shared.SEARCHING)
                    .font(.body)
                    .foregroundColor(.secondary)
            } else {
                Text(ScanLabels.shared.TAP_TO_SEARCH)
                    .font(.body)
                    .foregroundColor(.secondary)
            }

            #if targetEnvironment(simulator)
            Divider()
                .padding(.vertical, 12)

            VStack(spacing: 12) {
                Text("Simulator Mode")
                    .font(.headline)
                    .foregroundColor(.orange)
                Text("BLE is not available on iOS Simulator")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button(action: { wheelManager.startMockMode() }) {
                    HStack {
                        Image(systemName: "play.circle.fill")
                        Text(ScanLabels.shared.START_DEMO)
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(Color.orange)
                    .foregroundColor(.white)
                    .cornerRadius(10)
                }

                Button(action: { startKmpTest() }) {
                    HStack {
                        Image(systemName: "testtube.2")
                        Text("Test KMP Decoder")
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(10)
                }

                Divider()
                    .padding(.vertical, 8)

                Text("Test Wheel Settings Panel")
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    testButton("KingSong", wheelType: .kingsong, color: .blue)
                    testButton("Begode", wheelType: .gotway, color: .green)
                    testButton("Veteran", wheelType: .veteran, color: .purple)
                    testButton("Ninebot Z", wheelType: .ninebotZ, color: .orange)
                    testButton("InMotion", wheelType: .inmotion, color: .teal)
                    testButton("InMotion V2", wheelType: .inmotionV2, color: .indigo)
                }
                .padding(.horizontal)
            }
            #endif

            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    /// Saved wheels — always visible. Uses live scan data if available, otherwise a placeholder.
    private var myWheels: [DiscoveredDevice] {
        let discovered = Dictionary(
            wheelManager.discoveredDevices.map { ($0.address, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        return wheelManager.savedAddresses.map { address in
            discovered[address] ?? DiscoveredDevice(
                address: address,
                name: wheelManager.getSavedDisplayName(address: address) ?? ScanLabels.shared.UNKNOWN_DEVICE,
                rssi: -100
            )
        }
    }

    /// New devices — discovered devices that are NOT in the saved list.
    private var newDevices: [DiscoveredDevice] {
        wheelManager.discoveredDevices.filter { !wheelManager.savedAddresses.contains($0.address) }
    }

    private var deviceList: some View {
        List {
            // "My Wheels" section — always shown if saved wheels exist
            if !myWheels.isEmpty {
                Section {
                    ForEach(myWheels) { device in
                        let isThisConnecting = connectingAddress == device.address
                        let isThisFailed = failedAddress == device.address
                        let isDisabled = connectingAddress != nil && !isThisConnecting
                        let savedName = wheelManager.getSavedDisplayName(address: device.address)
                        DeviceRow(
                            device: device,
                            displayNameOverride: savedName,
                            isConnecting: isThisConnecting,
                            isFailed: isThisFailed,
                            statusText: isThisConnecting ? wheelManager.connectionState.statusText : nil,
                            isDisabled: isDisabled,
                            onCancel: isThisConnecting ? cancelConnection : nil
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if !isDisabled && !isThisConnecting {
                                connectToDevice(device)
                            }
                        }
                    }
                    .onDelete { indexSet in
                        for index in indexSet {
                            let device = myWheels[index]
                            wheelManager.forgetProfile(address: device.address)
                        }
                    }
                } header: {
                    Text(ScanLabels.shared.MY_WHEELS)
                }
            }

            // "New Devices" section — discovered devices not in saved list
            if !newDevices.isEmpty {
                Section {
                    ForEach(newDevices) { device in
                        let isThisConnecting = connectingAddress == device.address
                        let isThisFailed = failedAddress == device.address
                        let isDisabled = connectingAddress != nil && !isThisConnecting
                        DeviceRow(
                            device: device,
                            isConnecting: isThisConnecting,
                            isFailed: isThisFailed,
                            statusText: isThisConnecting ? wheelManager.connectionState.statusText : nil,
                            isDisabled: isDisabled,
                            onCancel: isThisConnecting ? cancelConnection : nil
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if !isDisabled && !isThisConnecting {
                                connectToDevice(device)
                            }
                        }
                    }
                } header: {
                    Text(ScanLabels.shared.NEW_DEVICES)
                } footer: {
                    if wheelManager.isScanning {
                        Text(ScanLabels.shared.SCANNING)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func toggleScan() {
        if wheelManager.isScanning {
            wheelManager.stopScan()
        } else {
            wheelManager.startScan()
        }
    }

    private func connectToDevice(_ device: DiscoveredDevice) {
        wheelManager.stopScan()
        wheelManager.connect(address: device.address)
    }

    private func cancelConnection() {
        wheelManager.disconnect()
    }

    #if targetEnvironment(simulator)
    private func testButton(_ label: String, wheelType: WheelType, color: Color) -> some View {
        Button(action: {
            wheelManager.startTestSession(wheelType: wheelType)
        }) {
            Text(label)
                .font(.caption)
                .fontWeight(.medium)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(color)
                .foregroundColor(.white)
                .cornerRadius(8)
        }
    }

    private func startKmpTest() {
        // Start a test session with Kingsong wheel type
        wheelManager.startTestSession(wheelType: .kingsong)

        // Inject real KS-S18 packets from KingsongDecoderComparisonTest
        // Expected values after decoding:
        // - Model: KS-S18, Version: 2.05
        // - Speed: 5.15 km/h, Voltage: 65.05V, Current: 2.15A
        // - Temperature: 13°C, Battery: 12%
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            let packets = [
                "aa554b532d5331382d30323035000000bb1484fd",  // Model name
                "aa556919030200009f36d700140500e0a9145a5a",  // Live data
                "aa550000090017011502140100004006b9145a5a",  // Distance/fan
                "aa55000000000000000000000000400cf5145a5a",  // CPU load
                "aa55850c010000000000000016000000f6145a5a"   // Output
            ]
            for packet in packets {
                self.wheelManager.injectTestData(packet)
            }
        }
    }
    #endif
}

struct DeviceRow: View {
    let device: DiscoveredDevice
    var displayNameOverride: String? = nil
    var isConnecting: Bool = false
    var isFailed: Bool = false
    var statusText: String? = nil
    var isDisabled: Bool = false
    var onCancel: (() -> Void)? = nil

    private var displayName: String {
        if let override = displayNameOverride, !override.isEmpty {
            return override
        }
        return device.name
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(displayName)
                    .font(.body)
                    .fontWeight(.medium)
                Text(device.address)
                    .font(.caption)
                    .foregroundColor(.secondary)
                if let statusText = statusText {
                    Text(statusText)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(isFailed ? .red : .blue)
                        .padding(.top, 2)
                }
                if isFailed {
                    Text(ScanLabels.shared.CONNECTION_FAILED)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.red)
                        .padding(.top, 2)
                }
            }

            Spacer()

            if isConnecting {
                HStack(spacing: 8) {
                    ProgressView()
                        .tint(.blue)
                    if let onCancel = onCancel {
                        Button(CommonLabels.shared.CANCEL, action: onCancel)
                            .font(.body)
                            .fontWeight(.medium)
                            .foregroundColor(.red)
                    }
                }
            } else if !isDisabled {
                VStack(alignment: .trailing, spacing: 4) {
                    signalStrengthIcon
                    Text(device.rssiDescription)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
        .opacity(isDisabled ? 0.4 : 1.0)
        .listRowBackground(
            isConnecting ? Color.blue.opacity(0.06) :
            isFailed ? Color.red.opacity(0.06) :
            nil
        )
    }

    private var signalStrengthIcon: some View {
        HStack(spacing: 2) {
            ForEach(0..<4) { index in
                RoundedRectangle(cornerRadius: 1)
                    .fill(barColor(for: index))
                    .frame(width: 4, height: CGFloat(6 + index * 3))
            }
        }
    }

    private func barColor(for index: Int) -> Color {
        let bars = signalBars
        return index < bars ? .green : .gray.opacity(0.3)
    }

    private var signalBars: Int {
        Int(DisplayUtils.shared.signalBars(rssi: Int32(device.rssi)))
    }
}

#Preview {
    ScanView()
        .environmentObject(WheelManager())
}
