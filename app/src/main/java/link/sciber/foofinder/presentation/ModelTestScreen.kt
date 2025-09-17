package link.sciber.foofinder.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import link.sciber.foofinder.data.detection.FooDetector
import link.sciber.foofinder.domain.BoundingBox
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.utils.YoloLabelParser

@Composable
fun ModelTestScreen(
        testImageAssetPath: String,
        testLabelAssetPath: String,
        modifier: Modifier = Modifier,
        isPreview: Boolean = false
) {
    val context = LocalContext.current

    var testBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detector by remember { mutableStateOf<FooDetector?>(null) }
    var detection by remember { mutableStateOf<Detection?>(null) }
    var groundTruthBoxes by remember { mutableStateOf<List<BoundingBox>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Initialize detector and load test data
    LaunchedEffect(Unit) {
        if (isPreview) {
            // Skip initialization in preview mode
            errorMessage = "Preview mode - model initialization skipped"
            isLoading = false
            return@LaunchedEffect
        }

        try {
            // Initialize detector
            detector = FooDetector(context, "models/best_plain_float16.tflite")

            // Load test image from assets
            try {
                val inputStream = context.assets.open(testImageAssetPath)
                testBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
            } catch (e: Exception) {
                throw Exception(
                        "Failed to load image from assets: $testImageAssetPath - ${e.message}"
                )
            }

            testBitmap?.let { bitmap ->
                // Load ground truth labels
                try {
                    groundTruthBoxes =
                            YoloLabelParser.parseLabelsFromAssets(
                                    context,
                                    testLabelAssetPath,
                                    bitmap.width,
                                    bitmap.height
                            )
                } catch (e: Exception) {
                    throw Exception(
                            "Failed to load labels from assets: $testLabelAssetPath - ${e.message}"
                    )
                }

                // Run detection
                detector?.let { det -> detection = det.detect(bitmap) }
            }
                    ?: throw Exception("Failed to decode bitmap from image: $testImageAssetPath")

            isLoading = false
        } catch (e: Exception) {
            errorMessage = "Failed to initialize test: ${e.message}"
            isLoading = false
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Title
        Text(
                text = "Model Test Screen",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
        )

        if (isLoading) {
            Text("Loading test data...")
        } else if (errorMessage != null) {
            Text(
                    text = errorMessage!!,
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium
            )
        } else {
            testBitmap?.let { bitmap ->
                // Test image with overlays
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                        // Test image
                        Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Test Image",
                                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
                                contentScale = ContentScale.Fit
                        )

                        // Ground truth overlay (green boxes)
                        GroundTruthOverlay(
                                groundTruthBoxes = groundTruthBoxes,
                                sourceWidth = bitmap.width,
                                sourceHeight = bitmap.height,
                                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                        )

                        // Detection overlay (blue boxes)
                        detection?.let { det ->
                            DetectionOverlay(
                                    detection = det,
                                    sourceWidth = bitmap.width,
                                    sourceHeight = bitmap.height,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                            )
                        }
                    }
                }

                // Results summary
                Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = Color.Black.copy(alpha = 0.7f)
                                )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                                text = "Test Results",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text(
                                text = "Image: ${bitmap.width}x${bitmap.height}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                                text = "Ground Truth: ${groundTruthBoxes.size} objects",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                        )

                        detection?.let { det ->
                            Text(
                                    text = "Detected: ${det.boundingBoxes.size} objects",
                                    color = Color.Blue,
                                    style = MaterialTheme.typography.bodyMedium
                            )

                            if (det.boundingBoxes.isNotEmpty()) {
                                val avgConfidence =
                                        det.boundingBoxes.map { it.confidence }.average()
                                Text(
                                        text = "Avg Confidence: ${"%.3f".format(avgConfidence)}",
                                        color = Color.Blue,
                                        style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                                ?: Text(
                                        text = "Detection: Failed",
                                        color = Color.Red,
                                        style = MaterialTheme.typography.bodyMedium
                                )
                    }
                }
            }
        }
    }
}
