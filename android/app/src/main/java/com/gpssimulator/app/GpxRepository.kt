package com.gpssimulator.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GpxRepository(
    private val context: Context,
    private val api: ApiClient,
) {
    private val cacheDir: File
        get() = File(context.filesDir, "gpx_cache").apply { if (!exists()) mkdirs() }

    private val listCacheFile: File
        get() = File(context.filesDir, "files_cache.json")

    /**
     * Returns the last-known list from disk, or null on miss/parse failure.
     * Cheap — small JSON. Lets the UI render instantly on cold start while
     * [list] refreshes from the network in the background.
     */
    suspend fun cachedList(): List<GpxFile>? = withContext(Dispatchers.IO) {
        val f = listCacheFile
        if (!f.exists()) return@withContext null
        runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val arr = obj.optJSONArray("files") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) add(GpxFile.fromJson(arr.getJSONObject(i)))
            }
        }.getOrNull()
    }

    /** Wipe the persisted list cache — call on sign-out to avoid leaking across accounts. */
    fun clearListCache() {
        runCatching { listCacheFile.delete() }
    }

    suspend fun list(): List<GpxFile> = withContext(Dispatchers.IO) {
        api.get("/gpx").use { res ->
            val body = res.body?.string().orEmpty()
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("files") ?: JSONArray()
            val result = buildList {
                for (i in 0 until arr.length()) add(GpxFile.fromJson(arr.getJSONObject(i)))
            }
            // Persist a snapshot so the next cold start can render instantly.
            // Best-effort — a write failure shouldn't break the call.
            runCatching {
                val out = JSONObject().put("files", JSONArray().apply {
                    for (item in result) put(item.toJson())
                })
                listCacheFile.writeText(out.toString(), Charsets.UTF_8)
            }
            result
        }
    }

    /** Read the bytes pointed to by a content URI. */
    suspend fun readUriBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ApiException(0, "Could not read file.")
    }

    suspend fun upload(uri: Uri, displayName: String): GpxFile {
        val bytes = readUriBytes(uri)
        return uploadBytes(bytes, displayName)
    }

    /** Upload an in-memory GPX body. Used by both file picker and the Add Time tool. */
    suspend fun uploadBytes(bytes: ByteArray, displayName: String): GpxFile = withContext(Dispatchers.IO) {
        api.postMultipart(
            path = "/gpx",
            fileBytes = bytes,
            filename = displayName,
            extraParts = mapOf("name" to displayName),
        ).use { res ->
            val body = res.body?.string().orEmpty()
            GpxFile.fromJson(JSONObject(body).getJSONObject("file"))
        }
    }

    /** Download the raw GPX bytes (no caching). For the Add Time tool. */
    suspend fun downloadBytes(file: GpxFile): ByteArray = withContext(Dispatchers.IO) {
        api.get("/gpx/${file.id}/download").use { res ->
            res.body?.bytes() ?: throw ApiException(0, "Empty download response.")
        }
    }

    suspend fun rename(file: GpxFile, newName: String): GpxFile = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("name", newName).toString()
        api.patchJson("/gpx/${file.id}", payload).use { res ->
            val body = res.body?.string().orEmpty()
            val updated = GpxFile.fromJson(JSONObject(body).getJSONObject("file"))
            // Invalidate the local cache if the bytes are the same but we want
            // the new filename for the cached copy.
            cachedFile(file).takeIf { it.exists() }?.let { old ->
                val newPath = cachedFile(updated)
                if (old.absolutePath != newPath.absolutePath) {
                    old.renameTo(newPath)
                }
            }
            updated
        }
    }

    suspend fun delete(file: GpxFile) = withContext(Dispatchers.IO) {
        api.delete("/gpx/${file.id}").close()
        cachedFile(file).delete()
    }

    /** Downloads the GPX bytes if not already cached. Returns the local file. */
    suspend fun ensureCached(file: GpxFile): File = withContext(Dispatchers.IO) {
        val cached = cachedFile(file)
        if (cached.exists() && cached.length() == file.sizeBytes) return@withContext cached
        api.get("/gpx/${file.id}/download").use { res ->
            val bytes = res.body?.bytes() ?: throw ApiException(0, "Empty download response.")
            cached.writeBytes(bytes)
        }
        cached
    }

    private fun cachedFile(file: GpxFile): File = File(cacheDir, "${file.id}.gpx")
}
