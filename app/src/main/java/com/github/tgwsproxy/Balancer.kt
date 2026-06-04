package com.github.tgwsproxy

import java.util.Random

/**
 * CF proxy domain balancer.
 * Port of proxy/balancer.py from the original Python project.
 * Simple domain rotation without blacklisting — matches upstream behaviour.
 */
class Balancer {
    private var domains: List<String> = emptyList()
    private val dcToDomain = mutableMapOf<Int, String>()

    @Synchronized
    fun updateDomainsList(domainsList: List<String>) {
        if (domains.toSet() == domainsList.toSet()) return
        domains = domainsList.toList()
        val rng = Random()
        for (dcId in listOf(1, 2, 3, 4, 5, 203)) {
            if (domains.isNotEmpty()) {
                dcToDomain[dcId] = domains[rng.nextInt(domains.size)]
            }
        }
    }

    @Synchronized
    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        if (dcToDomain[dcId] == domain) return false
        dcToDomain[dcId] = domain
        return true
    }

    @Synchronized
    fun getDomainsForDc(dcId: Int): Iterator<String> {
        val currentDomain = dcToDomain[dcId]
        val shuffled = domains.shuffled()
        val result = mutableListOf<String>()
        if (currentDomain != null) result.add(currentDomain)
        for (d in shuffled) {
            if (d != currentDomain) result.add(d)
        }
        return result.iterator()
    }
}
