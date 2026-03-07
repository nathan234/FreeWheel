import SwiftUI
import FreeWheelCore

struct NavigationEditView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) var dismiss

    @State private var activeTabs: [NavigationTab] = []

    private func sfSymbol(for tab: NavigationTab) -> String {
        switch tab.iconName {
        case "bluetooth": return "antenna.radiowaves.left.and.right"
        case "show_chart": return "chart.xyaxis.line"
        case "battery_full": return "battery.100"
        case "route": return "road.lanes"
        case "tune": return "slider.horizontal.3"
        case "settings": return "gearshape"
        default: return "questionmark"
        }
    }

    private var isConfigValid: Bool {
        let config = NavigationConfig(tabs: activeTabs)
        return config.isValid()
    }

    private var warnings: [String] {
        let config = NavigationConfig(tabs: activeTabs)
        return Array(config.warnings())
    }

    var body: some View {
        List {
            Section {
                Text("Choose which screens appear as tabs. Devices is always included.")
                    .foregroundColor(.secondary)
                    .font(.callout)
            }

            Section("Tabs") {
                ForEach(NavigationTab.entries, id: \.name) { tab in
                    let isActive = activeTabs.contains(tab)
                    let activeIndex = activeTabs.firstIndex(of: tab)

                    HStack {
                        Image(systemName: sfSymbol(for: tab))
                            .foregroundColor(.secondary)
                            .frame(width: 24)
                        Text(tab.label)

                        Spacer()

                        if isActive, let idx = activeIndex, !tab.isRequired {
                            if idx > 0 {
                                Button(action: {
                                    activeTabs.swapAt(idx, idx - 1)
                                }) {
                                    Image(systemName: "arrow.up")
                                }
                                .buttonStyle(.borderless)
                            }
                            if idx < activeTabs.count - 1 {
                                Button(action: {
                                    activeTabs.swapAt(idx, idx + 1)
                                }) {
                                    Image(systemName: "arrow.down")
                                }
                                .buttonStyle(.borderless)
                            }
                        }

                        Toggle("", isOn: Binding(
                            get: { isActive },
                            set: { newValue in
                                if tab.isRequired { return }
                                if newValue {
                                    if activeTabs.count < 5 { activeTabs.append(tab) }
                                } else {
                                    if activeTabs.count > 2 { activeTabs.removeAll { $0 == tab } }
                                }
                            }
                        ))
                        .disabled(tab.isRequired)
                        .labelsHidden()
                    }
                }
            }

            // Warnings
            if !warnings.isEmpty {
                Section {
                    ForEach(warnings, id: \.self) { warning in
                        Label(warning, systemImage: "exclamationmark.triangle")
                            .foregroundColor(.orange)
                            .font(.callout)
                    }
                }
            }

            // Preview
            Section("Preview") {
                HStack(spacing: 0) {
                    ForEach(activeTabs, id: \.name) { tab in
                        VStack(spacing: 4) {
                            Image(systemName: sfSymbol(for: tab))
                                .font(.system(size: 20))
                            Text(tab.label)
                                .font(.caption2)
                        }
                        .frame(maxWidth: .infinity)
                        .foregroundColor(tab == .devices ? .accentColor : .secondary)
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .navigationTitle("Customize Navigation")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    let config = NavigationConfig(tabs: activeTabs)
                    if config.isValid() {
                        wheelManager.navigationConfig = config
                    }
                    dismiss()
                }
                .fontWeight(.bold)
                .disabled(!isConfigValid)
            }
        }
        .onAppear {
            activeTabs = Array(wheelManager.navigationConfig.tabs)
        }
    }
}
