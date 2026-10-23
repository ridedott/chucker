package com.chuckerteam.chucker.internal.data.entity

import com.chuckerteam.chucker.util.GrpcTransactionFactory
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class GrpcTransactionTest {
    private val transaction = GrpcTransactionFactory.createTransaction()

    @Test
    fun `the gRPC status is the response summary, even when the call failed`() {
        transaction.error = "java.net.SocketException: Connection reset"

        assertThat(transaction.status).isEqualTo(HttpTransaction.Status.Failed)
        assertThat(transaction.responseSummaryText).isEqualTo("14 UNAVAILABLE")
    }

    @Test
    fun `notifications show the status name and the method`() {
        assertThat(transaction.notificationText).isEqualTo("UNAVAILABLE ride.v1.RideService/WatchRide")
    }

    @Test
    fun `URL and path are kept as recorded`() {
        val withoutScheme = GrpcTransactionFactory.createTransaction(scheme = null)

        assertThat(withoutScheme.getFormattedUrl(encode = true))
            .isEqualTo("api.example.com:443/ride.v1.RideService/WatchRide")
        assertThat(withoutScheme.getFormattedPath(encode = true)).isEqualTo("/ride.v1.RideService/WatchRide")
    }

    @Test
    fun `a single message is shared as is`() {
        assertThat(transaction.getGrpcBodyText(isRequest = true, pretty = false)).isEqualTo("""{"rideId":"r-1"}""")
    }

    @Test
    fun `several messages are shared with their index, time and size`() {
        val text = transaction.getGrpcBodyText(isRequest = false, pretty = false)

        assertThat(text).isEqualTo(
            "// #1 +30 ms, 20 B\n{\"state\":\"STARTED\"}\n\n// #2 +1200 ms, 20 B\n{\"state\":\"ENDED\"}",
        )
    }

    @Test
    fun `formatted bodies are pretty printed`() {
        assertThat(transaction.getFormattedRequestBody()).isEqualTo("{\n  \"rideId\": \"r-1\"\n}")
    }

    @Test
    fun `a snapshot has the same content`() {
        assertThat(transaction.snapshot().hasTheSameContent(transaction)).isTrue()
    }
}
