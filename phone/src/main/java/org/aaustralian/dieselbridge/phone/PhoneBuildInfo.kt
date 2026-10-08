// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

data class PhoneBuildInfo(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val gitSha: String,
    val ciRunId: String,
    val buildTimestampUtc: String,
) {
    fun diagnosticText(): String =
        listOf(
            "Milestone: M6.0b",
            "Package: $applicationId",
            "Version: $versionName ($versionCode)",
            "Git SHA: $gitSha",
            "CI run: $ciRunId",
            "Built: $buildTimestampUtc",
            "",
            "Gadgetbridge request transport: implemented",
            "Diesel response correlation: not implemented (M6.0c)",
            "Gateway timeout/reconnect policy: not implemented (M6.0d)",
            "Android next-alarm provider: not implemented (M6.1a)",
            "",
            "BLE ownership: stock Gadgetbridge only",
        ).joinToString(
            separator = "\n",
        )
}
