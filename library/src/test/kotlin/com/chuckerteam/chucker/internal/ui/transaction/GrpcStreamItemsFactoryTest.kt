package com.chuckerteam.chucker.internal.ui.transaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.HttpHeader
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.util.GrpcTransactionFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class GrpcStreamItemsFactoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val factory get() = GrpcStreamItemsFactory(context)

    @Test
    fun `a finished stream shows its status, duration and trailers`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val items = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val summary = (items.first() as StreamItem.Summary).summary
        assertThat(summary.status).isEqualTo("14 UNAVAILABLE")
        assertThat(summary.isLive).isFalse()
        assertThat(summary.description).isEqualTo("Connection reset")
        assertThat(summary.durationLabel).isEqualTo("Duration")
        assertThat(summary.duration).isEqualTo("250 ms")
        assertThat(summary.sentCount).isEqualTo(1)
        assertThat(summary.receivedCount).isEqualTo(2)
        assertThat(items.last()).isInstanceOf(StreamItem.Event::class.java)

        val closed = eventRow(items, GrpcEvent.Type.CLOSED)
        assertThat(closed.title).isEqualTo("Closed · 14 UNAVAILABLE")
        assertThat(closed.subtitle).isEqualTo("Connection reset")
        assertThat(closed.detailsLabel).isEqualTo("Trailers")
        assertThat(closed.headers).containsExactly(HttpHeader("x-processing-ms", "12"))
        assertThat(closed.isExpandable).isTrue()
    }

    @Test
    fun `an open stream is live and waits for more messages`() {
        val transaction = GrpcTransactionFactory.createTransaction().open()
        val now = transaction.requestDate!! + 12_400

        val items = factory.create(transaction, isLiveStream = true, expandedKeys = emptySet(), nowMs = now)

        val summary = (items.first() as StreamItem.Summary).summary
        assertThat(summary.status).isEqualTo("LIVE")
        assertThat(summary.isLive).isTrue()
        assertThat(summary.durationLabel).isEqualTo("Open for")
        assertThat(summary.duration).isEqualTo("12.4 s")
        assertThat(items.last()).isEqualTo(StreamItem.Waiting("12.4 s"))
    }

    @Test
    fun `a stream with a status is not live, even before it stops being tracked`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val items = factory.create(transaction, isLiveStream = true, expandedKeys = emptySet(), nowMs = NOW)

        assertThat((items.first() as StreamItem.Summary).summary.isLive).isFalse()
        assertThat(items.none { it is StreamItem.Waiting }).isTrue()
    }

    @Test
    fun `a stream that never closed and is no longer tracked has no status`() {
        val transaction = GrpcTransactionFactory.createTransaction().open()

        val items = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val summary = (items.first() as StreamItem.Summary).summary
        assertThat(summary.status).isEqualTo("No status")
        assertThat(summary.duration).isEqualTo("—")
        assertThat(items.none { it is StreamItem.Waiting }).isTrue()
    }

    @Test
    fun `metadata and headers are shown on the events they arrived with`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val items = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val started = eventRow(items, GrpcEvent.Type.STARTED)
        assertThat(started.subtitle).isEqualTo("Metadata: 2")
        assertThat(started.headers.map { it.name }).containsExactly("x-request-id", "note").inOrder()
        val headers = eventRow(items, GrpcEvent.Type.HEADERS_RECEIVED)
        assertThat(headers.subtitle).isEqualTo("Headers: 1")
        assertThat(headers.headers).containsExactly(HttpHeader("content-type", "application/grpc"))
        assertThat(eventRow(items, GrpcEvent.Type.HALF_CLOSED).isExpandable).isFalse()
    }

    @Test
    fun `messages show their body as a preview`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val items = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val sent = eventRow(items, GrpcEvent.Type.MESSAGE_SENT)
        assertThat(sent.title).isEqualTo("Sent #1 · 9 B")
        assertThat(sent.subtitle).isEqualTo("""{"rideId":"r-1"}""")
        assertThat(sent.isSubtitleCode).isTrue()
        assertThat(sent.isExpandable).isTrue()
    }

    @Test
    fun `only the rows the user expanded are expanded`() {
        val transaction = GrpcTransactionFactory.createTransaction()
        val expanded = setOf(GrpcStreamAdapter.KEY_DETAILS, GrpcStreamAdapter.eventKey(SENT_POSITION))

        val items = factory.create(transaction, isLiveStream = false, expandedKeys = expanded, nowMs = NOW)

        val summary = (items.first() as StreamItem.Summary).summary
        assertThat(summary.isExpanded).isTrue()
        val url = "https://api.example.com:443/ride.v1.RideService/WatchRide"
        assertThat(summary.details.first()).isEqualTo("URL" to url)
        val expandedPositions = items.filterIsInstance<StreamItem.Event>().filter { it.row.isExpanded }
        assertThat(expandedPositions.map { it.row.position }).containsExactly(SENT_POSITION)
    }

    private fun eventRow(
        items: List<StreamItem>,
        type: GrpcEvent.Type,
    ): StreamEventRow = items.filterIsInstance<StreamItem.Event>().first { it.row.event.type == type }.row

    /** Turns the call back into one that hasn't finished yet. */
    private fun HttpTransaction.open(): HttpTransaction =
        apply {
            responseCode = null
            responseMessage = null
            grpcStatusDescription = null
            tookMs = null
            grpcEvents = grpcEvents!!.replace(Regex(""",\{"type":"closed"[^}]*\}"""), "")
        }

    private companion object {
        const val NOW = 1_760_000_100_000L
        const val SENT_POSITION = 1
    }
}
