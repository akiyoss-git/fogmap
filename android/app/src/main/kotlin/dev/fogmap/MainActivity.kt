package dev.fogmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dev.fogmap.map.MapScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FogmapApp
        setContent {
            MaterialTheme {
                MapScreen(app.fogStore, app.routingClient, app.syncRepository, app.socialRepository)
            }
        }
    }
}
