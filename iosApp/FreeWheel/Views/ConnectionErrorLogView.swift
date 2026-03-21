import SwiftUI

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/ConnectionErrorLogScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Title with back button
//  2. Empty state with icon and message
//  3. Log file list with swipe-to-delete
//  4. Log row: date, wheel type (from filename), file size
//  5. Share button per log
//  6. Clear All button

struct ConnectionErrorLogView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) private var dismiss

    @State private var logFiles: [URL] = []

    var body: some View {
        List {
            if logFiles.isEmpty {
                Section {
                    VStack(spacing: 16) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary.opacity(0.5))
                        Text("No error logs yet. Logs are recorded automatically during each connection.")
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                }
            } else {
                Section {
                    ForEach(logFiles, id: \.lastPathComponent) { file in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(friendlyDate(file))
                                    .font(.body)
                                let wheelType = wheelTypeName(from: file)
                                Text("\(wheelType) \u{00B7} \(fileSize(file))")
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
                    }
                    .onDelete { offsets in
                        for index in offsets {
                            wheelManager.deleteErrorLog(at: logFiles[index])
                        }
                        logFiles.remove(atOffsets: offsets)
                    }
                } header: {
                    Text("Error Logs")
                }
            }
        }
        .navigationTitle("Connection Errors")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if !logFiles.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(role: .destructive) {
                        wheelManager.clearErrorLogs()
                        logFiles = []
                    } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
        .onAppear {
            logFiles = wheelManager.errorLogFiles()
        }
    }

    private func friendlyDate(_ url: URL) -> String {
        let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
        let formatter = DateFormatter()
        formatter.doesRelativeDateFormatting = true
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    private func fileSize(_ url: URL) -> String {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return "\(size / 1024) KB"
    }

    private func wheelTypeName(from url: URL) -> String {
        let name = url.deletingPathExtension().lastPathComponent
        let parts = name.split(separator: "_")
        guard let last = parts.last else { return "Unknown" }
        return String(last).capitalized
    }
}
