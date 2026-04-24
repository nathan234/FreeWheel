package org.freewheel.core.location

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OcmPoi(
    @SerialName("ID") val id: Int,
    @SerialName("UUID") val uuid: String? = null,
    @SerialName("AddressInfo") val addressInfo: OcmAddressInfo? = null,
    @SerialName("OperatorInfo") val operatorInfo: OcmOperatorInfo? = null,
    @SerialName("UsageType") val usageType: OcmUsageType? = null,
    @SerialName("Connections") val connections: List<OcmConnection> = emptyList(),
)

@Serializable
internal data class OcmAddressInfo(
    @SerialName("Title") val title: String? = null,
    @SerialName("AddressLine1") val addressLine1: String? = null,
    @SerialName("Town") val town: String? = null,
    @SerialName("StateOrProvince") val stateOrProvince: String? = null,
    @SerialName("Latitude") val latitude: Double? = null,
    @SerialName("Longitude") val longitude: Double? = null,
    @SerialName("Distance") val distance: Double? = null,
)

@Serializable
internal data class OcmOperatorInfo(
    @SerialName("Title") val title: String? = null,
)

@Serializable
internal data class OcmUsageType(
    @SerialName("IsMembershipRequired") val isMembershipRequired: Boolean? = null,
    @SerialName("IsAccessKeyRequired") val isAccessKeyRequired: Boolean? = null,
    @SerialName("IsPrivateIndividuals") val isPrivateIndividuals: Boolean? = null,
    @SerialName("Title") val title: String? = null,
)

@Serializable
internal data class OcmConnection(
    @SerialName("ConnectionTypeID") val connectionTypeId: Int? = null,
    @SerialName("PowerKW") val powerKw: Double? = null,
)

/**
 * Maps OpenChargeMap ConnectionTypeID → an EUC-relevant ConnectorType, or null
 * when the connector is not useful for EUCs (CCS, CHAdeMO, Tesla, etc.).
 *
 * IDs taken from https://api.openchargemap.io/v3/referencedata/ (ConnectionTypes).
 */
internal fun ocmConnectorType(connectionTypeId: Int?): ConnectorType? = when (connectionTypeId) {
    1, 15 -> ConnectorType.Type1J1772
    25, 32 -> ConnectorType.Type2Mennekes
    // Household outlets EUC charging bricks can plug into
    //   7  = NEMA 5-15 (US)
    //   8  = NEMA 5-20 (US)
    //   9  = BS 1363 Type G (UK)
    //  13  = CEE 7/4 Schuko (EU / Germany)
    //  14  = CEE 7/5 (France / Belgium)
    7, 8, 9, 13, 14 -> ConnectorType.DomesticSocket
    else -> null
}

internal fun OcmUsageType?.toAccessType(): AccessType {
    if (this == null) return AccessType.Unknown
    return when {
        isPrivateIndividuals == true -> AccessType.PrivateRestricted
        isMembershipRequired == true || isAccessKeyRequired == true -> AccessType.PublicMembership
        title?.contains("Private", ignoreCase = true) == true -> AccessType.PrivateRestricted
        title?.contains("Public", ignoreCase = true) == true -> AccessType.Public
        else -> AccessType.Unknown
    }
}

internal fun OcmPoi.toChargingStation(): ChargingStation? {
    val info = addressInfo ?: return null
    val lat = info.latitude ?: return null
    val lng = info.longitude ?: return null

    val relevant = connections.mapNotNull { ocmConnectorType(it.connectionTypeId) }.distinct()
    if (relevant.isEmpty()) return null

    val addressParts = listOfNotNull(
        info.addressLine1?.takeIf { it.isNotBlank() },
        info.town?.takeIf { it.isNotBlank() },
        info.stateOrProvince?.takeIf { it.isNotBlank() },
    )
    val address = if (addressParts.isEmpty()) null else addressParts.joinToString(", ")

    return ChargingStation(
        id = id.toString(),
        name = info.title?.takeIf { it.isNotBlank() } ?: "Charging station",
        latitude = lat,
        longitude = lng,
        address = address,
        operator = operatorInfo?.title?.takeIf { it.isNotBlank() },
        accessType = usageType.toAccessType(),
        connectors = relevant,
        distanceKm = info.distance,
    )
}
