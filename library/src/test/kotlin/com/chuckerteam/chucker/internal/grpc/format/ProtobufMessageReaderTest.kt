package com.chuckerteam.chucker.internal.grpc.format

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Any
import com.google.protobuf.BoolValue
import com.google.protobuf.ByteString
import com.google.protobuf.BytesValue
import com.google.protobuf.Duration
import com.google.protobuf.Empty
import com.google.protobuf.Field
import com.google.protobuf.FieldMask
import com.google.protobuf.ListValue
import com.google.protobuf.MessageLite
import com.google.protobuf.NullValue
import com.google.protobuf.Option
import com.google.protobuf.StringValue
import com.google.protobuf.Struct
import com.google.protobuf.Syntax
import com.google.protobuf.Timestamp
import com.google.protobuf.Type
import com.google.protobuf.UInt64Value
import com.google.protobuf.Value
import org.junit.jupiter.api.Test

// protobuf-javalite only ships the well-known types, which are enough to exercise every kind of
// field: scalars, enums, nested and repeated messages, maps, oneofs and bytes.
internal class ProtobufMessageReaderTest {
    @Test
    fun `fields are named after the proto fields and ordered by number`() {
        val type =
            Type
                .newBuilder()
                .setName("chucker.Ride")
                .addOneofs("payment")
                .setSyntax(Syntax.SYNTAX_PROTO3)
                .addFields(
                    Field
                        .newBuilder()
                        .setName("ride_id")
                        .setNumber(1)
                        .setKind(Field.Kind.TYPE_STRING)
                        .setJsonName("rideId"),
                ).build()

        val json = ProtobufMessageReader.read(type).toString()

        assertThat(json).isEqualTo(
            """{"name":"chucker.Ride",""" +
                """"fields":[{"kind":"TYPE_STRING","number":1,"name":"ride_id","jsonName":"rideId"}],""" +
                """"oneofs":["payment"],"syntax":"SYNTAX_PROTO3"}""",
        )
    }

    @Test
    fun `fields with default values are left out`() {
        assertThat(ProtobufMessageReader.read(Type.getDefaultInstance()).toString()).isEqualTo("{}")
    }

    @Test
    fun `enum values unknown to the client are shown as numbers`() {
        val field =
            Field
                .newBuilder()
                .setKindValue(99)
                .setName("future")
                .build()

        assertThat(ProtobufMessageReader.read(field).toString()).isEqualTo("""{"kind":99,"name":"future"}""")
    }

    @Test
    fun `nested messages are read, including Any`() {
        val packed = pack(StringValue.of("hello"), "google.protobuf.StringValue")
        val type =
            Type
                .newBuilder()
                .addOptions(Option.newBuilder().setName("deprecated").setValue(packed))
                .build()

        val json = ProtobufMessageReader.read(type).toString()

        assertThat(json).isEqualTo(
            """{"options":[{"name":"deprecated","value":{"@type":"type.googleapis.com/google.protobuf.StringValue",""" +
                """"value":{"#1":"hello"}}}]}""",
        )
    }

    @Test
    fun `fields unknown to the client are decoded from the wire`() {
        // Field 99, varint 7, which Type doesn't declare.
        val unknownField = byteArrayOf(0x98.toByte(), 0x06, 0x07)
        val bytes =
            Type
                .newBuilder()
                .setName("chucker.Ride")
                .build()
                .toByteArray() + unknownField

        val json = ProtobufMessageReader.read(Type.parseFrom(bytes)).toString()

        assertThat(json).isEqualTo("""{"name":"chucker.Ride","#99":7}""")
    }

    @Test
    fun `Struct, Value and ListValue use their JSON representation`() {
        val list = ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("ams"))
        val struct =
            Struct
                .newBuilder()
                .putFields("enabled", Value.newBuilder().setBoolValue(true).build())
                .putFields("speed", Value.newBuilder().setNumberValue(25.5).build())
                .putFields("regions", Value.newBuilder().setListValue(list).build())
                .putFields("motd", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                .build()

        val json = ProtobufMessageReader.read(struct)!!.asJsonObject

        assertThat(json.get("enabled").asBoolean).isTrue()
        assertThat(json.get("speed").asDouble).isEqualTo(25.5)
        assertThat(json.get("regions").toString()).isEqualTo("""["ams"]""")
        assertThat(json.get("motd").isJsonNull).isTrue()
    }

    @Test
    fun `Timestamp and Duration use their JSON representation`() {
        val timestamp =
            Timestamp
                .newBuilder()
                .setSeconds(1_760_522_521)
                .setNanos(120_000_000)
                .build()
        val duration =
            Duration
                .newBuilder()
                .setSeconds(1)
                .setNanos(500_000_000)
                .build()
        val option = Option.newBuilder().setValue(pack(timestamp, "google.protobuf.Timestamp")).build()

        assertThat(readValue(timestamp)).isEqualTo("\"2025-10-15T10:02:01.120Z\"")
        assertThat(readValue(duration)).isEqualTo("\"1.500s\"")
        assertThat(ProtobufMessageReader.read(option).toString()).contains("google.protobuf.Timestamp")
    }

    @Test
    fun `wrappers, FieldMask and Empty use their JSON representation`() {
        assertThat(readValue(BoolValue.of(true))).isEqualTo("true")
        assertThat(readValue(UInt64Value.of(-1))).isEqualTo("18446744073709551615")
        assertThat(readValue(BytesValue.of(ByteString.copyFromUtf8("hi")))).isEqualTo("\"aGk=\"")
        assertThat(
            readValue(
                FieldMask
                    .newBuilder()
                    .addPaths("user.display_name")
                    .addPaths("id")
                    .build(),
            ),
        ).isEqualTo("\"user.displayName,id\"")
        assertThat(ProtobufMessageReader.read(Empty.getDefaultInstance()).toString()).isEqualTo("{}")
    }

    @Test
    fun `other objects are not read`() {
        assertThat(ProtobufMessageReader.read("not a message")).isNull()
    }

    // Lite has no Any.pack().
    private fun pack(
        message: MessageLite,
        typeName: String,
    ): Any =
        Any
            .newBuilder()
            .setTypeUrl("type.googleapis.com/$typeName")
            .setValue(message.toByteString())
            .build()

    private fun readValue(message: MessageLite): String = ProtobufMessageReader.read(message).toString()
}
