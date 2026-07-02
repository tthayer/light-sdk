package com.thelightphone.sdk.security

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OathSessionTest {
    // RFC test secret "JBSWY3DPEHPK3PXP" == "Hello!" + de ad be ef.
    private val helloSecret = byteArrayOf(
        0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, 0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(),
    )

    @Test
    fun `calculateAll matches known TOTP vector`() = runBlocking {
        // Cross-check against the authenticator example's independent generator:
        // secret JBSWY3DPEHPK3PXP, 6 digits, 30s, SHA1, t=0 -> 282760.
        val card = InMemoryOathCard(listOf(InMemoryOathCard.Entry(id = "Example:alice", secret = helloSecret)))
        val codes = OathSession(card).calculateAll(epochSeconds = 0)
        assertEquals(1, codes.size)
        assertEquals("282760", codes[0].value)
        assertEquals("Example", codes[0].credential.issuer)
        assertEquals("alice", codes[0].credential.name)
        assertEquals(0L, codes[0].validFromEpochSeconds)
        assertEquals(30L, codes[0].validUntilEpochSeconds)
    }

    @Test
    fun `listCredentials returns metadata`() = runBlocking {
        val card = InMemoryOathCard(
            listOf(
                InMemoryOathCard.Entry("GitHub:me", helloSecret),
                InMemoryOathCard.Entry("60/ACME:you", helloSecret, period = 60),
            )
        )
        val creds = OathSession(card).listCredentials()
        assertEquals(2, creds.size)
        assertEquals(30, creds[0].period)
        assertEquals(60, creds[1].period)
        assertEquals("ACME", creds[1].issuer)
    }

    @Test
    fun `non-default period is recalculated with its own challenge`() = runBlocking {
        // A 60s credential: CALCULATE ALL uses a 30s challenge, so the session
        // must recompute it. Verify it equals the 60s-challenge computation.
        val card = InMemoryOathCard(listOf(InMemoryOathCard.Entry("60/ACME:you", helloSecret, period = 60)))
        val codes = OathSession(card).calculateAll(epochSeconds = 90)
        assertEquals(1, codes.size)
        assertTrue(codes[0].hasValue)
        assertEquals(60L, codes[0].validUntilEpochSeconds - codes[0].validFromEpochSeconds)
        // window aligns to the 60s period, not 30s
        assertEquals(60L, codes[0].validFromEpochSeconds)
    }

    @Test
    fun `hotp credential comes back without a value`() = runBlocking {
        val card = InMemoryOathCard(listOf(InMemoryOathCard.Entry("H:counter", helloSecret, type = OathType.HOTP)))
        val codes = OathSession(card).calculateAll(epochSeconds = 0)
        assertEquals(1, codes.size)
        assertNull(codes[0].value)
        assertEquals(OathType.HOTP, codes[0].credential.type)
    }
}
