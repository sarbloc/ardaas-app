package io.sarbloc.ardaas

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.sarbloc.ardaas.ui.AppRoot
import io.sarbloc.ardaas.ui.ArdaasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bars must be styled dark
        // explicitly. The default follows the DEVICE theme: on a light-mode
        // phone it draws dark status-bar icons over our dark background,
        // leaving them all but invisible.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ArdaasTheme {
                AppRoot()
            }
        }
    }
}
