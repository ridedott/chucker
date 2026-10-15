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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
internal class GrpcStreamItemsFactoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val factory get() = GrpcStreamItemsFactory(context)

    @Test
    fun `a finished stream shows its status, duration and trailers`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val screen = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val summary = screen.summary
        assertThat(summary.status).isEqualTo("14 UNAVAILABLE")
        assertThat(summary.isLive).isFalse()
        assertThat(summary.description).isEqualTo("Connection reset")
        assertThat(summary.durationLabel).isEqualTo("Duration")
        assertThat(summary.duration).isEqualTo("250 ms")
        assertThat(summary.sentCount).isEqualTo(1)
        assertThat(summary.receivedCount).isEqualTo(2)
        val events = screen.items.filterIsInstance<StreamItem.Event>().map { it.row.event.type }
        assertThat(events.first()).isEqualTo(GrpcEvent.Type.CLOSED)
        assertThat(events.last()).isEqualTo(GrpcEvent.Type.STARTED)

        val closed = eventRow(screen, GrpcEvent.Type.CLOSED)
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

        val screen = factory.create(transaction, isLiveStream = true, expandedKeys = emptySet(), nowMs = now)

        val summary = screen.summary
        assertThat(summary.status).isEqualTo("LIVE")
        assertThat(summary.isLive).isTrue()
        assertThat(summary.durationLabel).isEqualTo("Open for")
        assertThat(summary.duration).isEqualTo("12 s")
        assertThat(screen.items.first()).isEqualTo(StreamItem.Waiting)
    }

    @Test
    fun `a stream with a status is not live, even before it stops being tracked`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val screen = factory.create(transaction, isLiveStream = true, expandedKeys = emptySet(), nowMs = NOW)

        assertThat(screen.summary.isLive).isFalse()
        assertThat(screen.items.none { it is StreamItem.Waiting }).isTrue()
    }

    @Test
    fun `a stream that never closed and is no longer tracked has no status`() {
        val transaction = GrpcTransactionFactory.createTransaction().open()

        val screen = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        assertThat(screen.summary.status).isEqualTo("No status")
        assertThat(screen.summary.duration).isEqualTo("—")
        assertThat(screen.items.none { it is StreamItem.Waiting }).isTrue()
    }

    @Test
    fun `metadata and headers are shown on the events they arrived with`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val screen = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val started = eventRow(screen, GrpcEvent.Type.STARTED)
        assertThat(started.subtitle).isEqualTo("Metadata: 2")
        assertThat(started.headers.map { it.name }).containsExactly("x-request-id", "note").inOrder()
        val headers = eventRow(screen, GrpcEvent.Type.HEADERS_RECEIVED)
        assertThat(headers.subtitle).isEqualTo("Headers: 1")
        assertThat(headers.headers).containsExactly(HttpHeader("content-type", "application/grpc"))
        assertThat(eventRow(screen, GrpcEvent.Type.HALF_CLOSED).isExpandable).isFalse()
    }

    @Test
    fun `messages show their body as a preview`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val screen = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val sent = eventRow(screen, GrpcEvent.Type.MESSAGE_SENT)
        assertThat(sent.title).isEqualTo("Sent #1 · 9 B")
        assertThat(sent.subtitle).isEqualTo("""{"rideId":"r-1"}""")
        assertThat(sent.isSubtitleCode).isTrue()
        assertThat(sent.isExpandable).isTrue()
    }

    @Test
    fun `only the rows the user expanded are expanded`() {
        val transaction = GrpcTransactionFactory.createTransaction()
        val expanded = setOf(GrpcStreamAdapter.KEY_DETAILS, GrpcStreamAdapter.eventKey(SENT_POSITION))

        val screen = factory.create(transaction, isLiveStream = false, expandedKeys = expanded, nowMs = NOW)

        assertThat(screen.summary.isExpanded).isTrue()
        val details = screen.items.first() as StreamItem.Details
        val url = "https://api.example.com:443/ride.v1.RideService/WatchRide"
        assertThat(details.rows.first()).isEqualTo("URL" to url)
        val expandedPositions = screen.items.filterIsInstance<StreamItem.Event>().filter { it.row.isExpanded }
        assertThat(expandedPositions.map { it.row.position }).containsExactly(SENT_POSITION)
    }

    @Test
    fun `events show the time they happened at`() {
        val transaction = GrpcTransactionFactory.createTransaction()

        val screen = factory.create(transaction, isLiveStream = false, expandedKeys = emptySet(), nowMs = NOW)

        val received = eventRow(screen, GrpcEvent.Type.HEADERS_RECEIVED)
        val expected = SimpleDateFormat("h:mm:ss.SSS a", Locale.US).format(Date(transaction.requestDate!! + 12))
        assertThat(received.timestamp).isEqualTo(expected)
    }

    private fun eventRow(
        screen: StreamScreen,
        type: GrpcEvent.Type,
    ): StreamEventRow =
        screen.items
            .filterIsInstance<StreamItem.Event>()
            .first { it.row.event.type == type }
            .row

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
