import SwiftUI
import FreeWheelCore

struct NavigationEditView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) var dismiss

    @State private var activeTabs: [NavigationTab] = []
    @State private var showCreateCustomTab = false

    private var isConfigValid: Bool {
        let config = NavigationConfig(tabs: activeTabs)
        return config.isValid()
    }

    private var warnings: [String] {
        let config = NavigationConfig(tabs: activeTabs)
        return Array(config.warnings())
    }

    private var customTabs: [NavigationTab.Custom] {
        activeTabs.compactMap { $0 as? NavigationTab.Custom }
    }

    var body: some View {
        List {
            Section {
                Text("Choose which screens appear as tabs. Devices is always included.")
                    .foregroundColor(.secondary)
                    .font(.callout)
            }

            // Built-in tabs
            Section("Built-in Tabs") {
                ForEach(Array(NavigationTab.companion.builtIn), id: \.id) { tab in
                    let isActive = activeTabs.contains(where: { $0.id == tab.id })
                    let activeIndex = activeTabs.firstIndex(where: { $0.id == tab.id })

                    HStack {
                        Image(systemName: sfSymbol(for: tab.iconName))
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
                                    if activeTabs.count > 2 { activeTabs.removeAll { $0.id == tab.id } }
                                }
                            }
                        ))
                        .disabled(tab.isRequired)
                        .labelsHidden()
                    }
                }
            }

            // Custom tabs
            Section("Custom Tabs") {
                if !customTabs.isEmpty {
                    ForEach(customTabs, id: \.id) { tab in
                        let activeIndex = activeTabs.firstIndex(where: { $0.id == tab.id })

                        HStack {
                            Image(systemName: sfSymbol(for: tab.iconName))
                                .foregroundColor(.secondary)
                                .frame(width: 24)
                            Text(tab.label)

                            Spacer()

                            if let idx = activeIndex {
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

                            // Delete
                            Button(action: {
                                activeTabs.removeAll { $0.id == tab.id }
                                wheelManager.deleteCustomTabLayout(tabId: tab.id)
                            }) {
                                Image(systemName: "xmark.circle")
                                    .foregroundColor(.red)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Button(action: { showCreateCustomTab = true }) {
                    Label("Create Custom Tab", systemImage: "plus")
                }
                .disabled(activeTabs.count >= 5)
            }

            if activeTabs.count >= 5 {
                Section {
                    Text("Maximum 5 tabs. Remove a tab to add another.")
                        .foregroundColor(.secondary)
                        .font(.callout)
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
                    ForEach(activeTabs, id: \.id) { tab in
                        VStack(spacing: 4) {
                            Image(systemName: sfSymbol(for: tab.iconName))
                                .font(.system(size: 20))
                            Text(tab.label)
                                .font(.caption2)
                        }
                        .frame(maxWidth: .infinity)
                        .foregroundColor(tab.id == NavigationTab.Devices.shared.id ? .accentColor : .secondary)
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
        .sheet(isPresented: $showCreateCustomTab) {
            CreateCustomTabSheet(existingIds: Set(activeTabs.map { $0.id })) { tab in
                if activeTabs.count < 5 {
                    activeTabs.append(tab)
                }
                showCreateCustomTab = false
            }
        }
    }
}

// MARK: - Create Custom Tab Sheet

struct CreateCustomTabSheet: View {
    let existingIds: Set<String>
    let onConfirm: (NavigationTab.Custom) -> Void
    @Environment(\.dismiss) var dismiss

    @State private var name: String = ""
    @State private var selectedIcon: String = "dashboard"

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }

    private var generatedId: String {
        let cleaned = trimmedName.lowercased().filter { c in
            c.isASCII && (c.isLetter || c.isNumber || c == "_")
        }
        return String(cleaned.prefix(30))
    }

    private var isValid: Bool {
        !trimmedName.isEmpty && !generatedId.isEmpty && !existingIds.contains(generatedId)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Tab Name") {
                    TextField("Name", text: $name)
                        .onChange(of: name) { newValue in
                            if newValue.count > 20 {
                                name = String(newValue.prefix(20))
                            }
                        }
                }

                Section("Icon") {
                    LazyVGrid(columns: [
                        GridItem(.adaptive(minimum: 50), spacing: 8)
                    ], spacing: 8) {
                        ForEach(Array(NavigationTab.companion.customIcons), id: \.self) { iconName in
                            let isSelected = iconName == selectedIcon
                            Button(action: { selectedIcon = iconName }) {
                                Image(systemName: sfSymbol(for: iconName))
                                    .font(.title2)
                                    .frame(width: 44, height: 44)
                                    .background(
                                        isSelected
                                            ? Color.accentColor.opacity(0.2)
                                            : Color(.systemGray5)
                                    )
                                    .clipShape(Circle())
                                    .overlay(
                                        isSelected
                                            ? Circle().stroke(Color.accentColor, lineWidth: 2)
                                            : nil
                                    )
                            }
                            .foregroundColor(isSelected ? .accentColor : .secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                if existingIds.contains(generatedId) && !trimmedName.isEmpty {
                    Section {
                        Text("A tab with this name already exists")
                            .foregroundColor(.red)
                            .font(.callout)
                    }
                }
            }
            .navigationTitle("Create Custom Tab")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        onConfirm(NavigationTab.Custom(
                            id: generatedId,
                            label: trimmedName,
                            iconName: selectedIcon
                        ))
                    }
                    .fontWeight(.bold)
                    .disabled(!isValid)
                }
            }
        }
    }
}
