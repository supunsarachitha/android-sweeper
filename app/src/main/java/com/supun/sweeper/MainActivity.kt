package com.supun.sweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.supun.sweeper.ui.HomeScreen
import com.supun.sweeper.ui.theme.SweeperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SweeperTheme {
                HomeScreen()
            }
        }
    }
}
