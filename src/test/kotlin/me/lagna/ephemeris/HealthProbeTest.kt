// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris

import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HealthProbeTest {
    @Test
    fun `reports serving, not serving and unreachable`() {
        val health = HealthStatusManager()
        val server =
            NettyServerBuilder
                .forPort(0)
                .addService(health.healthService)
                .build()
                .start()
        val target = "127.0.0.1:${server.port}"
        try {
            health.setStatus("", ServingStatus.SERVING)
            HealthProbe.run(listOf(target)) shouldBe 0
            health.setStatus("", ServingStatus.NOT_SERVING)
            HealthProbe.run(listOf(target)) shouldBe 1
        } finally {
            server.shutdownNow().awaitTermination()
        }
        HealthProbe.run(listOf(target)) shouldBe 1
    }
}
