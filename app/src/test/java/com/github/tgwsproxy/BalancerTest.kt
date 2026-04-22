package com.github.tgwsproxy

import org.junit.Assert.*
import org.junit.Test

class BalancerTest {

    @Test
    fun `getDomainsForDc returns current domain first when not blacklisted`() {
        val b = Balancer()
        b.updateDomainsList(listOf("a.com", "b.com", "c.com"))
        b.updateDomainForDc(2, "a.com")

        val result = b.getDomainsForDc(2).asSequence().toList()
        assertTrue("Expected current domain first", result.first() == "a.com")
        assertEquals("Expected 3 unique domains", 3, result.distinct().size)
    }

    @Test
    fun `getDomainsForDc excludes blacklisted domain`() {
        val b = Balancer()
        b.updateDomainsList(listOf("a.com", "b.com", "c.com"))
        b.updateDomainForDc(2, "a.com")
        b.markDomainFailed("a.com")

        val result = b.getDomainsForDc(2).asSequence().toList()
        assertTrue("Blacklisted domain should be excluded", "a.com" !in result)
        assertTrue("Other domains should be present", result.contains("b.com"))
    }

    @Test
    fun `getDomainsForDc returns expired blacklisted domain`() {
        val b = Balancer()
        b.updateDomainsList(listOf("a.com", "b.com"))
        b.markDomainFailed("a.com", ttlMs = -1L) // already expired

        val result = b.getDomainsForDc(2).asSequence().toList()
        assertTrue("Expired blacklist domain should be available again", "a.com" in result)
    }

    @Test
    fun `markDomainFailed custom TTL respected`() {
        val b = Balancer()
        b.updateDomainsList(listOf("x.com"))
        b.markDomainFailed("x.com", ttlMs = 1_000L)
        val before = b.getDomainsForDc(2).asSequence().toList()
        assertTrue("x.com should be blacklisted", "x.com" !in before)

        Thread.sleep(1_200L) // wait for TTL to expire
        val after = b.getDomainsForDc(2).asSequence().toList()
        assertTrue("x.com should be available after TTL expires", "x.com" in after)
    }

    @Test
    fun `updateDomainsList resets current domain mapping but includes expired blacklist`() {
        val b = Balancer()
        b.updateDomainsList(listOf("a.com", "b.com"))
        b.updateDomainForDc(2, "a.com")
        b.markDomainFailed("b.com", ttlMs = -1L) // expired

        b.updateDomainsList(listOf("a.com", "b.com", "c.com"))
        val result = b.getDomainsForDc(2).asSequence().toList()
        assertTrue("Expired domain should be included", result.contains("b.com"))
        assertTrue("Updated domains should include all 3", "c.com" in result)
    }

    @Test
    fun `shuffling does not lose domains`() {
        val b = Balancer()
        val domains = (1..20).map { "d$it.com" }
        b.updateDomainsList(domains)

        val observed = mutableSetOf<String>()
        repeat(50) {
            val list = b.getDomainsForDc(2).asSequence().toList()
            observed.addAll(list)
            assertEquals("All domains should be present", domains.size, list.size)
        }
        assertEquals("Every domain should appear over repeated calls", domains.toSet(), observed)
    }
}
