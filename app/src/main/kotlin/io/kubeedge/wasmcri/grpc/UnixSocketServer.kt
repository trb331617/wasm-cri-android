package io.kubeedge.wasmcri.grpc

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * gRPC server bound to a Unix domain socket. Uses netty epoll native transport,
 * which works on Android because the OS kernel is Linux.
 */
class UnixSocketServer(
    private val socketPath: String,
    private val services: List<BindableService>,
) {
    private val boss = EpollEventLoopGroup(1)
    private val workers = EpollEventLoopGroup()
    private var server: Server? = null

    fun start() {
        check(Epoll.isAvailable()) {
            "epoll native transport is not available: ${Epoll.unavailabilityCause()}"
        }
        val sockFile = File(socketPath)
        if (sockFile.exists()) sockFile.delete()
        sockFile.parentFile?.mkdirs()

        val builder = NettyServerBuilder
            .forAddress(DomainSocketAddress(socketPath))
            .channelType(EpollServerDomainSocketChannel::class.java)
            .bossEventLoopGroup(boss)
            .workerEventLoopGroup(workers)
            .maxInboundMessageSize(MAX_MSG_SIZE)
        services.forEach(builder::addService)

        server = builder.build().start()

        // 0700 — only the same UID can connect.
        sockFile.setReadable(false, false); sockFile.setReadable(true, true)
        sockFile.setWritable(false, false); sockFile.setWritable(true, true)
        sockFile.setExecutable(false, false)
    }

    fun shutdown() {
        try {
            server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        } finally {
            boss.shutdownGracefully()
            workers.shutdownGracefully()
            server = null
        }
    }

    fun awaitTermination() {
        server?.awaitTermination()
    }

    companion object {
        private const val MAX_MSG_SIZE = 16 * 1024 * 1024  // 16 MiB, matches kubelet
    }
}
