package com.chuckerteam.chucker.internal.grpc.format

import com.chuckerteam.chucker.api.GrpcMessageFormatter
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.util.NoLoggerRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.StringValue
import com.google.protobuf.Value
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(NoLoggerRule::class)
internal class GrpcMessageRendererTest {
    private val message = Value.newBuilder().setStringValue("hello").build()

    @Test
    fun `generated messages are rendered with their schema`() {
        val rendered = render(GrpcMessageRenderer(emptyList()), message)

        assertThat(rendered.text).isEqualTo("\"hello\"")
        assertThat(rendered.format).isEqualTo(BodyFormat.SCHEMA_JSON)
    }

    @Test
    fun `formatters run first and in order`() {
        val renderer =
            GrpcMessageRenderer(
                listOf(
                    GrpcMessageFormatter { _, _ -> null },
                    GrpcMessageFormatter { method, _ -> """{"method":"$method"}""" },
                    GrpcMessageFormatter { _, _ -> "never used" },
                ),
            )

        val rendered = render(renderer, message)

        assertThat(rendered.text).isEqualTo("""{"method":"svc/Method"}""")
        assertThat(rendered.format).isEqualTo(BodyFormat.CUSTOM_JSON)
    }

    @Test
    fun `formatters can return plain text`() {
        val rendered = render(GrpcMessageRenderer(listOf(GrpcMessageFormatter { _, _ -> "{ not json" })), message)

        assertThat(rendered.text).isEqualTo("{ not json")
        assertThat(rendered.format).isEqualTo(BodyFormat.TEXT)
    }

    @Test
    fun `a failing formatter falls back to the built-in rendering`() {
        val renderer = GrpcMessageRenderer(listOf(GrpcMessageFormatter { _, _ -> error("boom") }))

        assertThat(render(renderer, message).format).isEqualTo(BodyFormat.SCHEMA_JSON)
    }

    @Test
    fun `messages without a generated class are decoded from the wire`() {
        val bytes = StringValue.of("raw").toByteArray()

        val rendered = GrpcMessageRenderer(emptyList()).render("svc/Method", bytes, bytes.size.toLong()) { bytes }

        assertThat(rendered.text).isEqualTo("""{"#1":"raw"}""")
        assertThat(rendered.format).isEqualTo(BodyFormat.RAW_JSON)
    }

    @Test
    fun `messages that can't be decoded use toString`() {
        val rendered = GrpcMessageRenderer(emptyList()).render("svc/Method", "plain", null) { null }

        assertThat(rendered.text).isEqualTo("plain")
        assertThat(rendered.format).isEqualTo(BodyFormat.TEXT)
    }

    private fun render(
        renderer: GrpcMessageRenderer,
        message: Any,
    ) = renderer.render("svc/Method", message, message.toString().length.toLong()) { null }
}
