package com.github.tgwsproxy

import org.junit.Assert.*
import org.junit.Test

class DoHResolverTest {

    @Test
    fun `isIpAddress returns true for valid IPv4 strings`() {
        assertTrue(DoHResolver.isIpAddress("149.154.167.220"))
        assertTrue(DoHResolver.isIpAddress("8.8.8.8"))
        assertTrue(DoHResolver.isIpAddress("0.0.0.0"))
        assertTrue(DoHResolver.isIpAddress("255.255.255.255"))
    }

    @Test
    fun `isIpAddress returns false for domains and non-IP strings`() {
        assertFalse(DoHResolver.isIpAddress("kws2.web.telegram.org"))
        assertFalse(DoHResolver.isIpAddress("hello"))
        assertFalse(DoHResolver.isIpAddress(""))
        assertFalse(DoHResolver.isIpAddress("1.1.1"))
        assertFalse(DoHResolver.isIpAddress("1.1.1.1.1"))
    }

    @Test
    fun `resolve returns IP immediately when hostname is already IP without network call`() {
        val result = DoHResolver.resolve("149.154.167.220", listOf("https://fake/"), 500)
        assertEquals(listOf("149.154.167.220"), result)
    }

    @Test
    fun `isIpAddress is lenient on octet range but strict on structure`() {
        // The current implementation is a fast structural check (1-3 digits octets).
        // It will match "256.1.1.1" because it looks like an IPv4 shape.
        assertTrue(DoHResolver.isIpAddress("256.1.1.1"))
        assertTrue(DoHResolver.isIpAddress("999.999.999.999"))
    }
}
