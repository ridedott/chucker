package com.chuckerteam.chucker.internal.grpc.format

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Timestamp
import com.google.protobuf.Value
import org.junit.jupiter.api.Test

internal class RawProtoDecoderTest {
    @Test
    fun `varint fields are decoded by field number`() {
        val bytes =
            Timestamp
                .newBuilder()
                .setSeconds(1_700_000_000)
                .setNanos(5)
                .build()
                .toByteArray()

        val json = RawProtoDecoder.decode(bytes)

        assertThat(json.toString()).isEqualTo("""{"#1":1700000000,"#2":5}""")
    }

    @Test
    fun `printable length-delimited fields are decoded as text`() {
        val bytes =
            Value
                .newBuilder()
                .setStringValue("Hello, Chucker")
                .build()
                .toByteArray()

        assertThat(RawProtoDecoder.decode(bytes).toString()).isEqualTo("""{"#3":"Hello, Chucker"}""")
    }

    @Test
    fun `nested messages and repeated fields are decoded`() {
        val list =
            ListValue
                .newBuilder()
                .addValues(Value.newBuilder().setBoolValue(true))
                .addValues(Value.newBuilder().setBoolValue(false))
        val bytes =
            Struct
                .newBuilder()
                .putFields("list", Value.newBuilder().setListValue(list).build())
                .build()
                .toByteArray()

        val json = RawProtoDecoder.decode(bytes).toString()

        assertThat(json).isEqualTo("""{"#1":{"#1":"list","#2":{"#6":{"#1":[{"#4":1},{"#4":0}]}}}}""")
    }

    @Test
    fun `fixed64 values that look like doubles are decoded as doubles`() {
        val bytes =
            Value
                .newBuilder()
                .setNumberValue(52.3676)
                .build()
                .toByteArray()

        assertThat(RawProtoDecoder.decode(bytes).toString()).isEqualTo("""{"#2":52.3676}""")
    }

    @Test
    fun `binary length-delimited fields are decoded as base64`() {
        // Field 1, length-delimited, 3 bytes that are neither text nor a message.
        val bytes = byteArrayOf(0x0A, 0x03, 0x00, 0xFF.toByte(), 0x07)

        assertThat(RawProtoDecoder.decode(bytes).toString()).isEqualTo("""{"#1":"AP8H"}""")
    }

    @Test
    fun `empty input is an empty message`() {
        assertThat(RawProtoDecoder.decode(ByteArray(0)).toString()).isEqualTo("{}")
    }

    @Test
    fun `invalid input is rejected`() {
        assertThat(RawProtoDecoder.decode("not a protobuf message".toByteArray())).isNull()
        // A length that goes past the end of the input.
        assertThat(RawProtoDecoder.decode(byteArrayOf(0x0A, 0x05, 0x01))).isNull()
        // Field number 0 doesn't exist.
        assertThat(RawProtoDecoder.decode(byteArrayOf(0x00, 0x01))).isNull()
    }

    @Test
    fun `negative varints are decoded as unsigned numbers`() {
        // Field 1, varint, -1 encoded on 10 bytes.
        val bytes = byteArrayOf(0x08) + ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x01)

        assertThat(RawProtoDecoder.decode(bytes).toString()).isEqualTo("""{"#1":18446744073709551615}""")
    }
}
