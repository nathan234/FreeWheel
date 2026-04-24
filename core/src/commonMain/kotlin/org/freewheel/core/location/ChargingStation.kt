package org.freewheel.core.location

data class ChargingStation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val operator: String?,
    val accessType: AccessType,
    val connectors: List<ConnectorType>,
    val distanceKm: Double? = null,
)

enum class AccessType {
    Public,
    PublicMembership,
    PrivateRestricted,
    Unknown,
}

/**
 * Connector types that matter for EUC riders. EUCs charge via a brick with a
 * standard wall plug or a Type 2 outlet — skip CCS/CHAdeMO/Tesla since those
 * don't help us.
 */
enum class ConnectorType(val displayName: String) {
    Type1J1772("Type 1 (J1772)"),
    Type2Mennekes("Type 2 (Mennekes)"),
    DomesticSocket("Wall outlet"),
    Other("Other"),
}
