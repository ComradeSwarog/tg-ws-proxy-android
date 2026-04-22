package com.github.tgwsproxy

import org.junit.Assert.*
import org.junit.Test

class DoHResolverTest {

    @Test
    fun `rotateEndpoints cycles through providers`() {
        val eps = listOf("cloudflare", "google", "quad9")
        val r1 = DoHResolver.rotateEndpoints(eps, rotation = true)
        val r2 = DoHResolver.rotateEndpoints(eps, rotation = true)
        // Both should contain all 3 endpoints
        assertEquals(3, r1.size)
        assertEquals(3, r2.size)
        assertEquals(setOf("cloudflare", "google", "quad9"), r1.toSet())
        assertEquals(setOf("cloudflare", "google", "quad9"), r2.toSet())
        // With 3 providers, r1 and r2 are different if AtomicInteger increments
        assertNotEquals(r1, r2)
    }

    @Test
    fun `rotateEndpoints with rotation disabled returns original order`() {
        val eps = listOf("a", "b", "c")
        val r = DoHResolver.rotateEndpoints(eps, rotation = false)
        assertEquals(eps, r)
    }

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
