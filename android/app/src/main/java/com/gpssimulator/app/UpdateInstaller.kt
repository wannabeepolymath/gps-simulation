package com.gpssimulator.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Companion to [UpdateChecker]: streams the APK from a GitHub Releases
 * download URL into the app's cache dir, then hands it off to the system
 * installer via FileProvider. Non-system apps can't bypass the system
 * install dialog — this just gets the user one tap away from it without
 * a browser bounce.
 */
object UpdateInstaller {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val APK_FILENAME = "update.apk"

    /** Android 8+ gates package installs behind a per-source consent toggle. */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    /** Open the system "Install unknown apps" page for this app. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /**
     * Streams `url` to `cacheDir/update.apk`, reporting integer % progress.
     * Throws on HTTP error / IO error. Callback may fire from a background
     * thread — Compose's snapshot state is safe to write from any thread.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, APK_FILENAME)
        if (out.exists()) out.delete()

        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty body")
            val total = body.contentLength().coerceAtLeast(1L)
            body.byteStream().use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        out
    }

    /** Hand the downloaded APK to the system installer. */
    fun launchInstall(context: Context, apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        context.startActivity(intent)
    }
}
