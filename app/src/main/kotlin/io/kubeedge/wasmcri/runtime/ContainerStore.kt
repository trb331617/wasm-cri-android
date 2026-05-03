package io.kubeedge.wasmcri.runtime

import io.runtime.v1.ContainerMetadata
import io.runtime.v1.ContainerState
import io.runtime.v1.Mount
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Container(
    val id: String,
    val sandboxId: String,
    val metadata: ContainerMetadata,
    val image: String,
    val wasmPath: String,
    val args: List<String>,
    val envs: Map<String, String>,
    val mounts: List<Mount>,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val logPath: String,
    val createdAt: Long,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val pid: Int = 0,
    val exitCode: Int = 0,
    val state: ContainerState,
)

class ContainerStore {
    private val map = ConcurrentHashMap<String, Container>()

    fun newId(): String = "c_" + UUID.randomUUID().toString().replace("-", "").take(16)
    fun put(c: Container) { map[c.id] = c }
    fun get(id: String): Container? = map[id]
    fun remove(id: String) { map.remove(id) }
    fun list(): List<Container> = map.values.toList()
    fun listBySandbox(sb: String): List<Container> = map.values.filter { it.sandboxId == sb }
    fun update(id: String, f: (Container) -> Container) {
        map.computeIfPresent(id) { _, v -> f(v) }
    }
}
