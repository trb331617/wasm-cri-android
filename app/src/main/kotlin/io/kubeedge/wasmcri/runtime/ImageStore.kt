package io.kubeedge.wasmcri.runtime

import android.util.Log
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Minimal image store. An "image" here is just a .wasm file on disk.
 * The reference can be:
 *   - http(s)://host/path/foo.wasm   → downloaded as-is
 *   - file:///absolute/path/foo.wasm → copied as-is
 *   - any other ref                  → caller is expected to have side-loaded
 *                                       the file via [registerLocal] beforehand
 *
 * For real OCI/wasm-OCI artifact pulling, plug in an OCI distribution client
 * here (e.g. parse manifest, pull layer with sha256 media type).
 */
class ImageStore(private val root: File) {
    init { root.mkdirs() }

    private val refToFile = mutableMapOf<String, File>()

    @Synchronized
    fun pull(ref: String): File {
        refToFile[ref]?.let { return it }
        val bytes: ByteArray = when {
            ref.startsWith("http://") || ref.startsWith("https://") ->
                URL(ref).openStream().use { it.readBytes() }
            ref.startsWith("file://") ->
                File(ref.removePrefix("file://")).readBytes()
            else ->
                throw IllegalArgumentException(
                    "unsupported image ref: $ref (use http(s)://, file://, or registerLocal())"
                )
        }
        val sha = sha256(bytes)
        val out = File(root, "$sha.wasm")
        if (!out.exists()) out.writeBytes(bytes)
        refToFile[ref] = out
        Log.i("WasmCRI", "pulled $ref → ${out.absolutePath} (${bytes.size} bytes)")
        return out
    }

    @Synchronized
    fun registerLocal(ref: String, file: File) {
        require(file.exists()) { "$file not found" }
        refToFile[ref] = file
    }

    @Synchronized
    fun list(): List<Pair<String, File>> = refToFile.toList()

    @Synchronized
    fun resolve(ref: String): File? = refToFile[ref]

    @Synchronized
    fun remove(ref: String) {
        refToFile.remove(ref)?.let { f ->
            // only delete if no other ref points to it
            if (refToFile.values.none { it == f }) f.delete()
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }
}
