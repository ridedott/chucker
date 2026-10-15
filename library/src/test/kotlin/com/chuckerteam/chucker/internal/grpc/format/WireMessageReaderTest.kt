package com.chuckerteam.chucker.internal.grpc.format

import com.google.common.truth.Truth.assertThat
import com.squareup.wire.FieldEncoding
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import com.squareup.wire.Syntax
import com.squareup.wire.WireEnum
import com.squareup.wire.WireField
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test

internal class WireMessageReaderTest {
    @Test
    fun `fields use their JSON names and are ordered by tag`() {
        val ride =
            TestRide(ride_id = "r-1", state = TestState.STARTED, tags = listOf("a", "b"), fares = mapOf("base" to 1.5))

        val json = WireMessageReader.read(ride).toString()

        assertThat(json).isEqualTo("""{"rideId":"r-1","state":"STARTED","tags":["a","b"],"fares":{"base":1.5}}""")
    }

    @Test
    fun `identity values are left out`() {
        assertThat(WireMessageReader.read(TestRide()).toString()).isEqualTo("{}")
    }

    @Test
    fun `nested messages, bytes and unsigned values are read`() {
        val ride =
            TestRide(
                battery_percent = -1,
                signature = "signed".encodeUtf8(),
                previous = TestRide(ride_id = "r-0"),
            )

        val json = WireMessageReader.read(ride).toString()

        assertThat(json)
            .isEqualTo("""{"batteryPercent":4294967295,"signature":"c2lnbmVk","previous":{"rideId":"r-0"}}""")
    }

    @Test
    fun `redacted fields are hidden`() {
        assertThat(WireMessageReader.read(TestRide(token = "secret")).toString()).isEqualTo("""{"token":"██"}""")
    }

    @Test
    fun `unknown fields are decoded from the wire`() {
        // Field 20, length-delimited "v2".
        val unknownFields = ByteString.of(0xA2.toByte(), 0x01, 0x02, 'v'.code.toByte(), '2'.code.toByte())

        val json = WireMessageReader.read(TestRide(ride_id = "r-1", unknownFields = unknownFields)).toString()

        assertThat(json).isEqualTo("""{"rideId":"r-1","#20":"v2"}""")
    }

    @Test
    fun `other objects are not read`() {
        assertThat(WireMessageReader.read("not a message")).isNull()
    }

    // Mirrors what Wire generates for a proto3 message, without the encoding logic.
    @Suppress("ConstructorParameterNaming", "LongParameterList")
    private class TestRide(
        @field:WireField(
            tag = 1,
            adapter = "com.squareup.wire.ProtoAdapter#STRING",
            label = WireField.Label.OMIT_IDENTITY,
            jsonName = "rideId",
        )
        val ride_id: String = "",
        @field:WireField(tag = 2, adapter = "TestState#ADAPTER", label = WireField.Label.OMIT_IDENTITY)
        val state: TestState = TestState.UNSPECIFIED,
        @field:WireField(tag = 3, adapter = "com.squareup.wire.ProtoAdapter#STRING", label = WireField.Label.REPEATED)
        val tags: List<String> = emptyList(),
        @field:WireField(
            tag = 4,
            keyAdapter = "com.squareup.wire.ProtoAdapter#STRING",
            adapter = "com.squareup.wire.ProtoAdapter#DOUBLE",
        )
        val fares: Map<String, Double> = emptyMap(),
        @field:WireField(
            tag = 5,
            adapter = "com.squareup.wire.ProtoAdapter#UINT32",
            label = WireField.Label.OMIT_IDENTITY,
            jsonName = "batteryPercent",
        )
        val battery_percent: Int = 0,
        @field:WireField(
            tag = 6,
            adapter = "com.squareup.wire.ProtoAdapter#BYTES",
            label = WireField.Label.OMIT_IDENTITY,
        )
        val signature: ByteString = ByteString.EMPTY,
        @field:WireField(tag = 7, adapter = "TestRide#ADAPTER")
        val previous: TestRide? = null,
        @field:WireField(
            tag = 8,
            adapter = "com.squareup.wire.ProtoAdapter#STRING",
            label = WireField.Label.OMIT_IDENTITY,
            redacted = true,
        )
        val token: String = "",
        unknownFields: ByteString = ByteString.EMPTY,
    ) : Message<TestRide, Nothing>(ADAPTER, unknownFields) {
        override fun newBuilder(): Nothing = throw AssertionError()

        companion object {
            val ADAPTER: ProtoAdapter<TestRide> =
                object : ProtoAdapter<TestRide>(FieldEncoding.LENGTH_DELIMITED, TestRide::class, null, Syntax.PROTO_3) {
                    override fun encodedSize(value: TestRide): Int = throw UnsupportedOperationException()

                    override fun encode(
                        writer: ProtoWriter,
                        value: TestRide,
                    ) = throw UnsupportedOperationException()

                    override fun decode(reader: ProtoReader): TestRide = throw UnsupportedOperationException()

                    override fun redact(value: TestRide): TestRide = value
                }
        }
    }

    private enum class TestState(
        override val value: Int,
    ) : WireEnum {
        UNSPECIFIED(0),
        STARTED(1),
    }
}
