// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import org.aaustralian.dieselbridge.api.DieselProviderResourceRequest
import org.aaustralian.dieselbridge.api.DieselPublicDecodeResult
import org.aaustralian.dieselbridge.api.DieselResourceKind
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate
import org.aaustralian.dieselbridge.protocol.DieselProtocolRules
import org.aaustralian.dieselbridge.protocol.DieselValue

internal object DieselIpcProviderCodec {
    fun decodeRequest(
        bundle: Bundle,
    ): DieselPublicDecodeResult<DieselProviderResourceRequest> {
        val id = bundle.getString("id")
            ?: return DieselPublicDecodeResult.Invalid("missing_id")
        val schema = bundle.getString("schema")
            ?: return DieselPublicDecodeResult.Invalid("missing_schema")
        val summary = bundle.getString("summary")
            ?: return DieselPublicDecodeResult.Invalid("missing_summary")

        val kind =
            when (bundle.getString("kind")?.lowercase()) {
                "state" -> DieselResourceKind.STATE
                "action" -> DieselResourceKind.ACTION
                "observation" -> DieselResourceKind.OBSERVATION
                "event" -> DieselResourceKind.EVENT
                "transport" -> DieselResourceKind.TRANSPORT
                else ->
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_kind",
                    )
            }

        return DieselPublicDecodeResult.Value(
            DieselProviderResourceRequest(
                id,
                kind,
                schema,
                summary,
            ),
        )
    }

    fun ok(
        registrationId: Long? = null,
    ): Bundle =
        Bundle().apply {
            putString("status", DieselIpcCodec.STATUS_OK)
            registrationId?.let {
                putLong("registrationId", it)
            }
        }

    fun rejected(
        reason: String,
    ): Bundle =
        Bundle().apply {
            putString("status", DieselIpcCodec.STATUS_REJECTED)
            putString("reason", reason)
        }

    fun decodeActionCompletion(
        bundle: Bundle,
    ): DieselPublicDecodeResult<DieselActionResult<DieselValue>> =
        when (bundle.getString("status")) {
            DieselIpcCodec.STATUS_OK -> {
                val encoded = bundle.getBundle("value")
                    ?: return DieselPublicDecodeResult.Invalid(
                        "missing_action_value",
                    )

                when (val decoded = DieselIpcCodec.decodeValue(encoded)) {
                    is DieselPublicDecodeResult.Invalid -> decoded
                    is DieselPublicDecodeResult.Value ->
                        DieselPublicDecodeResult.Value(
                            DieselActionResult.Success(decoded.value),
                        )
                }
            }

            DieselIpcCodec.STATUS_UNAVAILABLE ->
                DieselPublicDecodeResult.Value(
                    DieselActionResult.Unavailable,
                )

            DieselIpcCodec.STATUS_REJECTED ->
                DieselPublicDecodeResult.Value(
                    DieselActionResult.Rejected(
                        boundedText(bundle.getString("reason"))
                            ?: "external_provider_rejected",
                    ),
                )

            DieselIpcCodec.STATUS_FAILED ->
                DieselPublicDecodeResult.Value(
                    DieselActionResult.Failed(
                        "external_provider",
                        boundedText(bundle.getString("reason")),
                    ),
                )

            else ->
                DieselPublicDecodeResult.Invalid(
                    "invalid_action_result_status",
                )
        }

    fun decodeAvailability(
        value: String?,
    ): ProviderAvailability? =
        when (value?.lowercase()) {
            "available" -> ProviderAvailability.AVAILABLE
            "unavailable" -> ProviderAvailability.UNAVAILABLE
            "error" -> ProviderAvailability.ERROR
            else -> null
        }

    fun boundedReason(
        value: String?,
    ): String? = boundedText(value)

    fun decodeObservationUpdate(
        bundle: Bundle,
        logicalId: String,
        providerId: String,
        serverDroppedTotal: Long,
    ): DieselPublicDecodeResult<SensorObservationUpdate> =
        when (bundle.getString("type")) {
            "started" -> {
                val effective =
                    if (bundle.containsKey("effectiveSamplePeriodMs")) {
                        bundle.getLong("effectiveSamplePeriodMs")
                    } else {
                        null
                    }
                val configured =
                    if (bundle.containsKey("configuredSamplePeriodMs")) {
                        bundle.getLong("configuredSamplePeriodMs")
                    } else {
                        null
                    }

                if (effective != null && effective <= 0L) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_effective_period",
                    )
                }
                if (configured != null && configured <= 0L) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_configured_period",
                    )
                }

                DieselPublicDecodeResult.Value(
                    SensorObservationUpdate.Started(
                        effective,
                        configured,
                    ),
                )
            }

            "sample" -> {
                val raw = bundle.getDoubleArray("values")
                    ?: return DieselPublicDecodeResult.Invalid(
                        "missing_values",
                    )

                if (
                    raw.isEmpty() ||
                    raw.size > DieselProtocolRules.MAX_COLLECTION_ENTRIES
                ) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_values",
                    )
                }

                val values = raw.map { it.toFloat() }
                if (
                    raw.any { !it.isFinite() } ||
                    values.any { !it.isFinite() }
                ) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_values",
                    )
                }

                if (!bundle.containsKey("timestampNanos")) {
                    return DieselPublicDecodeResult.Invalid(
                        "missing_timestamp",
                    )
                }

                val timestampNanos = bundle.getLong("timestampNanos")
                if (timestampNanos < 0L) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_timestamp",
                    )
                }

                val accuracy =
                    if (bundle.containsKey("accuracy")) {
                        bundle.getInt("accuracy")
                    } else {
                        null
                    }

                val providerDropped =
                    if (bundle.containsKey("sourceDroppedTotal")) {
                        bundle.getLong("sourceDroppedTotal")
                    } else {
                        0L
                    }

                if (providerDropped < 0L) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_drop_count",
                    )
                }

                DieselPublicDecodeResult.Value(
                    SensorObservationUpdate.Sample(
                        reading =
                            SensorReading(
                                capabilityId =
                                    SensorCapabilityId(
                                        "sensor.$logicalId",
                                    ),
                                providerId = providerId,
                                values = values,
                                timestampNanos = timestampNanos,
                                accuracy = accuracy,
                                elapsedMs = 0L,
                            ),
                        sourceDroppedTotal =
                            saturatingAdd(
                                providerDropped,
                                serverDroppedTotal,
                            ),
                    ),
                )
            }

            "source_drops" -> {
                val providerDropped = bundle.getLong("sourceDroppedTotal")
                if (providerDropped < 0L) {
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_drop_count",
                    )
                }

                DieselPublicDecodeResult.Value(
                    SensorObservationUpdate.SourceDrops(
                        saturatingAdd(
                            providerDropped,
                            serverDroppedTotal,
                        ),
                    ),
                )
            }

            "permission_denied" ->
                DieselPublicDecodeResult.Value(
                    SensorObservationUpdate.PermissionDenied(
                        boundedText(
                            bundle.getString("requiredPermission"),
                        ),
                    ),
                )

            "unavailable" ->
                DieselPublicDecodeResult.Value(
                    SensorObservationUpdate.Unavailable(
                        boundedText(bundle.getString("reason")),
                    ),
                )

            "registration_rejected" ->
                DieselPublicDecodeResult.Value(
                    SensorObservationUpdate.RegistrationRejected(
                        boundedText(bundle.getString("reason")),
                    ),
                )

            else ->
                DieselPublicDecodeResult.Invalid(
                    "invalid_observation_update_type",
                )
        }

    private fun boundedText(
        value: String?,
    ): String? {
        if (value == null) return null
        if (
            value.toByteArray(Charsets.UTF_8).size >
            DieselProtocolRules.MAX_TEXT_VALUE_BYTES
        ) {
            return null
        }
        return value
    }

    private fun saturatingAdd(
        first: Long,
        second: Long,
    ): Long =
        if (Long.MAX_VALUE - first < second) {
            Long.MAX_VALUE
        } else {
            first + second
        }
}
