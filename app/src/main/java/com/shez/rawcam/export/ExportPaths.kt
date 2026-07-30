package com.shez.rawcam.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Resolves where exported DNG folders are written. Public (`Download/RawCam`) when
 * this app holds MANAGE_EXTERNAL_STORAGE, so a USB cable + a desktop file browser
 * can see them via MediaStore; falls back to the original private
 * getExternalFilesDir/exports location otherwise, so export never fails outright
 * for lack of this optional permission. Every call site that reads or writes
 * export folders goes through this object instead of building the path itself.
 */
object ExportPaths {

    fun hasAllFilesAccess(context: Context): Boolean = Environment.isExternalStorageManager()

    /** Computes the public Download/RawCam path without any side effects (no
     * mkdirs, no marker file) -- just the path itself, so callers that only
     * need to compare against it (e.g. [isPublicRoot]) don't pay for or risk
     * the directory-creation work [exportsRootDir] does. */
    @Suppress("DEPRECATION")
    private fun publicRootPath(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "RawCam",
    )

    /** True when [dir] sits under the public Download/RawCam root -- i.e. this is
     * where [exportsRootDir] would have written it while this app held
     * MANAGE_EXTERNAL_STORAGE. Purely a path comparison: does not itself check
     * the current permission state, since a directory already written under the
     * public root stays there regardless of whether the permission was later
     * revoked. */
    fun isPublicRoot(context: Context, dir: File): Boolean =
        dir.absolutePath.startsWith(publicRootPath().absolutePath)

    /** Root directory new exports are written under. Ensures the directory (and,
     * for the public case, a Download/RawCam/.nomedia marker) exist before
     * returning, so callers can immediately mkdirs a clip subfolder under it.
     * Never throws: both the directory creation and the marker-file write are
     * best-effort -- this is called from ClipsScreen's and ExportsScreen's 2s
     * poll loop, so a checked IOException here (disk full, unwritable volume, a
     * pre-existing .nomedia directory) must not propagate and crash-loop the
     * app; it's logged and swallowed instead, and the caller still gets back a
     * usable File either way. */
    fun exportsRootDir(context: Context): File {
        if (hasAllFilesAccess(context)) {
            // Deprecated since API 29, but this is exactly the API this
            // permission model is designed around: MANAGE_EXTERNAL_STORAGE
            // grants direct, real-path access to public storage, and this is
            // the real path to the public Downloads directory.
            val dir = publicRootPath()
            try {
                dir.mkdirs()
            } catch (e: SecurityException) {
                Log.w(TAG, "exportsRootDir: mkdirs failed for $dir", e)
            }
            try {
                val nomedia = File(dir, ".nomedia")
                if (!nomedia.exists()) nomedia.createNewFile()
            } catch (e: IOException) {
                Log.w(TAG, "exportsRootDir: failed to create .nomedia marker under $dir", e)
            }
            return dir
        }
        val dir = File(context.getExternalFilesDir(null), "exports")
        try {
            dir.mkdirs()
        } catch (e: SecurityException) {
            Log.w(TAG, "exportsRootDir: mkdirs failed for $dir", e)
        }
        return dir
    }

    /** Opens the per-app "All files access" grant screen, falling back to the
     * unscoped grant-screen list if the per-app intent doesn't resolve on a
     * given OEM skin. */
    fun requestAllFilesAccess(context: Context) {
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
        if (perApp.resolveActivity(context.packageManager) != null) {
            context.startActivity(perApp)
        } else {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "requestAllFilesAccess: no activity resolves the fallback grant screen", e)
            }
        }
    }

    private const val TAG = "ExportPaths"
}
