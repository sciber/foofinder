# Using the EfficientDet-Lite0 TFLite Model in Your Android App

This document explains how to integrate your exported EfficientDet-Lite0 TFLite model into your existing Android detection pipeline, replacing the YOLOX-based `FooDetector` while keeping the same `Detector` / `BoundingBox` / `Detection` abstractions.

## 1. Model characteristics

From the Colab export and evaluation code:

- **Input tensor**
  - Shape: `[1, 320, 320, 3]` (NHWC)
  - Type: typically `float32` (if you used the default TFLite converter) or `uint8` for a quantized model.
- **Output tensor**
  - Single tensor, shape: `[1, 100, 7]`.
  - Each detection row is: `[image_id, ymin, xmin, ymax, xmax, score, class]`.
  - Coordinates are in **model input space** (height/width = 320), not normalized to `[0,1]`.
  - You have a **single class** ("poo"), so `class` will usually be `0` or `1` depending on how you trained/exported.

## 2. Create a new detector: `EfficientDetLiteDetector`

Create a new Kotlin file `EfficientDetLiteDetector.kt` in `link.sciber.foofinder.data.detection`:

```kotlin
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
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer
import kotlin.math.max
import kotlin.math.min

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

    private val inputShape: IntArray
    private val inputDataType: DataType
    private val inputHeight: Int
    private val inputWidth: Int
    private val floatInput: Boolean
    private val imageProcessor: ImageProcessor

    init {
        // Load model and configure accelerator
        val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelPath)
        val threads = numThreads.coerceAtLeast(1)

        val options = Interpreter.Options().apply {
            setNumThreads(threads)
            when (accelerator) {
                Accelerator.GPU -> {
                    try {
                        val compat = CompatibilityList()
                        if (compat.isDelegateSupportedOnThisDevice) {
                            gpuDelegate = GpuDelegate()
                            addDelegate(gpuDelegate)
                            Log.d(TAG, "Using GPU delegate")
                        } else {
                            Log.w(TAG, "GPU delegate not supported; using CPU")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "GPU delegate failed; using CPU", t)
                    }
                }
                Accelerator.NNAPI -> {
                    try {
                        nnApiDelegate = NnApiDelegate()
                        addDelegate(nnApiDelegate)
                        Log.d(TAG, "Using NNAPI delegate")
                    } catch (t: Throwable) {
                        Log.w(TAG, "NNAPI delegate failed; using CPU", t)
                    }
                }
                Accelerator.CPU -> {
                    Log.d(TAG, "Using CPU with $threads threads")
                }
            }
        }

        interpreter = Interpreter(modelBuffer, options)

        val inputTensor = interpreter!!.getInputTensor(0)
        inputShape = inputTensor.shape()          // [1, H, W, 3]
        inputDataType = inputTensor.dataType()
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        floatInput = inputDataType == DataType.FLOAT32

        Log.d(TAG, "EfficientDet input shape=${inputShape.contentToString()} type=$inputDataType")

        // Preprocessing: resize only; normalize if model expects float32 [0,1]
        imageProcessor = ImageProcessor.Builder()
            .add(
                ResizeOp(
                    inputHeight,
                    inputWidth,
                    ResizeOp.ResizeMethod.BILINEAR
                )
            )
            .build()
    }

    override fun detect(image: Bitmap): Detection {
        val area = DetectionArea(
            startX = 0f,
            startY = 0f,
            width = image.width.toFloat(),
            height = image.height.toFloat()
        )
        return detectInArea(image, area)
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
                inferenceMs = inferMs.toInt(),
                fps = -1f,
                rawDetections = boxes.size,
                afterNmsDetections = boxes.size
            )
        }
    }

    private fun preprocess(image: Bitmap, area: DetectionArea): TensorBuffer {
        // Crop to area then resize to model input size
        val crop = Bitmap.createBitmap(
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
            // Convert to [0,1] float32
            val src = resized.tensorBuffer.floatArray
            val norm = FloatArray(src.size) { src[it] / 255f }
            buffer.loadArray(norm, inputShape)
        } else {
            // UINT8 model: copy raw bytes
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

    // Output: [1, numDet(=100), 7] with [image_id, ymin, xmin, ymax, xmax, score, class]
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

            // Scale from model input (320x320) back to original detection area
            val x1 = xmin * scaleX + area.startX
            val y1 = ymin * scaleY + area.startY
            val x2 = xmax * scaleX + area.startX
            val y2 = ymax * scaleY + area.startY

            val w = (x2 - x1).coerceAtLeast(0f)
            val h = (y2 - y1).coerceAtLeast(0f)
            if (w <= 0f || h <= 0f) continue

            boxes += BoundingBox(
                startX = x1,
                startY = y1,
                width = w,
                height = h,
                confidence = score,
                classId = cls,
                className = "poo"  // single-class model
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
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            try { nnApiDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null
            nnApiDelegate = null
        }
    }

    fun setConfidenceThreshold(th: Float) {
        confThreshold = th.coerceIn(0f, 1f)
    }
}
```

## 3. Wire into your app

1. **Place the model**

   Put your exported file into `app/src/main/assets/`, e.g.

   ```text
   app/src/main/assets/deepoo_efficientdet-lite0.tflite
   ```

2. **Instantiate the detector**

   Wherever you currently create `FooDetector`, create the EfficientDet version instead:

   ```kotlin
   private val detector: Detector by lazy {
       EfficientDetLiteDetector(
           context = applicationContext,
           modelPath = "deepoo_efficientdet-lite0.tflite",
           confThreshold = 0.3f,
           iouThreshold = 0.5f,
           accelerator = Accelerator.GPU  // or CPU / NNAPI
       )
   }
   ```

3. **Use as before**

   Your existing code that calls `detector.detect(bitmap)` and renders `BoundingBox` objects can remain unchanged, because `EfficientDetLiteDetector` implements the same `Detector` interface and returns the same domain types.

## 4. Notes for quantized (INT8/UINT8) EfficientDet

If you later export a quantized EfficientDet model:

- If the input tensor type is `UINT8`, the current implementation (no normalization, just copy the bytes) is correct.
- If the input tensor type is `INT8`, you may need to apply the same input scaling/zero-point that you used during quantization calibration. In that case:
  - Read `inputTensor.quantizationParams().scale` and `zeroPoint` on Android.
  - Map your float pixels into `INT8` using `(value / scale + zeroPoint)` as you already do in your YOLOX code.

## 5. Sanity checks

To verify the integration:

- Log the input tensor shape and type on device:

  ```kotlin
  Log.d(TAG, "Input: shape=${inputShape.contentToString()}, type=$inputDataType")
  ```

- Run on test images you used in Colab and compare visually with the TFLite visualizations:
  - Bounding boxes should align closely.
  - Detection counts and scores should be similar.

If the shapes or dtypes differ from the assumptions above, you can tweak the `preprocess` method and the output parsing to match the exact model signature. 
