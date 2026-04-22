package com.github.tgwsproxy

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe proxy statistics.
 * Mirrors proxy/stats.py from the original Python project.
 */
class ProxyStats {
    val connectionsTotal = AtomicLong(0)
    val connectionsActive = AtomicLong(0)
    val connectionsWs = AtomicLong(0)
    val connectionsTcpFallback = AtomicLong(0)
    val connectionsCfproxy = AtomicLong(0)
    val connectionsBad = AtomicLong(0)
    val connectionsMasked = AtomicLong(0)
    val wsErrors = AtomicLong(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val poolHits = AtomicLong(0)
    val poolMisses = AtomicLong(0)

    fun reset() {
        connectionsTotal.set(0)
        connectionsActive.set(0)
        connectionsWs.set(0)
        connectionsTcpFallback.set(0)
        connectionsCfproxy.set(0)
        connectionsBad.set(0)
        connectionsMasked.set(0)
        wsErrors.set(0)
        bytesUp.set(0)
        bytesDown.set(0)
        poolHits.set(0)
        poolMisses.set(0)
    }

    fun summary(): String {
        val poolTotal = poolHits.get() + poolMisses.get()
        val poolStr = if (poolTotal > 0) "${poolHits.get()}/$poolTotal" else "n/a"
        return "total=${connectionsTotal.get()} " +
                "active=${connectionsActive.get()} " +
                "ws=${connectionsWs.get()} " +
                "tcp_fb=${connectionsTcpFallback.get()} " +
                "cf=${connectionsCfproxy.get()} " +
                "bad=${connectionsBad.get()} " +
                "masked=${connectionsMasked.get()} " +
                "err=${wsErrors.get()} " +
                "pool=$poolStr " +
                "up=${MtProtoConstants.humanBytes(bytesUp.get())} " +
                "down=${MtProtoConstants.humanBytes(bytesDown.get())}"
    }

    fun shortSummary(): String {
        return "↑${MtProtoConstants.humanBytes(bytesUp.get())} ↓${MtProtoConstants.humanBytes(bytesDown.get())} " +
                "conn:${connectionsActive.get()}/${connectionsTotal.get()}"
    }
}

val stats = ProxyStats()