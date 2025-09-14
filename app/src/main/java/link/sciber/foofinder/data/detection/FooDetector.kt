package link.sciber.foofinder.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.MappedByteBuffer
import kotlin.math.max
import kotlin.math.min
import link.sciber.foofinder.domain.BoundingBox
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea
import link.sciber.foofinder.domain.Detector
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class FooDetector(
        private val context: Context,
        modelPath: String,
        private val confThreshold: Float = 0.6f,
        private val iouThreshold: Float = 0.45f
) : Detector {
    private var interpreter: Interpreter? = null

    // Model input/output details
    private lateinit var modelInputDataType: DataType
    private lateinit var modelInputShape: IntArray
    private lateinit var modelOutputDataType: DataType
    private lateinit var modelOutputShape: IntArray

    private lateinit var imageProcessor: ImageProcessor

    companion object {
        private const val TAG = "FooDetector"
    }

    init {
        try {
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelPath)
            val options =
                    Interpreter.Options().apply {
                        setNumThreads(1) // Single thread for deterministic behavior
                    }
            interpreter = Interpreter(modelBuffer, options)

            val inputTensor = interpreter!!.getInputTensor(0)
            modelInputDataType = inputTensor.dataType()
            modelInputShape = inputTensor.shape()

            val outputTensor = interpreter!!.getOutputTensor(0)
            modelOutputDataType = outputTensor.dataType()
            modelOutputShape = outputTensor.shape()

            // Create image processor for YOLO input (normalize to [0,1])
            imageProcessor =
                    ImageProcessor.Builder()
                        .add(
                            ResizeOp(
                                modelInputShape[1], // height
                                modelInputShape[2], // width
                                ResizeOp.ResizeMethod.BILINEAR
                            )
                        )
                        .build()

            Log.d(TAG, "Model loaded successfully")
            Log.d(TAG, "Input shape: ${modelInputShape.contentToString()}")
            Log.d(TAG, "Output shape: ${modelOutputShape.contentToString()}")
            Log.d(TAG, "Input dtype: $modelInputDataType, Output dtype: $modelOutputDataType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FooDetector", e)
            throw e
        }
    }

    override fun detect(image: Bitmap): Detection {
        return try {
            val originalWidth = image.width
            val originalHeight = image.height

            Log.d(TAG, "Processing image: ${originalWidth}x${originalHeight}")

            // Detection area is a square from the top the image
            val detectionAreaSide = min(originalWidth, originalHeight).toFloat()
            val detectionArea = DetectionArea(
                0f,
                0f,
                detectionAreaSide,
                detectionAreaSide
            )
            Log.d(TAG, "Detection area: ${detectionArea.width}x${detectionArea.height}, startX: ${detectionArea.startX}, startY: ${detectionArea.startY}")
            // Preprocess image
            val input = preprocessImage(image, detectionArea)
            val output = TensorBuffer.createFixedSize(modelOutputShape, modelOutputDataType)

            Log.d(TAG, "Processed image: ${modelInputShape[1]}x${modelInputShape[2]}")

            // Run inference
            interpreter!!.run(input.buffer, output.buffer)

            // Parse YOLO output
            val boundingBoxes =
                    parseYoloOutput(
                            output.floatArray,
                            detectionArea
                    )

            Log.d(
                    TAG,
                    "Detected ${boundingBoxes.size} objects above confidence threshold $confThreshold"
            )

            Detection(boundingBoxes = boundingBoxes, area = detectionArea)
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            Detection(boundingBoxes = emptyList(), area = DetectionArea(0f, 0f, 0f, 0f))
        }
    }

    private fun preprocessImage(image: Bitmap, detectionArea: DetectionArea): TensorImage {
        val croppedImage = Bitmap.createBitmap(
            image,
            detectionArea.startX.toInt(),
            detectionArea.startY.toInt(),
            detectionArea.width.toInt(),
            detectionArea.height.toInt()
        )

        val tensorImage = TensorImage(modelInputDataType)
        tensorImage.load(croppedImage)

        val processedImage = imageProcessor.process(tensorImage)


        // Normalize to [0, 1] for YOLO
        val buffer = processedImage.buffer
        val floatBuffer = buffer.asFloatBuffer()
        val floatArray = FloatArray(floatBuffer.remaining())
        floatBuffer.get(floatArray)

        // Normalize pixel values to [0, 1]
        for (i in floatArray.indices) {
            floatArray[i] = floatArray[i] / 255.0f
        }

        floatBuffer.rewind()
        floatBuffer.put(floatArray)
        buffer.rewind()

        return processedImage
    }

    private fun parseYoloOutput(
            output: FloatArray,
            detectionArea: DetectionArea
    ): List<BoundingBox> {
        val predictions = mutableListOf<BoundingBox>()

        try {
            // YOLOv11 output format: [batch, 5, 8400] -> flattened to [5 * 8400]
            // Where 5 = [x_center, y_center, width, height, confidence]
            val numDetections = output.size / 5

            Log.d(
                    TAG,
                    "Processing $numDetections detections from output array of size ${output.size}"
            )

            for (i in 0 until numDetections) {
                val baseIndex = i * 5
                if (baseIndex + 4 < output.size) {
                    val xCenter = output[baseIndex]
                    val yCenter = output[baseIndex + 1]
                    val width = output[baseIndex + 2]
                    val height = output[baseIndex + 3]
                    val confidence = output[baseIndex + 4]

                    if (confidence >= confThreshold && !confidence.isNaN()) {
                        // Convert from normalized coordinates to pixel coordinates
                        val x1 = ((xCenter - width / 2) * detectionArea.width).coerceIn(0f, detectionArea.width) + detectionArea.startX
                        val y1 = ((yCenter - height / 2) * detectionArea.height).coerceIn(0f, detectionArea.height) + detectionArea.startY
                        val x2 = ((xCenter + width / 2) * detectionArea.width).coerceIn(0f, detectionArea.width) + detectionArea.startX
                        val y2 = ((yCenter + height / 2) * detectionArea.height).coerceIn(0f, detectionArea.height) + detectionArea.startY

                        val boxWidth = x2 - x1
                        val boxHeight = y2 - y1

                        // Skip invalid boxes
                        if (boxWidth > 0 && boxHeight > 0) {
                            predictions.add(
                                    BoundingBox(
                                            startX = x1,
                                            startY = y1,
                                            width = boxWidth,
                                            height = boxHeight,
                                            confidence = confidence,
                                            classId = 0, // Single class (poo)
                                            className = "poo"
                                    )
                            )
                        }
                    }
                }
            }

            Log.d(
                    TAG,
                    "Found ${predictions.size} valid predictions above confidence threshold $confThreshold"
            )

            // Apply Non-Maximum Suppression if we have multiple predictions
            return if (predictions.size > 1) {
                applyNMS(predictions, iouThreshold)
            } else {
                predictions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YOLO output", e)
            return emptyList()
        }
    }

    private fun applyNMS(boxes: List<BoundingBox>, iouThreshold: Float): List<BoundingBox> {
        if (boxes.isEmpty()) return emptyList()

        // Sort by confidence (descending)
        val sortedBoxes = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val current = sortedBoxes.removeAt(0)
            keep.add(current)

            sortedBoxes.removeAll { box -> calculateIoU(current, box) > iouThreshold }
        }

        Log.d(TAG, "NMS: ${boxes.size} -> ${keep.size} boxes")
        return keep
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = max(box1.startX, box2.startX)
        val y1 = max(box1.startY, box2.startY)
        val x2 = min(box1.startX + box1.width, box2.startX + box2.width)
        val y2 = min(box1.startY + box1.height, box2.startY + box2.height)

        if (x2 <= x1 || y2 <= y1) return 0f

        val intersectionArea = (x2 - x1) * (y2 - y1)
        val box1Area = box1.width * box1.height
        val box2Area = box2.width * box2.height
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "FooDetector closed")
    }
}
