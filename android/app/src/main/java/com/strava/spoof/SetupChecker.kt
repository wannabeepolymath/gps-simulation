package com.strava.spoof

import android.content.Context
import android.location.LocationManager
import android.os.Build

sealed class SetupIssue {
    data object NoLocationPermission : SetupIssue()
    data object NoNotificationPermission : SetupIssue()
    data object MockLocationNotAllowed : SetupIssue()
}

object SetupChecker {

    fun missingIssues(context: Context, hasLocationPerm: Boolean, hasNotifPerm: Boolean): List<SetupIssue> {
        val issues = mutableListOf<SetupIssue>()
        if (!hasLocationPerm) issues += SetupIssue.NoLocationPermission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
            issues += SetupIssue.NoNotificationPermission
        }
        if (!canUseMockProvider(context)) issues += SetupIssue.MockLocationNotAllowed
        return issues
    }

    private fun canUseMockProvider(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            try { lm.removeTestProvider(PROBE_PROVIDER) } catch (_: Exception) {}
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                PROBE_PROVIDER,
                false, false, false, false, false, false, false, 1, 1,
            )
            lm.removeTestProvider(PROBE_PROVIDER)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private const val PROBE_PROVIDER = "strava_spoof_probe"
}
