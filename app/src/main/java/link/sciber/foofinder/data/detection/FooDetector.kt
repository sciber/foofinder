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
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

enum class Accelerator {
    CPU,
    GPU,
    NNAPI
}

class FooDetector(
        private val context: Context,
        modelPath: String,
        private var confThreshold: Float = 0.45f,
        private val iouThreshold: Float = 0.45f,
        private val accelerator: Accelerator = Accelerator.CPU,
        private val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
) : Detector {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var activeAccelerator: Accelerator = Accelerator.CPU
    private var nmsEnabled: Boolean = true

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
            val threads = if (numThreads < 1) 1 else numThreads

            // Try preferred accelerator first
            fun buildOptionsWith(accel: Accelerator): Interpreter.Options {
                return Interpreter.Options().apply {
                    setNumThreads(threads)
                    when (accel) {
                        Accelerator.GPU -> {
                            try {
                                // Only attempt on supported devices
                                val compat = CompatibilityList()
                                if (compat.isDelegateSupportedOnThisDevice) {
                                    gpuDelegate = GpuDelegate()
                                    addDelegate(gpuDelegate)
                                    Log.d(TAG, "Using GPU delegate")
                                } else {
                                    Log.w(
                                            TAG,
                                            "GPU delegate not supported on this device; skipping GPU"
                                    )
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "Failed to create GPU delegate, will fall back", e)
                            }
                        }
                        Accelerator.NNAPI -> {
                            try {
                                nnApiDelegate = NnApiDelegate()
                                addDelegate(nnApiDelegate)
                                Log.d(TAG, "Using NNAPI delegate")
                            } catch (e: Throwable) {
                                Log.w(TAG, "Failed to create NNAPI delegate, will fall back", e)
                            }
                        }
                        Accelerator.CPU -> {
                            Log.d(TAG, "Using CPU/XNNPACK with $threads threads")
                        }
                    }
                }
            }

            // 1) Preferred accel
            var lastError: Throwable? = null
            val preferred = buildOptionsWith(accelerator)
            try {
                interpreter = Interpreter(modelBuffer, preferred)
                // If we made it here, preferred accelerator worked
                activeAccelerator =
                        when {
                            gpuDelegate != null -> Accelerator.GPU
                            nnApiDelegate != null -> Accelerator.NNAPI
                            else -> Accelerator.CPU
                        }
            } catch (e: Throwable) {
                lastError = e
                Log.w(
                        TAG,
                        "Preferred accelerator failed to initialize interpreter; attempting fallback",
                        e
                )
                // Close any delegates created
                try {
                    gpuDelegate?.close()
                } catch (_: Throwable) {}
                gpuDelegate = null
                try {
                    nnApiDelegate?.close()
                } catch (_: Throwable) {}
                nnApiDelegate = null

                // 2) Secondary fallback: CPU
                try {
                    val cpuOptions = buildOptionsWith(Accelerator.CPU)
                    interpreter = Interpreter(modelBuffer, cpuOptions)
                    lastError = null
                    activeAccelerator = Accelerator.CPU
                } catch (e2: Throwable) {
                    lastError = e2
                }
            }

            if (interpreter == null) {
                throw (lastError
                        ?: IllegalStateException("Interpreter is null after initialization"))
            }

            // Final, explicit log about which delegate is in use
            when (activeAccelerator) {
                Accelerator.GPU -> Log.d(TAG, "Using GPU delegate (TfLiteGpuDelegateV2)")
                Accelerator.NNAPI -> Log.d(TAG, "Using NNAPI delegate")
                Accelerator.CPU ->
                        Log.d(
                                TAG,
                                "Using CPU/XNNPACK delegate with ${if (numThreads < 1) 1 else numThreads} threads"
                        )
            }

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

            // Warmup: run 1 lightweight inference to stabilize delegate/allocations
            try {
                val warmupInput = TensorBuffer.createFixedSize(modelInputShape, modelInputDataType)
                // Fill zeros (buffer is zeroed by default for newly allocated direct buffers)
                val warmupOutput =
                        TensorBuffer.createFixedSize(modelOutputShape, modelOutputDataType)
                val tWarmStart = System.nanoTime()
                interpreter!!.run(warmupInput.buffer, warmupOutput.buffer)
                val tWarmEnd = System.nanoTime()
                Log.d(TAG, "Warmup inference took ${((tWarmEnd - tWarmStart) / 1_000_000L)} ms")
            } catch (we: Throwable) {
                Log.w(TAG, "Warmup inference failed (non-fatal)", we)
            }
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

            // Overall timer
            val tOverallStart = System.nanoTime()

            // Detection area is a square from the top the image
            val detectionAreaSide = min(originalWidth, originalHeight).toFloat()
            val detectionArea = DetectionArea(0f, 0f, detectionAreaSide, detectionAreaSide)
            Log.d(
                    TAG,
                    "Detection area: ${detectionArea.width}x${detectionArea.height}, startX: ${detectionArea.startX}, startY: ${detectionArea.startY}"
            )
            // Preprocess image
            val tPreStart = System.nanoTime()
            val input = preprocessImage(image, detectionArea)
            val tPreEnd = System.nanoTime()
            val preprocessMs = ((tPreEnd - tPreStart) / 1_000_000L)

            val output = TensorBuffer.createFixedSize(modelOutputShape, modelOutputDataType)

            Log.d(TAG, "Processed image: ${modelInputShape[1]}x${modelInputShape[2]}")

            // Run inference with timing
            val t0 = System.nanoTime()
            interpreter!!.run(input.buffer, output.buffer)
            val t1 = System.nanoTime()
            val inferenceMs = ((t1 - t0) / 1_000_000L)

            // Parse YOLO output
            val tPostStart = System.nanoTime()
            val parsed =
                    parseYoloOutput(
                            output.floatArray,
                            detectionArea,
                            modelInputShape[1], // input height
                            modelInputShape[2] // input width
                    )
            val tPostEnd = System.nanoTime()
            val postprocessMs = ((tPostEnd - tPostStart) / 1_000_000L)

            val boundingBoxes = parsed.boxes
            val rawDetections = parsed.rawCount

            Log.d(
                    TAG,
                    "Detected ${boundingBoxes.size} objects above confidence threshold $confThreshold"
            )

            val tOverallEnd = System.nanoTime()
            val totalMs = ((tOverallEnd - tOverallStart) / 1_000_000L)
            Log.d(
                    TAG,
                    "Timing: preprocess=${preprocessMs}ms, inference=${inferenceMs}ms, postprocess=${postprocessMs}ms, total=${totalMs}ms"
            )

            Detection(
                    boundingBoxes = boundingBoxes,
                    area = detectionArea,
                    inferenceMs = inferenceMs,
                    fps = -1f,
                    rawDetections = rawDetections,
                    afterNmsDetections = boundingBoxes.size
            )
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

    private data class ParsedResult(val boxes: List<BoundingBox>, val rawCount: Int)

    private fun parseYoloOutput(
            output: FloatArray,
            detectionArea: DetectionArea,
            inputHeight: Int,
            inputWidth: Int
    ): ParsedResult {
        val predictions = mutableListOf<BoundingBox>()
        var rawAboveThreshold = 0

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
                        rawAboveThreshold += 1
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

            val finalBoxes =
                    if (nmsEnabled && predictions.size > 1) {
                        applyNMS(predictions, iouThreshold)
                    } else {
                        if (!nmsEnabled)
                                Log.d(
                                        TAG,
                                        "NMS disabled: showing ${predictions.size} raw predictions"
                                )
                        predictions
                    }

            return ParsedResult(finalBoxes, rawAboveThreshold)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YOLO output", e)
            return ParsedResult(emptyList(), 0)
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
        try {
            gpuDelegate?.close()
        } catch (_: Throwable) {}
        gpuDelegate = null
        try {
            nnApiDelegate?.close()
        } catch (_: Throwable) {}
        nnApiDelegate = null
        Log.d(TAG, "FooDetector closed")
    }

    /** Returns a short human-readable label of the active delegate for UI display. */
    fun activeDelegateLabel(): String {
        return when (activeAccelerator) {
            Accelerator.GPU -> "GPU"
            Accelerator.NNAPI -> "NNAPI"
            Accelerator.CPU -> "CPU/XNNPACK (${if (numThreads < 1) 1 else numThreads}t)"
        }
    }

    /** Update the detector's confidence threshold at runtime. */
    fun setConfidenceThreshold(value: Float) {
        confThreshold = value.coerceIn(0f, 1f)
        Log.d(TAG, "Confidence threshold set to $confThreshold")
    }

    /** Enable or disable Non-Maximum Suppression at runtime. */
    fun setNmsEnabled(enabled: Boolean) {
        nmsEnabled = enabled
        Log.d(TAG, "NMS enabled set to $nmsEnabled")
    }
}
