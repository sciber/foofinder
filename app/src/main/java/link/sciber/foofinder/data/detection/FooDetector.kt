package link.sciber.foofinder.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import java.nio.MappedByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class Accelerator {
    CPU, GPU, NNAPI
}

class FooDetector(
    context: Context,
    modelPath: String,
    private var confThreshold: Float = 0.3f,
    private val iouThreshold: Float = 0.45f,
    accelerator: Accelerator = Accelerator.CPU,
    numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
) : Detector {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var activeAccelerator: Accelerator = Accelerator.CPU
    private var nmsEnabled: Boolean = true

    @Volatile
    private var isClosed: Boolean = false
    private val interpreterLock = Any()

    // Model input/output details
    private var modelInputDataType: DataType
    private var modelInputShape: IntArray
    private val outputInfos: List<OutputInfo>

    private val inputIsNchw: Boolean
    private val inputHeight: Int
    private val inputWidth: Int
    private val inputChannels: Int

    private val imageProcessor: ImageProcessor

    companion object {
        private const val TAG = "FooDetector"
    }

    private enum class TensorLayout { NHWC, NCHW }
    private enum class OutputRole { CLS, BBOX, OBJ }

    private data class OutputInfo(
        val index: Int,
        val name: String,
        val shape: IntArray,
        val dataType: DataType,
        val layout: TensorLayout,
        val channels: Int,
        val height: Int,
        val width: Int,
        val role: OutputRole
    )

    private data class LevelBuffers(
        var cls: Pair<TensorBuffer, OutputInfo>? = null,
        var bbox: Pair<TensorBuffer, OutputInfo>? = null,
        var obj: Pair<TensorBuffer, OutputInfo>? = null
    )

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

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
                activeAccelerator = when {
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
                } catch (_: Throwable) {
                }
                gpuDelegate = null
                try {
                    nnApiDelegate?.close()
                } catch (_: Throwable) {
                }
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
                Accelerator.CPU -> Log.d(
                    TAG,
                    "Using CPU/XNNPACK delegate with ${if (numThreads < 1) 1 else numThreads} threads"
                )
            }

            val inputTensor = interpreter!!.getInputTensor(0)
            modelInputDataType = inputTensor.dataType()
            modelInputShape = inputTensor.shape()

            if (modelInputShape.size != 4) {
                throw IllegalArgumentException(
                    "Expected 4D input tensor, got ${modelInputShape.contentToString()}"
                )
            }

            val channelsFirstCandidate = modelInputShape[1]
            val channelsLastCandidate = modelInputShape[3]

            if (channelsFirstCandidate <= 4) {
                inputIsNchw = true
                inputChannels = channelsFirstCandidate
                inputHeight = modelInputShape[2]
                inputWidth = modelInputShape[3]
            } else {
                inputIsNchw = false
                inputHeight = modelInputShape[1]
                inputWidth = modelInputShape[2]
                inputChannels = channelsLastCandidate
            }

            // Create image processor for YOLOX input
            // YOLOX uses ImageNet normalization: (pixel/255 - mean) / std
            // Where mean=[0.485, 0.456, 0.406] and std=[0.229, 0.224, 0.225]
            // 
            // NormalizeOp applies: (pixel - mean) / std
            // So we need: mean = [0.485*255, 0.456*255, 0.406*255] = [123.675, 116.28, 103.53]
            //             std = [0.229*255, 0.224*255, 0.225*255] = [58.395, 57.12, 57.375]
            imageProcessor = ImageProcessor.Builder()
                .add(
                    ResizeOp(
                        inputHeight,
                        inputWidth,
                        ResizeOp.ResizeMethod.BILINEAR
                    )
                )
                .add(
                    NormalizeOp(
                        floatArrayOf(123.675f, 116.28f, 103.53f),  // mean * 255
                        floatArrayOf(58.395f, 57.12f, 57.375f)     // std * 255
                    )
                )
                .add(CastOp(DataType.FLOAT32))
                .build()

            val tmpOutputInfos = mutableListOf<OutputInfo>()
            val outputCount = interpreter!!.outputTensorCount
            
            // First pass: collect all output tensor info
            data class TempOutputInfo(
                val index: Int,
                val name: String,
                val shape: IntArray,
                val dataType: DataType,
                val layout: TensorLayout,
                val channels: Int,
                val height: Int,
                val width: Int
            )
            
            val tempInfos = mutableListOf<TempOutputInfo>()
            for (i in 0 until outputCount) {
                val tensor = interpreter!!.getOutputTensor(i)
                val shape = tensor.shape()
                val layout = if (shape.size == 4 && shape[1] <= 4) {
                    TensorLayout.NCHW
                } else {
                    TensorLayout.NHWC
                }
                val channels = if (layout == TensorLayout.NCHW) {
                    shape[1]
                } else {
                    shape.getOrElse(3) { 1 }
                }
                val height = if (layout == TensorLayout.NCHW) {
                    shape.getOrElse(2) { 1 }
                } else {
                    shape.getOrElse(1) { 1 }
                }
                val width = if (layout == TensorLayout.NCHW) {
                    shape.getOrElse(3) { 1 }
                } else {
                    shape.getOrElse(2) { 1 }
                }
                
                tempInfos += TempOutputInfo(
                    index = i,
                    name = tensor.name() ?: "output_$i",
                    shape = shape,
                    dataType = tensor.dataType(),
                    layout = layout,
                    channels = channels,
                    height = height,
                    width = width
                )
            }
            
            // Second pass: assign roles based on channels and spatial dimensions
            // YOLOX outputs are grouped by pyramid level (same H,W) with different channel counts:
            // - BBOX: 4 channels (cx, cy, w, h)
            // - OBJ: 1 channel (objectness)
            // - CLS: num_classes channels (class scores)
            for (info in tempInfos) {
                val role = when {
                    info.channels == 4 -> OutputRole.BBOX
                    info.channels == 1 -> OutputRole.OBJ
                    else -> OutputRole.CLS  // num_classes channels
                }
                
                tmpOutputInfos += OutputInfo(
                    index = info.index,
                    name = info.name,
                    shape = info.shape,
                    dataType = info.dataType,
                    layout = info.layout,
                    channels = info.channels,
                    height = info.height,
                    width = info.width,
                    role = role
                )
            }
            outputInfos = tmpOutputInfos

            Log.d(TAG, "Model loaded successfully")
            Log.d(TAG, "Input shape: ${modelInputShape.contentToString()}, layout=${if (inputIsNchw) "NCHW" else "NHWC"}")
            Log.d(TAG, "Total outputs: ${outputInfos.size}")
            outputInfos.forEach { info ->
                Log.d(
                    TAG,
                    "Output[${info.index}] name=${info.name}, shape=${info.shape.contentToString()}, channels=${info.channels}, spatial=${info.height}x${info.width}, role=${info.role}, layout=${info.layout}, dtype=${info.dataType}"
                )
            }
            
            // Verify we have complete sets for each pyramid level
            val levelMap = outputInfos.groupBy { it.height to it.width }
            Log.d(TAG, "Found ${levelMap.size} pyramid levels:")
            levelMap.forEach { (size, outputs) ->
                val roles = outputs.map { it.role }.distinct()
                Log.d(TAG, "  Level ${size.first}x${size.second}: ${outputs.size} outputs with roles $roles")
            }

            // Warmup: run 1 lightweight inference to stabilize delegate/allocations
            try {
                val warmupInput = TensorBuffer.createFixedSize(modelInputShape, modelInputDataType)
                val tWarmStart = System.nanoTime()
                runInference(warmupInput)
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
        return synchronized(interpreterLock) {
            // Check if closed while holding lock
            val currentInterpreter = interpreter
            if (isClosed || currentInterpreter == null) {
                Log.w(TAG, "detect() called on closed detector, returning empty result")
                return Detection(boundingBoxes = emptyList(), area = DetectionArea(0f, 0f, 0f, 0f))
            }
            try {
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

                Log.d(TAG, "Processed image: ${inputHeight}x${inputWidth}")

                // Run inference with timing
                val t0 = System.nanoTime()
                val outputs = runInference(input)
                val t1 = System.nanoTime()
                val inferenceMs = ((t1 - t0) / 1_000_000L)

                // Parse YOLO output
                val tPostStart = System.nanoTime()
                val parsed = parseYoloOutputs(
                    outputs, detectionArea
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
    }

    fun getModelInputSize(): Int {
        return if (inputIsNchw) {
            min(modelInputShape[2], modelInputShape[3])
        } else {
            min(modelInputShape[1], modelInputShape[2])
        }
    }

    /**
     * Run detection restricted to the provided detectionArea (in source image pixel coordinates).
     * The area is cropped and then resized to model input size (via existing imageProcessor).
     */
    fun detectInArea(image: Bitmap, detectionArea: DetectionArea): Detection {
        return synchronized(interpreterLock) {
            // Check if closed while holding lock
            val currentInterpreter = interpreter
            if (isClosed || currentInterpreter == null) {
                Log.w(TAG, "detectInArea() called on closed detector, returning empty result")
                return Detection(boundingBoxes = emptyList(), area = DetectionArea(0f, 0f, 0f, 0f))
            }
            try {
                val tOverallStart = System.nanoTime()

                // Preprocess (crop to area -> resize/normalize -> cast)
                val tPreStart = System.nanoTime()
                val input = preprocessImage(image, detectionArea)
                val tPreEnd = System.nanoTime()
                val preprocessMs = ((tPreEnd - tPreStart) / 1_000_000L)

                val t0 = System.nanoTime()
                val outputs = runInference(input)
                val t1 = System.nanoTime()
                val inferenceMs = ((t1 - t0) / 1_000_000L)

                val tPostStart = System.nanoTime()
                val parsed = parseYoloOutputs(outputs, detectionArea)
                val tPostEnd = System.nanoTime()
                val postprocessMs = ((tPostEnd - tPostStart) / 1_000_000L)

                val tOverallEnd = System.nanoTime()
                val totalMs = ((tOverallEnd - tOverallStart) / 1_000_000L)
                Log.d(
                    TAG,
                    "detectInArea: pre=${preprocessMs}ms, infer=${inferenceMs}ms, post=${postprocessMs}ms, total=${totalMs}ms"
                )

                Detection(
                    boundingBoxes = parsed.boxes,
                    area = detectionArea,
                    inferenceMs = inferenceMs,
                    fps = -1f,
                    rawDetections = parsed.rawCount,
                    afterNmsDetections = parsed.boxes.size
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during detection in area", e)
                Detection(boundingBoxes = emptyList(), area = DetectionArea(0f, 0f, 0f, 0f))
            }
        }
    }

    private fun preprocessImage(image: Bitmap, detectionArea: DetectionArea): TensorBuffer {
        val croppedImage = Bitmap.createBitmap(
            image,
            detectionArea.startX.toInt(),
            detectionArea.startY.toInt(),
            detectionArea.width.toInt(),
            detectionArea.height.toInt()
        )

        val tensorImage = TensorImage(modelInputDataType)
        tensorImage.load(croppedImage)

        val processed = imageProcessor.process(tensorImage)

        return convertToModelInput(processed)
    }

    private fun convertToModelInput(processed: TensorImage): TensorBuffer {
        val source = processed.tensorBuffer
        val target = TensorBuffer.createFixedSize(modelInputShape, modelInputDataType)

        if (!inputIsNchw) {
            target.loadArray(source.floatArray, modelInputShape)
            return target
        }

        val nhwc = source.floatArray
        val nchw = FloatArray(inputChannels * inputHeight * inputWidth)
        var nhwcIndex = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                for (c in 0 until inputChannels) {
                    val nchwIndex = c * inputHeight * inputWidth + y * inputWidth + x
                    nchw[nchwIndex] = nhwc[nhwcIndex++]
                }
            }
        }

        target.loadArray(nchw, modelInputShape)
        return target
    }

    private data class ParsedResult(val boxes: List<BoundingBox>, val rawCount: Int)

    private fun runInference(input: TensorBuffer): Map<Int, TensorBuffer> {
        val currentInterpreter = interpreter
        if (currentInterpreter == null) {
            throw IllegalStateException("Interpreter is null during inference")
        }

        val buffers = mutableMapOf<Int, TensorBuffer>()
        outputInfos.forEach { info ->
            buffers[info.index] = TensorBuffer.createFixedSize(info.shape, info.dataType)
        }

        val outputs: MutableMap<Int, Any> = mutableMapOf()
        buffers.forEach { (index, buffer) -> outputs[index] = buffer.buffer }

        currentInterpreter.runForMultipleInputsOutputs(arrayOf(input.buffer), outputs)

        return buffers
    }

    private fun parseYoloOutputs(
        outputs: Map<Int, TensorBuffer>, detectionArea: DetectionArea
    ): ParsedResult {
        val levelBuffers = mutableMapOf<Pair<Int, Int>, LevelBuffers>()
        outputInfos.forEach { info ->
            val buffer = outputs[info.index]
                ?: throw IllegalStateException("Missing buffer for output index ${info.index}")

            val key = info.height to info.width
            val entry = levelBuffers.getOrPut(key) { LevelBuffers() }
            when (info.role) {
                OutputRole.CLS -> entry.cls = buffer to info
                OutputRole.BBOX -> entry.bbox = buffer to info
                OutputRole.OBJ -> entry.obj = buffer to info
            }
        }
        
        Log.d(TAG, "parseYoloOutputs: Found ${levelBuffers.size} pyramid levels")
        levelBuffers.forEach { (key, buffers) ->
            Log.d(TAG, "  Level ${key.first}x${key.second}: cls=${buffers.cls != null}, bbox=${buffers.bbox != null}, obj=${buffers.obj != null}")
        }

        val predictions = mutableListOf<BoundingBox>()
        var rawCount = 0

        val areaW = detectionArea.width
        val areaH = detectionArea.height
        val areaX = detectionArea.startX
        val areaY = detectionArea.startY

        fun tensorValue(array: FloatArray, info: OutputInfo, channel: Int, y: Int, x: Int): Float {
            return when (info.layout) {
                TensorLayout.NCHW -> {
                    val base = ((channel * info.height) + y) * info.width + x
                    array[base]
                }

                TensorLayout.NHWC -> {
                    val base = ((y * info.width) + x) * info.channels + channel
                    array[base]
                }
            }
        }

        levelBuffers.forEach { (sizeKey, buffersPerLevel) ->
            val (height, width) = sizeKey
            val clsPair = buffersPerLevel.cls
            val bboxPair = buffersPerLevel.bbox
            val objPair = buffersPerLevel.obj
            
            // For single-class models, CLS output may not exist
            // In this case, we treat all detections as class 0 with confidence = objectness
            if (bboxPair == null || objPair == null) {
                Log.w(TAG, "Missing required buffers for level ${sizeKey.first}x${sizeKey.second}: bbox=${bboxPair != null}, obj=${objPair != null}")
                return@forEach
            }
            
            val isSingleClass = clsPair == null
            if (isSingleClass) {
                Log.d(TAG, "Single-class model detected for level ${height}x${width}")
            }

            val stride = run {
                val grid = min(height, width)
                val input = min(inputHeight, inputWidth)
                if (grid == 0) {
                    Log.w(TAG, "Zero-sized grid for output level ${sizeKey.first}x${sizeKey.second}; skipping")
                    return@forEach
                }
                (input / grid.toFloat()).takeIf { it.isFinite() && it > 0f }
                    ?: run {
                        Log.w(TAG, "Invalid stride computed for level ${sizeKey.first}x${sizeKey.second}; skipping")
                        return@forEach
                    }
            }
            
            Log.d(TAG, "Processing level ${height}x${width} with stride=$stride")

            val bboxArray = bboxPair.first.floatArray
            val objArray = objPair.first.floatArray
            
            var levelDetections = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val objScore = sigmoid(tensorValue(objArray, objPair.second, 0, y, x))
                    if (objScore < confThreshold) continue

                    val bestClassScore: Float
                    val bestClassId: Int
                    
                    if (isSingleClass) {
                        // Single-class model: confidence = objectness, class = 0
                        bestClassScore = objScore
                        bestClassId = 0
                    } else {
                        // Multi-class model: compute class scores
                        val clsArray = clsPair!!.first.floatArray
                        var maxScore = 0f
                        var maxId = 0
                        for (c in 0 until clsPair.second.channels) {
                            val clsScore = sigmoid(tensorValue(clsArray, clsPair.second, c, y, x)) * objScore
                            if (clsScore > maxScore) {
                                maxScore = clsScore
                                maxId = c
                            }
                        }
                        bestClassScore = maxScore
                        bestClassId = maxId
                        
                        if (bestClassScore < confThreshold) continue
                    }

                    rawCount += 1
                    levelDetections += 1

                    val cx = tensorValue(bboxArray, bboxPair.second, 0, y, x)
                    val cy = tensorValue(bboxArray, bboxPair.second, 1, y, x)
                    val w = tensorValue(bboxArray, bboxPair.second, 2, y, x)
                    val h = tensorValue(bboxArray, bboxPair.second, 3, y, x)

                    val centerX = (cx + x) * stride
                    val centerY = (cy + y) * stride
                    val widthPx = exp(w) * stride
                    val heightPx = exp(h) * stride

                    val x1 = (centerX - widthPx / 2f).coerceIn(0f, inputWidth.toFloat())
                    val y1 = (centerY - heightPx / 2f).coerceIn(0f, inputHeight.toFloat())
                    val x2 = (centerX + widthPx / 2f).coerceIn(0f, inputWidth.toFloat())
                    val y2 = (centerY + heightPx / 2f).coerceIn(0f, inputHeight.toFloat())

                    val scaleX = areaW / inputWidth
                    val scaleY = areaH / inputHeight

                    val pixelX1 = (areaX + x1 * scaleX).coerceIn(areaX, areaX + areaW)
                    val pixelY1 = (areaY + y1 * scaleY).coerceIn(areaY, areaY + areaH)
                    val pixelX2 = (areaX + x2 * scaleX).coerceIn(areaX, areaX + areaW)
                    val pixelY2 = (areaY + y2 * scaleY).coerceIn(areaY, areaY + areaH)

                    val boxWidth = pixelX2 - pixelX1
                    val boxHeight = pixelY2 - pixelY1

                    if (boxWidth <= 0f || boxHeight <= 0f) continue

                    predictions += BoundingBox(
                        startX = pixelX1,
                        startY = pixelY1,
                        width = boxWidth,
                        height = boxHeight,
                        confidence = bestClassScore,
                        classId = bestClassId,
                        className = if (bestClassId == 0) "poo" else "unknown"
                    )
                }
            }
            
            Log.d(TAG, "  Level ${height}x${width}: ${levelDetections} raw detections")
        }

        val finalBoxes = if (nmsEnabled && predictions.size > 1) {
            applyNMS(predictions, iouThreshold)
        } else {
            predictions
        }

        return ParsedResult(finalBoxes, rawCount)
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
        synchronized(interpreterLock) {
            isClosed = true
            interpreter?.close()
            interpreter = null
            try {
                gpuDelegate?.close()
            } catch (_: Throwable) {
            }
            gpuDelegate = null
            try {
                nnApiDelegate?.close()
            } catch (_: Throwable) {
            }
            nnApiDelegate = null
            Log.d(TAG, "FooDetector closed")
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
