package io.legado.app.ui.book.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal object AiImageHttp {

    /** 在 OkHttp 工作线程读取完整响应，直到正文读完前都保留取消连接的能力。 */
    suspend fun <T> read(
        client: OkHttpClient,
        builder: Request.Builder.() -> Unit,
        readResponse: (Response) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().apply(builder).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resumeWith(runCatching { response.use(readResponse) })
            }
        })
    }
}
