import SwiftUI
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/BleCaptureScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Title header
//  2. Capture status bar (packet counts, elapsed time)
//  3. Start/Stop button
//  4. Marker input (visible when capturing)
//  5. Quick-marker buttons (send command + insert marker)
//  6. Share Unhandled Frames button
//  7. Capture history with share/delete

struct BleCaptureView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) private var dismiss

    @State private var markerText = ""
    @State private var captureFiles: [URL] = []
    @State private var elapsedTimer: Timer? = nil
    @State private var elapsedSeconds: Int = 0
    @State private var unhandledShareText: String? = nil

    var body: some View {
        List {
            // Status bar
            if wheelManager.isCapturing {
                Section("Capture Status") {
                    HStack {
                        Text("RX: \(wheelManager.captureRxCount)")
                        Spacer()
                        Text("TX: \(wheelManager.captureTxCount)")
                        Spacer()
                        Text("Markers: \(wheelManager.captureMarkerCount)")
                    }
                    Text("Elapsed: \(formatElapsed(elapsedSeconds))")
                        .foregroundColor(.secondary)
                }
            }

            // Start/Stop button
            Section {
                if wheelManager.isCapturing {
                    Button(action: {
                        stopCaptureAndRefresh()
                    }) {
                        HStack {
                            Image(systemName: "stop.circle.fill")
                            Text("Stop Capture")
                        }
                        .foregroundColor(.red)
                    }
                } else {
                    Button(action: {
                        wheelManager.startCapture()
                        startTimer()
                    }) {
                        HStack {
                            Image(systemName: "record.circle")
                            Text("Start Capture")
                        }
                    }
                }
            }

            // Marker input (visible when capturing)
            if wheelManager.isCapturing {
                Section("Markers") {
                    HStack {
                        TextField("Marker label", text: $markerText)
                            .textFieldStyle(.roundedBorder)
                        Button("Add") {
                            if !markerText.trimmingCharacters(in: .whitespaces).isEmpty {
                                wheelManager.insertCaptureMarker(markerText.trimmingCharacters(in: .whitespaces))
                                markerText = ""
                            }
                        }
                        .disabled(markerText.trimmingCharacters(in: .whitespaces).isEmpty)
                    }

                    // Quick-marker buttons
                    HStack(spacing: 12) {
                        Button("Toggle Light") {
                            wheelManager.toggleLight()
                            wheelManager.insertCaptureMarker("toggled light")
                        }
                        .buttonStyle(.bordered)

                        Button("Beep") {
                            wheelManager.wheelBeep()
                            wheelManager.insertCaptureMarker("beep")
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }

            // Copy Diagnostic Info
            Section {
                Button(action: {
                    if let text = wheelManager.buildDiagnosticText() {
                        UIPasteboard.general.string = text
                    }
                }) {
                    HStack {
                        Image(systemName: "doc.on.doc")
                        Text("Copy Diagnostic Info")
                    }
                }
                .disabled(!wheelManager.connectionState.isConnected)

                // Share Unhandled Frames
                Button(action: {
                    unhandledShareText = wheelManager.buildUnhandledFramesText()
                }) {
                    HStack {
                        Image(systemName: "square.and.arrow.up")
                        Text("Share Unhandled Frames (\(wheelManager.unhandledCount))")
                    }
                }
                .disabled(wheelManager.unhandledCount == 0)
                .sheet(isPresented: Binding(
                    get: { unhandledShareText != nil },
                    set: { if !$0 { unhandledShareText = nil } }
                )) {
                    if let text = unhandledShareText {
                        ShareSheet(items: [text])
                    }
                }
            }

            // Capture history
            Section("Capture History") {
                if captureFiles.isEmpty {
                    Text("No captures yet")
                        .foregroundColor(.secondary)
                } else {
                    ForEach(captureFiles, id: \.lastPathComponent) { file in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(friendlyDate(file))
                                    .font(.body)
                                Text(fileSize(file))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            ShareLink(item: file) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.caption)
                            }
                            .buttonStyle(.borderless)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            replayCapture(file)
                        }
                    }
                    .onDelete(perform: deleteCaptures)
                }
            }
        }
        .navigationTitle("BLE Capture")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            loadCaptureFiles()
            if wheelManager.isCapturing {
                startTimer()
            }
        }
        .onDisappear {
            stopTimer()
        }
    }

    // MARK: - Timer

    private func startTimer() {
        elapsedSeconds = 0
        elapsedTimer?.invalidate()
        elapsedTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            if let start = wheelManager.captureStartTime {
                elapsedSeconds = Int(Date().timeIntervalSince(start))
            }
        }
    }

    private func stopTimer() {
        elapsedTimer?.invalidate()
        elapsedTimer = nil
    }

    // MARK: - Helpers

    private func stopCaptureAndRefresh() {
        wheelManager.stopCapture()
        stopTimer()
        loadCaptureFiles()
    }

    private func loadCaptureFiles() {
        let dir = WheelManager.capturesDirectory()
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: .skipsHiddenFiles
        ) else {
            captureFiles = []
            return
        }
        captureFiles = files
            .filter { $0.pathExtension == "csv" }
            .sorted { (a, b) in
                let dateA = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let dateB = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return dateA > dateB
            }
    }

    private func deleteCaptures(at offsets: IndexSet) {
        for index in offsets {
            let file = captureFiles[index]
            try? FileManager.default.removeItem(at: file)
        }
        captureFiles.remove(atOffsets: offsets)
    }

    private func friendlyDate(_ file: URL) -> String {
        let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
        let ms = Int64(date.timeIntervalSince1970 * 1000)
        return PlatformDateFormatter.shared.formatFriendlyDate(epochMs: ms)
    }

    private func fileSize(_ file: URL) -> String {
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0
        return "\(size / 1024) KB"
    }

    private func replayCapture(_ file: URL) {
        guard let csvContent = try? String(contentsOf: file, encoding: .utf8) else { return }
        wheelManager.startReplay(csvContent: csvContent)
        dismiss()
    }

    private func formatElapsed(_ seconds: Int) -> String {
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        } else {
            return String(format: "%d:%02d", m, s)
        }
    }
}
