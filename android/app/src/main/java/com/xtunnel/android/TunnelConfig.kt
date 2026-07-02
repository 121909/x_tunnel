package com.xtunnel.android

import org.json.JSONArray
import org.json.JSONObject

data class TunnelConfig(
    val forwardUrl: String = "wss://example.com/tunnel",
    val token: String = "",
    val socksPort: String = "1080",
    val connectionNum: String = "3",
    val dnsServer: String = "https://doh.pub/dns-query",
    val echDomain: String = "cloudflare-ech.com",
    val targetIps: String = "",
    val udpBlockPorts: String = "443",
    val ipStrategy: String = "",
    val insecure: Boolean = false,
    val fallback: Boolean = false,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("listen_addrs", JSONArray(buildListenAddrs()))
        root.put("forward_addr", forwardUrl.trim())
        root.put("token", token.trim())
        root.put("connection_num", connectionNum.trim().toIntOrNull() ?: 3)
        root.put("dns_server", dnsServer.trim())
        root.put("ech_domain", echDomain.trim())
        root.put("fallback", fallback)
        root.put("insecure", insecure)
        root.put("udp_block_ports", JSONArray(splitCsvInts(udpBlockPorts)))

        val ips = splitCsvStrings(targetIps)
        if (ips.isNotEmpty()) {
            root.put("target_ips", JSONArray(ips))
        }

        val strategy = ipStrategy.trim()
        if (strategy.isNotEmpty()) {
            root.put("ip_strategy", strategy)
        }

        return root.toString(2)
    }

    fun buildListenAddrs(): List<String> {
        val addrs = mutableListOf<String>()
        val socks = socksPort.trim()
        if (socks.isNotEmpty()) {
            addrs += "socks5://127.0.0.1:$socks"
        }
        return addrs
    }

    companion object {
        fun fromJson(raw: String): TunnelConfig {
            return try {
                val root = JSONObject(raw)
                TunnelConfig(
                    forwardUrl = root.optString("forward_addr", "wss://example.com/tunnel"),
                    token = root.optString("token", ""),
                    socksPort = extractPort(root.optJSONArray("listen_addrs"), "socks5"),
                    connectionNum = root.optInt("connection_num", 3).toString(),
                    dnsServer = root.optString("dns_server", "https://doh.pub/dns-query"),
                    echDomain = root.optString("ech_domain", "cloudflare-ech.com"),
                    targetIps = joinJsonArray(root.optJSONArray("target_ips")),
                    udpBlockPorts = joinIntArray(root.optJSONArray("udp_block_ports"), "443"),
                    ipStrategy = root.optString("ip_strategy", ""),
                    insecure = root.optBoolean("insecure", false),
                    fallback = root.optBoolean("fallback", false),
                )
            } catch (_: Exception) {
                TunnelConfig()
            }
        }

        private fun extractPort(listeners: JSONArray?, scheme: String): String {
            if (listeners == null) {
                return defaultPortForScheme(scheme)
            }
            for (i in 0 until listeners.length()) {
                val value = listeners.optString(i)
                if (!value.startsWith("$scheme://")) {
                    continue
                }
                val host = value.removePrefix("$scheme://").substringAfterLast("@")
                val port = host.substringAfterLast(":", "")
                if (port.isNotEmpty()) {
                    return port
                }
            }
            return defaultPortForScheme(scheme)
        }

        private fun defaultPortForScheme(scheme: String): String =
            when (scheme) {
                "socks5" -> "1080"
                else -> ""
            }

        private fun joinJsonArray(array: JSONArray?): String {
            if (array == null) {
                return ""
            }
            val values = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) {
                    values += value.trim()
                }
            }
            return values.joinToString(", ")
        }

        private fun joinIntArray(array: JSONArray?, defaultValue: String): String {
            if (array == null || array.length() == 0) {
                return defaultValue
            }
            val values = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.opt(i)?.toString()?.trim()
                if (!value.isNullOrBlank()) {
                    values += value
                }
            }
            return if (values.isEmpty()) defaultValue else values.joinToString(", ")
        }

        private fun splitCsvStrings(raw: String): List<String> =
            raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        private fun splitCsvInts(raw: String): List<Int> =
            raw.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
    }
}
