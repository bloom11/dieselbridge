// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.companion

import org.aaustralian.dieselbridge.platform.state.DieselStateSnapshot
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

class CompanionAlarmCommandModule(
    private val owner: CompanionAlarmStateOwner,
) : DieselCommandModule {

    override fun install(registry: DieselCommandRegistry) {
        registry.register(
            DieselCommandSpec(
                name = COMMAND_SYNC,
                summary =
                    "Replace synchronized companion next-alarm state",
                metadata =
                    mapOf(
                        "effect" to
                            DieselValue.Text("state_sync"),
                    ),
            ),
        ) { context ->
            if (context.name != null) {
                invalid("target_not_allowed")
            } else {
                sync(context.args)
            }
        }

        registry.register(
            DieselCommandSpec(
                name = COMMAND_GET,
                summary =
                    "Return synchronized companion next-alarm state",
                metadata =
                    mapOf(
                        "effect" to
                            DieselValue.Text("read_only"),
                    ),
            ),
        ) { context ->
            if (
                context.name != null ||
                context.args.isNotEmpty()
            ) {
                invalid("invalid_args")
            } else {
                current()
            }
        }
    }

    private fun sync(
        args: Map<String, DieselValue>,
    ): DieselCommandResult {
        val kind =
            (args["kind"] as? DieselValue.Text)
                ?.value
                ?.let(CompanionAlarmKind::fromWire)
                ?: return invalid("invalid_kind")

        val observedAtMs =
            (args["observedAtMs"] as? DieselValue.Integer)
                ?.value
                ?: return invalid("missing_observed_at")

        val allowed =
            when (kind) {
                CompanionAlarmKind.SCHEDULED ->
                    setOf(
                        "kind",
                        "triggerAtMs",
                        "observedAtMs",
                    )
                CompanionAlarmKind.NONE ->
                    setOf(
                        "kind",
                        "observedAtMs",
                    )
                CompanionAlarmKind.UNAVAILABLE ->
                    setOf(
                        "kind",
                        "reason",
                        "observedAtMs",
                    )
            }

        if (args.keys != allowed) {
            return invalid("unexpected_args")
        }

        val triggerAtMs =
            if (kind == CompanionAlarmKind.SCHEDULED) {
                (args["triggerAtMs"] as?
                    DieselValue.Integer)
                    ?.value
                    ?: return invalid("missing_trigger_at")
            } else {
                null
            }

        val reason =
            if (kind == CompanionAlarmKind.UNAVAILABLE) {
                (args["reason"] as? DieselValue.Text)
                    ?.value
                    ?.takeIf {
                        it.isNotBlank() && it.length <= 128
                    }
                    ?: return invalid("invalid_reason")
            } else {
                null
            }

        val applied =
            runCatching {
                owner.apply(
                    kind = kind,
                    triggerAtMs = triggerAtMs,
                    observedAtMs = observedAtMs,
                    reason = reason,
                )
            }.getOrElse {
                return invalid("invalid_alarm_state")
            }

        return DieselCommandResult.ok(
            data = snapshotData(applied.snapshot),
        )
    }

    private fun current(): DieselCommandResult {
        val snapshot =
            owner.current()
                ?: return DieselCommandResult.ok(
                    data =
                        mapOf(
                            "synced" to
                                DieselValue.Flag(false),
                        ),
                )

        return DieselCommandResult.ok(
            data = snapshotData(snapshot),
        )
    }

    private fun snapshotData(
        snapshot:
            DieselStateSnapshot<CompanionNextAlarmState>,
    ): Map<String, DieselValue> {
        val state = snapshot.value

        return linkedMapOf<String, DieselValue>()
            .apply {
                put("synced", DieselValue.Flag(true))
                put(
                    "kind",
                    DieselValue.Text(state.kind.wireName),
                )
                put(
                    "observedAtMs",
                    DieselValue.Integer(state.observedAtMs),
                )
                put(
                    "receivedAtMs",
                    DieselValue.Integer(state.receivedAtMs),
                )
                put(
                    "revision",
                    DieselValue.Integer(snapshot.revision),
                )
                put(
                    "updatedAtMs",
                    DieselValue.Integer(snapshot.updatedAtMs),
                )

                state.triggerAtMs?.let {
                    put(
                        "triggerAtMs",
                        DieselValue.Integer(it),
                    )
                }

                state.reason?.let {
                    put(
                        "reason",
                        DieselValue.Text(it),
                    )
                }
            }
    }

    private fun invalid(reason: String): DieselCommandResult =
        DieselCommandResult(
            status = DieselResponseStatus.INVALID_REQUEST,
            data =
                mapOf(
                    "reason" to DieselValue.Text(reason),
                ),
        )

    companion object {
        const val COMMAND_SYNC =
            "companion.alarm.next.sync"
        const val COMMAND_GET =
            "companion.alarm.next.get"
    }
}
