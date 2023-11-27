package io.github.takusan23.androidsimpleupsampling

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor

/** 音声の操作をするユーティリティ関数たち */
object AudioProcessor {

    /**
     * Uri からサンプリングレートを取得する
     *
     * @param context [Context]
     * @param uri [Uri]
     * @return サンプリングレート
     */
    suspend fun extractSamplingRate(
        context: Context,
        uri: Uri
    ) = withContext(Dispatchers.IO) {
        val (extractor, mediaFormat) = context.contentResolver.openFileDescriptor(uri, "r")!!.use {
            createMediaExtractor(it.fileDescriptor)
        }
        val samplingRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        extractor.release()
        return@withContext samplingRate
    }

    /**
     * flac aac 等を PCM にする。デコードする。
     *
     * @param context [Context]
     * @param uri [Uri]
     * @param outputFile PCM データ出力先ファイル
     */
    suspend fun decodeAudio(
        context: Context,
        uri: Uri,
        outputFile: File,
    ) = withContext(Dispatchers.IO) {
        // Uri からデータを取り出す
        val (extractor, mediaFormat) = context.contentResolver.openFileDescriptor(uri, "r")!!.use {
            createMediaExtractor(it.fileDescriptor)
        }
        // デコーダーにメタデータを渡す
        val audioDecoder = AudioDecoder().apply {
            prepareDecoder(mediaFormat)
        }
        // ファイルに書き込む準備
        outputFile.outputStream().use { outputStream ->
            // デコードする
            audioDecoder.startAudioDecode(
                readSampleData = { byteBuffer ->
                    // データを進める
                    val size = extractor.readSampleData(byteBuffer, 0)
                    extractor.advance()
                    size to extractor.sampleTime
                },
                onOutputBufferAvailable = { bytes ->
                    // データを書き込む
                    outputStream.write(bytes)
                }
            )
        }
    }

    /**
     * PCM データをエンコードする
     *
     * @param rawFile PCM データ
     * @param resultFile 出力先ファイル
     * @param sampleRate サンプリングレート
     */
    suspend fun encodeAudio(
        rawFile: File,
        resultFile: File,
        sampleRate: Int
    ) = withContext(Dispatchers.Default) {
        // エンコーダーを初期化
        val audioEncoder = AudioEncoder().apply {
            prepareEncoder(
                codec = MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate = sampleRate,
                channelCount = 2,
                bitRate = 192_000
            )
        }
        // コンテナフォーマットに保存していくやつ
        val mediaMuxer = MediaMuxer(resultFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        rawFile.inputStream().use { inputStream ->
            audioEncoder.startAudioEncode(
                onRecordInput = { bytes ->
                    // データをエンコーダーに渡す
                    inputStream.read(bytes)
                },
                onOutputBufferAvailable = { byteBuffer, bufferInfo ->
                    // 無いと思うけど MediaMuxer が開始していなければ追加しない
                    if (trackIndex != -1) {
                        mediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                    }
                },
                onOutputFormatAvailable = {
                    // フォーマットが確定したら MediaMuxer を開始する
                    trackIndex = mediaMuxer.addTrack(it)
                    mediaMuxer.start()
                }
            )
        }
        mediaMuxer.stop()
        mediaMuxer.release()
    }

    /**
     * アップサンプリングする
     *
     * @param inFile 元の PCM ファイル
     * @param outFile アップサンプリングした PCM ファイル出力先
     * @param inSamplingRate 元のサンプリングレート
     * @param outSamplingRate アップサンプリングしたいサンプリングレート
     */
    suspend fun upsampling(
        inFile: File,
        outFile: File,
        inSamplingRate: Int,
        outSamplingRate: Int
    ) = withContext(Dispatchers.IO) {
        inFile.inputStream().use { inputStream ->
            outFile.outputStream().use { outputStream ->
                while (isActive) {
                    // データを取り出す
                    val pcmByteArray = ByteArray(8192)
                    val size = inputStream.read(pcmByteArray)
                    if (size == -1) {
                        break
                    }
                    // 水増しする
                    val upsamplingData = simpleUpsampling(
                        pcmByteArray = pcmByteArray,
                        inSamplingRate = inSamplingRate,
                        outSamplingRate = outSamplingRate
                    )
                    outputStream.write(upsamplingData)
                }
            }
        }
    }

    /**
     * 音声フォルダにコピーする
     *
     * @param context [Context]
     * @param file ファイル
     */
    fun addAudioFolder(
        context: Context,
        file: File
    ) {
        val contentResolver = context.contentResolver
        val contentValues = contentValuesOf(
            MediaStore.MediaColumns.DISPLAY_NAME to file.name,
            // MediaStore.MediaColumns.RELATIVE_PATH は android 10 以降
            MediaStore.MediaColumns.RELATIVE_PATH to "${Environment.DIRECTORY_MUSIC}/AndroidSimpleUpsampling"
        )
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
        contentResolver.openOutputStream(uri!!)?.use { outputStream ->
            file.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    private suspend fun simpleUpsampling(
        pcmByteArray: ByteArray,
        inSamplingRate: Int,
        outSamplingRate: Int
    ) = withContext(Dispatchers.IO) {

        fun filterSingleChannel(pcmByteArray: ByteArray, startIndex: Int): ByteArray {
            // 元は 2 チャンネルだったので
            val singleChannelByteArray = ByteArray(pcmByteArray.size / 2)
            var readIndex = startIndex
            var writtenIndex = 0
            while (true) {
                singleChannelByteArray[writtenIndex++] = pcmByteArray[readIndex++]
                singleChannelByteArray[writtenIndex++] = pcmByteArray[readIndex++]
                // 次の 2 バイト分飛ばす
                // どういうことかと言うと 右左右左右左... で右だけほしい
                readIndex += 2
                // もうない場合
                if (pcmByteArray.size <= readIndex) {
                    break
                }
            }
            return singleChannelByteArray
        }

        fun upsampling(singleChannelPcm: ByteArray, inSamplingRate: Int, outSamplingRate: Int): ByteArray {
            // 返すデータ。元のサイズに倍率をかけて増やしておく
            val resultSingleChannelPcm = ByteArray((singleChannelPcm.size * (outSamplingRate / inSamplingRate.toFloat())).toInt())
            // 足りない分
            val diffSize = resultSingleChannelPcm.size - singleChannelPcm.size
            val addIndex = singleChannelPcm.size / diffSize
            // データを入れていく
            var writtenIndex = 0
            var readIndex = 0
            while (true) {
                // 1つのサンプルは 2byte で表現されているため、2バイトずつ扱う必要がある
                val byte1 = singleChannelPcm[readIndex++]
                val byte2 = singleChannelPcm[readIndex++]
                resultSingleChannelPcm[writtenIndex++] = byte1
                resultSingleChannelPcm[writtenIndex++] = byte2
                // 足りない分を入れる必要がある
                // ただ前回の値をもう一回使ってるだけ
                if (readIndex % addIndex == 0) {
                    resultSingleChannelPcm[writtenIndex++] = byte1
                    resultSingleChannelPcm[writtenIndex++] = byte2
                }
                if (singleChannelPcm.size <= readIndex) {
                    break
                }
                if (resultSingleChannelPcm.size <= writtenIndex) {
                    break
                }
            }
            return resultSingleChannelPcm
        }

        // 2 チャンネルしか想定していないので、右と左で予め分けておく
        // 右右左左右右左左... みたいに入ってて、それぞれ分ける必要がある
        val singleChannelPcm1 = filterSingleChannel(pcmByteArray, 0)
        val singleChannelPcm2 = filterSingleChannel(pcmByteArray, 2)

        // アップサンプリングにより、元のサンプリングレートにはなかった間を埋める
        val singleChannelUpscalingPcm1 = upsampling(singleChannelPcm1, inSamplingRate, outSamplingRate)
        val singleChannelUpscalingPcm2 = upsampling(singleChannelPcm2, inSamplingRate, outSamplingRate)

        // 右左、交互にデータを入れていく
        val resultPcm = ByteArray((pcmByteArray.size * (outSamplingRate / inSamplingRate.toFloat())).toInt())
        var writtenIndex = 0
        var readIndex1 = 0
        var readIndex2 = 0
        // 2 チャンネル、2 バイトずつ
        while (true) {
            resultPcm[writtenIndex++] = singleChannelUpscalingPcm1[readIndex1++]
            resultPcm[writtenIndex++] = singleChannelUpscalingPcm1[readIndex1++]
            resultPcm[writtenIndex++] = singleChannelUpscalingPcm2[readIndex2++]
            resultPcm[writtenIndex++] = singleChannelUpscalingPcm2[readIndex2++]
            // データがなくなったら return
            if (singleChannelUpscalingPcm1.size <= readIndex1) {
                break
            }
        }

        return@withContext resultPcm
    }

    private fun createMediaExtractor(fileDescriptor: FileDescriptor): Pair<MediaExtractor, MediaFormat> {
        // コンテナフォーマットからデータを取り出すやつ
        val extractor = MediaExtractor().apply {
            setDataSource(fileDescriptor)
        }
        // 音声トラックを見つける
        // 音声ファイルなら、音声トラックしか無いはずなので、0 決め打ちでも良さそう
        val audioTrackIndex = (0 until extractor.trackCount)
            .first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        extractor.selectTrack(audioTrackIndex)
        return extractor to extractor.getTrackFormat(audioTrackIndex)
    }

}