package io.kubeedge.wasmcri.runtime

import io.runtime.v1.PodSandboxMetadata
import io.runtime.v1.PodSandboxState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Sandbox(
    val id: String,
    val metadata: PodSandboxMetadata,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val logDir: String,
    val createdAt: Long,
    val state: PodSandboxState,
)

class SandboxStore {
    private val map = ConcurrentHashMap<String, Sandbox>()

    fun newId(): String = "sb_" + UUID.randomUUID().toString().replace("-", "").take(16)
    fun put(s: Sandbox) { map[s.id] = s }
    fun get(id: String): Sandbox? = map[id]
    fun remove(id: String) { map.remove(id) }
    fun list(): List<Sandbox> = map.values.toList()
    fun update(id: String, f: (Sandbox) -> Sandbox) {
        map.computeIfPresent(id) { _, v -> f(v) }
    }
}
