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
    private val BlacklistTTL = 300_000L // 5 min

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
        return true
    }

    @Synchronized
    fun markDomainFailed(domain: String) {
        domainBlacklist[domain] = System.currentTimeMillis() + BlacklistTTL
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