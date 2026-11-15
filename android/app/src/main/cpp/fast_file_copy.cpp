#include <jni.h>
#include <sys/sendfile.h>
#include <errno.h>
#include <algorithm>
#include <cstdint>

/**
 * Direct kernel-side transfer for seekable local files. This does not touch
 * video codecs and never changes source media. -2 tells Kotlin to fall back
 * to a 512 KiB buffered copy on providers that do not support sendfile.
 *
 * The caller invokes this in bounded chunks so coroutine cancellation and
 * progress checks remain responsive between calls.
 */
static int fdFromJava(JNIEnv* env, jobject descriptor) {
    if (!descriptor) return -1;
    jclass descriptorClass = env->FindClass("java/io/FileDescriptor");
    if (!descriptorClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    jfieldID field = env->GetFieldID(descriptorClass, "descriptor", "I");
    if (!field) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(descriptorClass);
        return -1;
    }
    int fd = env->GetIntField(descriptor, field);
    env->DeleteLocalRef(descriptorClass);
    return fd;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_reivex_browser_downloads_DownloadNativeCopy_sendChunk(
    JNIEnv* env, jobject, jobject input, jobject output, jlong requested) {
    int inFd = fdFromJava(env, input);
    int outFd = fdFromJava(env, output);
    if (inFd < 0 || outFd < 0 || requested <= 0) return -2;
    auto size = static_cast<size_t>(std::min<jlong>(requested, 4LL * 1024LL * 1024LL));
    ssize_t copied;
    do {
        copied = sendfile(outFd, inFd, nullptr, size);
    } while (copied < 0 && errno == EINTR);
    if (copied >= 0) return static_cast<jlong>(copied);
    // SAF, FUSE and some MediaStore providers cannot use kernel sendfile.
    // Fall back without losing offsets or touching the original file.
    return -2;
}
