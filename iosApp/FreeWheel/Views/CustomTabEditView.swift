import SwiftUI
import FreeWheelCore

/// Edit screen for a custom tab's layout.
/// Thin wrapper around LayoutEditorBody with custom-tab-specific load/save.
struct CustomTabEditView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @Environment(\.dismiss) var dismiss
    let tabId: String

    private var tabLabel: String {
        for tab in Array(wheelManager.navigationConfig.tabs) {
            if let custom = tab as? NavigationTab.Custom, custom.id == tabId {
                return custom.label
            }
        }
        return "Custom"
    }

    private var currentLayout: DashboardLayout {
        wheelManager.customTabLayouts[tabId] ?? DashboardLayout.companion.default()
    }

    var body: some View {
        LayoutEditorBody(
            title: "Edit \(tabLabel)",
            initialLayout: currentLayout,
            wheelType: wheelManager.identity.wheelType,
            onSave: { layout in
                wheelManager.saveCustomTabLayout(tabId: tabId, layout: layout)
                dismiss()
            },
            onCancel: { dismiss() }
        )
    }
}
