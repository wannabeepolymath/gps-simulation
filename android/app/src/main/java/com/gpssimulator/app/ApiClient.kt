package com.gpssimulator.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

class ApiClient(
    private val baseUrl: String,
    private val auth: AuthRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun get(path: String): Response = call { buildRequest(path).get() }
    suspend fun delete(path: String): Response = call { buildRequest(path).delete() }

    suspend fun patchJson(path: String, json: String): Response = call {
        buildRequest(path).patch(json.toRequestBody(JSON_MEDIA))
    }

    suspend fun postMultipart(
        path: String,
        fileBytes: ByteArray,
        fileFieldName: String = "file",
        filename: String,
        extraParts: Map<String, String> = emptyMap(),
    ): Response = call {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart(
            fileFieldName,
            filename,
            fileBytes.toRequestBody("application/gpx+xml".toMediaTypeOrNull()),
        )
        for ((k, v) in extraParts) builder.addFormDataPart(k, v)
        buildRequest(path).post(builder.build())
    }

    private fun buildRequest(path: String): Request.Builder {
        val url = baseUrl.trimEnd('/') + path
        return Request.Builder().url(url)
    }

    /**
     * Executes a request with a Bearer ID token. On 401, performs one silent refresh
     * via Credential Manager and retries; if that also fails, propagates the 401.
     */
    private suspend fun call(buildBase: () -> Request.Builder): Response {
        var token = auth.currentIdToken()
            ?: runCatching { auth.silentRefresh() }
                .getOrElse { throw ApiException(401, it.message ?: "Not signed in.") }
        val firstResponse = execute(buildBase(), token)
        if (firstResponse.code != 401) return firstResponse
        firstResponse.close()

        token = try {
            auth.silentRefresh()
        } catch (e: Exception) {
            throw ApiException(401, e.message ?: "Sign-in expired.")
        }
        val retry = execute(buildBase(), token)
        if (!retry.isSuccessful) {
            val msg = runCatching { retry.body?.string() }.getOrNull()
                ?.let { extractErrorMessage(it) } ?: "HTTP ${retry.code}"
            retry.close()
            throw ApiException(retry.code, msg)
        }
        return retry
    }

    private suspend fun execute(builder: Request.Builder, token: String): Response {
        val req = builder.header("Authorization", "Bearer $token").build()
        val res = withContext(Dispatchers.IO) { http.newCall(req).execute() }
        if (res.code == 401) return res
        if (!res.isSuccessful) {
            val msg = runCatching { res.body?.string() }.getOrNull()
                ?.let { extractErrorMessage(it) } ?: "HTTP ${res.code}"
            res.close()
            throw ApiException(res.code, msg)
        }
        return res
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            org.json.JSONObject(body).optString("error", body)
        } catch (_: Exception) {
            body.take(200)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaTypeOrNull()
    }
}
