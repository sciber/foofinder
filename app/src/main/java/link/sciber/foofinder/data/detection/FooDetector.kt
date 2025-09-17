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
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class FooDetector(
        private val context: Context,
        modelPath: String,
        private val confThreshold: Float = 0.8f,
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
                            // Match LiteRT preprocessing: normalize to [0,1] and cast to FLOAT32
                            .add(NormalizeOp(0f, 255f))
                            .add(CastOp(DataType.FLOAT32))
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
            val detectionArea = DetectionArea(0f, 0f, detectionAreaSide, detectionAreaSide)
            Log.d(
                    TAG,
                    "Detection area: ${detectionArea.width}x${detectionArea.height}, startX: ${detectionArea.startX}, startY: ${detectionArea.startY}"
            )
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
                            detectionArea,
                            modelInputShape[1], // input height
                            modelInputShape[2] // input width
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
        val croppedImage =
                Bitmap.createBitmap(
                        image,
                        detectionArea.startX.toInt(),
                        detectionArea.startY.toInt(),
                        detectionArea.width.toInt(),
                        detectionArea.height.toInt()
                )

        val tensorImage = TensorImage(modelInputDataType)
        tensorImage.load(croppedImage)

        val processedImage = imageProcessor.process(tensorImage)

        return processedImage
    }

    private fun parseYoloOutput(
            output: FloatArray,
            detectionArea: DetectionArea,
            inputHeight: Int,
            inputWidth: Int
    ): List<BoundingBox> {
        val predictions = mutableListOf<BoundingBox>()

        try {
            // LiteRT / YOLOv10n exported TFLite output format:
            // Shape: [1, numElements, numChannel] where numChannel = 6
            // Per detection layout: [x1, y1, x2, y2, confidence, classId] with all
            // coordinates normalized to [0,1] relative to model input size.
            val numElements = modelOutputShape[1]
            val numChannel = modelOutputShape[2]

            Log.d(
                    TAG,
                    "Processing $numElements detections from output array of size ${output.size}"
            )

            // Log raw tensor values for debugging
            Log.d(
                    TAG,
                    "Raw tensor sample: [0]=$output[0], [1]=$output[1], [2]=$output[2], [3]=$output[3], [4]=$output[4]"
            )

            for (r in 0 until numElements) {
                val baseIndex = r * numChannel
                if (baseIndex + 5 < output.size) {
                    val x1 = output[baseIndex] // normalized x1
                    val y1 = output[baseIndex + 1] // normalized y1
                    val x2 = output[baseIndex + 2] // normalized x2
                    val y2 = output[baseIndex + 3] // normalized y2
                    val confidence = output[baseIndex + 4] // object confidence
                    val clsId = output[baseIndex + 5].toInt()

                    if (confidence >= confThreshold && !confidence.isNaN()) {
                        // Debug logging for first few detections
                        if (predictions.size < 5) {
                            Log.d(
                                    TAG,
                                    "CORNER Detection $r: conf=$confidence, x1=$x1, y1=$y1, x2=$x2, y2=$y2, cls=$clsId"
                            )
                        }

                        // Convert from normalized coordinates (relative to cropped detection area)
                        // to pixel coordinates in the original image space by scaling with
                        // detectionArea width/height and offsetting by startX/startY.
                        val areaW = detectionArea.width
                        val areaH = detectionArea.height
                        val areaX = detectionArea.startX
                        val areaY = detectionArea.startY

                        val pixelX1 = (areaX + x1 * areaW).coerceIn(0f, areaX + areaW)
                        val pixelY1 = (areaY + y1 * areaH).coerceIn(0f, areaY + areaH)
                        val pixelX2 = (areaX + x2 * areaW).coerceIn(0f, areaX + areaW)
                        val pixelY2 = (areaY + y2 * areaH).coerceIn(0f, areaY + areaH)

                        val boxWidth = pixelX2 - pixelX1
                        val boxHeight = pixelY2 - pixelY1

                        if (boxWidth > 0 && boxHeight > 0) {
                            predictions.add(
                                    BoundingBox(
                                            startX = pixelX1,
                                            startY = pixelY1,
                                            width = boxWidth,
                                            height = boxHeight,
                                            confidence = confidence,
                                            classId = clsId,
                                            className = if (clsId == 0) "poo" else "unknown"
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
