package io.kubeedge.wasmcri.runtime

import io.grpc.Status
import io.kubeedge.wasmcri.wasm.WasmEdgeRunner
import io.runtime.v1.Container as PbContainer
import io.runtime.v1.ContainerStatus
import io.runtime.v1.ContainerState
import io.runtime.v1.ContainerStatusRequest
import io.runtime.v1.ContainerStatusResponse
import io.runtime.v1.CreateContainerRequest
import io.runtime.v1.CreateContainerResponse
import io.runtime.v1.ImageSpec
import io.runtime.v1.ListContainersRequest
import io.runtime.v1.ListContainersResponse
import io.runtime.v1.ListPodSandboxRequest
import io.runtime.v1.ListPodSandboxResponse
import io.runtime.v1.PodSandbox
import io.runtime.v1.PodSandboxState
import io.runtime.v1.PodSandboxStatus
import io.runtime.v1.PodSandboxStatusRequest
import io.runtime.v1.PodSandboxStatusResponse
import io.runtime.v1.RemoveContainerRequest
import io.runtime.v1.RemoveContainerResponse
import io.runtime.v1.RemovePodSandboxRequest
import io.runtime.v1.RemovePodSandboxResponse
import io.runtime.v1.RunPodSandboxRequest
import io.runtime.v1.RunPodSandboxResponse
import io.runtime.v1.RuntimeCondition
import io.runtime.v1.RuntimeServiceGrpcKt
import io.runtime.v1.RuntimeStatus
import io.runtime.v1.StartContainerRequest
import io.runtime.v1.StartContainerResponse
import io.runtime.v1.StatusRequest
import io.runtime.v1.StatusResponse
import io.runtime.v1.StopContainerRequest
import io.runtime.v1.StopContainerResponse
import io.runtime.v1.StopPodSandboxRequest
import io.runtime.v1.StopPodSandboxResponse
import io.runtime.v1.UpdateRuntimeConfigRequest
import io.runtime.v1.UpdateRuntimeConfigResponse
import io.runtime.v1.VersionRequest
import io.runtime.v1.VersionResponse

class RuntimeServiceImpl(
    private val sandboxes: SandboxStore,
    private val containers: ContainerStore,
    private val images: ImageStore,
    private val runner: WasmEdgeRunner,
) : RuntimeServiceGrpcKt.RuntimeServiceCoroutineImplBase() {

    // ── Version / Status ─────────────────────────────────────────
    override suspend fun version(request: VersionRequest): VersionResponse =
        VersionResponse.newBuilder()
            .setVersion("0.1.0")
            .setRuntimeName(RUNTIME_NAME)
            .setRuntimeVersion("0.14.x")
            .setRuntimeApiVersion("v1")
            .build()

    override suspend fun status(request: StatusRequest): StatusResponse {
        val runtimeReady = RuntimeCondition.newBuilder()
            .setType("RuntimeReady").setStatus(true).build()
        val networkReady = RuntimeCondition.newBuilder()
            .setType("NetworkReady").setStatus(true).build()
        return StatusResponse.newBuilder()
            .setStatus(
                RuntimeStatus.newBuilder()
                    .addConditions(runtimeReady)
                    .addConditions(networkReady)
            )
            .build()
    }

    override suspend fun updateRuntimeConfig(request: UpdateRuntimeConfigRequest):
            UpdateRuntimeConfigResponse = UpdateRuntimeConfigResponse.getDefaultInstance()

    // ── PodSandbox ───────────────────────────────────────────────
    override suspend fun runPodSandbox(request: RunPodSandboxRequest): RunPodSandboxResponse {
        val cfg = request.config
        val id = sandboxes.newId()
        sandboxes.put(
            Sandbox(
                id = id,
                metadata = cfg.metadata,
                labels = cfg.labelsMap,
                annotations = cfg.annotationsMap,
                logDir = cfg.logDirectory,
                createdAt = System.nanoTime(),
                state = PodSandboxState.SANDBOX_READY,
            )
        )
        return RunPodSandboxResponse.newBuilder().setPodSandboxId(id).build()
    }

    override suspend fun stopPodSandbox(request: StopPodSandboxRequest): StopPodSandboxResponse {
        val id = request.podSandboxId
        containers.listBySandbox(id).forEach { c ->
            runner.stop(c.id)
            containers.update(c.id) {
                it.copy(
                    state = ContainerState.CONTAINER_EXITED,
                    finishedAt = System.nanoTime(),
                    exitCode = runner.exitCode(c.id) ?: 137,
                )
            }
        }
        sandboxes.update(id) { it.copy(state = PodSandboxState.SANDBOX_NOTREADY) }
        return StopPodSandboxResponse.getDefaultInstance()
    }

    override suspend fun removePodSandbox(request: RemovePodSandboxRequest):
            RemovePodSandboxResponse {
        containers.listBySandbox(request.podSandboxId).forEach { containers.remove(it.id) }
        sandboxes.remove(request.podSandboxId)
        return RemovePodSandboxResponse.getDefaultInstance()
    }

    override suspend fun podSandboxStatus(request: PodSandboxStatusRequest):
            PodSandboxStatusResponse {
        val sb = sandboxes.get(request.podSandboxId)
            ?: throw Status.NOT_FOUND.withDescription("sandbox not found").asException()
        return PodSandboxStatusResponse.newBuilder()
            .setStatus(
                PodSandboxStatus.newBuilder()
                    .setId(sb.id).setMetadata(sb.metadata)
                    .setState(sb.state).setCreatedAt(sb.createdAt)
                    .putAllLabels(sb.labels).putAllAnnotations(sb.annotations)
            )
            .build()
    }

    override suspend fun listPodSandbox(request: ListPodSandboxRequest):
            ListPodSandboxResponse {
        val items = sandboxes.list().map { sb ->
            PodSandbox.newBuilder()
                .setId(sb.id).setMetadata(sb.metadata)
                .setState(sb.state).setCreatedAt(sb.createdAt)
                .putAllLabels(sb.labels).putAllAnnotations(sb.annotations)
                .build()
        }
        return ListPodSandboxResponse.newBuilder().addAllItems(items).build()
    }

    // ── Container ────────────────────────────────────────────────
    override suspend fun createContainer(request: CreateContainerRequest):
            CreateContainerResponse {
        val sb = sandboxes.get(request.podSandboxId)
            ?: throw Status.NOT_FOUND.withDescription("sandbox not found").asException()
        val cfg = request.config
        val ref = cfg.image.image
        val wasmFile = images.resolve(ref)
            ?: throw Status.FAILED_PRECONDITION
                .withDescription("image $ref not pulled")
                .asException()

        val id = containers.newId()
        val logPath = if (cfg.logPath.isNotEmpty()) cfg.logPath
                      else "${sb.logDir}/$id.log"
        containers.put(
            Container(
                id = id,
                sandboxId = sb.id,
                metadata = cfg.metadata,
                image = ref,
                wasmPath = wasmFile.absolutePath,
                args = cfg.argsList.toList(),
                envs = cfg.envsList.associate { it.key to it.value },
                mounts = cfg.mountsList.toList(),
                labels = cfg.labelsMap,
                annotations = cfg.annotationsMap,
                logPath = logPath,
                createdAt = System.nanoTime(),
                state = ContainerState.CONTAINER_CREATED,
            )
        )
        return CreateContainerResponse.newBuilder().setContainerId(id).build()
    }

    override suspend fun startContainer(request: StartContainerRequest):
            StartContainerResponse {
        val c = containers.get(request.containerId)
            ?: throw Status.NOT_FOUND.asException()
        if (c.state != ContainerState.CONTAINER_CREATED) {
            throw Status.FAILED_PRECONDITION
                .withDescription("container in state ${c.state}")
                .asException()
        }
        val pid = runner.start(c)
        containers.update(c.id) {
            it.copy(
                state = ContainerState.CONTAINER_RUNNING,
                pid = pid,
                startedAt = System.nanoTime(),
            )
        }
        return StartContainerResponse.getDefaultInstance()
    }

    override suspend fun stopContainer(request: StopContainerRequest):
            StopContainerResponse {
        runner.stop(request.containerId, timeoutMs = request.timeout * 1000L)
        containers.update(request.containerId) {
            it.copy(
                state = ContainerState.CONTAINER_EXITED,
                finishedAt = System.nanoTime(),
                exitCode = runner.exitCode(request.containerId) ?: 137,
            )
        }
        return StopContainerResponse.getDefaultInstance()
    }

    override suspend fun removeContainer(request: RemoveContainerRequest):
            RemoveContainerResponse {
        runner.stop(request.containerId)
        containers.remove(request.containerId)
        return RemoveContainerResponse.getDefaultInstance()
    }

    override suspend fun listContainers(request: ListContainersRequest):
            ListContainersResponse {
        val items = containers.list().map { c ->
            PbContainer.newBuilder()
                .setId(c.id).setPodSandboxId(c.sandboxId)
                .setMetadata(c.metadata)
                .setImage(ImageSpec.newBuilder().setImage(c.image))
                .setImageRef(c.image)
                .setState(c.state).setCreatedAt(c.createdAt)
                .putAllLabels(c.labels).putAllAnnotations(c.annotations)
                .build()
        }
        return ListContainersResponse.newBuilder().addAllContainers(items).build()
    }

    override suspend fun containerStatus(request: ContainerStatusRequest):
            ContainerStatusResponse {
        val c = containers.get(request.containerId)
            ?: throw Status.NOT_FOUND.asException()

        // Reconcile state: if the wasmedge process has exited, sync our store.
        if (c.state == ContainerState.CONTAINER_RUNNING && !runner.isRunning(c.id)) {
            containers.update(c.id) {
                it.copy(
                    state = ContainerState.CONTAINER_EXITED,
                    finishedAt = System.nanoTime(),
                    exitCode = runner.exitCode(c.id) ?: 0,
                )
            }
        }
        val cur = containers.get(c.id)!!
        return ContainerStatusResponse.newBuilder()
            .setStatus(
                ContainerStatus.newBuilder()
                    .setId(cur.id).setMetadata(cur.metadata)
                    .setState(cur.state).setCreatedAt(cur.createdAt)
                    .setStartedAt(cur.startedAt).setFinishedAt(cur.finishedAt)
                    .setExitCode(cur.exitCode)
                    .setImage(ImageSpec.newBuilder().setImage(cur.image))
                    .setImageRef(cur.image)
                    .setLogPath(cur.logPath)
                    .putAllLabels(cur.labels).putAllAnnotations(cur.annotations)
            )
            .build()
    }

    companion object {
        const val RUNTIME_NAME = "wasmedge-cri-android"
    }
}
