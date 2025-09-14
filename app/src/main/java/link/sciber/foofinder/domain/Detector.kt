package link.sciber.foofinder.domain

import android.graphics.Bitmap

interface Detector {
    fun detect(image: Bitmap): Detection
}