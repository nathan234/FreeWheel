import SwiftUI
import FreeWheelCore

struct DashboardEditView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) var dismiss

    @State private var heroMetric: DashboardMetric = .speed
    @State private var tiles: [DashboardMetric] = []
    @State private var stats: [DashboardMetric] = []
    @State private var showWheelSettings: Bool = true
    @State private var showWheelInfo: Bool = true
    @State private var showAddTile = false
    @State private var showAddStat = false

    private var isLayoutValid: Bool {
        heroMetric.supportedDisplayTypes.contains(.heroGauge) &&
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
                                let filtered = preset.layout.filteredFor(wheelType: wheelManager.wheelState.wheelType)
                                heroMetric = filtered.heroMetric
                                tiles = Array(filtered.tiles)
                                stats = Array(filtered.stats)
                                showWheelSettings = filtered.showWheelSettings
                                showWheelInfo = filtered.showWheelInfo
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }

            // Hero metric
            Section("Hero Gauge") {
                let heroOptions = DashboardMetric.entries.filter {
                    $0.supportedDisplayTypes.contains(.heroGauge) &&
                    $0.isAvailableFor(wheelType: wheelManager.wheelState.wheelType)
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
            }
        }
        .navigationTitle("Edit Dashboard")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    var sections: Set<DashboardSection> = []
                    if showWheelSettings { sections.insert(.wheelSettings) }
                    if showWheelInfo { sections.insert(.wheelInfo) }
                    let layout = DashboardLayout.companion.create(
                        id: nil, name: nil,
                        heroMetric: heroMetric,
                        tiles: tiles,
                        stats: stats,
                        sections: sections
                    )
                    wheelManager.dashboardLayout = layout
                    dismiss()
                }
                .fontWeight(.bold)
                .disabled(!isLayoutValid)
            }
        }
        .onAppear {
            heroMetric = wheelManager.dashboardLayout.heroMetric
            tiles = Array(wheelManager.dashboardLayout.tiles)
            stats = Array(wheelManager.dashboardLayout.stats)
            showWheelSettings = wheelManager.dashboardLayout.showWheelSettings
            showWheelInfo = wheelManager.dashboardLayout.showWheelInfo
        }
        .sheet(isPresented: $showAddTile) {
            AddMetricSheet(
                title: "Add Gauge Tile",
                widgetType: .gaugeTile,
                wheelType: wheelManager.wheelState.wheelType,
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
                wheelType: wheelManager.wheelState.wheelType,
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
