import SwiftUI
import FreeWheelCore

struct EventLogView: View {
    @ObservedObject var manager: WheelManager

    var body: some View {
        List {
            if manager.eventLogEntries.isEmpty {
                Text("Tap the download button to read the wheel's event log.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(Array(manager.eventLogEntries.enumerated()), id: \.offset) { _, entry in
                        EventLogRow(entry: entry)
                    }
                } header: {
                    Text("\(manager.eventLogEntries.count) entries")
                }
            }
        }
        .navigationTitle("Wheel Event Log")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    manager.requestEventLog()
                } label: {
                    Image(systemName: "arrow.down.circle")
                }
            }
        }
    }
}

private struct EventLogRow: View {
    let entry: EventLogEntry

    var body: some View {
        HStack {
            Text("#\(entry.index)")
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .frame(width: 40, alignment: .leading)

            Text("Code: \(entry.contentCode)")
                .font(.body.monospaced())

            Spacer()

            if entry.extraBytes.size > 0 {
                let bytes = (0..<entry.extraBytes.size).map { i in
                    String(format: "%02X", entry.extraBytes.get(index: i))
                }
                Text(bytes.joined(separator: " "))
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            }

            if !entry.text.isEmpty {
                Text(entry.text)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
