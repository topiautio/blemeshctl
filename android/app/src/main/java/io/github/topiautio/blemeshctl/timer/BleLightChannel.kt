package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.ble.BleMeshClient
import io.github.topiautio.blemeshctl.ble.MeshCredentials
import io.github.topiautio.blemeshctl.protocol.AdvertisementInfo
import io.github.topiautio.blemeshctl.script.LightCommand

/**
 * Writes timer commands through a [BleMeshClient] shared by all timers that use the same light,
 * so two timers never open two connections to one light. The client connects on the first command
 * and closes after the idle time passed with each send.
 */
class BleLightChannel(
    private val client: BleMeshClient,
    private val light: LightRef,
    private val credentials: MeshCredentials,
) : LightChannel {
    @Volatile
    private var info: AdvertisementInfo? = light.info

    override suspend fun send(command: LightCommand, keepAliveMillis: Long) {
        val receipt = client.send(light.address, info, credentials, command.opcode, command.parameters, keepAliveMillis)
        info = receipt.info
    }
}
