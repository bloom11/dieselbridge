// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.Context
import org.aaustralian.dieselbridge.BuildConfig

/**
 * Immutable identity of the APK currently running on the watch.
 *
 * Build provenance comes from BuildConfig and installation timestamps come
 * from Android package metadata. UI and protocol commands consume this same
 * snapshot so their reported identity cannot drift independently.
 */
internal data class DeveloperBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val debug: Boolean,
    val gitSha: String?,
    val buildTimestampUtc: String,
    val ciRunId: String?,
    val firstInstallTimeMs: Long?,
    val lastUpdateTimeMs: Long?,
) {
    val buildType: String
        get() =
            if (debug) {
                "debug"
            } else {
                "release"
            }

    val gitShaShort: String?
        get() =
            gitSha?.take(7)
}

internal object DeveloperBuildInfoSource {

    @Suppress("DEPRECATION")
    fun snapshot(
        context: Context,
    ): DeveloperBuildInfo {
        val packageInfo =
            runCatching {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    0,
                )
            }.getOrNull()

        val gitSha =
            BuildConfig.BUILD_GIT_SHA
                .trim()
                .takeUnless {
                    it.isEmpty() ||
                        it == "unknown"
                }

        val ciRunId =
            BuildConfig.BUILD_CI_RUN_ID
                .trim()
                .takeUnless {
                    it.isEmpty() ||
                        it == "unknown"
                }

        return DeveloperBuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode =
                BuildConfig.VERSION_CODE.toLong(),
            debug = BuildConfig.DEBUG,
            gitSha = gitSha,
            buildTimestampUtc =
                BuildConfig.BUILD_TIMESTAMP_UTC,
            ciRunId = ciRunId,
            firstInstallTimeMs =
                packageInfo?.firstInstallTime,
            lastUpdateTimeMs =
                packageInfo?.lastUpdateTime,
        )
    }
}
