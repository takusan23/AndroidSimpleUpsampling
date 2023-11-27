package io.github.takusan23.androidsimpleupsampling

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AudioProcessorProgress {
    Idle,
    Decode,
    Upsampling,
    Encode,
    Complete
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) }) }
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val audioFileUri = remember { mutableStateOf<Uri?>(null) }
        val outSamplingRate = remember { mutableIntStateOf(48_000) }
        val currentState = remember { mutableStateOf(AudioProcessorProgress.Idle) }

        val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { audioFileUri.value = it }
        )

        fun upsampling() {
            scope.launch(Dispatchers.Main) {
                // データを作る
                val uri = audioFileUri.value ?: return@launch
                // 一時的なファイル
                val decodeFile = context.getExternalFilesDir(null)!!.resolve("decode_file")
                val upsamplingFile = context.getExternalFilesDir(null)!!.resolve("upsampling_file")
                val encodeFile = context.getExternalFilesDir(null)!!.resolve("upsampling_${System.currentTimeMillis()}.aac")

                // 変換元、変換先
                val outSamplingRate = outSamplingRate.intValue
                val inSamplingRate = AudioProcessor.extractSamplingRate(
                    context = context,
                    uri = uri,
                )

                // デコードする
                currentState.value = AudioProcessorProgress.Decode
                AudioProcessor.decodeAudio(
                    context = context,
                    uri = uri,
                    outputFile = decodeFile
                )

                // アップサンプリングする
                currentState.value = AudioProcessorProgress.Upsampling
                AudioProcessor.upsampling(
                    inFile = decodeFile,
                    outFile = upsamplingFile,
                    inSamplingRate = inSamplingRate,
                    outSamplingRate = outSamplingRate
                )

                // アップサンプリングしたデータをエンコードする
                currentState.value = AudioProcessorProgress.Encode
                AudioProcessor.encodeAudio(
                    rawFile = upsamplingFile,
                    resultFile = encodeFile,
                    sampleRate = outSamplingRate
                )

                // 消す
                withContext(Dispatchers.IO) {
                    decodeFile.delete()
                    upsamplingFile.delete()
                }

                currentState.value = AudioProcessorProgress.Complete
            }
        }

        Column(
            modifier = Modifier
                .padding(it)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Button(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
                Text(text = "アップサンプリングしたい音声ファイルを選ぶ")
            }

            OutlinedTextField(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                label = { Text(text = "変換後のサンプリングレート") },
                value = outSamplingRate.intValue.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.also { int ->
                        outSamplingRate.intValue = int
                    }
                }
            )

            if (audioFileUri.value != null) {
                Text(text = "ファイル = ${audioFileUri.value}")
                Button(onClick = { upsampling() }) {
                    Text(text = "処理を開始")
                }
            }

            Text(text = "現在の状態 = ${currentState.value}")
        }
    }
}