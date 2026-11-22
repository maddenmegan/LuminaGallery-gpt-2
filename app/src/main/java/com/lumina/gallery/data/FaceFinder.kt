package com.lumina.gallery.data

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FaceFinder {
    data class Hit(val asset: MediaAsset, val faceHash: Long)
    data class Group(val id: Long, val hits: List<Hit>)

    fun scan(assets: List<MediaAsset>, bitmapFor: (MediaAsset) -> Bitmap?): List<Group> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
        val detector = FaceDetection.getClient(options)
        val hits = mutableListOf<Hit>()
        try {
            assets.filter { it.isImage && !it.isGif }.forEach { asset ->
                val bitmap = bitmapFor(asset) ?: return@forEach
                val faces = runCatching {
                    Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
                }.getOrDefault(emptyList())
                faces.forEach { face ->
                    if (isCredibleFace(face, bitmap)) {
                        val crop = cropFace(bitmap, face.boundingBox) ?: return@forEach
                        hits += Hit(asset, differenceHash(crop))
                        crop.recycle()
                    }
                }
                bitmap.recycle()
            }
        } finally {
            detector.close()
        }
        return cluster(hits)
    }

    private fun isCredibleFace(face: Face, bitmap: Bitmap): Boolean {
        val box = face.boundingBox
        val areaRatio = box.width().toFloat() * box.height() / (bitmap.width.toFloat() * bitmap.height.toFloat())
        if (box.width() < 64 || box.height() < 64 || areaRatio < 0.012f || face.headEulerAngleY.let { abs(it) > 32f }) return false
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return false
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return false
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return false
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return false
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return false
        val eyeDistance = abs(rightEye.x - leftEye.x)
        if (eyeDistance < box.width() * 0.16f || eyeDistance > box.width() * 0.68f) return false
        if (nose.y < max(leftEye.y, rightEye.y) || nose.y > box.bottom) return false
        if (mouthLeft.y < nose.y || mouthRight.y < nose.y) return false
        if (abs(mouthLeft.y - mouthRight.y) > box.height() * 0.18f) return false
        val crop = cropFace(bitmap, box) ?: return false
        val sharp = laplacianVariance(crop)
        val skin = skinPixelRatio(crop)
        crop.recycle()
        return sharp >= 7.5 && skin >= 0.10
    }

    private fun cropFace(bitmap: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        return runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }.getOrNull()
    }

    private fun skinPixelRatio(bitmap: Bitmap): Float {
        var matching = 0
        var sampled = 0
        val left = bitmap.width / 5
        val right = bitmap.width * 4 / 5
        val top = bitmap.height / 4
        val bottom = bitmap.height * 4 / 5
        val stepX = max(1, bitmap.width / 32)
        val stepY = max(1, bitmap.height / 32)
        for (y in top until bottom step stepY) for (x in left until right step stepX) {
            val color = bitmap.getPixel(x, y)
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cr in 132f..180f && cb in 72f..135f && r > 45 && g > 25 && b > 15) matching++
            sampled++
        }
        return if (sampled == 0) 0f else matching.toFloat() / sampled
    }

    private fun laplacianVariance(bitmap: Bitmap): Double {
        if (bitmap.width < 3 || bitmap.height < 3) return 0.0
        val scale = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val values = DoubleArray(46 * 46)
        var index = 0
        for (y in 1 until 47) for (x in 1 until 47) {
            fun gray(px: Int, py: Int): Double {
                val c = scale.getPixel(px, py)
                return (((c shr 16) and 255) * 0.299 + ((c shr 8) and 255) * 0.587 + (c and 255) * 0.114)
            }
            values[index++] = 4 * gray(x, y) - gray(x - 1, y) - gray(x + 1, y) - gray(x, y - 1) - gray(x, y + 1)
        }
        scale.recycle()
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    private fun differenceHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = small.getPixel(x, y)
            val b = small.getPixel(x + 1, y)
            val ga = ((a shr 16 and 255) * 299 + (a shr 8 and 255) * 587 + (a and 255) * 114) / 1000
            val gb = ((b shr 16 and 255) * 299 + (b shr 8 and 255) * 587 + (b and 255) * 114) / 1000
            if (ga > gb) hash = hash or (1L shl bit)
            bit++
        }
        small.recycle()
        return hash
    }

    private fun cluster(hits: List<Hit>): List<Group> {
        val groups = mutableListOf<MutableList<Hit>>()
        hits.forEach { hit ->
            val match = groups.firstOrNull { group ->
                java.lang.Long.bitCount(group.first().faceHash xor hit.faceHash) <= 11
            }
            if (match == null) groups += mutableListOf(hit) else match += hit
        }
        return groups.filter { it.map(Hit::asset).distinctBy(MediaAsset::id).size >= 2 }
            .mapIndexed { index, group -> Group(index.toLong(), group.distinctBy { it.asset.id }) }
    }
}
