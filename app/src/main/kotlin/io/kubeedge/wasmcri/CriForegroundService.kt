package io.kubeedge.wasmcri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.kubeedge.wasmcri.grpc.UnixSocketServer
import io.kubeedge.wasmcri.runtime.ContainerStore
import io.kubeedge.wasmcri.runtime.ImageServiceImpl
import io.kubeedge.wasmcri.runtime.ImageStore
import io.kubeedge.wasmcri.runtime.RuntimeServiceImpl
import io.kubeedge.wasmcri.runtime.SandboxStore
import io.kubeedge.wasmcri.wasm.WasmEdgeRunner
import java.io.File

class CriForegroundService : Service() {

    private var server: UnixSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("starting"))

        try {
            val socketPath = File(filesDir, "cri.sock").absolutePath
            val sandboxes = SandboxStore()
            val containers = ContainerStore()
            val images = ImageStore(File(filesDir, "images"))
            val runner = WasmEdgeRunner(this, containers)

            val srv = UnixSocketServer(
                socketPath = socketPath,
                services = listOf(
                    RuntimeServiceImpl(sandboxes, containers, images, runner),
                    ImageServiceImpl(images),
                ),
            )
            srv.start()
            server = srv
            Log.i(TAG, "gRPC server listening on unix://$socketPath")
            updateNotification("listening on $socketPath")
        } catch (e: Throwable) {
            Log.e(TAG, "failed to start CRI server", e)
            updateNotification("error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { server?.shutdown() }
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WasmCRI", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WasmCRI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "WasmCRI"
        private const val CHANNEL_ID = "wasm-cri"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CriForegroundService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CriForegroundService::class.java))
        }
    }
}
