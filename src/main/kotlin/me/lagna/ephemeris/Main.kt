// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris

import io.grpc.Server
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import me.lagna.ephemeris.cache.CaffeineResultCache
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.grpc.EphemerisService
import me.lagna.ephemeris.swe.SwissEphemeris
import java.util.logging.Logger

private val log = Logger.getLogger("me.lagna.ephemeris")

fun main() {
    val config = ServiceConfig.fromEnvironment()
    val ephemeris = SwissEphemeris.open(config.library, config.ephemeris, config.threads)
    val cache = if (config.cacheMaxBytes > 0) CaffeineResultCache(config.cacheMaxBytes) else NoopResultCache
    val health = HealthStatusManager()
    val server: Server =
        NettyServerBuilder
            .forPort(config.port)
            .addService(EphemerisService(ephemeris, cache, config.limits, config.sourceUrl))
            .addService(health.healthService)
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()
            .start()
    health.setStatus("", ServingStatus.SERVING)
    log.info("${ephemeris.version} serving gRPC on port ${config.port}; source: ${config.sourceUrl}")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            health.enterTerminalState()
            server.shutdown()
            ephemeris.close()
        },
    )
    server.awaitTermination()
}
