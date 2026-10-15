package com.chuckerteam.chucker.internal.ui.transaction

import android.content.Context
import android.provider.Settings
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.annotation.StringRes
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.internal.support.FormatUtils
import java.util.Locale

/** Labels shared by the screens that show gRPC calls. */
internal object GrpcPresentation {
    private const val UNARY = "UNARY"
    private const val MILLIS_PER_SECOND = 1000.0
    private const val PULSE_DURATION_MS = 800L
    private const val PULSE_MIN_ALPHA = 0.25f

    @StringRes
    fun callTypeLabel(methodType: String?): Int =
        when (methodType) {
            UNARY -> R.string.chucker_grpc_unary
            "CLIENT_STREAMING" -> R.string.chucker_grpc_client_streaming
            "SERVER_STREAMING" -> R.string.chucker_grpc_server_streaming
            "BIDI_STREAMING" -> R.string.chucker_grpc_bidi_streaming
            else -> R.string.chucker_grpc_unknown_call_type
        }

    fun isStreaming(methodType: String?): Boolean = methodType != UNARY

    /** Shortens `/ride.v1.RideService/WatchRide` to `RideService/WatchRide`. */
    fun shortMethodName(path: String?): String {
        val fullMethodName = path.orEmpty().removePrefix("/")
        val service = fullMethodName.substringBefore('/')
        val method = fullMethodName.substringAfter('/', missingDelimiterValue = "")
        return "${service.substringAfterLast('.')}/$method"
    }

    /** Formats a time offset within a call, e.g. `31 ms` or `1.2 s`. */
    fun formatOffset(millis: Long): String =
        if (millis < MILLIS_PER_SECOND) {
            "$millis ms"
        } else {
            String.format(Locale.US, "%.1f s", millis / MILLIS_PER_SECOND)
        }

    fun formatSize(bytes: Long?): String = FormatUtils.formatByteCount(bytes ?: 0, true)

    /** Returns the `host:port` a call was made to, from its URL. */
    fun authority(url: String?): String = url.orEmpty().substringAfter("://").substringBefore('/')

    /** Whether the user allows animations, which the live markers respect by not pulsing. */
    fun areAnimationsEnabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    /** The pulse of the markers of open streams. */
    fun pulseAnimation(): Animation =
        AlphaAnimation(1f, PULSE_MIN_ALPHA).apply {
            duration = PULSE_DURATION_MS
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
}
