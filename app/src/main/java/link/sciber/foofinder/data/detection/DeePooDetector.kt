package link.sciber.foofinder.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/**
 * DeePoo YOLOv4-Tiny 416×416 INT8 detector.
 *
 * Model produced by the DeePoo_YOLOv4_Tiny_416x416_INT8_FULL notebook.
 *
 * Input : uint8 [1, 416, 416, 3]  — raw pixel values [0, 255]
 * Output: two float32 tensors
 *   - scores : [1, N, numClasses]  (N = 2535 for 416 input)
 *   - boxes  : [1, N, 1, 4]       (x1, y1, x2, y2 in input-pixel coords)
 */
class DeePooDetector(
        context: Context,
        modelPath: String = "models/deepoo_yolov4_tiny_416_int8.tflite",
        private var confThreshold: Float = MIN_CONFIDENCE,
        private val iouThreshold: Float = 0.45f,
        accelerator: Accelerator = Accelerator.GPU,
        numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
) : Detector {

    companion object {
        private const val TAG = "DeePooDetector"

        /**
         * Hard floor for confidence threshold.  INT8 quantisation of the
         * sigmoid function causes background anchors to output ≈ 0.49
         * instead of ≈ 0.0 (sigmoid of a near-zero logit).  Allowing a
         * threshold below 0.50 would let hundreds of these through.
         */
        private const val MIN_CONFIDENCE = 0.50f
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private val interpreterLock = Any()
    @Volatile private var isClosed = false

    private val inputWidth: Int
    private val inputHeight: Int
    private val inputIsUint8: Boolean
    private val threads: Int
    private var activeAccelerator: Accelerator = accelerator

    // Output tensor indices (resolved at init based on shape inspection)
    private val scoresOutputIndex: Int
    private val boxesOutputIndex: Int
    private val numDetections: Int
    private val numClasses: Int

    init {
        val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelPath)
        threads = numThreads.coerceAtLeast(1)

        val options =
                Interpreter.Options().apply {
                    setNumThreads(threads)
                    when (accelerator) {
                        Accelerator.GPU -> {
                            try {
                                val compat = CompatibilityList()
                                if (compat.isDelegateSupportedOnThisDevice) {
                                    gpuDelegate = GpuDelegate()
                                    addDelegate(gpuDelegate)
                                    Log.d(TAG, "Using GPU delegate")
                                    activeAccelerator = Accelerator.GPU
                                } else {
                                    Log.w(TAG, "GPU delegate not supported; falling back to CPU")
                                    activeAccelerator = Accelerator.CPU
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "GPU delegate failed; falling back to CPU", t)
                                activeAccelerator = Accelerator.CPU
                            }
                        }
                        Accelerator.NNAPI -> {
                            try {
                                nnApiDelegate = NnApiDelegate()
                                addDelegate(nnApiDelegate)
                                Log.d(TAG, "Using NNAPI delegate")
                                activeAccelerator = Accelerator.NNAPI
                            } catch (t: Throwable) {
                                Log.w(TAG, "NNAPI delegate failed; falling back to CPU", t)
                                activeAccelerator = Accelerator.CPU
                            }
                        }
                        Accelerator.CPU -> {
                            Log.d(TAG, "Using CPU with $threads threads")
                            activeAccelerator = Accelerator.CPU
                        }
                    }
                }

        interpreter = Interpreter(modelBuffer, options)

        // --- Inspect input tensor ---
        val inputTensor = interpreter!!.getInputTensor(0)
        val inShape = inputTensor.shape()   // expected [1, 416, 416, 3]
        inputHeight = inShape[1]
        inputWidth = inShape[2]
        inputIsUint8 = inputTensor.dataType() == DataType.UINT8

        Log.d(TAG, "Input: shape=${inShape.contentToString()}, dtype=${inputTensor.dataType()}")

        // --- Inspect output tensors ---
        // The model has 2 outputs. One is scores, the other is boxes.
        // Identify them by checking which has last-dim == 4.
        val numOutputs = interpreter!!.outputTensorCount
        require(numOutputs == 2) {
            "Expected 2 output tensors, got $numOutputs"
        }

        val out0Shape = interpreter!!.getOutputTensor(0).shape()
        val out1Shape = interpreter!!.getOutputTensor(1).shape()

        Log.d(TAG, "Output 0: shape=${out0Shape.contentToString()}")
        Log.d(TAG, "Output 1: shape=${out1Shape.contentToString()}")

        // The boxes tensor has last dim == 4
        val out0IsBoxes = out0Shape.last() == 4
        if (out0IsBoxes) {
            boxesOutputIndex = 0
            scoresOutputIndex = 1
        } else {
            boxesOutputIndex = 1
            scoresOutputIndex = 0
        }

        val scoresShape = interpreter!!.getOutputTensor(scoresOutputIndex).shape()
        val boxesShape = interpreter!!.getOutputTensor(boxesOutputIndex).shape()

        // scores: [1, N, numClasses]  or  [1, N, 1]
        numDetections = scoresShape[1]
        numClasses = if (scoresShape.size >= 3) scoresShape[2] else 1

        Log.d(
                TAG,
                "Resolved: scores[idx=$scoresOutputIndex]=${scoresShape.contentToString()}, " +
                        "boxes[idx=$boxesOutputIndex]=${boxesShape.contentToString()}, " +
                        "N=$numDetections, classes=$numClasses"
        )
    }

    // ---- Public API (mirrors EfficientDetLiteDetector for drop-in replacement) ----

    override fun detect(image: Bitmap): Detection {
        val area =
                DetectionArea(
                        startX = 0f,
                        startY = 0f,
                        width = image.width.toFloat(),
                        height = image.height.toFloat()
                )
        return detectInArea(image, area)
    }

    fun getModelInputSize(): Int = min(inputHeight, inputWidth)

    fun getDelegateDescription(): String {
        return when (activeAccelerator) {
            Accelerator.GPU -> "GPU delegate"
            Accelerator.NNAPI -> "NNAPI delegate"
            Accelerator.CPU -> "CPU/XNNPACK(${threads}t)"
        }
    }

    fun setConfidenceThreshold(th: Float) {
        confThreshold = th.coerceIn(MIN_CONFIDENCE, 1f)
        Log.d(TAG, "Confidence threshold set to $confThreshold (requested $th)")
    }

    fun detectInArea(image: Bitmap, area: DetectionArea): Detection {
        return synchronized(interpreterLock) {
            val currentInterpreter = interpreter
            if (isClosed || currentInterpreter == null) {
                return Detection(emptyList(), area)
            }

            val t0 = System.nanoTime()
            val input = preprocess(image, area)
            val t1 = System.nanoTime()
            val rawOutputs = runInference(currentInterpreter, input)
            val t2 = System.nanoTime()
            val boxes = decodeOutputs(rawOutputs, area)
            val t3 = System.nanoTime()

            val preMs = (t1 - t0) / 1_000_000L
            val inferMs = (t2 - t1) / 1_000_000L
            val postMs = (t3 - t2) / 1_000_000L
            Log.d(TAG, "pre=$preMs ms  infer=$inferMs ms  post=$postMs ms  boxes=${boxes.size}")

            Detection(
                    boundingBoxes = boxes,
                    area = area,
                    inferenceMs = inferMs,
                    fps = -1f,
                    rawDetections = boxes.size,
                    afterNmsDetections = boxes.size
            )
        }
    }

    fun close() {
        synchronized(interpreterLock) {
            isClosed = true
            interpreter?.close()
            interpreter = null
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            try { nnApiDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null
            nnApiDelegate = null
        }
    }

    // ---- Preprocessing ----

    private fun preprocess(image: Bitmap, area: DetectionArea): ByteBuffer {
        // Crop the detection area from the source bitmap
        val crop =
                Bitmap.createBitmap(
                        image,
                        area.startX.toInt(),
                        area.startY.toInt(),
                        area.width.toInt(),
                        area.height.toInt()
                )

        // Resize to model input size
        val resized =
                if (crop.width != inputWidth || crop.height != inputHeight) {
                    Bitmap.createScaledBitmap(crop, inputWidth, inputHeight, true).also {
                        if (it !== crop) crop.recycle()
                    }
                } else {
                    crop
                }

        // Fill a ByteBuffer with pixel data
        val buffer =
                if (inputIsUint8) {
                    // UINT8 input: raw pixel bytes [0, 255]
                    val buf = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * 3)
                    buf.order(ByteOrder.nativeOrder())
                    val pixels = IntArray(inputWidth * inputHeight)
                    resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
                    for (pixel in pixels) {
                        buf.put(((pixel shr 16) and 0xFF).toByte()) // R
                        buf.put(((pixel shr 8) and 0xFF).toByte())  // G
                        buf.put((pixel and 0xFF).toByte())           // B
                    }
                    buf.rewind()
                    buf
                } else {
                    // FLOAT32 input: normalize to [0, 1]
                    val buf = ByteBuffer.allocateDirect(4 * inputHeight * inputWidth * 3)
                    buf.order(ByteOrder.nativeOrder())
                    val pixels = IntArray(inputWidth * inputHeight)
                    resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
                    for (pixel in pixels) {
                        buf.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
                        buf.putFloat(((pixel shr 8) and 0xFF) / 255f)  // G
                        buf.putFloat((pixel and 0xFF) / 255f)           // B
                    }
                    buf.rewind()
                    buf
                }

        if (resized !== image) resized.recycle()
        return buffer
    }

    // ---- Inference ----

    private data class RawOutputs(val scores: FloatArray, val boxes: FloatArray)

    private fun runInference(interp: Interpreter, input: ByteBuffer): RawOutputs {
        // Allocate output buffers
        val scoresTensor = interp.getOutputTensor(scoresOutputIndex)
        val boxesTensor = interp.getOutputTensor(boxesOutputIndex)

        val scoresSize = scoresTensor.shape().fold(1) { acc, v -> acc * v }
        val boxesSize = boxesTensor.shape().fold(1) { acc, v -> acc * v }

        val scoresBuf = ByteBuffer.allocateDirect(scoresSize * 4).order(ByteOrder.nativeOrder())
        val boxesBuf = ByteBuffer.allocateDirect(boxesSize * 4).order(ByteOrder.nativeOrder())

        val outputMap = HashMap<Int, Any>()
        outputMap[scoresOutputIndex] = scoresBuf
        outputMap[boxesOutputIndex] = boxesBuf

        interp.runForMultipleInputsOutputs(arrayOf(input), outputMap)

        // Extract float arrays
        scoresBuf.rewind()
        val scoresArr = FloatArray(scoresSize)
        scoresBuf.asFloatBuffer().get(scoresArr)

        boxesBuf.rewind()
        val boxesArr = FloatArray(boxesSize)
        boxesBuf.asFloatBuffer().get(boxesArr)

        return RawOutputs(scoresArr, boxesArr)
    }

    // ---- Post-processing ----

    /**
     * Decode YOLOv4-Tiny TFLite outputs into bounding boxes.
     *
     * scores: flat [1 * N * numClasses]
     * boxes:  flat [1 * N * 1 * 4]  — (x1, y1, x2, y2) normalised [0..1]
     *
     * The Tianxiaomo pytorch-YOLOv4 ONNX export always produces normalised
     * coordinates.  onnx2tf preserves the value range, so the TFLite model
     * also outputs normalised [0..1] box coordinates.
     *
     * Follows the same logic as `decode_yolov4_tflite_output` in the training notebook.
     */
    private fun decodeOutputs(raw: RawOutputs, area: DetectionArea): List<BoundingBox> {
        // Log raw output statistics and score distribution for diagnostics
        if (raw.scores.isNotEmpty() && raw.boxes.isNotEmpty()) {
            var above90 = 0; var above70 = 0; var above50 = 0; var above40 = 0
            for (s in raw.scores) {
                if (s > 0.9f) above90++
                else if (s > 0.7f) above70++
                else if (s > 0.5f) above50++
                else if (s > 0.4f) above40++
            }
            Log.d(
                    TAG,
                    "Raw outputs: scores=[${raw.scores.min()}..${raw.scores.max()}], " +
                            "boxes=[${raw.boxes.min()}..${raw.boxes.max()}]  " +
                            "dist: >0.9=$above90  >0.7=$above70  >0.5=$above50  >0.4=$above40"
            )
        }

        // Minimum box dimension in *area* pixels — reject degenerate tiny boxes
        val minBoxPixels = 4f

        val candidates = mutableListOf<BoundingBox>()

        for (i in 0 until numDetections) {
            // Best class confidence for this detection
            var bestClassId = 0
            var bestScore = raw.scores[i * numClasses]
            for (c in 1 until numClasses) {
                val s = raw.scores[i * numClasses + c]
                if (s > bestScore) {
                    bestScore = s
                    bestClassId = c
                }
            }

            if (bestScore < confThreshold) continue

            // Box coordinates: [x1, y1, x2, y2] — normalised [0..1] for valid
            // detections.  Background anchors with extreme exp(tw/th) can
            // produce coordinates far outside [0,1]; reject those early.
            val boxBase = i * 4
            val x1 = raw.boxes[boxBase]
            val y1 = raw.boxes[boxBase + 1]
            val x2 = raw.boxes[boxBase + 2]
            val y2 = raw.boxes[boxBase + 3]

            // Reject boxes whose coordinates are clearly out of the image
            if (x1 < -0.1f || y1 < -0.1f || x2 > 1.1f || y2 > 1.1f) continue

            // Reject boxes larger than the full image (degenerate exp overflow)
            val nw = x2 - x1
            val nh = y2 - y1
            if (nw <= 0f || nh <= 0f || nw > 1.0f || nh > 1.0f) continue

            // Clamp surviving coords to [0,1] then scale to detection-area coords
            val left = x1.coerceIn(0f, 1f) * area.width + area.startX
            val top = y1.coerceIn(0f, 1f) * area.height + area.startY
            val right = x2.coerceIn(0f, 1f) * area.width + area.startX
            val bottom = y2.coerceIn(0f, 1f) * area.height + area.startY

            val w = (right - left).coerceAtLeast(0f)
            val h = (bottom - top).coerceAtLeast(0f)
            if (w < minBoxPixels || h < minBoxPixels) continue

            candidates +=
                    BoundingBox(
                            startX = left,
                            startY = top,
                            width = w,
                            height = h,
                            confidence = bestScore,
                            classId = bestClassId,
                            className = "poo"
                    )
        }

        return applyNms(candidates, iouThreshold)
    }

    // ---- NMS ----

    private fun applyNms(boxes: List<BoundingBox>, iouThreshold: Float): List<BoundingBox> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<BoundingBox>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = max(a.startX, b.startX)
        val y1 = max(a.startY, b.startY)
        val x2 = min(a.startX + a.width, b.startX + b.width)
        val y2 = min(a.startY + a.height, b.startY + b.height)
        if (x2 <= x1 || y2 <= y1) return 0f

        val inter = (x2 - x1) * (y2 - y1)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }
}
