package link.sciber.foofinder.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import link.sciber.foofinder.domain.BoundingBox

object YoloLabelParser {
    private const val TAG = "YoloLabelParser"

    /**
     * Parse YOLO format labels from assets Format: class_id x_center y_center width height (all
     * normalized 0-1)
     */
    fun parseLabelsFromAssets(
            context: Context,
            labelPath: String,
            imageWidth: Int,
            imageHeight: Int
    ): List<BoundingBox> {
        val boundingBoxes = mutableListOf<BoundingBox>()

        try {
            val inputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))

            reader.useLines { lines ->
                lines.forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        val parts = trimmedLine.split("\\s+".toRegex())
                        if (parts.size >= 5) {
                            try {
                                val classId = parts[0].toInt()
                                val xCenter = parts[1].toFloat()
                                val yCenter = parts[2].toFloat()
                                val width = parts[3].toFloat()
                                val height = parts[4].toFloat()

                                // Convert from YOLO format (normalized) to pixel coordinates
                                val x1 = (xCenter - width / 2) * imageWidth
                                val y1 = (yCenter - height / 2) * imageHeight
                                val boxWidth = width * imageWidth
                                val boxHeight = height * imageHeight

                                boundingBoxes.add(
                                        BoundingBox(
                                                startX = x1,
                                                startY = y1,
                                                width = boxWidth,
                                                height = boxHeight,
                                                confidence =
                                                        1.0f, // Ground truth has 100% confidence
                                                classId = classId,
                                                className = getClassName(classId)
                                        )
                                )
                            } catch (e: NumberFormatException) {
                                Log.w(TAG, "Failed to parse line: $trimmedLine", e)
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Parsed ${boundingBoxes.size} ground truth boxes from $labelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse labels from $labelPath", e)
        }

        return boundingBoxes
    }

    private fun getClassName(classId: Int): String {
        return when (classId) {
            0 -> "poo"
            1 -> "not_poo"
            else -> "unknown"
        }
    }
}
