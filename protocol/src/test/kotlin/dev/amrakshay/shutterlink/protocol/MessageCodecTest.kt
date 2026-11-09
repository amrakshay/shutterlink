package dev.amrakshay.shutterlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessageCodecTest {

    @Test
    fun `hello survives the round trip`() {
        val sent = Message.Hello(deviceName = "OnePlus 7")
        assertEquals(sent, MessageCodec.decode(MessageCodec.encode(sent)))
    }

    @Test
    fun `capabilities survive the round trip`() {
        val sent = Message.Capabilities(
            deviceName = "OnePlus 7",
            hasFrontCamera = true,
            hasBackCamera = true,
            minZoom = 1.0f,
            maxZoom = 10.0f,
        )
        assertEquals(sent, MessageCodec.decode(MessageCodec.encode(sent)))
    }

    @Test
    fun `ping carries its stamp back unchanged`() {
        val sent = Message.Ping(stamp = 1_762_700_000_123L)
        val back = MessageCodec.decode(MessageCodec.encode(sent)) as Message.Ping
        assertEquals(sent.stamp, back.stamp)
    }

    @Test
    fun `every message type is decodable as itself`() {
        val all = listOf(
            Message.Hello(deviceName = "a"),
            Message.Capabilities(
                deviceName = "b",
                hasFrontCamera = true,
                hasBackCamera = false,
                minZoom = 1f,
                maxZoom = 8f,
            ),
            Message.Ping(1),
            Message.Pong(1),
            Message.Bye("done"),
        )
        all.forEach { sent ->
            val back = MessageCodec.decode(MessageCodec.encode(sent))
            assertNotNull("$sent did not survive encoding", back)
            assertEquals(sent, back)
        }
    }

    @Test
    fun `garbage is ignored rather than fatal`() {
        assertNull(MessageCodec.decode(byteArrayOf(0x00, 0x13, 0x37, 0x42)))
    }

    @Test
    fun `an empty payload is ignored`() {
        assertNull(MessageCodec.decode(ByteArray(0)))
    }

    @Test
    fun `a truncated payload is ignored rather than fatal`() {
        val whole = MessageCodec.encode(
            Message.Capabilities(
                deviceName = "OnePlus 7",
                hasFrontCamera = true,
                hasBackCamera = true,
                minZoom = 1f,
                maxZoom = 10f,
            ),
        )
        // Half a message is exactly what a dropped connection leaves behind.
        assertNull(MessageCodec.decode(whole.copyOf(whole.size / 2)))
    }

    @Test
    fun `a message type this build has never heard of is ignored, not fatal`() {
        // Stand-in for a future build sending something new. Rather than
        // hand-rolling CBOR — which would really just be another garbage test —
        // take a genuinely well-formed message and rename its type to one that
        // does not exist here. "ping" and "pinX" are the same length, so every
        // CBOR length prefix around it stays valid: the payload is structurally
        // perfect and only the type is unknown.
        val real = MessageCodec.encode(Message.Ping(stamp = 42))
        val name = "ping".toByteArray(Charsets.US_ASCII)
        val at = real.indices.firstOrNull { i ->
            i + name.size <= real.size && real.copyOfRange(i, i + name.size).contentEquals(name)
        }
        assertNotNull("the type name should appear in the encoding", at)

        val future = real.copyOf().also { it[at!! + 3] = 'X'.code.toByte() }
        assertNull(MessageCodec.decode(future))

        // And the connection is still usable afterwards: a dropped message must
        // not poison the codec for the next one.
        assertEquals(Message.Ping(stamp = 42), MessageCodec.decode(real))
    }

    @Test
    fun `hello announces this build's protocol version by default`() {
        assertEquals(PROTOCOL_VERSION, Message.Hello(deviceName = "x").protocolVersion)
    }
}
