package com.sd.demo.loader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.sd.demo.loader.theme.AppTheme
import com.sd.lib.loader.FLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class SampleActivity : ComponentActivity() {
   private val _loader = FLoader()

   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      setContent {
         AppTheme {
            Content(
               onClick = {
                  lifecycleScope.launch {
                     load()
                  }
               }
            )
         }
      }
   }

   private suspend fun load() {
      val uuid = UUID.randomUUID().toString()
      logMsg { "$uuid load start" }

      _loader.load {
         delay(3_000)
      }.onSuccess {
         logMsg { "$uuid load onSuccess" }
      }.onFailure {
         logMsg { "$uuid load onFailure $it" }
      }
   }
}

@Composable
private fun Content(
   modifier: Modifier = Modifier,
   onClick: () -> Unit,
) {
   Column(
      modifier = modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
   ) {
      Button(onClick = onClick) {
         Text("click")
      }
   }
}