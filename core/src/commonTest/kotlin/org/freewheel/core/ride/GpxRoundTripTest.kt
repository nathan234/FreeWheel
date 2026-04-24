package org.freewheel.core.ride

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxRoundTripTest {

    @Test
    fun writer_emitsAllManifestFieldsAndNamespace() {
        val gpx = GpxWriter.write(FIXTURE_BUNDLE)

        assertContains(gpx, "xmlns=\"http://www.topografix.com/GPX/1/1\"")
        assertContains(gpx, "xmlns:fw=\"https://freewheel.app/gpx/v1\"")
        assertContains(gpx, "<fw:rideId>550e8400-e29b-41d4-a716-446655440000</fw:rideId>")
        assertContains(gpx, "<fw:wheelType>KINGSONG</fw:wheelType>")
        assertContains(gpx, "<fw:wheelName>KS-S22</fw:wheelName>")
        assertContains(gpx, "<fw:distanceMeters>12450</fw:distanceMeters>")
        assertContains(gpx, "<fw:durationMs>2580000</fw:durationMs>")
        assertContains(gpx, "<fw:schemaVersion>1</fw:schemaVersion>")
    }

    @Test
    fun writer_emitsTrkptWithExtensions() {
        val gpx = GpxWriter.write(FIXTURE_BUNDLE)

        assertContains(gpx, "<trkpt lat=\"37.7749\" lon=\"-122.4194\">")
        assertContains(gpx, "<ele>82.3</ele>")
        assertContains(gpx, "<fw:speedKmh>32.5</fw:speedKmh>")
        assertContains(gpx, "<fw:batteryPct>78.0</fw:batteryPct>")
        assertContains(gpx, "<fw:pwmPct>42.0</fw:pwmPct>")
        assertContains(gpx, "<fw:powerW>1230.0</fw:powerW>")
    }

    @Test
    fun writer_skipsNullTelemetryFields() {
        val minimal = FIXTURE_BUNDLE.copy(
            samples = listOf(
                RideSample(
                    timestampMs = 1_700_000_000_000L,
                    latitude = 37.0,
                    longitude = -122.0,
                    // no telemetry
                )
            )
        )

        val gpx = GpxWriter.write(minimal)

        assertTrue(!gpx.contains("<fw:speedKmh>"), "speed should be omitted")
        assertTrue(!gpx.contains("<fw:batteryPct>"), "battery should be omitted")
        assertTrue(!gpx.contains("<ele>"), "elevation should be omitted")
    }

    @Test
    fun roundTrip_preservesManifestAndAllSamples() {
        val gpx = GpxWriter.write(FIXTURE_BUNDLE)
        val parsed = GpxReader.parse(gpx)

        assertNotNull(parsed)
        assertEquals(FIXTURE_BUNDLE.manifest.rideId, parsed.manifest.rideId)
        assertEquals(FIXTURE_BUNDLE.manifest.name, parsed.manifest.name)
        assertEquals(FIXTURE_BUNDLE.manifest.wheelType, parsed.manifest.wheelType)
        assertEquals(FIXTURE_BUNDLE.manifest.wheelName, parsed.manifest.wheelName)
        assertEquals(FIXTURE_BUNDLE.manifest.distanceMeters, parsed.manifest.distanceMeters)
        assertEquals(FIXTURE_BUNDLE.manifest.durationMs, parsed.manifest.durationMs)
        assertEquals(FIXTURE_BUNDLE.samples.size, parsed.samples.size)

        FIXTURE_BUNDLE.samples.zip(parsed.samples).forEach { (written, read) ->
            assertEquals(written.latitude, read.latitude, 1e-9)
            assertEquals(written.longitude, read.longitude, 1e-9)
            assertEquals(written.timestampMs, read.timestampMs)
            assertEquals(written.speedKmh, read.speedKmh)
            assertEquals(written.batteryPct, read.batteryPct)
            assertEquals(written.pwmPct, read.pwmPct)
            assertEquals(written.powerW, read.powerW)
            assertEquals(written.elevationMeters, read.elevationMeters)
        }
    }

    @Test
    fun reader_parsesStravaStyleGpxWithNoExtensions() {
        val parsed = GpxReader.parse(STRAVA_STYLE_GPX)

        assertNotNull(parsed)
        // Strava files have no rideId — reader must synthesize one so that
        // downstream code doesn't need to special-case.
        assertTrue(parsed.manifest.rideId.isNotBlank())
        assertNull(parsed.manifest.wheelType)
        assertEquals(3, parsed.samples.size)
        parsed.samples.forEach { sample ->
            assertNull(sample.speedKmh)
            assertNull(sample.batteryPct)
        }
        assertEquals(37.7749, parsed.samples.first().latitude, 1e-6)
    }

    @Test
    fun reader_rejectsMalformedXml() {
        assertNull(GpxReader.parse("this is not xml"))
        assertNull(GpxReader.parse("<gpx><trkpt"))
        assertNull(GpxReader.parse(""))
    }

    @Test
    fun reader_isLenientOnUnknownExtensions() {
        // Real-world case: a Garmin export with Garmin's own extensions present
        // alongside ours. Reader must ignore the Garmin bits, keep the fw bits.
        val hybrid = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="GarminConnect"
                 xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:fw="https://freewheel.app/gpx/v1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <metadata>
                <fw:rideId>deadbeef-1234-5678-90ab-cdef12345678</fw:rideId>
                <time>2026-04-24T18:30:00Z</time>
              </metadata>
              <trk><trkseg>
                <trkpt lat="37.0" lon="-122.0">
                  <time>2026-04-24T18:30:01Z</time>
                  <extensions>
                    <gpxtpx:TrackPointExtension><gpxtpx:hr>142</gpxtpx:hr></gpxtpx:TrackPointExtension>
                    <fw:speedKmh>28.0</fw:speedKmh>
                  </extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxReader.parse(hybrid)

        assertNotNull(parsed)
        assertEquals("deadbeef-1234-5678-90ab-cdef12345678", parsed.manifest.rideId)
        assertEquals(1, parsed.samples.size)
        assertEquals(28.0, parsed.samples.first().speedKmh)
    }

    @Test
    fun reader_dedup_sameFileParsesToSameRideId() {
        val gpx = GpxWriter.write(FIXTURE_BUNDLE)

        val first = GpxReader.parse(gpx)
        val second = GpxReader.parse(gpx)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.manifest.rideId, second.manifest.rideId)
    }

    @Test
    fun writer_escapesSpecialCharactersInRideName() {
        val withAngry = FIXTURE_BUNDLE.copy(
            manifest = FIXTURE_BUNDLE.manifest.copy(name = "Evening & <fun> \"ride\"")
        )

        val gpx = GpxWriter.write(withAngry)
        val parsed = GpxReader.parse(gpx)

        assertNotNull(parsed)
        assertEquals("Evening & <fun> \"ride\"", parsed.manifest.name)
    }

    companion object {
        private val FIXTURE_BUNDLE = RideBundle(
            manifest = RideManifest(
                rideId = "550e8400-e29b-41d4-a716-446655440000",
                name = "Evening loop",
                startedAtUtc = 1_745_516_400_000L, // 2025-04-24T18:00:00Z
                wheelType = "KINGSONG",
                wheelName = "KS-S22",
                wheelAddress = "AA:BB:CC:DD:EE:FF",
                distanceMeters = 12_450L,
                durationMs = 2_580_000L,
                appVersion = "0.1.0",
            ),
            samples = listOf(
                RideSample(
                    timestampMs = 1_745_516_401_000L,
                    latitude = 37.7749,
                    longitude = -122.4194,
                    elevationMeters = 82.3,
                    speedKmh = 32.5,
                    batteryPct = 78.0,
                    pwmPct = 42.0,
                    powerW = 1230.0,
                    voltageV = 83.4,
                    currentA = 14.8,
                    motorTempC = 55.0,
                    boardTempC = 48.0,
                ),
                RideSample(
                    timestampMs = 1_745_516_402_000L,
                    latitude = 37.7750,
                    longitude = -122.4195,
                    elevationMeters = 82.5,
                    speedKmh = 33.0,
                    batteryPct = 77.9,
                    pwmPct = 43.5,
                    powerW = 1275.0,
                    voltageV = 83.3,
                    currentA = 15.3,
                    motorTempC = 55.0,
                    boardTempC = 48.1,
                ),
                RideSample(
                    timestampMs = 1_745_516_403_000L,
                    latitude = 37.7751,
                    longitude = -122.4196,
                    elevationMeters = 82.8,
                    speedKmh = 33.4,
                    batteryPct = 77.8,
                    pwmPct = 44.0,
                    powerW = 1310.0,
                    voltageV = 83.2,
                    currentA = 15.7,
                    motorTempC = 56.0,
                    boardTempC = 48.2,
                ),
            ),
        )

        private val STRAVA_STYLE_GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="StravaGPX"
                 xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <time>2026-04-24T18:30:00Z</time>
              </metadata>
              <trk>
                <name>Morning Ride</name>
                <type>1</type>
                <trkseg>
                  <trkpt lat="37.7749" lon="-122.4194">
                    <ele>82.3</ele>
                    <time>2026-04-24T18:30:01Z</time>
                  </trkpt>
                  <trkpt lat="37.7750" lon="-122.4195">
                    <ele>82.5</ele>
                    <time>2026-04-24T18:30:02Z</time>
                  </trkpt>
                  <trkpt lat="37.7751" lon="-122.4196">
                    <ele>82.8</ele>
                    <time>2026-04-24T18:30:03Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
    }
}
