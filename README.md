# wasm-cri-android

An Android app that exposes the Kubernetes **CRI** (Container Runtime
Interface) over a Unix domain socket and runs each "container" as a
**WasmEdge** child process. Drop the resulting socket path into edged /
kubelet's `containerRuntimeEndpoint` and the device becomes a wasm-only
edge node.

```
┌─ Android app (single UID) ─────────────────────────────────────────┐
│  Foreground Service                                                 │
│   └ gRPC server (grpc-netty-shaded + epoll, AF_UNIX)               │
│       ├ runtime.v1.RuntimeService                                  │
│       └ runtime.v1.ImageService                                    │
│                          │                                          │
│                          ▼                                          │
│               WasmEdgeRunner (ProcessBuilder)                       │
│                  └ wasmedge run --dir … --env … app.wasm args…     │
│                                                                     │
│  unix socket: /data/data/io.kubeedge.wasmcri/files/cri.sock         │
└─────────────────────────────────────────────────────────────────────┘
```

## Layout

```
.
├── settings.gradle.kts
├── build.gradle.kts                    # root, plugin versions only
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── scripts/prepare-proto.sh            # re-import api.proto from kubeedge
└── app/
    ├── build.gradle.kts                # AGP, dependencies, protobuf plugin
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   ├── proto/api.proto             # CRI v1 (gogoproto stripped)
    │   ├── jniLibs/arm64-v8a/
    │   │     libwasmedge.so            # ⚠ drop the wasmedge binary here
    │   ├── kotlin/io/kubeedge/wasmcri/
    │   │   ├── MainActivity.kt
    │   │   ├── CriForegroundService.kt
    │   │   ├── grpc/UnixSocketServer.kt
    │   │   ├── runtime/SandboxStore.kt
    │   │   ├── runtime/ContainerStore.kt
    │   │   ├── runtime/ImageStore.kt
    │   │   ├── runtime/RuntimeServiceImpl.kt
    │   │   ├── runtime/ImageServiceImpl.kt
    │   │   └── wasm/WasmEdgeRunner.kt
    │   └── res/                        # minimal layout/strings
```

## Build

Prereqs: Android SDK 34, JDK 17, NDK if you intend to build wasmedge yourself.

```bash
# 1. Drop the wasmedge ELF binary into jniLibs (see jniLibs/arm64-v8a/README.md)
cp /path/to/wasmedge-aarch64-android/wasmedge \
   app/src/main/jniLibs/arm64-v8a/libwasmedge.so

# 2. (Optional) re-import the CRI proto
scripts/prepare-proto.sh /home/all/bao/kubeedge

# 3. Build APK
./gradlew :app:assembleDebug
```

## Install & run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.kubeedge.wasmcri/.MainActivity
# Tap "Start CRI service" in the UI; or:
adb shell am start-foreground-service \
    -n io.kubeedge.wasmcri/.CriForegroundService
```

The socket lives at:

    /data/data/io.kubeedge.wasmcri/files/cri.sock

## Smoke-test with crictl

```bash
adb push crictl /data/local/tmp/
adb shell chmod +x /data/local/tmp/crictl

# Same UID required: run via run-as
adb shell run-as io.kubeedge.wasmcri \
    /data/local/tmp/crictl \
    --runtime-endpoint unix:///data/data/io.kubeedge.wasmcri/files/cri.sock \
    --image-endpoint   unix:///data/data/io.kubeedge.wasmcri/files/cri.sock \
    version
```

## Wire up to edged

In `edgecore.yaml`:

```yaml
modules:
  edged:
    tailoredKubeletConfig:
      containerRuntimeEndpoint: unix:///data/data/io.kubeedge.wasmcri/files/cri.sock
      imageServiceEndpoint:     unix:///data/data/io.kubeedge.wasmcri/files/cri.sock
```

edged must run as the same UID as `io.kubeedge.wasmcri` (typically only
possible on rooted / custom Android builds, or by running edged as a
plugin inside this same app process).

## What is implemented

- `Version`, `Status`, `UpdateRuntimeConfig`
- `RunPodSandbox`, `StopPodSandbox`, `RemovePodSandbox`,
  `PodSandboxStatus`, `ListPodSandbox`
- `CreateContainer`, `StartContainer`, `StopContainer`,
  `RemoveContainer`, `ListContainers`, `ContainerStatus`
- `PullImage`, `ListImages`, `ImageStatus`, `RemoveImage`, `ImageFsInfo`
  (PullImage supports `http(s)://` and `file://` refs out of the box)

Anything else (Exec/Attach/PortForward/Checkpoint/ContainerStats/
ListContainerStats/PodSandboxStats/ListPodSandboxStats/Metrics/
RuntimeConfig/GetContainerEvents) returns `UNIMPLEMENTED` from the
generated coroutine base class — extend as you need.

## Caveats

- **Cross-UID access**: a regular Android app cannot expose a socket to
  arbitrary other processes. Either run the client as the same UID
  (run-as), root the device and chcon the socket, or compile this code
  as a system service.
- **WASI networking**: WasmEdge's socket extension goes through the
  Android UID firewall — non-trivial workloads will need
  `INTERNET`/`ACCESS_NETWORK_STATE` and possibly more.
- **Image pulling**: only `http(s)://` and `file://` refs work today.
  Plug a real OCI distribution client (or wasm-OCI artifact resolver)
  into `ImageStore.pull` for production.
- **Sandboxing**: there is no Linux-namespace sandboxing on Android
  user apps. Each container is just a `wasmedge` child process under
  the app's UID. WasmEdge itself enforces the wasm sandbox.
- **No log rotation**: container logs append to `<logPath>` forever.
  Implement `ReopenContainerLog` if you care.
