package io.kubeedge.wasmcri.grpc

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * gRPC server exposed via a filesystem AF_UNIX socket on Android.
 *
 *   external client (kubelet / crictl)
 *           │
 *           ▼
 *   /data/data/<pkg>/files/cri.sock     ← LocalServerSocket(FileDescriptor)
 *           │
 *           ▼ relay thread (per-connection bidirectional pipe)
 *   127.0.0.1:<ephemeral>               ← Netty NIO TCP gRPC server
 *           │
 *           ▼
 *   RuntimeService / ImageService impls
 *
 * Why this dance instead of "just use netty-transport-native-epoll"?
 * Netty's prebuilt native epoll .so is glibc-linked; Android is bionic.
 * Loading it fails with UnsatisfiedLinkError. Rebuilding netty-epoll for
 * Android with NDK is doable but heavy. The relay solves it with ~50 lines
 * of code and zero netty-internal patching, at the cost of one extra
 * memcpy per packet — invisible at CRI traffic volumes.
 */
class UnixSocketServer(
    private val socketPath: String,
    private val services: List<BindableService>,
) {
    private val boss = NioEventLoopGroup(1)
    private val workers = NioEventLoopGroup()
    private var server: Server? = null

    private var listenPfd: ParcelFileDescriptor? = null
    private var localServerSocket: LocalServerSocket? = null
    private val relayPool = Executors.newCachedThreadPool { r ->
        Thread(r, "uds-relay").apply { isDaemon = true }
    }
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    fun start() {
        // 1) gRPC server on TCP loopback, NIO transport (no native libs).
        val builder = NettyServerBuilder
            .forAddress(InetSocketAddress("127.0.0.1", 0))
            .channelType(NioServerSocketChannel::class.java)
            .bossEventLoopGroup(boss)
            .workerEventLoopGroup(workers)
            .maxInboundMessageSize(MAX_MSG_SIZE)
        services.forEach(builder::addService)
        server = builder.build().start()
        val grpcPort = server!!.port
        Log.i(TAG, "gRPC NIO server bound to 127.0.0.1:$grpcPort")

        // 2) Bind a filesystem AF_UNIX listener via NDK helper.
        File(socketPath).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val fd = UdsNative.bind(socketPath)
        check(fd >= 0) { "failed to bind unix socket at $socketPath, errno=${-fd}" }

        // 3) Wrap raw FD in a Java FileDescriptor; LocalServerSocket calls
        //    listen() inside its constructor.
        listenPfd = ParcelFileDescriptor.adoptFd(fd)
        localServerSocket = LocalServerSocket(listenPfd!!.fileDescriptor)
        // 0700 — only same UID can connect.
        File(socketPath).apply {
            setReadable(false, false); setReadable(true, true)
            setWritable(false, false); setWritable(true, true)
            setExecutable(false, false)
        }
        Log.i(TAG, "AF_UNIX listener ready at $socketPath")

        // 4) Accept loop: each LocalSocket gets piped into TCP loopback.
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try {
                    localServerSocket!!.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                relayPool.submit { relay(client, grpcPort) }
            }
        }, "uds-accept").apply { isDaemon = true; start() }
    }

    private fun relay(client: LocalSocket, grpcPort: Int) {
        val tcp = try {
            Socket("127.0.0.1", grpcPort)
        } catch (e: IOException) {
            Log.e(TAG, "connect to grpc loopback failed", e)
            try { client.close() } catch (_: Throwable) {}
            return
        }
        // Two pump threads. When either side closes, the read returns -1
        // and we tear both ends down.
        val t1 = Thread({ pump(client.inputStream, tcp.getOutputStream()); closeBoth(client, tcp) },
                         "uds-relay-c2s").apply { isDaemon = true }
        val t2 = Thread({ pump(tcp.getInputStream(), client.outputStream); closeBoth(client, tcp) },
                         "uds-relay-s2c").apply { isDaemon = true }
        t1.start(); t2.start()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: IOException) {
            // peer closed — normal
        }
    }

    @Synchronized
    private fun closeBoth(client: LocalSocket, tcp: Socket) {
        try { client.close() } catch (_: Throwable) {}
        try { tcp.close() } catch (_: Throwable) {}
    }

    fun shutdown() {
        running = false
        try { localServerSocket?.close() } catch (_: Throwable) {}
        try { listenPfd?.close() } catch (_: Throwable) {}
        relayPool.shutdownNow()
        try { server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS) } catch (_: Throwable) {}
        boss.shutdownGracefully()
        workers.shutdownGracefully()
        try { File(socketPath).delete() } catch (_: Throwable) {}
        server = null
        localServerSocket = null
        listenPfd = null
    }

    fun awaitTermination() {
        server?.awaitTermination()
    }

    companion object {
        private const val TAG = "WasmCRI"
        private const val MAX_MSG_SIZE = 16 * 1024 * 1024  // matches kubelet
    }
}
