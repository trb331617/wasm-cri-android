package io.kubeedge.wasmcri.grpc

object UdsNative {
    init { System.loadLibrary("uds_helper") }

    /**
     * Create a new AF_UNIX SOCK_STREAM socket and bind() it to [path] in the
     * filesystem namespace. Returns a positive int FD on success, or
     * `-errno` on failure (e.g. -13 for EACCES, -2 for ENOENT…).
     *
     * The returned FD is *unlistened* — pass it to LocalServerSocket(fd),
     * which will invoke listen() itself.
     */
    @JvmStatic external fun bind(path: String): Int
}
