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
            "Milestone: M6.1e bundle",
            "Package: $applicationId",
            "Version: $versionName ($versionCode)",
            "Git SHA: $gitSha",
            "CI run: $ciRunId",
            "Built: $buildTimestampUtc",
            "",
            "Gadgetbridge request transport: implemented",
            "Diesel response correlation: implemented",
            "Bounded gateway timeout/session: implemented",
            "Android next-alarm provider: implemented",
            "Alarm StateStore sync: implemented",
            "Alarm change/reconnect resync: implemented",
            "",
            "BLE ownership: stock Gadgetbridge only",
        ).joinToString(separator = "\n")
}
