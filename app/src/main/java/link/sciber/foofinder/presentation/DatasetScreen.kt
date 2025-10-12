package link.sciber.foofinder.presentation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.sciber.foofinder.R
import link.sciber.foofinder.utils.ImageStorageManager
import link.sciber.foofinder.utils.SavedImage

/**
 * Screen displaying a grid of saved dataset images
 *
 * @param onNavigateToExample Callback when an image is tapped, provides imageUri and fileName
 * @param onNavigateToDetector Callback when radar button is tapped
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetScreen(
    onNavigateToExample: (imageUri: String, fileName: String) -> Unit,
    onNavigateToDetector: () -> Unit
) {
    val context = LocalContext.current
    var savedImages by remember { mutableStateOf<List<SavedImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isNavigating by remember { mutableStateOf(false) }

    // Load saved images
    LaunchedEffect(Unit) {
        isLoading = true
        savedImages = withContext(Dispatchers.IO) {
            ImageStorageManager.getSavedImages(context)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                Text(
                    text = "Dataset", fontWeight = FontWeight.SemiBold
                )
            }, actions = {
                IconButton(
                    onClick = {
                        // Prevent concurrent navigation
                        if (!isNavigating) {
                            isNavigating = true
                            onNavigateToDetector()
                        }
                    }, enabled = !isNavigating
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.radar_24),
                        contentDescription = "Detector"
                    )
                }
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
            )
        }) { paddingValues ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            savedImages.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No images yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Capture images from the detector screen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(savedImages, key = { it.uri.toString() }) { image ->
                        ImageThumbnail(
                            savedImage = image, onClick = {
                                onNavigateToExample(image.uri.toString(), image.displayName)
                            })
                    }
                }
            }
        }
    }
}

/**
 * Thumbnail composable for a saved image
 */
@Composable
private fun ImageThumbnail(
    savedImage: SavedImage, onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    // Load thumbnail
    LaunchedEffect(savedImage.uri) {
        isLoading = true
        thumbnail = withContext(Dispatchers.IO) {
            try {
                // Read entire stream into byte array first to avoid stream positioning issues
                val bytes = context.contentResolver.openInputStream(savedImage.uri)?.use { stream ->
                    stream.readBytes()
                }

                bytes?.let {
                    // Decode with inSampleSize for memory efficiency
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4 // Downsample to 1/4 size
                    }
                    BitmapFactory.decodeByteArray(it, 0, it.size, options)
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "DatasetScreen",
                    "Failed to load thumbnail for ${savedImage.displayName}",
                    e
                )
                null
            }
        }
        isLoading = false
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = {
                // Debounce clicks to prevent double-tap issues
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime > 500) {
                    lastClickTime = currentTime
                    onClick()
                }
            }), contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary
                )
            }

            thumbnail != null -> {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = savedImage.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            else -> {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
