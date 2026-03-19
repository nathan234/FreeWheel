import SwiftUI
import FreeWheelCore

struct DashboardEditView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) var dismiss

    var body: some View {
        LayoutEditorBody(
            title: "Edit Dashboard",
            initialLayout: wheelManager.dashboardLayout,
            wheelType: wheelManager.identity.wheelType,
            onSave: { layout in
                wheelManager.dashboardLayout = layout
                dismiss()
            },
            onCancel: { dismiss() }
        )
    }
}

/// Shared layout editor used by both DashboardEditView and CustomTabEditView.
/// Contains preset picker, hero gauge selector, tile/stat list editors, and info card toggles.
struct LayoutEditorBody: View {
    let title: String
    let initialLayout: DashboardLayout
    let wheelType: WheelType
    let onSave: (DashboardLayout) -> Void
    let onCancel: () -> Void

    @State private var heroMetric: DashboardMetric? = .speed
    @State private var tiles: [DashboardMetric] = []
    @State private var stats: [DashboardMetric] = []
    @State private var showWheelSettings: Bool = true
    @State private var showWheelInfo: Bool = true
    @State private var showBmsSummary: Bool = true
    @State private var showAddTile = false
    @State private var showAddStat = false

    private var isLayoutValid: Bool {
        (heroMetric == nil || heroMetric!.supportedDisplayTypes.contains(.heroGauge)) &&
        tiles.allSatisfy { $0.supportedDisplayTypes.contains(.gaugeTile) } &&
        stats.allSatisfy { $0.supportedDisplayTypes.contains(.statRow) }
    }

    var body: some View {
        List {
            // Presets
            Section("Presets") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(DashboardPresets.shared.all(), id: \.id) { preset in
                            Button(preset.name) {
                                let filtered = preset.layout.filteredFor(wheelType: wheelType)
                                heroMetric = filtered.heroMetric
                                tiles = Array(filtered.tiles)
                                stats = Array(filtered.stats)
                                showWheelSettings = filtered.showWheelSettings
                                showWheelInfo = filtered.showWheelInfo
                                showBmsSummary = filtered.showBmsSummary
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }

            // Hero metric
            Section("Hero Gauge") {
                // "None" option for tiles-only layout
                Button(action: { heroMetric = nil }) {
                    HStack {
                        Text("None (tiles only)")
                        Spacer()
                        if heroMetric == nil {
                            Image(systemName: "checkmark")
                                .foregroundColor(.accentColor)
                        }
                    }
                }
                .foregroundColor(.primary)

                let heroOptions = DashboardMetric.entries.filter {
                    $0.supportedDisplayTypes.contains(.heroGauge) &&
                    $0.isAvailableFor(wheelType: wheelType)
                }
                ForEach(heroOptions, id: \.name) { metric in
                    Button(action: { heroMetric = metric }) {
                        HStack {
                            Text(metric.label)
                            Spacer()
                            if heroMetric == metric {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.accentColor)
                            }
                        }
                    }
                    .foregroundColor(.primary)
                }
            }

            // Tiles
            Section("Gauge Tiles") {
                ForEach(Array(tiles.enumerated()), id: \.element.name) { index, metric in
                    HStack {
                        Text(metric.label)
                        Spacer()
                        if index > 0 {
                            Button(action: {
                                tiles.swapAt(index, index - 1)
                            }) {
                                Image(systemName: "arrow.up")
                            }
                            .buttonStyle(.borderless)
                        }
                        if index < tiles.count - 1 {
                            Button(action: {
                                tiles.swapAt(index, index + 1)
                            }) {
                                Image(systemName: "arrow.down")
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
                .onDelete { indexSet in
                    tiles.remove(atOffsets: indexSet)
                }

                Button(action: { showAddTile = true }) {
                    Label("Add Tile", systemImage: "plus")
                }
            }

            // Stats
            Section("Stat Rows") {
                ForEach(Array(stats.enumerated()), id: \.element.name) { index, metric in
                    HStack {
                        Text(metric.label)
                        Spacer()
                        if index > 0 {
                            Button(action: {
                                stats.swapAt(index, index - 1)
                            }) {
                                Image(systemName: "arrow.up")
                            }
                            .buttonStyle(.borderless)
                        }
                        if index < stats.count - 1 {
                            Button(action: {
                                stats.swapAt(index, index + 1)
                            }) {
                                Image(systemName: "arrow.down")
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
                .onDelete { indexSet in
                    stats.remove(atOffsets: indexSet)
                }

                Button(action: { showAddStat = true }) {
                    Label("Add Stat", systemImage: "plus")
                }
            }

            // Info cards
            Section("Info Cards") {
                Toggle("Show Wheel Settings", isOn: $showWheelSettings)
                Toggle("Show Wheel Info", isOn: $showWheelInfo)
                Toggle("Show BMS Summary", isOn: $showBmsSummary)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { onCancel() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    var sections: Set<DashboardSection> = []
                    if showWheelSettings { sections.insert(.wheelSettings) }
                    if showWheelInfo { sections.insert(.wheelInfo) }
                    if showBmsSummary { sections.insert(.bmsSummary) }
                    let layout = DashboardLayout.companion.create(
                        id: nil, name: nil,
                        heroMetric: heroMetric,
                        tiles: tiles,
                        stats: stats,
                        sections: sections
                    )
                    onSave(layout)
                }
                .fontWeight(.bold)
                .disabled(!isLayoutValid)
            }
        }
        .onAppear {
            heroMetric = initialLayout.heroMetric
            tiles = Array(initialLayout.tiles)
            stats = Array(initialLayout.stats)
            showWheelSettings = initialLayout.showWheelSettings
            showWheelInfo = initialLayout.showWheelInfo
            showBmsSummary = initialLayout.showBmsSummary
        }
        .sheet(isPresented: $showAddTile) {
            AddMetricSheet(
                title: "Add Gauge Tile",
                widgetType: .gaugeTile,
                wheelType: wheelType,
                alreadySelected: Set(tiles + stats)
            ) { metric in
                tiles.append(metric)
                showAddTile = false
            }
        }
        .sheet(isPresented: $showAddStat) {
            AddMetricSheet(
                title: "Add Stat Row",
                widgetType: .statRow,
                wheelType: wheelType,
                alreadySelected: Set(tiles + stats)
            ) { metric in
                stats.append(metric)
                showAddStat = false
            }
        }
    }
}

struct AddMetricSheet: View {
    let title: String
    let widgetType: WidgetType
    let wheelType: WheelType
    let alreadySelected: Set<DashboardMetric>
    let onSelect: (DashboardMetric) -> Void

    @Environment(\.dismiss) var dismiss

    private var available: [DashboardMetric] {
        DashboardMetric.entries.filter { metric in
            metric.supportedDisplayTypes.contains(widgetType) &&
            metric.isAvailableFor(wheelType: wheelType) &&
            !alreadySelected.contains(metric)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if available.isEmpty {
                    Text("No more metrics available")
                        .foregroundColor(.secondary)
                } else {
                    ForEach(available, id: \.name) { metric in
                        Button(action: { onSelect(metric) }) {
                            HStack {
                                Text(metric.label)
                                Spacer()
                                Text(metric.unit)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
