// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris

import io.grpc.ManagedChannelBuilder
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc
import java.util.concurrent.TimeUnit

/**
 * `healthcheck [host[:port]]`: asks a running instance's gRPC health service whether it is serving, for container
 * health checks that have no gRPC tooling. Exits 0 when SERVING, 1 otherwise. Defaults to 127.0.0.1 and `PORT`.
 */
object HealthProbe {
    private const val DEADLINE_SECONDS = 3L

    fun run(args: List<String>): Int {
        val target = args.firstOrNull() ?: "127.0.0.1:${System.getenv("PORT") ?: ServiceConfig.DEFAULT_PORT}"
        val channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build()
        return try {
            val response =
                HealthGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .check(HealthCheckRequest.getDefaultInstance())
            if (response.status == ServingStatus.SERVING) 0 else 1
        } catch (e: io.grpc.StatusRuntimeException) {
            System.err.println("healthcheck: ${e.status}")
            1
        } finally {
            channel.shutdownNow()
        }
    }
}
