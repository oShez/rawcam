package com.shez.rawcam.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

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

    /** Root directory new exports are written under. Ensures the directory (and,
     * for the public case, a Download/RawCam/.nomedia marker) exist before
     * returning, so callers can immediately mkdirs a clip subfolder under it. */
    fun exportsRootDir(context: Context): File {
        if (hasAllFilesAccess(context)) {
            // Deprecated since API 29, but this is exactly the API this
            // permission model is designed around: MANAGE_EXTERNAL_STORAGE
            // grants direct, real-path access to public storage, and this is
            // the real path to the public Downloads directory.
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "RawCam",
            )
            dir.mkdirs()
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            return dir
        }
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
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
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
