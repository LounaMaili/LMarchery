package com.lmarchery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lmarchery.ui.theme.LMarcheryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LMarcheryTheme {
                LMarcheryApp()
            }
        }
    }
}
