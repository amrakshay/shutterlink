package dev.amrakshay.shutterlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `every control message survives the round trip`() {
        everyMessage.forEach { sent ->
            val back = MessageCodec.decode(MessageCodec.encode(sent))
            assertNotNull("$sent did not survive encoding", back)
            assertEquals(sent, back)
        }
    }

    /** One list, every case — shared by the round-trip test and the coverage check. */
    private val everyMessage: List<Message> = listOf(
            Message.Hello(deviceName = "a"),
            Message.Capabilities(
                deviceName = "b",
                hasFrontCamera = true,
                hasBackCamera = false,
                minZoom = 1f,
                maxZoom = 8f,
            ),
            Message.Ping(7), Message.Pong(7), Message.Bye("done"),
            Message.StartPreview(1280, 720, 24, 3_000_000),
            Message.StopPreview,
            Message.SetZoom(0.42f),
            Message.SwitchCamera(front = true),
            Message.Capture(timerSeconds = 3, engine = ENGINE_SHUTTERLINK),
            Message.Countdown(2),
            Message.CaptureStarted,
            Message.CaptureFailed("no storage"),
            Message.PhotoReady(99L, "SHUTTERLINK_x.jpg", 3_600_000L, 4000, 3000),
            Message.Status(78, "none", streaming = true, shotsTaken = 4),
            Message.Engines(
                listOf(
                    CaptureEngine(ENGINE_SHUTTERLINK, "ShutterLink", handsPhotoBack = true, tested = true),
                    CaptureEngine("com.oneplus.camera", "OnePlus Camera", handsPhotoBack = true),
                ),
            ),
        Message.DeletePhoto("SHUTTERLINK_x.jpg"),
    )

    @Test
    fun `every message type is covered by the round-trip test`() {
        // Derived, not a magic number: a hardcoded count tells you something
        // changed but not what, and is wrong the moment anyone edits the list.
        // This names the case that is missing.
        val covered = everyMessage.map { it::class }.toSet()
        val declared = Message::class.sealedSubclasses.toSet()
        val untested = declared - covered
        assertTrue(
            "these Message cases are not in the round-trip test: " +
                untested.joinToString { it.simpleName ?: "?" },
            untested.isEmpty(),
        )
    }

    @Test
    fun `engine ids and labels survive, including the untested flag`() {
        val sent = Message.Engines(
            listOf(CaptureEngine("com.picsart", "PicsArt", handsPhotoBack = false, tested = false)),
        )
        val back = MessageCodec.decode(MessageCodec.encode(sent)) as Message.Engines
        assertEquals("com.picsart", back.engines.single().id)
        assertEquals(false, back.engines.single().tested)
        assertEquals(false, back.engines.single().handsPhotoBack)
    }

    @Test
    fun `hello announces this build's protocol version by default`() {
        assertEquals(PROTOCOL_VERSION, Message.Hello(deviceName = "x").protocolVersion)
    }
}
