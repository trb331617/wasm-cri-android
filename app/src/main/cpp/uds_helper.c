// Tiny JNI helper that creates an AF_UNIX SOCK_STREAM listener bound to a
// filesystem path on Android. The returned int FD is wrapped in a
// java.io.FileDescriptor on the Kotlin side via ParcelFileDescriptor, then
// fed into android.net.LocalServerSocket(FileDescriptor) which calls
// listen() and offers a normal accept() loop.
//
// We do this in C because:
//  - LocalServerSocket(String) only supports the abstract namespace.
//  - android.system.Os.bind(FileDescriptor, SocketAddress) only accepts a
//    UnixSocketAddress on API 29+, which is hidden API on lower versions.
//  - Reflection on hidden classes is blocked since Android 9.
//
// 30 lines of plain POSIX is the cleanest portable workaround.

#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>

#define TAG "WasmCRI/uds"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_io_kubeedge_wasmcri_grpc_UdsNative_bind(JNIEnv* env, jclass clazz, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -100;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -errno;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    // Stale socket file from a previous run will make bind() fail with EADDRINUSE.
    unlink(addr.sun_path);

    if (bind(fd, (const struct sockaddr*) &addr, sizeof(addr)) < 0) {
        LOGE("bind(%s) failed: %s", path, strerror(errno));
        int e = errno;
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -e;
    }

    LOGI("bound AF_UNIX listener fd=%d at %s", fd, path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd;
}
