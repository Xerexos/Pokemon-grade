package com.pokegrade.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A rear camera the phone exposes to apps, with what we know about it. */
data class BackCamera(
    val id: String,
    val megapixels: Double,
    /** 35 mm-equivalent focal length, if known. */
    val equivalentFocalMm: Int?,
    val autofocus: Boolean,
) {
    val kind: String
        get() = when {
            equivalentFocalMm == null -> "camera"
            equivalentFocalMm < 20 -> "ultra-wide"
            equivalentFocalMm <= 35 -> "main"
            equivalentFocalMm < 50 -> "wide"
            else -> "telephoto"
        }

    val label: String
        get() = buildString {
            append("${"%.0f".format(megapixels)} MP ${kind}")
            equivalentFocalMm?.let { append(" · ${it} mm") }
            if (!autofocus) append(" · fixed focus")
        }

    val selector: CameraSelector
        @OptIn(ExperimentalCamera2Interop::class)
        get() = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
            .build()
}

object BackCameras {

    /** All rear cameras, best first (highest megapixels with autofocus, closest to a normal lens). */
    @OptIn(ExperimentalCamera2Interop::class)
    fun list(provider: ProcessCameraProvider): List<BackCamera> =
        provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .map { describe(it) }
            .sortedWith(
                compareByDescending<BackCamera> { it.autofocus }
                    .thenByDescending { it.megapixels.roundToInt() }
                    .thenBy { abs((it.equivalentFocalMm ?: 26) - 26) },
            )

    @OptIn(ExperimentalCamera2Interop::class)
    private fun describe(info: CameraInfo): BackCamera {
        val c2 = Camera2CameraInfo.from(info)
        val pixels = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val eq = if (focal != null && physical != null) {
            // 35 mm film diagonal is 43.27 mm.
            val diag = sqrt(physical.width * physical.width + physical.height * physical.height)
            if (diag > 0) (focal * 43.27f / diag).roundToInt() else null
        } else null
        return BackCamera(
            id = c2.cameraId,
            megapixels = if (pixels != null) pixels.width.toDouble() * pixels.height / 1_000_000 else 0.0,
            equivalentFocalMm = eq,
            autofocus = minFocus > 0f,
        )
    }

    private const val PREFS = "pokegrade"
    private const val KEY = "camera_id"

    fun savedId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun save(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
    }
}
