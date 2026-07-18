import SwiftUI
import FreeWheelCore

/// Sheet shown when `ConnectionStateWrapper.wheelTypeRequired` fires — the
/// topology fingerprint matcher and name detector both miss and no
/// `SAVED_PROFILE` / `EXPLICIT_API` hint exists. The user picks a wheel type;
/// confirmation runs ConfigureBle against the still-live peripheral and
/// transitions to `.connected`. Dismissing without picking calls
/// `disconnect()` — Pass 4 plan guardrail: never auto-pick.
struct WheelTypePickerSheet: View {
    let address: String
    let deviceName: String?
    let onConfirm: (WheelType) -> Void
    let onDismiss: () -> Void

    /// Pickable wheel types. Excludes `Unknown` and `GOTWAY_VIRTUAL`
    /// (sentinels — `forType` returns nil for them).
    private static let pickable: [WheelType] = [
        .kingsong,
        .gotway,
        .veteran,
        .leaperkim,
        .inmotion,
        .lorin,
        .ninebot,
        .ninebotZ,
    ]

    private var likelyType: WheelType? {
        WheelConnectionManagerHelper.shared.deriveWheelTypeFromName(name: deviceName)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                let nameSuffix: String = {
                    if let deviceName, !deviceName.isEmpty {
                        return " for \"\(deviceName)\""
                    }
                    return ""
                }()
                Text("We couldn't auto-detect the protocol\(nameSuffix). Pick the matching wheel type to finish connecting.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)

                List {
                    Section {
                        ForEach(Self.pickable, id: \.self) { type in
                            Button {
                                onConfirm(type)
                            } label: {
                                HStack {
                                    Text(pickerLabel(type))
                                        .foregroundColor(.primary)
                                    if type == likelyType {
                                        Spacer()
                                        Text("Likely")
                                            .font(.caption)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 4)
                                            .background(Color.accentColor.opacity(0.2))
                                            .foregroundColor(.accentColor)
                                            .clipShape(Capsule())
                                    }
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
            .padding(.top, 8)
            .navigationTitle("Select wheel type")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDismiss() }
                }
            }
        }
    }

    /// Picker-specific labels. `WheelType.displayName` collapses protocol
    /// pairs (e.g. NINEBOT / NINEBOT_Z both → "Ninebot"); the picker has to
    /// disambiguate so the user can tell legacy/V1 apart from V2/Z protocols.
    private func pickerLabel(_ type: WheelType) -> String {
        switch type {
        case .kingsong: return "KingSong"
        case .gotway: return "Begode / Gotway"
        case .veteran: return "Veteran (Sherman/Lynx/Patton)"
        case .leaperkim: return "Leaperkim CAN (newer firmware)"
        case .inmotion: return "InMotion V1 (V8/V10/V11)"
        case .lorin: return "InMotion Lorin (V12+, P6)"
        case .ninebot: return "Ninebot (legacy)"
        case .ninebotZ: return "Ninebot Z (Z10+)"
        case .gotwayVirtual, .unknown: return ""
        default: return type.displayName
        }
    }
}

#Preview {
    WheelTypePickerSheet(
        address: "AA:BB:CC:DD:EE:FF",
        deviceName: "S22-3A0F",
        onConfirm: { _ in },
        onDismiss: { }
    )
}
