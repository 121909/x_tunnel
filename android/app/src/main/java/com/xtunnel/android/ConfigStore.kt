package com.xtunnel.android

import android.content.Context

private const val PREFS_NAME = "xtunnel_prefs"
private const val KEY_CONFIG_JSON = "config_json"

object ConfigStore {
    fun load(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CONFIG_JSON, null) ?: defaultConfigJson()
    }

    fun save(context: Context, json: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG_JSON, json)
            .apply()
    }

    fun loadConfig(context: Context): TunnelConfig = TunnelConfig.fromJson(load(context))

    fun saveConfig(context: Context, config: TunnelConfig) {
        save(context, config.toJson())
    }

    fun defaultConfigJson(): String = """
        {
          "listen_addrs": [
            "socks5://127.0.0.1:1080"
          ],
          "forward_addr": "wss://example.com/tunnel",
          "token": "",
          "connection_num": 3,
          "dns_server": "https://doh.pub/dns-query",
          "ech_domain": "cloudflare-ech.com",
          "fallback": false,
          "insecure": false,
          "udp_block_ports": [443]
        }
    """.trimIndent()
}
