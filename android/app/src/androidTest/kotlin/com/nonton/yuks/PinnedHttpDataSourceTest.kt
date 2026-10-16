package com.nonton.yuks

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinnedHttpDataSourceTest {
    @Test fun rangesUseExactNumericBoundaries() {
        assertEquals(Triple(262144L, 524287L, 900000L), PinnedHttpPolicy.range("bytes 262144-524287/900000"))
        assertEquals(Triple(0L, 12L, -1L), PinnedHttpPolicy.range("bytes 0-12/*"))
        listOf("bytes \\d-12/20", "bytes 1-0/20", "bytes 0-20/20", "bytes 0-1/0", "bytes 0-1/20 extra",
            "bytes 0-9223372036854775808/9223372036854775809").forEach { value ->
            rejected { PinnedHttpPolicy.range(value) }
        }
    }

    @Test fun mediaUrisAndHeadersCannotEscapeTrustedTransport() {
        assertEquals("media.googlevideo.com", PinnedHttpPolicy.uri("https://media.googlevideo.com/path?signature=fake", "youtube").host)
        listOf("http://media.googlevideo.com/x", "https://media.googlevideo.com:444/x",
            "https://user@media.googlevideo.com/x", "https://googlevideo.com.attacker.test/x",
            "https://media.googlevideo.com/x\r\nHost: localhost", "https://media.googlevideo.com/x#fragment").forEach { value ->
            rejected { PinnedHttpPolicy.uri(value, "youtube") }
        }
        rejected { PinnedHttpPolicy.uri("https://media.googlevideo.com/x", "facebook") }
        rejected { PinnedHttpPolicy.headers(mapOf("Authorization" to "not-a-real-token")) }
        rejected { PinnedHttpPolicy.headers(mapOf("Range" to "bytes=0-")) }
        rejected { PinnedHttpPolicy.headers(mapOf("User-Agent" to "test\r\nHost: localhost")) }
    }

    @Test fun allAddressFamiliesRejectNonPublicDestinations() {
        listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1",
            "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "2001:db8::1", "64:ff9b::7f00:1").forEach { literal ->
            assertFalse(literal, PinnedHttpPolicy.publicAddress(InetAddress.getByName(literal)))
        }
        assertTrue(PinnedHttpPolicy.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PinnedHttpPolicy.publicAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test fun validatorsRejectWeakTagsAndUncalibratedModificationDates() {
        assertEquals("\"version1\"", PinnedHttpPolicy.strongEtag("\"version1\""))
        assertNull(PinnedHttpPolicy.strongEtag("W/\"version1\""))
        assertNull(PinnedHttpPolicy.strongEtag("unquoted"))
        assertNull(PinnedHttpPolicy.strongEtag("\"x\r\ny\""))
        val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
        assertEquals(modified, PinnedHttpPolicy.lastModified(modified, "Wed, 21 Oct 2015 07:30:00 GMT"))
        assertNull(PinnedHttpPolicy.lastModified(modified, "Wed, 21 Oct 2015 07:28:30 GMT"))
        assertNull(PinnedHttpPolicy.lastModified(modified, null))
    }

    @Test fun fixedLengthBodyRejectsTruncationInsteadOfReturningSuccessfulEof() {
        val body = HttpBody(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 5L, false)
        val bytes = ByteArray(8)
        assertEquals(3, body.read(bytes, 0, bytes.size))
        rejected { body.read(bytes, 0, bytes.size) }
    }

    @Test fun chunkedBodyHonorsFramingAndRejectsIncompleteTerminator() {
        assertEquals("abcdef", drain(HttpBody(input("3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n"), 6L, true)))
        assertEquals("abc", drain(HttpBody(input("3;part=one\r\nabc\r\n0\r\nChecksum: unused\r\n\r\n"), -1L, true)))
        listOf("3\r\nab", "3\r\nabc", "3\r\nabc\r\n0\r\n", "3\r\nabc\n0\r\n\r\n",
            "-1\r\n", "10000000000000000\r\n", "4\r\nabcd\r\n0\r\n\r\n").forEach { wire ->
            rejected { drain(HttpBody(input(wire), 3L, true)) }
        }
    }

    @Test fun disabledOrRevokedNetworkLeaseNeverAuthorizesNewReads() {
        val gate = NetworkGate()
        rejected { gate.register(Any()) { fail("must not register while paused") } }
        var closed = false
        gate.enable()
        val lease = gate.register(Any()) { closed = true }
        gate.check(lease)
        gate.disable()
        assertTrue(closed)
        rejected { gate.check(lease) }
        gate.enable()
        rejected { gate.check(lease) }
    }

    private fun input(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.US_ASCII))
    private fun drain(body: HttpBody): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(2)
        while (true) {
            val count = body.read(buffer, 0, buffer.size)
            if (count < 0) return out.toString("US-ASCII")
            out.write(buffer, 0, count)
        }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("unsafe or incomplete input must fail") } catch (_: IOException) { }
    }
}
