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
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class EfficientDetLiteDetector(
        context: Context,
        modelPath: String,
        private var confThreshold: Float = 0.3f,
        private val iouThreshold: Float = 0.5f,
        accelerator: Accelerator = Accelerator.CPU,
        numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
) : Detector {

    companion object {
        private const val TAG = "EfficientDetLiteDetector"
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private val interpreterLock = Any()
    @Volatile private var isClosed = false

    private var inputShape: IntArray
    private var inputDataType: DataType
    private var inputHeight: Int
    private var inputWidth: Int
    private var floatInput: Boolean
    private val imageProcessor: ImageProcessor
    private val threads: Int
    private var activeAccelerator: Accelerator = accelerator

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
                                    Log.w(TAG, "GPU delegate not supported; using CPU")
                                    activeAccelerator = Accelerator.CPU
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "GPU delegate failed; using CPU", t)
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
                                Log.w(TAG, "NNAPI delegate failed; using CPU", t)
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

        // Some converted models export a placeholder input shape like [1, 1, 1, 1].
        // Mirror the notebook behavior by resizing the input to the expected
        // EfficientDet-Lite0 size [1, 320, 320, 3] when needed.
        fun ensureConcreteInputShape() {
            val t = interpreter!!.getInputTensor(0)
            val shape = t.shape()
            if (shape.size == 4) {
                val h = shape[1]
                val w = shape[2]
                val c = if (shape[3] > 0) shape[3] else 3

                val needsResize = (h <= 1 && w <= 1) || c != 3
                if (needsResize) {
                    val desiredSize = 320
                    Log.w(
                            TAG,
                            "Resizing model input from ${shape.contentToString()} to [1,$desiredSize,$desiredSize,3]"
                    )
                    interpreter!!.resizeInput(0, intArrayOf(1, desiredSize, desiredSize, 3))
                    interpreter!!.allocateTensors()
                }
            }
        }

        ensureConcreteInputShape()

        val inputTensor = interpreter!!.getInputTensor(0)
        inputShape = inputTensor.shape()
        inputDataType = inputTensor.dataType()
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        floatInput = inputDataType == DataType.FLOAT32

        Log.d(TAG, "EfficientDet input shape=${inputShape.contentToString()} type=$inputDataType")

        imageProcessor =
                ImageProcessor.Builder()
                        .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                        .build()
    }

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

    fun getModelInputSize(): Int {
        return min(inputHeight, inputWidth)
    }

    fun getDelegateDescription(): String {
        val t = threads
        return when (activeAccelerator) {
            Accelerator.GPU -> "GPU delegate"
            Accelerator.NNAPI -> "NNAPI delegate"
            Accelerator.CPU -> "CPU/XNNPACK(${t}t)"
        }
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
            val output = runInference(input)
            val t2 = System.nanoTime()
            val boxes = parseEfficientDetOutputs(output, area)
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

    private fun preprocess(image: Bitmap, area: DetectionArea): TensorBuffer {
        val crop =
                Bitmap.createBitmap(
                        image,
                        area.startX.toInt(),
                        area.startY.toInt(),
                        area.width.toInt(),
                        area.height.toInt()
                )

        val tensorImage = TensorImage(inputDataType)
        tensorImage.load(crop)

        val resized = imageProcessor.process(tensorImage)
        val buffer = TensorBuffer.createFixedSize(inputShape, inputDataType)

        if (floatInput) {
            val src = resized.tensorBuffer.floatArray
            val norm = FloatArray(src.size) { src[it] / 255f }
            buffer.loadArray(norm, inputShape)
        } else {
            buffer.loadBuffer(resized.tensorBuffer.buffer)
        }

        return buffer
    }

    private fun runInference(input: TensorBuffer): TensorBuffer {
        val currentInterpreter = interpreter ?: throw IllegalStateException("Interpreter is null")
        val outTensor = currentInterpreter.getOutputTensor(0)
        val output = TensorBuffer.createFixedSize(outTensor.shape(), outTensor.dataType())
        currentInterpreter.run(input.buffer, output.buffer)
        return output
    }

    private fun parseEfficientDetOutputs(
            output: TensorBuffer,
            area: DetectionArea
    ): List<BoundingBox> {
        val shape = output.shape
        if (shape.size != 3 || shape[2] < 7) {
            Log.w(TAG, "Unexpected EfficientDet output shape: ${shape.contentToString()}")
            return emptyList()
        }

        val out = output.floatArray
        val numDet = shape[1]
        val stride = shape[2]

        val boxes = mutableListOf<BoundingBox>()
        val scaleX = area.width / inputWidth
        val scaleY = area.height / inputHeight

        for (i in 0 until numDet) {
            val base = i * stride
            val score = out[base + 5]
            if (score < confThreshold) continue

            val ymin = out[base + 1]
            val xmin = out[base + 2]
            val ymax = out[base + 3]
            val xmax = out[base + 4]
            val cls = out[base + 6].toInt()

            val x1 = xmin * scaleX + area.startX
            val y1 = ymin * scaleY + area.startY
            val x2 = xmax * scaleX + area.startX
            val y2 = ymax * scaleY + area.startY

            val w = (x2 - x1).coerceAtLeast(0f)
            val h = (y2 - y1).coerceAtLeast(0f)
            if (w <= 0f || h <= 0f) continue

            boxes +=
                    BoundingBox(
                            startX = x1,
                            startY = y1,
                            width = w,
                            height = h,
                            confidence = score,
                            classId = cls,
                            className = "poo"
                    )
        }

        return applyNms(boxes, iouThreshold)
    }

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

    fun close() {
        synchronized(interpreterLock) {
            isClosed = true
            interpreter?.close()
            interpreter = null
            try {
                gpuDelegate?.close()
            } catch (_: Throwable) {}
            try {
                nnApiDelegate?.close()
            } catch (_: Throwable) {}
            gpuDelegate = null
            nnApiDelegate = null
        }
    }

    fun setConfidenceThreshold(th: Float) {
        confThreshold = th.coerceIn(0f, 1f)
    }
}
