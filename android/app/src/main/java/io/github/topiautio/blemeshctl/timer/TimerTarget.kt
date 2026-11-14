package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.ble.MeshCredentials
import io.github.topiautio.blemeshctl.protocol.AdvertisementInfo

/** One light a timer writes to; [info] is the cached advertisement that lets it connect without a scan. */
data class LightRef(val address: String, val name: String, val info: AdvertisementInfo? = null)

/** The lights a timer controls: one light, or every member of a group. */
data class TimerTarget(val label: String, val lights: List<LightRef>, val isGroup: Boolean) {
    val addresses: Set<String> get() = lights.map { it.address.uppercase() }.toSet()
}

/**
 * Everything the service needs to run one timer. Held in memory only: [credentials] are never
 * stored in a timer record or notification.
 */
data class TimerSpec(val request: TimerRequest, val target: TimerTarget, val credentials: MeshCredentials)
