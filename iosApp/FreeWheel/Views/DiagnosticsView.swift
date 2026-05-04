import SwiftUI
import MessageUI
import UIKit
import FreeWheelCore

/// Settings → Diagnostics. Shows the recent event log, with filters and
/// share/send-to-developer actions.
struct DiagnosticsView: View {

    @State private var rawLines: [String] = []
    @State private var levelFilter: Set<String> = ["error", "warn", "info"]
    @State private var categoryFilter: Set<String> = []
    @State private var expandedRow: Int? = nil
    @State private var showMail = false
    @State private var showShare = false
    @State private var bundleURL: URL? = nil
    @State private var mailUnavailable = false

    private static let pageSize: Int32 = 500

    private var parsed: [DisplayEvent] {
        rawLines.map(parseLineForDisplay)
    }

    private var filtered: [DisplayEvent] {
        parsed.filter {
            levelFilter.contains($0.level) &&
            (categoryFilter.isEmpty || categoryFilter.contains($0.category))
        }
    }

    private var visibleCategories: [String] {
        Array(Set(parsed.map { $0.category })).sorted()
    }

    var body: some View {
        VStack(spacing: 0) {
            filterBar
            Divider()
            if filtered.isEmpty {
                Spacer()
                Text("No matching events.")
                    .foregroundColor(.secondary)
                Spacer()
            } else {
                List(Array(filtered.enumerated()), id: \.offset) { (i, evt) in
                    EventRow(
                        event: evt,
                        expanded: expandedRow == i,
                        onTap: { expandedRow = (expandedRow == i) ? nil : i }
                    )
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 16) {
                    Button(action: reload) { Image(systemName: "arrow.clockwise") }
                    Button(action: tapEmail) { Image(systemName: "envelope") }
                    Button(action: tapShare) { Image(systemName: "square.and.arrow.up") }
                    Button(action: tapClear) { Image(systemName: "trash") }
                }
            }
        }
        .onAppear(perform: reload)
        .sheet(isPresented: $showMail) {
            if let url = bundleURL {
                MailComposeView(
                    subject: subjectLine(),
                    body: BODY_TEMPLATE,
                    recipient: BuildConstants.supportEmail,
                    attachmentURL: url
                )
            }
        }
        .sheet(isPresented: $showShare) {
            if let url = bundleURL {
                ActivityView(items: [url])
            }
        }
        .alert("Mail not available", isPresented: $mailUnavailable) {
            Button("Share instead") {
                buildBundle()
                showShare = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("No Mail account is configured on this device. You can share the diagnostics file another way.")
        }
    }

    private var filterBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                ForEach(["error", "warn", "info"], id: \.self) { lvl in
                    chip(label: lvl.uppercased(), selected: levelFilter.contains(lvl)) {
                        if levelFilter.contains(lvl) { levelFilter.remove(lvl) }
                        else { levelFilter.insert(lvl) }
                    }
                }
            }
            if !visibleCategories.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(visibleCategories, id: \.self) { cat in
                            chip(label: cat, selected: categoryFilter.contains(cat)) {
                                if categoryFilter.contains(cat) { categoryFilter.remove(cat) }
                                else { categoryFilter.insert(cat) }
                            }
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func chip(label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(selected ? Color.accentColor.opacity(0.2) : Color(.systemGray5))
                .foregroundColor(.primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Actions

    private func reload() {
        let lines = Diagnostics.shared.readRecent(maxLines: Self.pageSize)
        rawLines = (lines as? [String] ?? []).reversed()
    }

    private func tapShare() {
        buildBundle()
        showShare = true
    }

    private func tapEmail() {
        buildBundle()
        if MFMailComposeViewController.canSendMail() {
            showMail = true
        } else {
            mailUnavailable = true
        }
    }

    private func tapClear() {
        Diagnostics.shared.clear()
        reload()
    }

    private func buildBundle() {
        let outDir = FileManager.default.temporaryDirectory.appendingPathComponent("diagnostics-bundles")
        try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let ts = Int(Date().timeIntervalSince1970)
        let url = outDir.appendingPathComponent("diagnostics-\(ts).txt")

        let header = """
        === FreeWheel Diagnostics ===
        App version:    \(BuildConstants.versionName) (build \(BuildConstants.versionCode))
        Platform:       iOS \(UIDevice.current.systemVersion) / \(UIDevice.current.model)
        Locale:         \(Locale.current.identifier)
        Generated:      \(ISO8601DateFormatter().string(from: Date()))

        === Event log (most recent first) ===

        """
        let recent = (Diagnostics.shared.readRecent(maxLines: 2000) as? [String] ?? []).reversed()
        let body = recent.joined(separator: "\n")
        try? (header + body).write(to: url, atomically: true, encoding: .utf8)
        bundleURL = url
    }

    private func subjectLine() -> String {
        "[FW-Diag] FreeWheel Diagnostics — v\(BuildConstants.versionName) (build \(BuildConstants.versionCode))"
    }
}

// MARK: - Event row

private struct DisplayEvent {
    let raw: String
    let timestamp: String
    let level: String
    let category: String
    let type: String
    let message: String
}

private func parseLineForDisplay(_ raw: String) -> DisplayEvent {
    func field(_ key: String) -> String {
        guard let r = raw.range(of: "\"\(key)\":\"") else { return "" }
        let after = raw[r.upperBound...]
        var out = ""
        var escaped = false
        for c in after {
            if escaped { out.append(c); escaped = false; continue }
            if c == "\\" { escaped = true; continue }
            if c == "\"" { break }
            out.append(c)
        }
        return out
    }
    return DisplayEvent(
        raw: raw,
        timestamp: field("ts"),
        level: field("level"),
        category: field("category"),
        type: field("type"),
        message: field("message")
    )
}

private struct EventRow: View {
    let event: DisplayEvent
    let expanded: Bool
    let onTap: () -> Void

    var body: some View {
        let dotColor: Color = {
            switch event.level {
            case "error": return .red
            case "warn": return .orange
            default: return .gray
            }
        }()
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 8) {
                Circle().fill(dotColor).frame(width: 8, height: 8).padding(.top, 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(event.timestamp.split(separator: "T").last.map { String($0).prefix(8) } ?? "")  \(event.type)")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(.secondary)
                    Text(event.message)
                        .font(.body)
                        .foregroundColor(.primary)
                    if expanded {
                        Text(event.raw)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(.secondary)
                            .padding(.top, 4)
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Share sheet

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Mail composer

struct MailComposeView: UIViewControllerRepresentable {
    let subject: String
    let body: String
    let recipient: String
    let attachmentURL: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.mailComposeDelegate = context.coordinator
        vc.setToRecipients([recipient])
        vc.setSubject(subject)
        vc.setMessageBody(body, isHTML: false)
        if let data = try? Data(contentsOf: attachmentURL) {
            vc.addAttachmentData(data, mimeType: "text/plain",
                                 fileName: attachmentURL.lastPathComponent)
        }
        return vc
    }

    func updateUIViewController(_ vc: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        func mailComposeController(_ controller: MFMailComposeViewController,
                                   didFinishWith result: MFMailComposeResult,
                                   error: Error?) {
            controller.dismiss(animated: true)
        }
    }
}

// MARK: - Body template

private let BODY_TEMPLATE = """
Hi! Something happened with my ride logging — describe below:




Ride affected (filename or approximate time, optional):


---
DO NOT EDIT BELOW THIS LINE
---
The attached diagnostics-*.txt file has the event log and a current-state snapshot.
"""
