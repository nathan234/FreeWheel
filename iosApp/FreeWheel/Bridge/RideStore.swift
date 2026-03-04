import Foundation

struct RideMetadata: Codable, Identifiable {
    let id: String
    let fileName: String
    let startDate: Date
    let endDate: Date
    let duration: TimeInterval
    let distance: Double
    let maxSpeed: Double
    let avgSpeed: Double
    let sampleCount: Int
    let fileSize: Int64
    let maxCurrent: Double
    let maxPower: Double
    let maxPwm: Double
    let consumptionWh: Double
    let consumptionWhPerKm: Double

    init(id: String, fileName: String, startDate: Date, endDate: Date,
         duration: TimeInterval, distance: Double, maxSpeed: Double,
         avgSpeed: Double, sampleCount: Int, fileSize: Int64,
         maxCurrent: Double = 0, maxPower: Double = 0, maxPwm: Double = 0,
         consumptionWh: Double = 0, consumptionWhPerKm: Double = 0) {
        self.id = id
        self.fileName = fileName
        self.startDate = startDate
        self.endDate = endDate
        self.duration = duration
        self.distance = distance
        self.maxSpeed = maxSpeed
        self.avgSpeed = avgSpeed
        self.sampleCount = sampleCount
        self.fileSize = fileSize
        self.maxCurrent = maxCurrent
        self.maxPower = maxPower
        self.maxPwm = maxPwm
        self.consumptionWh = consumptionWh
        self.consumptionWhPerKm = consumptionWhPerKm
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        fileName = try c.decode(String.self, forKey: .fileName)
        startDate = try c.decode(Date.self, forKey: .startDate)
        endDate = try c.decode(Date.self, forKey: .endDate)
        duration = try c.decode(TimeInterval.self, forKey: .duration)
        distance = try c.decode(Double.self, forKey: .distance)
        maxSpeed = try c.decode(Double.self, forKey: .maxSpeed)
        avgSpeed = try c.decode(Double.self, forKey: .avgSpeed)
        sampleCount = try c.decode(Int.self, forKey: .sampleCount)
        fileSize = try c.decode(Int64.self, forKey: .fileSize)
        maxCurrent = try c.decodeIfPresent(Double.self, forKey: .maxCurrent) ?? 0
        maxPower = try c.decodeIfPresent(Double.self, forKey: .maxPower) ?? 0
        maxPwm = try c.decodeIfPresent(Double.self, forKey: .maxPwm) ?? 0
        consumptionWh = try c.decodeIfPresent(Double.self, forKey: .consumptionWh) ?? 0
        consumptionWhPerKm = try c.decodeIfPresent(Double.self, forKey: .consumptionWhPerKm) ?? 0
    }
}

@MainActor
class RideStore: ObservableObject {

    @Published var rides: [RideMetadata] = []

    private static let metadataFileName = "metadata.json"

    nonisolated init() {
        Task { @MainActor in
            loadRides()
        }
    }

    // MARK: - CRUD

    func addRide(_ ride: RideMetadata) {
        rides.insert(ride, at: 0)
        saveRides()
    }

    func deleteRide(at offsets: IndexSet) {
        let toDelete = offsets.map { rides[$0] }
        for ride in toDelete {
            let fileURL = Self.ridesDirectory().appendingPathComponent(ride.fileName)
            try? FileManager.default.removeItem(at: fileURL)
        }
        rides.remove(atOffsets: offsets)
        saveRides()
    }

    func fileURL(for ride: RideMetadata) -> URL {
        Self.ridesDirectory().appendingPathComponent(ride.fileName)
    }

    // MARK: - Persistence

    func loadRides() {
        let metaURL = Self.ridesDirectory().appendingPathComponent(Self.metadataFileName)
        guard let data = try? Data(contentsOf: metaURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let loaded = try? decoder.decode([RideMetadata].self, from: data) {
            rides = loaded.sorted { $0.startDate > $1.startDate }
        }
    }

    private func saveRides() {
        let ridesDir = Self.ridesDirectory()
        try? FileManager.default.createDirectory(at: ridesDir, withIntermediateDirectories: true)

        let metaURL = ridesDir.appendingPathComponent(Self.metadataFileName)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(rides) {
            try? data.write(to: metaURL)
        }
    }

    // MARK: - Directory

    static func ridesDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("rides")
    }
}
