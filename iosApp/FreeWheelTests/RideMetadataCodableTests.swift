import XCTest
@testable import FreeWheel

final class RideMetadataCodableTests: XCTestCase {

    private func decode(_ json: String) throws -> RideMetadata {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(RideMetadata.self, from: Data(json.utf8))
    }

    func test_decode_legacyJson_appliesDefaultSourceOwnLog() throws {
        let json = """
        {
          "id": "abc",
          "fileName": "ride.csv",
          "startDate": "2026-01-01T00:00:00Z",
          "endDate": "2026-01-01T00:05:00Z",
          "duration": 300,
          "distance": 1.5,
          "maxSpeed": 30.0,
          "avgSpeed": 18.0,
          "sampleCount": 100,
          "fileSize": 1024
        }
        """
        let ride = try decode(json)
        XCTAssertEqual(ride.source, .ownLog)
        XCTAssertEqual(ride.maxCurrent, 0)
        XCTAssertEqual(ride.maxPower, 0)
        XCTAssertEqual(ride.maxPwm, 0)
        XCTAssertEqual(ride.consumptionWh, 0)
        XCTAssertEqual(ride.consumptionWhPerKm, 0)
    }

    func test_decode_withImportedSource_isPreserved() throws {
        let json = """
        {
          "id": "abc",
          "fileName": "ride.csv",
          "startDate": "2026-01-01T00:00:00Z",
          "endDate": "2026-01-01T00:05:00Z",
          "duration": 300,
          "distance": 1.5,
          "maxSpeed": 30.0,
          "avgSpeed": 18.0,
          "sampleCount": 100,
          "fileSize": 1024,
          "source": "IMPORTED"
        }
        """
        let ride = try decode(json)
        XCTAssertEqual(ride.source, .imported)
    }

    func test_roundTrip_preservesAllFields() throws {
        let original = RideMetadata(
            id: "r1",
            fileName: "ride.csv",
            startDate: Date(timeIntervalSince1970: 1_700_000_000),
            endDate: Date(timeIntervalSince1970: 1_700_000_300),
            duration: 300,
            distance: 1.5,
            maxSpeed: 30.0,
            avgSpeed: 18.0,
            sampleCount: 100,
            fileSize: 1024,
            maxCurrent: 7.5,
            maxPower: 1200,
            maxPwm: 88,
            consumptionWh: 42.5,
            consumptionWhPerKm: 28.3,
            source: .imported
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(original)

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(RideMetadata.self, from: data)

        XCTAssertEqual(decoded.id, original.id)
        XCTAssertEqual(decoded.fileName, original.fileName)
        XCTAssertEqual(decoded.startDate.timeIntervalSince1970, original.startDate.timeIntervalSince1970, accuracy: 0.001)
        XCTAssertEqual(decoded.endDate.timeIntervalSince1970, original.endDate.timeIntervalSince1970, accuracy: 0.001)
        XCTAssertEqual(decoded.duration, original.duration)
        XCTAssertEqual(decoded.distance, original.distance)
        XCTAssertEqual(decoded.maxSpeed, original.maxSpeed)
        XCTAssertEqual(decoded.avgSpeed, original.avgSpeed)
        XCTAssertEqual(decoded.sampleCount, original.sampleCount)
        XCTAssertEqual(decoded.fileSize, original.fileSize)
        XCTAssertEqual(decoded.maxCurrent, original.maxCurrent)
        XCTAssertEqual(decoded.maxPower, original.maxPower)
        XCTAssertEqual(decoded.maxPwm, original.maxPwm)
        XCTAssertEqual(decoded.consumptionWh, original.consumptionWh)
        XCTAssertEqual(decoded.consumptionWhPerKm, original.consumptionWhPerKm)
        XCTAssertEqual(decoded.source, original.source)
    }
}
