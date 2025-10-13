package link.sciber.foofinder

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import link.sciber.foofinder.presentation.DatasetScreen
import link.sciber.foofinder.presentation.DetectorScreen
import link.sciber.foofinder.presentation.ExampleScreen
import link.sciber.foofinder.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!hasRequiredPermissions()) {
            ActivityCompat.requestPermissions(this, CAMERAX_PERMISSIONS, 0)
        }

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    FooFinderNavigation()
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return CAMERAX_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                applicationContext, it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private val CAMERAX_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
        )
    }
}

@Composable
fun FooFinderNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController, startDestination = "detector"
    ) {
        composable("detector") {
            DetectorScreen(onNavigateToImageViewer = { imageUri, fileName ->
                val encodedUri = Uri.encode(imageUri)
                val encodedFileName = Uri.encode(fileName)
                navController.navigate("image_viewer/$encodedUri/$encodedFileName") {
                    launchSingleTop = true
                }
            }, onNavigateToDataset = {
                navController.navigate("dataset") {
                    launchSingleTop = true
                }
            })
        }

        composable("dataset") {
            DatasetScreen(onNavigateToExample = { imageUri, fileName ->
                val encodedUri = Uri.encode(imageUri)
                val encodedFileName = Uri.encode(fileName)
                navController.navigate("image_viewer/$encodedUri/$encodedFileName") {
                    launchSingleTop = true
                }
            }, onNavigateToDetector = {
                // Safe pop back to detector - only pop if not already at start
                if (navController.currentBackStackEntry?.destination?.route != "detector") {
                    navController.popBackStack("detector", inclusive = false)
                }
            })
        }

        composable(
            route = "image_viewer/{imageUri}/{fileName}",
            arguments = listOf(
                navArgument("imageUri") { type = NavType.StringType },
                navArgument("fileName") { type = NavType.StringType })
        ) { backStackEntry ->
            val imageUri = backStackEntry.arguments?.getString("imageUri")
            val fileName = backStackEntry.arguments?.getString("fileName")

            if (imageUri != null && fileName != null) {
                ExampleScreen(
                    imageUri = Uri.decode(imageUri),
                    fileName = Uri.decode(fileName),
                    onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}
