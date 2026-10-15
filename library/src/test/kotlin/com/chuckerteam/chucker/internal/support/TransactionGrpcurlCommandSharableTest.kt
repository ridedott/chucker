package com.chuckerteam.chucker.internal.support

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.util.GrpcTransactionFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class TransactionGrpcurlCommandSharableTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `create grpcurl command`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val command = TransactionGrpcurlCommandSharable(transaction).toSharableUtf8Content(context)

        assertThat(command).isEqualTo(
            "grpcurl -H 'x-request-id: abc' -H 'note: it'\\''s here' -d '{\"rideId\":\"r-1\"}' " +
                "'api.example.com:443' 'ride.v1.RideService/WatchRide'",
        )
    }

    @Test
    fun `plaintext calls use the plaintext flag and the default port`() {
        val transaction = GrpcTransactionFactory.createTransaction(scheme = "http", authority = "localhost")

        val command = TransactionGrpcurlCommandSharable(transaction).toSharableUtf8Content(context)

        assertThat(command).startsWith("grpcurl -plaintext ")
        assertThat(command).endsWith(" 'localhost:80' 'ride.v1.RideService/WatchRide'")
    }

    @Test
    fun `messages that are not proto3 JSON are left out with a warning`() {
        val transaction = GrpcTransactionFactory.createTransaction(requestFormat = BodyFormat.RAW_JSON)

        val command = TransactionGrpcurlCommandSharable(transaction).toSharableUtf8Content(context)

        assertThat(command).startsWith("# ")
        assertThat(command).doesNotContain(" -d ")
    }
}
