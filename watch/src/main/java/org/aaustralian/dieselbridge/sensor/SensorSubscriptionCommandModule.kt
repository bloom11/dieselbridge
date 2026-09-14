// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlin.math.max
import org.aaustralian.dieselbridge.protocol.DieselCommandContext
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Thin public command surface for bounded logical sensor subscriptions.
 *
 * Provider selection, acquisition sharing, cadence negotiation and provider
 * failover remain owned by the shared observation runtime behind
 * [PublicSensorSubscriptionController].
 */
class SensorSubscriptionCommandModule(
    private val controller:
        PublicSensorSubscriptionController,
    private val monotonicMs: () -> Long = {
        System.nanoTime() /
            1_000_000L
    },
) : DieselCommandModule {

    override fun install(
        registry: DieselCommandRegistry,
    ) {
        registry.register(
            spec =
                DieselCommandSpec(
                    name =
                        COMMAND_SENSOR_SUBSCRIBE,
                    summary =
                        "Open a bounded logical sensor subscription",
                    metadata =
                        mapOf(
                            "domain" to
                                DieselValue.Text(
                                    "sensor",
                                ),
                            "effect" to
                                DieselValue.Text(
                                    "stream",
                                ),
                            "routing" to
                                DieselValue.Text(
                                    "automatic_provider_selection",
                                ),
                            "target" to
                                DieselValue.Text(
                                    "logical sensor name",
                                ),
                            "buffer" to
                                DieselValue.Text(
                                    "latest_only",
                                ),
                            "arguments" to
                                DieselValue.ObjectValue(
                                    mapOf(
                                        ARG_PERIOD_MS to
                                            DieselValue.Text(
                                                "integer 250..60000, default 1000",
                                            ),
                                        ARG_LEASE_MS to
                                            DieselValue.Text(
                                                "integer 5000..300000, default 60000",
                                            ),
                                    ),
                                ),
                        ),
                ),
            handler = {
                    context,
                ->
                subscribe(
                    context,
                )
            },
        )

        registry.register(
            spec =
                DieselCommandSpec(
                    name =
                        COMMAND_SENSOR_UNSUBSCRIBE,
                    summary =
                        "Close one logical sensor subscription",
                    metadata =
                        mapOf(
                            "domain" to
                                DieselValue.Text(
                                    "sensor",
                                ),
                            "effect" to
                                DieselValue.Text(
                                    "stream_control",
                                ),
                            "idempotent" to
                                DieselValue.Flag(
                                    true,
                                ),
                            "arguments" to
                                DieselValue.ObjectValue(
                                    mapOf(
                                        ARG_SUBSCRIPTION_ID to
                                            DieselValue.Text(
                                                "positive integer",
                                            ),
                                    ),
                                ),
                        ),
                ),
            handler = {
                    context,
                ->
                unsubscribe(
                    context,
                )
            },
        )

        registry.register(
            spec =
                DieselCommandSpec(
                    name =
                        COMMAND_SENSOR_SUBSCRIPTIONS,
                    summary =
                        "List active public logical sensor subscriptions",
                    metadata =
                        mapOf(
                            "domain" to
                                DieselValue.Text(
                                    "sensor",
                                ),
                            "effect" to
                                DieselValue.Text(
                                    "read_only",
                                ),
                            "bounded" to
                                DieselValue.Text(
                                    "maximum four entries",
                                ),
                        ),
                ),
            handler = {
                    context,
                ->
                subscriptions(
                    context,
                )
            },
        )
    }

    private fun subscribe(
        context: DieselCommandContext,
    ): DieselCommandResult {
        val logicalId =
            context.name
                ?: return invalidArguments()

        if (
            context.args.keys.any {
                it !in
                    SUBSCRIBE_ARGUMENTS
            }
        ) {
            return invalidArguments()
        }

        val periodMs =
            optionalLongArgument(
                context =
                    context,
                key =
                    ARG_PERIOD_MS,
            )
                ?: DEFAULT_PERIOD_MS

        val leaseMs =
            optionalLongArgument(
                context =
                    context,
                key =
                    ARG_LEASE_MS,
            )
                ?: DEFAULT_LEASE_MS

        if (
            context.args.containsKey(
                ARG_PERIOD_MS,
            ) &&
            context.args[
                ARG_PERIOD_MS
            ] !is
                DieselValue.Integer
        ) {
            return invalidArguments()
        }

        if (
            context.args.containsKey(
                ARG_LEASE_MS,
            ) &&
            context.args[
                ARG_LEASE_MS
            ] !is
                DieselValue.Integer
        ) {
            return invalidArguments()
        }

        return when (
            val result =
                controller.subscribe(
                    logicalId =
                        logicalId,
                    periodMs =
                        periodMs,
                    leaseMs =
                        leaseMs,
                )
        ) {
            is PublicSensorSubscribeResult.Opened ->
                DieselCommandResult.ok(
                    linkedMapOf(
                        "subscriptionId" to
                            DieselValue.Integer(
                                result.snapshot
                                    .subscriptionId,
                            ),
                        "capability" to
                            DieselValue.Text(
                                "sensor." +
                                    result.snapshot
                                        .logicalId,
                            ),
                        "requestedPeriodMs" to
                            DieselValue.Integer(
                                result.snapshot
                                    .requestedPeriodMs,
                            ),
                        "leaseMs" to
                            DieselValue.Integer(
                                result.snapshot
                                    .leaseMs,
                            ),
                    ),
                )

            is PublicSensorSubscribeResult.Rejected ->
                encodeRejection(
                    result.reason,
                )
        }
    }

    private fun unsubscribe(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (
            context.name != null ||
            context.args.keys !=
            setOf(
                ARG_SUBSCRIPTION_ID,
            )
        ) {
            return invalidArguments()
        }

        val subscriptionId =
            (
                context.args[
                    ARG_SUBSCRIPTION_ID
                ] as?
                    DieselValue.Integer
            )
                ?.value
                ?.takeIf {
                    it > 0L
                }
                ?: return invalidArguments()

        val wasActive =
            controller.unsubscribe(
                subscriptionId,
            )

        return DieselCommandResult.ok(
            linkedMapOf(
                "subscriptionId" to
                    DieselValue.Integer(
                        subscriptionId,
                    ),
                "closed" to
                    DieselValue.Flag(
                        true,
                    ),
                "wasActive" to
                    DieselValue.Flag(
                        wasActive,
                    ),
            ),
        )
    }

    private fun subscriptions(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (
            context.name != null ||
            context.args.isNotEmpty()
        ) {
            return invalidArguments()
        }

        val nowMs =
            monotonicMs()

        val snapshots =
            controller
                .snapshots()
                .take(
                    MAX_PUBLIC_SUBSCRIPTIONS,
                )

        return DieselCommandResult.ok(
            linkedMapOf(
                "count" to
                    DieselValue.Integer(
                        snapshots.size
                            .toLong(),
                    ),
                "subscriptions" to
                    DieselValue.ListValue(
                        snapshots.map {
                            encodeSnapshot(
                                snapshot =
                                    it,
                                nowMs =
                                    nowMs,
                            )
                        },
                    ),
            ),
        )
    }

    private fun encodeSnapshot(
        snapshot:
            PublicSensorSubscriptionSnapshot,
        nowMs: Long,
    ): DieselValue.ObjectValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "subscriptionId" to
                    DieselValue.Integer(
                        snapshot
                            .subscriptionId,
                    ),
                "target" to
                    DieselValue.Text(
                        snapshot.logicalId,
                    ),
                "phase" to
                    DieselValue.Text(
                        snapshot.phase
                            .name
                            .lowercase(),
                    ),
                "providerId" to
                    (
                        snapshot.providerId
                            ?.let(
                                DieselValue::Text,
                            )
                            ?: DieselValue.Null
                    ),
                "requestedPeriodMs" to
                    DieselValue.Integer(
                        snapshot
                            .requestedPeriodMs,
                    ),
                "acquisitionPeriodMs" to
                    nullableInteger(
                        snapshot
                            .acquisitionPeriodMs,
                    ),
                "providerEffectivePeriodMs" to
                    nullableInteger(
                        snapshot
                            .providerEffectivePeriodMs,
                    ),
                "expiresInMs" to
                    DieselValue.Integer(
                        max(
                            0L,
                            snapshot
                                .expiresAtMs -
                                nowMs,
                        ),
                    ),
                /*
                 * Keep original Diesel v1 fields stable. Provider loss was
                 * added later and therefore gets an additive explicit field.
                 */
                "sourceDroppedTotal" to
                    DieselValue.Integer(
                        snapshot
                            .sourceDroppedTotal,
                    ),
                "providerDroppedTotal" to
                    DieselValue.Integer(
                        snapshot
                            .providerDroppedTotal,
                    ),
                "subscriptionDroppedTotal" to
                    DieselValue.Integer(
                        snapshot
                            .subscriptionDroppedTotal,
                    ),
                "transportDroppedTotal" to
                    DieselValue.Integer(
                        snapshot
                            .transportDroppedTotal,
                    ),
                "droppedTotal" to
                    DieselValue.Integer(
                        snapshot
                            .subscriptionDroppedTotal +
                            snapshot
                                .transportDroppedTotal,
                    ),
                "allDroppedTotal" to
                    DieselValue.Integer(
                        snapshot
                            .providerDroppedTotal +
                            snapshot
                                .subscriptionDroppedTotal +
                            snapshot
                                .transportDroppedTotal,
                    ),
                "reason" to
                    (
                        snapshot.reason
                            ?.let(
                                DieselValue::Text,
                            )
                            ?: DieselValue.Null
                    ),
            ),
        )

    private fun encodeRejection(
        reason:
            PublicSensorSubscriptionRejectReason,
    ): DieselCommandResult =
        when (reason) {
            PublicSensorSubscriptionRejectReason
                .UNKNOWN_SENSOR ->
                DieselCommandResult(
                    status =
                        DieselResponseStatus
                            .UNKNOWN_TARGET,
                    data =
                        mapOf(
                            "reason" to
                                DieselValue.Text(
                                    "unknown_sensor_capability",
                                ),
                        ),
                )

            PublicSensorSubscriptionRejectReason
                .SUBSCRIPTION_LIMIT_REACHED ->
                DieselCommandResult(
                    status =
                        DieselResponseStatus
                            .RATE_LIMITED,
                    data =
                        mapOf(
                            "reason" to
                                DieselValue.Text(
                                    "sensor_subscription_limit",
                                ),
                        ),
                )

            PublicSensorSubscriptionRejectReason
                .PERIOD_OUT_OF_BOUNDS,
            PublicSensorSubscriptionRejectReason
                .LEASE_OUT_OF_BOUNDS,
            ->
                invalidArguments()
        }

    private fun optionalLongArgument(
        context: DieselCommandContext,
        key: String,
    ): Long? =
        (
            context.args[
                key
            ] as?
                DieselValue.Integer
        )
            ?.value

    private fun nullableInteger(
        value: Long?,
    ): DieselValue =
        value
            ?.let(
                DieselValue::Integer,
            )
            ?: DieselValue.Null

    private fun invalidArguments():
        DieselCommandResult =
        DieselCommandResult(
            status =
                DieselResponseStatus
                    .INVALID_REQUEST,
            data =
                mapOf(
                    "reason" to
                        DieselValue.Text(
                            "invalid_args",
                        ),
                ),
        )

    companion object {
        const val COMMAND_SENSOR_SUBSCRIBE =
            "sensor.subscribe"

        const val COMMAND_SENSOR_UNSUBSCRIBE =
            "sensor.unsubscribe"

        const val COMMAND_SENSOR_SUBSCRIPTIONS =
            "sensor.subscriptions"

        const val ARG_PERIOD_MS =
            "periodMs"

        const val ARG_LEASE_MS =
            "leaseMs"

        const val ARG_SUBSCRIPTION_ID =
            "subscriptionId"

        const val DEFAULT_PERIOD_MS =
            1_000L

        const val DEFAULT_LEASE_MS =
            60_000L

        const val MAX_PUBLIC_SUBSCRIPTIONS =
            4

        private val SUBSCRIBE_ARGUMENTS =
            setOf(
                ARG_PERIOD_MS,
                ARG_LEASE_MS,
            )
    }
}
