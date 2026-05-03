package io.kubeedge.wasmcri.runtime

import io.runtime.v1.Image
import io.runtime.v1.ImageFsInfoRequest
import io.runtime.v1.ImageFsInfoResponse
import io.runtime.v1.ImageServiceGrpcKt
import io.runtime.v1.ImageStatusRequest
import io.runtime.v1.ImageStatusResponse
import io.runtime.v1.ListImagesRequest
import io.runtime.v1.ListImagesResponse
import io.runtime.v1.PullImageRequest
import io.runtime.v1.PullImageResponse
import io.runtime.v1.RemoveImageRequest
import io.runtime.v1.RemoveImageResponse

class ImageServiceImpl(
    private val store: ImageStore,
) : ImageServiceGrpcKt.ImageServiceCoroutineImplBase() {

    override suspend fun pullImage(request: PullImageRequest): PullImageResponse {
        val ref = request.image.image
        val f = store.pull(ref)
        return PullImageResponse.newBuilder()
            .setImageRef(f.absolutePath)
            .build()
    }

    override suspend fun listImages(request: ListImagesRequest): ListImagesResponse {
        val items = store.list().map { (ref, f) ->
            Image.newBuilder()
                .setId(f.nameWithoutExtension)
                .addRepoTags(ref)
                .setSize(f.length())
                .build()
        }
        return ListImagesResponse.newBuilder().addAllImages(items).build()
    }

    override suspend fun imageStatus(request: ImageStatusRequest): ImageStatusResponse {
        val f = store.resolve(request.image.image)
            ?: return ImageStatusResponse.getDefaultInstance()
        return ImageStatusResponse.newBuilder()
            .setImage(
                Image.newBuilder()
                    .setId(f.nameWithoutExtension)
                    .addRepoTags(request.image.image)
                    .setSize(f.length())
            )
            .build()
    }

    override suspend fun removeImage(request: RemoveImageRequest): RemoveImageResponse {
        store.remove(request.image.image)
        return RemoveImageResponse.getDefaultInstance()
    }

    override suspend fun imageFsInfo(request: ImageFsInfoRequest): ImageFsInfoResponse =
        ImageFsInfoResponse.getDefaultInstance()
}
