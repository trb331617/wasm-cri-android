# Drop the wasmedge ELF binary here

Place the `wasmedge` executable for `aarch64-linux-android` in this folder
**renamed to `libwasmedge.so`**:

    app/src/main/jniLibs/arm64-v8a/libwasmedge.so

Why the rename? Android's PackageManager only extracts files matching
`lib*.so` from `jniLibs/` into `nativeLibraryDir` and sets the executable bit.
Naming the binary `libwasmedge.so` is a standard trick that lets us ship a
standalone ELF inside an APK and exec it at runtime via
`Runtime.getRuntime().exec(...)` / `ProcessBuilder`.

## Where to get the binary

The WasmEdge project publishes Android builds on its release page:
<https://github.com/WasmEdge/WasmEdge/releases>

Pick the `aarch64-android` archive, extract it, and copy the `wasmedge`
binary here:

```bash
cp /path/to/wasmedge app/src/main/jniLibs/arm64-v8a/libwasmedge.so
```

Optional: do the same for `armeabi-v7a` / `x86_64` / `x86` if you want to
support more device ABIs — and update `defaultConfig.ndk.abiFilters` in
`app/build.gradle.kts` accordingly.

## Verifying

After installing the APK:

```bash
adb shell run-as io.kubeedge.wasmcri ls -l \
  /data/data/io.kubeedge.wasmcri/lib/libwasmedge.so
adb shell run-as io.kubeedge.wasmcri \
  /data/data/io.kubeedge.wasmcri/lib/libwasmedge.so --version
```

The second command should print the wasmedge version.
