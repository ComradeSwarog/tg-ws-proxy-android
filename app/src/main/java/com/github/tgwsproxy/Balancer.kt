package com.github.tgwsproxy

import java.util.Random

/**
 * CF proxy domain balancer.
 * Port of proxy/balancer.py from the original Python project.
 */
class Balancer {
    private var domains: List<String> = emptyList()
    private val dcToDomain = mutableMapOf<Int, String>()
    private val domainBlacklist = mutableMapOf<String, Long>() // domain -> expireAt
    private val domainFailCount = mutableMapOf<String, Int>() // domain -> consecutive failures
    val BlacklistTTL = 300_000L // 5 min base
    val BlacklistTTLMax = 1_800_000L // 30 min max
    val JitterRangeMs = 30_000L // ±30s jitter

    @Synchronized
    fun updateDomainsList(domainsList: List<String>) {
        if (domains == domainsList) return
        domains = domainsList.toList()
        val rng = Random()
        for (dcId in listOf(1, 2, 3, 4, 5, 203)) {
            if (domains.isNotEmpty()) {
                dcToDomain[dcId] = domains[rng.nextInt(domains.size)]
            }
        }
        // drop expired blacklist entries
        val now = System.currentTimeMillis()
        domainBlacklist.entries.removeIf { it.value < now }
    }

    @Synchronized
    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        if (dcToDomain[dcId] == domain) return false
        dcToDomain[dcId] = domain
        // Reset failure count on success — domain works again
        domainFailCount.remove(domain)
        return true
    }

    @Synchronized
    fun markDomainFailed(domain: String, baseTtlMs: Long = BlacklistTTL) {
        val count = (domainFailCount[domain] ?: 0) + 1
        domainFailCount[domain] = count
        // Exponential backoff: double TTL with each consecutive failure, cap at max
        val effectiveTtl = (baseTtlMs * (1 shl (count - 1))).coerceAtMost(BlacklistTTLMax)
        val jitterRange = if (effectiveTtl > JitterRangeMs) JitterRangeMs.coerceAtMost(effectiveTtl / 2) else 0L
        val jitter = if (jitterRange > 0) Random().nextInt((2 * jitterRange).toInt() + 1).toLong() - jitterRange else 0L
        domainBlacklist[domain] = System.currentTimeMillis() + effectiveTtl + jitter
    }

    @Synchronized
    fun getDomainsForDc(dcId: Int): Iterator<String> {
        val now = System.currentTimeMillis()
        domainBlacklist.entries.removeIf { it.value < now }
        val currentDomain = dcToDomain[dcId]
        val shuffled = domains.shuffled()
        val result = mutableListOf<String>()
        if (currentDomain != null && !domainBlacklist.containsKey(currentDomain)) result.add(currentDomain)
        for (d in shuffled) {
            if (d != currentDomain && !domainBlacklist.containsKey(d)) result.add(d)
        }
        return result.iterator()
    }
}