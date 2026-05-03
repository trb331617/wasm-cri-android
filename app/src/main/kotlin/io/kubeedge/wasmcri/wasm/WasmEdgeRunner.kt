package io.kubeedge.wasmcri.wasm

import android.content.Context
import android.util.Log
import io.kubeedge.wasmcri.runtime.Container
import io.kubeedge.wasmcri.runtime.ContainerStore
import io.runtime.v1.ContainerState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Spawns the wasmedge binary as a child process to run a .wasm module.
 *
 * The wasmedge ELF binary is shipped inside the APK as
 *   app/src/main/jniLibs/<abi>/libwasmedge.so
 * which Android's PackageManager extracts to nativeLibraryDir at install time
 * and sets the executable bit, since we declared
 *   android.packaging.jniLibs.useLegacyPackaging = true
 * in app/build.gradle.kts.
 */
class WasmEdgeRunner(
    context: Context,
    private val containers: ContainerStore,
) {
    private val wasmedgeBin: File =
        File(context.applicationInfo.nativeLibraryDir, "libwasmedge.so")
            .also {
                check(it.exists()) {
                    "wasmedge binary not found at ${it.path}; place it at " +
                            "app/src/main/jniLibs/<abi>/libwasmedge.so"
                }
                if (!it.canExecute()) it.setExecutable(true, false)
            }

    private val rootDir: File =
        File(context.filesDir, "containers").apply { mkdirs() }

    private val procs = ConcurrentHashMap<String, Process>()

    fun start(c: Container): Int {
        val workDir = File(rootDir, c.id).apply { mkdirs() }

        val cmd = buildList {
            add(wasmedgeBin.absolutePath)
            add("run")
            // Map CRI Mounts onto WASI --dir guest:host
            for (m in c.mounts) {
                add("--dir")
                add("${m.containerPath}:${m.hostPath}")
            }
            for ((k, v) in c.envs) {
                add("--env")
                add("$k=$v")
            }
            add(c.wasmPath)
            addAll(c.args)
        }

        val logFile = File(c.logPath).also { it.parentFile?.mkdirs() }

        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        pb.environment().putAll(c.envs)

        Log.i(TAG, "exec: ${cmd.joinToString(" ")}")
        val proc = pb.start()
        procs[c.id] = proc

        // Reaper: when wasmedge exits, push the container state to EXITED.
        Thread({
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            Log.i(TAG, "container ${c.id} exited code=$code")
            containers.update(c.id) {
                it.copy(
                    state = ContainerState.CONTAINER_EXITED,
                    finishedAt = System.nanoTime(),
                    exitCode = code,
                )
            }
        }, "wasm-wait-${c.id}").apply { isDaemon = true }.start()

        return runCatching { proc.pid().toInt() }.getOrDefault(0)
    }

    fun stop(id: String, timeoutMs: Long = 10_000) {
        val p = procs[id] ?: return
        if (!p.isAlive) return
        p.destroy()  // SIGTERM
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()  // SIGKILL
            p.waitFor()
        }
    }

    fun isRunning(id: String): Boolean = procs[id]?.isAlive == true

    fun exitCode(id: String): Int? =
        procs[id]?.takeIf { !it.isAlive }?.exitValue()

    companion object {
        private const val TAG = "WasmCRI"
    }
}
