package dev.tohma.logger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tohma.logger.ui.activities.ActivitiesScreen
import dev.tohma.logger.ui.theme.LoggerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LoggerTheme {
                ActivitiesScreen()
            }
        }
    }
}
