package me.gm.cleaner.util

import java.net.URI

@JvmOverloads
fun URI.copy(
    scheme: String? = this.scheme,
    userInfo: String? = this.userInfo,
    host: String? = this.host,
    port: Int = this.port,
    path: String? = this.path,
    query: String? = this.query,
    fragment: String? = this.fragment
): URI = URI(scheme, userInfo, host, port, path, query, fragment)
