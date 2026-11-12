package com.laoke.weshirt.data

/** Release builds use the privately supplied service; only tests may override it. */
internal fun serviceEndpoint(debug: Boolean, bundled: String, testOverride: String?): String =
    if (debug) testOverride ?: bundled else bundled
