package io.sarbloc.ardaas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.sarbloc.ardaas.ui.AppRoot
import io.sarbloc.ardaas.ui.ArdaasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArdaasTheme {
                AppRoot()
            }
        }
    }
}
