import XCTest
import FreeWheelCore
@testable import FreeWheel

@MainActor
final class DashboardLayoutMigrationTests: XCTestCase {

    private let legacyKey = PreferenceKeys.shared.DASHBOARD_LAYOUT
    private let canonical = "AA:BB:CC:DD:EE:FF_dashboard_layout"

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: legacyKey)
        UserDefaults.standard.removeObject(forKey: canonical)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: legacyKey)
        UserDefaults.standard.removeObject(forKey: canonical)
        super.tearDown()
    }

    func test_noLegacyValue_returnsNil() {
        let result = WheelManager.migrateLegacyDashboardLayoutKey(canonical: canonical)
        XCTAssertNil(result)
        XCTAssertNil(UserDefaults.standard.string(forKey: canonical))
    }

    func test_legacyValueMovesToCanonical_andIsRemoved() {
        let payload = "serialized_layout_blob"
        UserDefaults.standard.set(payload, forKey: legacyKey)

        let result = WheelManager.migrateLegacyDashboardLayoutKey(canonical: canonical)

        XCTAssertEqual(result, payload)
        XCTAssertEqual(UserDefaults.standard.string(forKey: canonical), payload)
        XCTAssertNil(UserDefaults.standard.string(forKey: legacyKey))
    }

    func test_canonicalEqualsLegacyKey_returnsNilWithoutMigrating() {
        // No-wheel-anchor case: canonical IS the bare legacy key. Migration
        // must skip — otherwise the fresh-install write target is wiped.
        UserDefaults.standard.set("fresh_install_layout", forKey: legacyKey)

        let result = WheelManager.migrateLegacyDashboardLayoutKey(canonical: legacyKey)

        XCTAssertNil(result)
        XCTAssertEqual(UserDefaults.standard.string(forKey: legacyKey), "fresh_install_layout")
    }
}
