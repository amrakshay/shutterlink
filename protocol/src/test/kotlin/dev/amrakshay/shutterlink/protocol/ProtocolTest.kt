package dev.amrakshay.shutterlink.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `talks to its own version`() {
        assertTrue(isCompatible(PROTOCOL_VERSION))
    }

    @Test
    fun `talks to a newer peer rather than refusing it`() {
        assertTrue(isCompatible(PROTOCOL_VERSION + 5))
    }

    @Test
    fun `refuses a peer older than the floor`() {
        assertFalse(isCompatible(MIN_SUPPORTED_PROTOCOL_VERSION - 1))
    }

    @Test
    fun `both roles exist`() {
        assertTrue(Role.entries.containsAll(listOf(Role.CAMERA, Role.CONTROLLER)))
    }
}
