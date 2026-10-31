package app.homeeye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.homeeye.ui.edit.EditCameraScreen
import app.homeeye.ui.list.CameraListScreen
import app.homeeye.ui.live.LiveScreen
import app.homeeye.ui.theme.HomeEyeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeEyeTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        CameraListScreen(
                            onOpen = { nav.navigate("live/$it") },
                            onAdd = { nav.navigate("edit") },
                            onEdit = { nav.navigate("edit?id=$it") },
                        )
                    }
                    composable(
                        "edit?id={id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { entry ->
                        EditCameraScreen(
                            cameraId = entry.arguments?.getString("id"),
                            onDone = { nav.popBackStack() },
                        )
                    }
                    composable("live/{id}") { entry ->
                        LiveScreen(
                            cameraId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onEdit = { nav.navigate("edit?id=$it") },
                        )
                    }
                }
            }
        }
    }
}
