/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2024 hua0512 (https://github.com/hua0512)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package github.hua0512.plugins.download.engines

import github.hua0512.data.media.VideoFormat
import github.hua0512.logger
import io.ktor.http.*
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Build the default ffmpeg input arguments
 * @author hua0512
 * @date : 2024/3/20 21:29
 */

private fun buildDefaultFFMpegInputArgs(
  headers: Map<String, String> = emptyMap(),
  cookies: String? = null,
  enableLogger: Boolean = false,
): Array<String> =
  mutableListOf<String>().apply {
    // ensure that the headers are properly separated
    if (headers.isNotEmpty()) {
      headers.forEach {
        val prefix = if (it.key == HttpHeaders.UserAgent) "-user_agent" else "-headers"
        add(prefix)
        add("${it.key}: ${it.value}")
      }
      // ensure that the headers are properly separated
      add("-headers")
      add("\r\n")
    }
    // add cookies if available
    if (cookies.isNullOrEmpty().not()) {
      add("-cookies")
      add(cookies!!)
    }
    add("-rw_timeout")
    add("20000000")
    if (enableLogger) {
      add("-loglevel")
      add("debug")
    }
  }.toTypedArray()

private fun buildDefaultFFMpegOutputArgs(
  downloadFormat: VideoFormat,
  segmentPart: Long,
  segmentTime: Long?,
  useSegmentation: Boolean,
): Array<String> {
  return mutableListOf<String>().apply {
    if (downloadFormat == VideoFormat.avi) {
      add("-bsf:v")
      add("h264_mp4toannexb")
    }
    if (useSegmentation) {
      if (segmentPart != 0L) logger.debug("Ignoring segmentPart($segmentPart)s for segmentation, using segmentTime instead")
      // segment time, default is 2 hours
      val time = segmentTime ?: 2.toDuration(DurationUnit.HOURS).inWholeSeconds
      addAll(
        arrayOf(
          "-f",
          "segment",
          "-segment_time",
          time.toString(),
        )
      )
      if (downloadFormat == VideoFormat.mp4 || downloadFormat == VideoFormat.mov) {
        add("-segment_format_options")
        add("movflags=+faststart")
      }
      add("-reset_timestamps")
      add("1")
      add("-strftime")
      add("1")
    } else {
      // segment the file, according to the maxPartDuration
      if (segmentTime != null) {
        add("-to")
        add(segmentTime.toString())
      } else { // segment the file, according to the maxPartSize
        add("-fs")
        add(segmentPart.toString())
      }
    }
  }.toTypedArray()
}


private fun buildFFMpegRunningCmd(
  defaultFFmpegInputArgs: Array<String>,
  defaultFFmpegOutputArgs: Array<String>,
  downloadUrl: String,
  downloadFormat: VideoFormat,
  useSegmentation: Boolean = false,
): Array<String> = defaultFFmpegInputArgs + mutableListOf<String>().apply {
  add("-i")
  add(downloadUrl)
  addAll(defaultFFmpegOutputArgs)
  add("-c")
  add("copy")
  if (!useSegmentation) {
    add("-f")
    add(downloadFormat.ffmpegMuxer)
  }
}


fun buildFFMpegCmd(
  headers: Map<String, String> = emptyMap(),
  cookies: String? = null,
  downloadUrl: String,
  downloadFormat: VideoFormat,
  segmentPart: Long,
  segmentTime: Long?,
  useSegmentation: Boolean? = false,
  outputPath: String,
): Array<String> {
  // ffmpeg input args
  val defaultFFmpegInputArgs = buildDefaultFFMpegInputArgs(headers, cookies)

  // default output args
  val defaultFFmpegOutputArgs = buildDefaultFFMpegOutputArgs(downloadFormat, segmentPart, segmentTime, useSegmentation ?: false)
  // build the ffmpeg command
  return buildFFMpegRunningCmd(
    defaultFFmpegInputArgs,
    defaultFFmpegOutputArgs,
    downloadUrl,
    downloadFormat,
    useSegmentation ?: false
  ) + outputPath
}


fun processFFmpegOutputLine(
  line: String,
  streamer: String,
  lastSize: Long,
  onSegmentStarted: (String) -> Unit,
  onDownloadProgress: (Long, Long, String) -> Unit,
) {
  when {
    !line.startsWith("size=") -> {
      logger.info("$streamer - $line")
      // handle opening segment for writing
      if (line.startsWith("[segment @") && line.contains("Opening")) {
        // [segment @ 000001c2e7450a40] Opening '2024-05-05_22-37-27.mp4' for writing
        // extract the segment name
        val segmentName = line.substringAfter('\'').substringBefore('\'')
        onSegmentStarted(segmentName)
      }
    }

    line.contains("time=") -> {
      //  size=     768kB time=00:00:02.70 bitrate=2330.2kbits/s speed=5.28x
      val sizeString = line.substringAfter("size=").substringBefore("time").trim()
      // extract the size in kB
      val size = sizeString.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0
      val diff = size - lastSize
      val bitrate = line.substringAfter("bitrate=").substringBefore("speed").trim()
      onDownloadProgress(size, diff, bitrate)
    }
  }
}