// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.DieselCommandContext
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Authorized remote access to developer-visible runtime information.
 *
 * Authorization can only be changed by the local watch developer UI.
 *
 * Export sections are discovered through [DeveloperExportRegistry]. Page size
 * is not tied to a watch or sensor count: the requested limit is only a
 * preference and items are admitted until the fixed Diesel response byte
 * budget would be exceeded.
 */
class DeveloperExportCommandModule(
    private val authorization:
        DeveloperExportAuthorization,
    private val registry:
        DeveloperExportRegistry,
) : DieselCommandModule {

    override fun install(
        commandRegistry:
            DieselCommandRegistry,
    ) {
        commandRegistry.register(
            DieselCommandSpec(
                name =
                    COMMAND_STATUS,
                summary =
                    "Show remote developer export status",
                metadata =
                    commandMetadata(
                        pagination = false,
                    ),
            ),
        ) { context ->
            status(
                context,
            )
        }

        commandRegistry.register(
            DieselCommandSpec(
                name =
                    COMMAND_EXPORT,
                summary =
                    "Export authorized developer data",
                metadata =
                    commandMetadata(
                        pagination = true,
                    ),
            ),
        ) { context ->
            export(
                context,
            )
        }
    }

    private fun status(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (
            context.name != null ||
            context.args.isNotEmpty()
        ) {
            return invalidArguments()
        }

        return DieselCommandResult.ok(
            data =
                linkedMapOf(
                    "enabled" to
                        DieselValue.Flag(
                            authorization
                                .isEnabled(),
                        ),
                    "authorization" to
                        DieselValue.Text(
                            "local_developer_ui",
                        ),
                    "sections" to
                        DieselValue.ListValue(
                            registry
                                .sections()
                                .map {
                                    DieselValue.Text(
                                        it,
                                    )
                                },
                        ),
                    "pagination" to
                        DieselValue.Text(
                            "byte_budget_offset_limit",
                        ),
                    "responseBudgetBytes" to
                        DieselValue.Integer(
                            DieselResponseCodec
                                .MAX_RESPONSE_JSON_BYTES
                                .toLong(),
                        ),
                    "defaultRequestedLimit" to
                        DieselValue.Integer(
                            DEFAULT_REQUESTED_LIMIT
                                .toLong(),
                        ),
                    "maxRequestedLimit" to
                        DieselValue.Integer(
                            MAX_REQUESTED_LIMIT
                                .toLong(),
                        ),
                ),
        )
    }

    private fun export(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (context.name != null) {
            return invalidArguments()
        }

        if (
            context.args.keys.any {
                it !in EXPORT_ARGUMENTS
            }
        ) {
            return invalidArguments()
        }

        val section =
            (
                context.args[ARG_SECTION]
                    as? DieselValue.Text
            )
                ?.value
                ?: return invalidArguments()

        if (
            section !in
            registry.sections()
        ) {
            return invalidArguments()
        }

        val offset =
            integerArgument(
                context = context,
                key = ARG_OFFSET,
                defaultValue = 0,
                minimum = 0,
                maximum = Int.MAX_VALUE,
            )
                ?: return invalidArguments()

        val requestedLimit =
            integerArgument(
                context = context,
                key = ARG_LIMIT,
                defaultValue =
                    DEFAULT_REQUESTED_LIMIT,
                minimum = 1,
                maximum =
                    MAX_REQUESTED_LIMIT,
            )
                ?: return invalidArguments()

        /*
         * Authorization deliberately precedes every provider snapshot. A
         * disabled export request therefore cannot touch sensor inventory,
         * logs, permissions or any other developer-data source.
         */
        if (!authorization.isEnabled()) {
            return DieselCommandResult(
                status =
                    DieselResponseStatus
                        .UNAVAILABLE,
                data =
                    mapOf(
                        "reason" to
                            DieselValue.Text(
                                REASON_DISABLED,
                            ),
                    ),
            )
        }

        val snapshot =
            runCatching {
                registry.snapshot(
                    section,
                )
            }
                .getOrElse {
                    return DieselCommandResult(
                        status =
                            DieselResponseStatus
                                .FAILED,
                        data =
                            mapOf(
                                "reason" to
                                    DieselValue.Text(
                                        REASON_SNAPSHOT_FAILED,
                                    ),
                            ),
                    )
                }
                ?: return invalidArguments()

        return buildPage(
            section = section,
            snapshot = snapshot,
            offset = offset,
            requestedLimit =
                requestedLimit,
        )
    }

    private fun buildPage(
        section: String,
        snapshot: DeveloperExportSnapshot,
        offset: Int,
        requestedLimit: Int,
    ): DieselCommandResult {
        val total =
            snapshot.items.size

        val candidates =
            snapshot
                .items
                .drop(
                    offset,
                )
                .take(
                    requestedLimit,
                )

        val accepted =
            mutableListOf<
                DieselValue.ObjectValue
            >()

        /*
         * First make sure provider-level metadata itself fits the control-plane
         * envelope. This should remain small, but the check preserves the hard
         * response invariant even for future plugin sections.
         */
        val emptyCandidate =
            pageData(
                section = section,
                snapshot = snapshot,
                total = total,
                offset = offset,
                requestedLimit =
                    requestedLimit,
                items = emptyList(),
            )

        if (
            !fitsResponseBudget(
                emptyCandidate,
            )
        ) {
            return failed(
                REASON_SECTION_TOO_LARGE,
            )
        }

        for (item in candidates) {
            val nextItems =
                accepted +
                    item

            val candidateData =
                pageData(
                    section = section,
                    snapshot = snapshot,
                    total = total,
                    offset = offset,
                    requestedLimit =
                        requestedLimit,
                    items = nextItems,
                )

            if (
                !fitsResponseBudget(
                    candidateData,
                )
            ) {
                break
            }

            accepted +=
                item
        }

        if (
            candidates.isNotEmpty() &&
            accepted.isEmpty()
        ) {
            return failed(
                REASON_ITEM_TOO_LARGE,
            )
        }

        val data =
            pageData(
                section = section,
                snapshot = snapshot,
                total = total,
                offset = offset,
                requestedLimit =
                    requestedLimit,
                items = accepted,
            )

        return DieselCommandResult.ok(
            data = data,
        )
    }

    private fun pageData(
        section: String,
        snapshot: DeveloperExportSnapshot,
        total: Int,
        offset: Int,
        requestedLimit: Int,
        items:
            List<DieselValue.ObjectValue>,
    ): Map<String, DieselValue> {
        val nextOffset =
            offset.toLong() +
                items.size.toLong()

        val hasMore =
            nextOffset <
                total.toLong()

        return linkedMapOf<String, DieselValue>(
            "section" to
                DieselValue.Text(
                    section,
                ),
            "source" to
                DieselValue.Text(
                    snapshot.source,
                ),
            "total" to
                DieselValue.Integer(
                    total.toLong(),
                ),
            "offset" to
                DieselValue.Integer(
                    offset.toLong(),
                ),
            "requestedLimit" to
                DieselValue.Integer(
                    requestedLimit.toLong(),
                ),
            "returned" to
                DieselValue.Integer(
                    items.size.toLong(),
                ),
            "nextOffset" to
                DieselValue.Integer(
                    nextOffset,
                ),
            "hasMore" to
                DieselValue.Flag(
                    hasMore,
                ),
            "responseBudgetBytes" to
                DieselValue.Integer(
                    DieselResponseCodec
                        .MAX_RESPONSE_JSON_BYTES
                        .toLong(),
                ),
            "items" to
                DieselValue.ListValue(
                    items,
                ),
        )
            .apply {
                if (
                    snapshot.metadata
                        .isNotEmpty()
                ) {
                    put(
                        "meta",
                        DieselValue.ObjectValue(
                            snapshot.metadata,
                        ),
                    )
                }
            }
    }

    /**
     * Measure using a maximum-length request id so a page accepted here also
     * fits when the real request uses the largest valid correlation id.
     */
    private fun fitsResponseBudget(
        data: Map<String, DieselValue>,
    ): Boolean {
        val response =
            DieselResponse(
                requestId =
                    WORST_CASE_REQUEST_ID,
                command =
                    COMMAND_EXPORT,
                status =
                    DieselResponseStatus.OK,
                data =
                    data,
            )

        val bytes =
            DieselResponseCodec
                .encodeResponseJson(
                    response,
                )
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size

        return bytes <=
            DieselResponseCodec
                .MAX_RESPONSE_JSON_BYTES
    }

    private fun integerArgument(
        context: DieselCommandContext,
        key: String,
        defaultValue: Int,
        minimum: Int,
        maximum: Int,
    ): Int? {
        val value =
            context.args[key]
                ?: return defaultValue

        val integer =
            (
                value as?
                    DieselValue.Integer
            )
                ?.value
                ?: return null

        if (
            integer <
            minimum.toLong() ||
            integer >
            maximum.toLong()
        ) {
            return null
        }

        return integer.toInt()
    }

    private fun failed(
        reason: String,
    ): DieselCommandResult =
        DieselCommandResult(
            status =
                DieselResponseStatus.FAILED,
            data =
                mapOf(
                    "reason" to
                        DieselValue.Text(
                            reason,
                        ),
                ),
        )

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
                            REASON_INVALID_ARGUMENTS,
                        ),
                ),
        )

    private fun commandMetadata(
        pagination: Boolean,
    ): Map<String, DieselValue> =
        linkedMapOf<String, DieselValue>(
            "domain" to
                DieselValue.Text(
                    "debug",
                ),
            "effect" to
                DieselValue.Text(
                    "read_only",
                ),
            "authorization" to
                DieselValue.Text(
                    "local_developer_ui",
                ),
        )
            .apply {
                if (pagination) {
                    put(
                        "pagination",
                        DieselValue.Text(
                            "byte_budget_offset_limit",
                        ),
                    )
                }
            }

    companion object {
        const val COMMAND_STATUS =
            "debug.status"

        const val COMMAND_EXPORT =
            "debug.export"

        const val DEFAULT_REQUESTED_LIMIT =
            32

        const val MAX_REQUESTED_LIMIT =
            64

        private const val ARG_SECTION =
            "section"

        private const val ARG_OFFSET =
            "offset"

        private const val ARG_LIMIT =
            "limit"

        private const val REASON_DISABLED =
            "remote_export_disabled"

        private const val REASON_INVALID_ARGUMENTS =
            "invalid_args"

        private const val REASON_SNAPSHOT_FAILED =
            "snapshot_failed"

        private const val REASON_SECTION_TOO_LARGE =
            "section_metadata_too_large"

        private const val REASON_ITEM_TOO_LARGE =
            "item_too_large"

        private val EXPORT_ARGUMENTS =
            setOf(
                ARG_SECTION,
                ARG_OFFSET,
                ARG_LIMIT,
            )

        private val WORST_CASE_REQUEST_ID =
            "x".repeat(
                DieselResponse
                    .MAX_REQUEST_ID_LENGTH,
            )
    }
}
