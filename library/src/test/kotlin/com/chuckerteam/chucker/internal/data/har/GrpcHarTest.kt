package com.chuckerteam.chucker.internal.data.har

import com.chuckerteam.chucker.internal.data.har.log.Entry
import com.chuckerteam.chucker.util.GrpcTransactionFactory
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class GrpcHarTest {
    @Test
    fun `a gRPC call is exported as an HTTP 2 request with its gRPC details`() {
        val entry = Entry(GrpcTransactionFactory.createTransaction(scheme = null))

        assertThat(entry.request.httpVersion).isEqualTo("HTTP/2")
        assertThat(entry.request.queryString).isEmpty()
        assertThat(entry.request.postData?.text).isEqualTo("""{"rideId":"r-1"}""")
        assertThat(entry.response.status).isEqualTo(200)
        assertThat(entry.response.statusText).isEqualTo("OK")
        assertThat(entry.grpc?.method).isEqualTo("ride.v1.RideService/WatchRide")
        assertThat(entry.grpc?.status).isEqualTo("UNAVAILABLE")
        assertThat(entry.grpc?.statusCode).isEqualTo(14)
        assertThat(
            entry.grpc
                ?.trailers
                ?.single()
                ?.name,
        ).isEqualTo("x-processing-ms")
    }
}
