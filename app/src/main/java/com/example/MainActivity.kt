package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Models ---
data class SplitRecord(
    val folderName: String,
    val displayName: String,
    val originalFileName: String,
    val timestamp: Long,
    val totalPages: Int,
    val oddFile: File,
    val evenFile: File
)

// --- States ---
sealed interface SplitState {
    object Idle : SplitState
    object Processing : SplitState
    data class Success(val record: SplitRecord) : SplitState
    data class Error(val message: String) : SplitState
}

// --- ViewModel ---
class SplitViewModel : ViewModel() {
    private val _splitState = MutableStateFlow<SplitState>(SplitState.Idle)
    val splitState: StateFlow<SplitState> = _splitState.asStateFlow()

    private val _history = MutableStateFlow<List<SplitRecord>>(emptyList())
    val history: StateFlow<List<SplitRecord>> = _history.asStateFlow()

    fun loadHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val records = loadRecordsFromStorage(context)
            _history.value = records
        }
    }

    fun deleteRecord(context: Context, record: SplitRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            val splitsDir = File(context.filesDir, "splits")
            val targetFolder = File(splitsDir, record.folderName)
            if (targetFolder.exists()) {
                targetFolder.deleteRecursively()
            }
            // Reload history
            val records = loadRecordsFromStorage(context)
            _history.value = records
            
            // If current active split is deleted, reset state
            val current = _splitState.value
            if (current is SplitState.Success && current.record.folderName == record.folderName) {
                _splitState.value = SplitState.Idle
            }
        }
    }

    fun splitPdf(context: Context, uri: Uri) {
        _splitState.value = SplitState.Processing
        viewModelScope.launch(Dispatchers.Default) {
            val originalName = getFileNameFromUri(context, uri) ?: "document.pdf"
            val baseName = originalName.substringBeforeLast(".")
            val ext = originalName.substringAfterLast(".", "").lowercase()
            val timestamp = System.currentTimeMillis()
            val folderName = "${baseName.replace(" ", "_")}_$timestamp"

            // Create target folder under context.filesDir/splits/
            val splitsDir = File(context.filesDir, "splits")
            val outputFolder = File(splitsDir, folderName)
            if (!outputFolder.exists()) {
                outputFolder.mkdirs()
            }

            // Exquisitely named output files as requested: [OriginalFileName]_OddPages.pdf and [OriginalFileName]_EvenPages.pdf
            val oddFile = File(outputFolder, "${baseName}_OddPages.pdf")
            val evenFile = File(outputFolder, "${baseName}_EvenPages.pdf")

            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var oddDocument: PdfDocument? = null
            var evenDocument: PdfDocument? = null

            try {
                if (ext != "pdf") {
                    throw Exception("Unsupported file type. Please select a PDF file.")
                }

                // Load selected PDF file
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Could not open file descriptor for splitting page.")

                renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                if (totalPages <= 0) {
                    throw Exception("The selected document file has no pages or is empty.")
                }

                oddDocument = PdfDocument()
                evenDocument = PdfDocument()

                var oddCount = 0
                var evenCount = 0

                for (i in 0 until totalPages) {
                    val page = renderer.openPage(i)
                    val width = page.width
                    val height = page.height

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val isOdd = (i + 1) % 2 != 0
                    if (isOdd) {
                        val pageInfo = PdfDocument.PageInfo.Builder(width, height, oddCount).create()
                        val newPage = oddDocument.startPage(pageInfo)
                        newPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        oddDocument.finishPage(newPage)
                        oddCount++
                    } else {
                        val pageInfo = PdfDocument.PageInfo.Builder(width, height, evenCount).create()
                        val newPage = evenDocument.startPage(pageInfo)
                        newPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        evenDocument.finishPage(newPage)
                        evenCount++
                    }
                    bitmap.recycle()
                }

                if (oddCount > 0) {
                    FileOutputStream(oddFile).use { out ->
                        oddDocument.writeTo(out)
                    }
                }
                if (evenCount > 0) {
                    FileOutputStream(evenFile).use { out ->
                        evenDocument.writeTo(out)
                    }
                }

                val record = SplitRecord(
                    folderName = folderName,
                    displayName = baseName.replace("_", " "),
                    originalFileName = originalName,
                    timestamp = timestamp,
                    totalPages = totalPages,
                    oddFile = oddFile,
                    evenFile = evenFile
                )

                withContext(Dispatchers.Main) {
                    _splitState.value = SplitState.Success(record)
                    val records = loadRecordsFromStorage(context)
                    _history.value = records
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _splitState.value = SplitState.Error(e.message ?: "An unknown separation error occurred.")
                }
            } finally {
                try { renderer?.close() } catch (e: Exception) { /* ignore */ }
                try { pfd?.close() } catch (e: Exception) { /* ignore */ }
                try { oddDocument?.close() } catch (e: Exception) { /* ignore */ }
                try { evenDocument?.close() } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    fun selectHistoryRecord(record: SplitRecord) {
        _splitState.value = SplitState.Success(record)
    }

    fun resetState() {
        _splitState.value = SplitState.Idle
    }

    data class DownloadInfo(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val originalUrl: String = "",
        val error: String? = null
    )

    private val _downloadState = MutableStateFlow<DownloadInfo>(DownloadInfo())
    val downloadState: StateFlow<DownloadInfo> = _downloadState.asStateFlow()

    fun resetDownloadState() {
        _downloadState.value = DownloadInfo()
    }

    fun handleSharedIntent(context: Context, intent: Intent) {
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    val url = extractUrlFromText(sharedText)
                    if (url != null) {
                        downloadAndSplitUrl(context, url)
                        return
                    }
                }
            }
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (uri != null) {
                splitPdf(context, uri)
                return
            }
        } else if (Intent.ACTION_VIEW == action) {
            val uri = intent.data
            if (uri != null) {
                splitPdf(context, uri)
                return
            }
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val regex = "(https?://[\\w.\\-]+(?:/[\\w.\\-?%#&=/]*)?)".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        return match?.value
    }

    fun downloadAndSplitUrl(context: Context, urlString: String) {
        _downloadState.value = DownloadInfo(
            isDownloading = true,
            progress = 0f,
            status = "Connecting to server...",
            originalUrl = urlString
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    val status = "Server returned error: ${connection.responseCode} ${connection.responseMessage}"
                    _downloadState.value = DownloadInfo(error = status)
                    return@launch
                }

                val fileLength = connection.contentLength
                val originalName = urlString.substringAfterLast("/").substringBefore("?").ifEmpty { "document.pdf" }
                val ext = if (originalName.contains(".")) originalName.substringAfterLast(".") else "pdf"
                val base = if (originalName.contains(".")) originalName.substringBeforeLast(".") else "document"

                val secureBase = base.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val tempFile = File(context.cacheDir, "${secureBase}_online_temp_${System.currentTimeMillis()}.$ext")

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val data = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        var lastUpdate = 0L
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val currentPct = total.toFloat() / fileLength.toFloat()
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 100) {
                                    _downloadState.value = _downloadState.value.copy(
                                        progress = currentPct,
                                        status = "Downloading online file..."
                                    )
                                    lastUpdate = now
                                }
                            }
                            output.write(data, 0, count)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _downloadState.value = DownloadInfo() // Reset dialog
                    splitPdf(context, Uri.fromFile(tempFile))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _downloadState.value = DownloadInfo(error = "Failed to download file: ${e.localizedMessage}")
                }
            }
        }
    }

    // Helper to list all split records stored inside the app directory
    private fun loadRecordsFromStorage(context: Context): List<SplitRecord> {
        val splitsDir = File(context.filesDir, "splits")
        if (!splitsDir.exists()) return emptyList()

        val folders = splitsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        val records = mutableListOf<SplitRecord>()

        for (folder in folders) {
            val folderName = folder.name
            val timestampStr = folderName.substringAfterLast("_", "")
            val timestamp = timestampStr.toLongOrNull() ?: 0L

            val filesInFolder = folder.listFiles() ?: emptyArray()
            val oddFile = filesInFolder.firstOrNull { it.name.endsWith("_OddPages.pdf") || it.name == "odd_pages.pdf" }
            val evenFile = filesInFolder.firstOrNull { it.name.endsWith("_EvenPages.pdf") || it.name == "even_pages.pdf" }

            if (oddFile != null && oddFile.exists()) {
                val basePart = folderName.substringBeforeLast("_")
                val originalName = "$basePart.pdf"
                records.add(
                    SplitRecord(
                        folderName = folderName,
                        displayName = basePart.replace("_", " "),
                        originalFileName = originalName,
                        timestamp = timestamp,
                        totalPages = getPageCountSafely(oddFile) + getPageCountSafely(evenFile ?: File(folder, "dummy")),
                        oddFile = oddFile,
                        evenFile = evenFile ?: File(folder, "even_pages.pdf")
                    )
                )
            }
        }
        return records.sortedByDescending { it.timestamp }
    }

    private fun getPageCountSafely(file: File): Int {
        if (!file.exists()) return 0
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) {
            0
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }
}

// --- Word, Excel, PowerPoint to PDF Converters ---
fun wrapText(text: String, paint: android.graphics.Paint, maxWidth: Int): List<String> {
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var currentLine = StringBuilder()
    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
        val width = paint.measureText(testLine)
        if (width <= maxWidth) {
            currentLine.append(if (currentLine.isEmpty()) word else " $word")
        } else {
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
            currentLine = StringBuilder(word)
        }
    }
    if (currentLine.isNotEmpty()) {
        lines.add(currentLine.toString())
    }
    return lines
}

fun truncateText(text: String, paint: android.graphics.Paint, maxWidth: Int): String {
    val testWidth = paint.measureText(text)
    if (testWidth <= maxWidth) return text
    var truncated = text
    while (truncated.isNotEmpty() && paint.measureText("$truncated...") > maxWidth) {
        truncated = truncated.dropLast(1)
    }
    return if (truncated.isEmpty()) "" else "$truncated..."
}

fun convertDocxToPdf(context: Context, uri: Uri, outputFile: File) {
    val pdfDoc = PdfDocument()
    val paragraphs = mutableListOf<String>()
    
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val zipInput = java.util.zip.ZipInputStream(inputStream)
        var entry = zipInput.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val bytes = zipInput.readBytes()
                val xml = String(bytes, Charsets.UTF_8)
                val textPattern = "<w:t[^>]*>(.*?)</w:t>".toRegex()
                val pPattern = "<w:p[^>]*>(.*?)</w:p>".toRegex()
                
                pPattern.findAll(xml).forEach { pMatch ->
                    val pXml = pMatch.groupValues[1]
                    val pText = textPattern.findAll(pXml)
                        .map { it.groupValues[1] }
                        .joinToString("")
                    if (pText.isNotBlank()) {
                        paragraphs.add(pText)
                    }
                }
            }
            zipInput.closeEntry()
            entry = zipInput.nextEntry
        }
    }
    
    if (paragraphs.isEmpty()) {
        paragraphs.add("Empty DOCX or text could not be extracted.")
    }
    
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 14f
        isAntiAlias = true
    }
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#0284C7")
        textSize = 18f
        isFakeBoldText = true
        isAntiAlias = true
    }
    
    val width = 595
    val height = 842
    val margin = 50
    var currentY = margin + 40
    
    var pageCount = 0
    var pageInfo = PdfDocument.PageInfo.Builder(width, height, pageCount).create()
    var currentPage = pdfDoc.startPage(pageInfo)
    var canvas = currentPage.canvas
    
    // Header
    canvas.drawText("Word Export: ${outputFile.name.substringBeforeLast("_")}", margin.toFloat(), margin.toFloat() + 10f, titlePaint)
    canvas.drawLine(margin.toFloat(), margin.toFloat() + 20f, (width - margin).toFloat(), margin.toFloat() + 20f, paint)
    
    for (p in paragraphs) {
        val lines = wrapText(p, paint, width - 2 * margin)
        for (line in lines) {
            if (currentY + 20 > height - margin) {
                pdfDoc.finishPage(currentPage)
                pageCount++
                pageInfo = PdfDocument.PageInfo.Builder(width, height, pageCount).create()
                currentPage = pdfDoc.startPage(pageInfo)
                canvas = currentPage.canvas
                currentY = margin
            }
            canvas.drawText(line, margin.toFloat(), currentY.toFloat(), paint)
            currentY += 20
        }
        currentY += 10
    }
    
    pdfDoc.finishPage(currentPage)
    
    FileOutputStream(outputFile).use { out ->
        pdfDoc.writeTo(out)
    }
    pdfDoc.close()
}

fun convertPptxToPdf(context: Context, uri: Uri, outputFile: File) {
    val pdfDoc = PdfDocument()
    val slidesText = mutableMapOf<Int, MutableList<String>>()
    
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val zipInput = java.util.zip.ZipInputStream(inputStream)
        var entry = zipInput.nextEntry
        while (entry != null) {
            if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                val numStr = entry.name.removePrefix("ppt/slides/slide").removeSuffix(".xml")
                val slideNum = numStr.toIntOrNull() ?: 1
                
                val bytes = zipInput.readBytes()
                val xml = String(bytes, Charsets.UTF_8)
                val textPattern = "<a:t[^>]*>(.*?)</a:t>".toRegex()
                val texts = textPattern.findAll(xml).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
                
                if (texts.isNotEmpty()) {
                    slidesText.getOrPut(slideNum) { mutableListOf() }.addAll(texts)
                }
            }
            zipInput.closeEntry()
            entry = zipInput.nextEntry
        }
    }
    
    val sortedSlides = slidesText.entries.sortedBy { it.key }
    
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 16f
        isAntiAlias = true
    }
    val slideTitlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#0284C7")
        textSize = 24f
        isFakeBoldText = true
        isAntiAlias = true
    }
    
    val width = 720
    val height = 540
    val margin = 50
    
    var pageCount = 0
    if (sortedSlides.isEmpty()) {
        val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageCount).create()
        val currentPage = pdfDoc.startPage(pageInfo)
        currentPage.canvas.drawText("Presentation Slide Export", margin.toFloat(), margin.toFloat() + 40f, paint)
        pdfDoc.finishPage(currentPage)
    } else {
        for ((slideIndex, slide) in sortedSlides) {
            val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageCount).create()
            val currentPage = pdfDoc.startPage(pageInfo)
            val canvas = currentPage.canvas
            
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#F8FAFC")
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            
            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#CBD5E1")
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRect(10f, 10f, (width - 10).toFloat(), (height - 10).toFloat(), borderPaint)
            
            canvas.drawText("Slide $slideIndex", margin.toFloat(), margin.toFloat() + 20f, slideTitlePaint)
            canvas.drawLine(margin.toFloat(), margin.toFloat() + 35f, (width - margin).toFloat(), margin.toFloat() + 35f, borderPaint.apply { strokeWidth = 1f })
            
            var currentY = margin + 70
            for (line in slide) {
                val wrappedLines = wrapText(line, paint, width - 2 * margin)
                for (wl in wrappedLines) {
                    if (currentY + 24 > height - margin) break
                    canvas.drawText(wl, margin.toFloat(), currentY.toFloat(), paint)
                    currentY += 24
                }
                currentY += 12
            }
            
            pdfDoc.finishPage(currentPage)
            pageCount++
        }
    }
    
    FileOutputStream(outputFile).use { out ->
        pdfDoc.writeTo(out)
    }
    pdfDoc.close()
}

fun convertXlsxToPdf(context: Context, uri: Uri, outputFile: File) {
    val pdfDoc = PdfDocument()
    val tableRows = mutableListOf<List<String>>()
    val sharedStrings = mutableListOf<String>()
    
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val zipInput = java.util.zip.ZipInputStream(inputStream)
        var entry = zipInput.nextEntry
        var xmlSharedStrings = ""
        val sheetXmls = mutableMapOf<String, String>()
        
        while (entry != null) {
            if (entry.name == "xl/sharedStrings.xml") {
                xmlSharedStrings = String(zipInput.readBytes(), Charsets.UTF_8)
            } else if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) {
                sheetXmls[entry.name] = String(zipInput.readBytes(), Charsets.UTF_8)
            }
            zipInput.closeEntry()
            entry = zipInput.nextEntry
        }
        
        if (xmlSharedStrings.isNotEmpty()) {
            val tPattern = "<t[^>]*>(.*?)</t>".toRegex()
            tPattern.findAll(xmlSharedStrings).forEach { match ->
                sharedStrings.add(match.groupValues[1])
            }
        }
        
        val firstSheetXml = sheetXmls.entries.sortedBy { it.key }.firstOrNull()?.value
        if (firstSheetXml != null) {
            val rowPattern = "<row[^>]*>(.*?)</row>".toRegex()
            rowPattern.findAll(firstSheetXml).forEach { rowMatch ->
                val rowInnerXml = rowMatch.groupValues[1]
                val cells = mutableListOf<String>()
                val colPattern = "<c[^>]*>(.*?)</c>".toRegex()
                colPattern.findAll(rowInnerXml).forEach { colMatch ->
                    val colXml = colMatch.value
                    val isSharedStr = colXml.contains("t=\"s\"")
                    val vPattern = "<v>([^<]+)</v>".toRegex()
                    val vMatch = vPattern.find(colXml)
                    if (vMatch != null) {
                        val value = vMatch.groupValues[1]
                        if (isSharedStr) {
                            val idx = value.toIntOrNull() ?: -1
                            if (idx >= 0 && idx < sharedStrings.size) {
                                cells.add(sharedStrings[idx])
                            } else {
                                cells.add(value)
                            }
                        } else {
                            cells.add(value)
                        }
                    } else {
                        cells.add("")
                    }
                }
                if (cells.any { it.isNotBlank() }) {
                    tableRows.add(cells)
                }
            }
        }
    }
    
    if (tableRows.isEmpty()) {
        tableRows.add(listOf("Empty Spreadsheet or parsing was empty."))
    }
    
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 11f
        isAntiAlias = true
    }
    val headerPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 12f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val thBackground = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#0284C7")
    }
    val gridPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#CBD5E1")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f
    }
    
    val width = 842
    val height = 595
    val margin = 40
    
    var pageCount = 0
    var pageInfo = PdfDocument.PageInfo.Builder(width, height, pageCount).create()
    var currentPage = pdfDoc.startPage(pageInfo)
    var canvas = currentPage.canvas
    
    var currentY = margin + 30
    
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#0369A1")
        textSize = 18f
        isFakeBoldText = true
        isAntiAlias = true
    }
    canvas.drawText("Spreadsheet Export: ${outputFile.name.substringBeforeLast("_")}", margin.toFloat(), margin.toFloat() + 10f, titlePaint)
    
    for ((rowIndex, rowData) in tableRows.withIndex()) {
        if (currentY + 24 > height - margin) {
            pdfDoc.finishPage(currentPage)
            pageCount++
            pageInfo = PdfDocument.PageInfo.Builder(width, height, pageCount).create()
            currentPage = pdfDoc.startPage(pageInfo)
            canvas = currentPage.canvas
            currentY = margin
        }
        
        val rowHeight = 24
        val numCols = minOf(rowData.size, 8)
        if (numCols == 0) continue
        
        val colWidth = (width - 2 * margin) / numCols
        
        for (colIndex in 0 until numCols) {
            val cellVal = rowData[colIndex]
            val cellLeft = margin + colIndex * colWidth
            val cellTop = currentY
            val cellRight = cellLeft + colWidth
            val cellBottom = cellTop + rowHeight
            
            if (rowIndex == 0) {
                canvas.drawRect(cellLeft.toFloat(), cellTop.toFloat(), cellRight.toFloat(), cellBottom.toFloat(), thBackground)
                canvas.drawText(truncateText(cellVal, headerPaint, colWidth - 10), (cellLeft + 8).toFloat(), (cellTop + 16).toFloat(), headerPaint)
            } else {
                if (rowIndex % 2 == 0) {
                    val altPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#F1F5F9") }
                    canvas.drawRect(cellLeft.toFloat(), cellTop.toFloat(), cellRight.toFloat(), cellBottom.toFloat(), altPaint)
                }
                canvas.drawRect(cellLeft.toFloat(), cellTop.toFloat(), cellRight.toFloat(), cellBottom.toFloat(), gridPaint)
                canvas.drawText(truncateText(cellVal, paint, colWidth - 10), (cellLeft + 8).toFloat(), (cellTop + 16).toFloat(), paint)
            }
        }
        currentY += rowHeight
    }
    
    pdfDoc.finishPage(currentPage)
    
    FileOutputStream(outputFile).use { out ->
        pdfDoc.writeTo(out)
    }
    pdfDoc.close()
}

// --- Document Print Adapter ---
class PdfPrintDocumentAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val builder = PrintDocumentInfo.Builder(file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
        val info = builder.build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out android.print.PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        try {
            input = FileInputStream(file)
            output = FileOutputStream(destination?.fileDescriptor)
            val buf = ByteArray(2048)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } >= 0) {
                output.write(buf, 0, bytesRead)
            }
            callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback?.onWriteFailed(e.toString())
        } finally {
            try { input?.close() } catch (e: Exception) {}
            try { output?.close() } catch (e: Exception) {}
        }
    }
}

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    private val viewModel: SplitViewModel by lazy {
        ViewModelProvider(this)[SplitViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        intent?.let { viewModel.handleSharedIntent(this, it) }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleSharedIntent(this, intent)
    }
}

// Global actions
fun printPdfFile(context: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "Split PDF file not found.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "${file.name.substringBeforeLast(".")}"
        printManager.print(jobName, PdfPrintDocumentAdapter(file), null)
    } catch (e: Exception) {
        Toast.makeText(context, "Printing error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun sharePdfFile(context: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "Split PDF file not found.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Split PDF"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun downloadPdfFile(context: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "File does not exist.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri).use { outputStream ->
                    FileInputStream(file).use { inputStream ->
                        inputStream.copyTo(outputStream!!)
                    }
                }
                Toast.makeText(context, "Saved to Downloads folder!", Toast.LENGTH_LONG).show()
            } else {
                saveFileLegacy(context, file)
            }
        } else {
            saveFileLegacy(context, file)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun saveFileLegacy(context: Context, file: File) {
    try {
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            file.name
        )
        FileInputStream(file).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        Toast.makeText(context, "Saved to Downloads: ${target.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Legacy save failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// --- UI Components ---
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SplitViewModel = viewModel()
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val splitState by viewModel.splitState.collectAsState()
    val history by viewModel.history.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    // Load list of previous splits on launch
    LaunchedEffect(Unit) {
        viewModel.loadHistory(context)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.splitPdf(context, uri)
        }
    }

    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        // --- Header Block ---
        HeaderBlock()

        // --- Navigation Tabs ---
        TabSelector(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Main Sliding/Swapping Area ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (selectedTab == 0) {
                // PDF Document Splitter Area
                PdfSplitterTab(
                    state = splitState,
                    history = history,
                    onReset = { viewModel.resetState() },
                    onSelectFile = {
                        launcher.launch(
                            arrayOf("application/pdf")
                        )
                    },
                    onSelectRecord = { viewModel.selectHistoryRecord(it) },
                    onDeleteRecord = { viewModel.deleteRecord(context, it) },
                    onPrint = { printPdfFile(context, it) },
                    onShare = { sharePdfFile(context, it) },
                    onDownload = { downloadPdfFile(context, it) }
                )
            } else {
                // Manual Command Helper Area
                ManualRangeTab()
            }
        }

        // --- Brand Footer Block ---
        FooterBlock()
    }

    // --- Overlay popups for background downloading or importing and split triggers ---
    DownloadPopup(
        info = downloadState,
        onDismiss = { viewModel.resetDownloadState() }
    )
}

@Composable
fun DownloadPopup(
    info: SplitViewModel.DownloadInfo,
    onDismiss: () -> Unit
) {
    if (info.isDownloading || info.error != null) {
        Dialog(onDismissRequest = { if (info.error != null) onDismiss() }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (info.error != null) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error status icon",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Import Failed",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = info.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Dismiss")
                        }
                    } else {
                        // Downloading status flow
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = info.progress,
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Importing Online File",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = info.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        LinearProgressIndicator(
                            progress = info.progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(info.progress * 100).toInt()}% imported",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.bentoPaddingVertical(), bottom = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            // High-fidelity App Launcher Icon Custom visual integration next to Title
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Duplex Printer Adaptive Icon logo",
                    tint = Color.Unspecified, // displays original blue/green vector colors perfectly
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "Duplex Printer",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "BY JOJO IT TEAM",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Automated page divider helper for dual-sided manual printing",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

// Added top margin helper
private fun Int.bentoPaddingVertical() = this.dp

@Composable
fun TabSelector(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val tab1Selected = selectedTab == 0
        val tab2Selected = selectedTab == 1

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (tab1Selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onTabSelected(0) }
                .padding(vertical = 12.dp)
                .testTag("tab_pdf_split"),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "PDF selector icon",
                    tint = if (tab1Selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Split PDF File",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (tab1Selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (tab2Selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onTabSelected(1) }
                .padding(vertical = 12.dp)
                .testTag("tab_manual_range"),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "Manual selector icon",
                    tint = if (tab2Selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Print Ranges",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (tab2Selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PdfSplitterTab(
    state: SplitState,
    history: List<SplitRecord>,
    onReset: () -> Unit,
    onSelectFile: () -> Unit,
    onSelectRecord: (SplitRecord) -> Unit,
    onDeleteRecord: (SplitRecord) -> Unit,
    onPrint: (File) -> Unit,
    onShare: (File) -> Unit,
    onDownload: (File) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        when (state) {
            is SplitState.Idle -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item {
                        UploadCard(onSelectFile = onSelectFile)
                    }

                    if (history.isNotEmpty()) {
                        item {
                            Text(
                                text = "Previous Page Splits",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }

                        items(history) { record ->
                            HistoryItem(
                                record = record,
                                onClick = { onSelectRecord(record) },
                                onDelete = { onDeleteRecord(record) }
                            )
                        }
                    } else {
                        item {
                            EmptyHistoryGraphic()
                        }
                    }
                }
            }

            is SplitState.Processing -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Analyzing & separating pages...",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Extracting odd/even documents",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            is SplitState.Success -> {
                SuccessSplitDisplay(
                    record = state.record,
                    onReset = onReset,
                    onPrint = onPrint,
                    onShare = onShare,
                    onDownload = onDownload
                )
            }

            is SplitState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Split error icon",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Separation Failed",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onReset,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Reset & Try Again")
                    }
                }
            }
        }
    }
}

@Composable
fun UploadCard(onSelectFile: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectFile() }
            .testTag("upload_zone"),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.onPrimaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Print,
                        contentDescription = "Upload document icon",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "SUPPORT: PDF",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Upload Document",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            Text(
                text = "Tap to select your file to split pages into odd/even documents instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun HistoryItem(
    record: SplitRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateString = remember(record.timestamp) {
        val sdf = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())
        sdf.format(Date(record.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("history_item_${record.folderName}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Success check indicator",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${record.totalPages} pages • $dateString",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_${record.folderName}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete record button",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun EmptyHistoryGraphic() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "No previous splits indicator",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No Separation History Yet",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun SuccessSplitDisplay(
    record: SplitRecord,
    onReset: () -> Unit,
    onPrint: (File) -> Unit,
    onShare: (File) -> Unit,
    onDownload: (File) -> Unit
) {
    val totalOdd = (record.totalPages + 1) / 2
    val totalEven = record.totalPages / 2

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            // Summary Header Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Successfully processed icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Extracted Successfully",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = record.originalFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // --- Side-by-Side Bento Grid Row: Odd & Even Pages ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SplitPartCard(
                        title = "Odd Pages",
                        pagesDescription = "File 1 • $totalOdd pages",
                        file = record.oddFile,
                        onPrint = { onPrint(record.oddFile) },
                        onShare = { onShare(record.oddFile) },
                        onDownload = { onDownload(record.oddFile) },
                        iconTint = MaterialTheme.colorScheme.primary,
                        titleColor = MaterialTheme.colorScheme.primary,
                        stepNumber = "1"
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (totalEven > 0) {
                        SplitPartCard(
                            title = "Even Pages",
                            pagesDescription = "File 2 • $totalEven pages",
                            file = record.evenFile,
                            onPrint = { onPrint(record.evenFile) },
                            onShare = { onShare(record.evenFile) },
                            onDownload = { onDownload(record.evenFile) },
                            iconTint = MaterialTheme.colorScheme.primary,
                            titleColor = MaterialTheme.colorScheme.primary,
                            stepNumber = "2"
                        )
                    } else {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No Even Pages\n(1 page only)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- Multi-Step Printing Guide Card ---
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "i",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "How it works:",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Print Odd first, re-insert paper tray, then print Even.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Back / Close Split button ---
        item {
            Button(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.outlineVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restart splitter flow icon",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Split Another PDF",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
fun SplitPartCard(
    title: String,
    pagesDescription: String,
    file: File,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    iconTint: Color,
    titleColor: Color,
    stepNumber: String
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(iconTint.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = iconTint
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    val tagStr = if (stepNumber == "1") "ODD" else "EVEN"
                    Text(
                        text = tagStr,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column {
                Text(
                    text = "Ready to print",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = pagesDescription,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPrint,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("print_${stepNumber}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(vertical = 6.bentoPaddingVertical())
                ) {
                    Text(
                        text = "PRINT",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color.White
                    )
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .size(36.dp)
                        .testTag("share_${stepNumber}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .size(36.dp)
                        .testTag("download_${stepNumber}")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Download file",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// --- Tab 2: Manual Ranges Tab ---
@Composable
fun ManualRangeTab() {
    var pageInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Parsed pagination statistics
    val totalNum = pageInput.toIntOrNull() ?: 0

    val oddRangeString = remember(totalNum) {
        if (totalNum <= 0) ""
        else (1..totalNum step 2).joinToString(",")
    }

    val evenRangeString = remember(totalNum) {
        if (totalNum <= 1) ""
        else (2..totalNum step 2).joinToString(",")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Quick Document Range Generator",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "For Microsoft Word, Excel, Slides, or non-PDF pages. Enter the total number of pages to generate range strings you can copy-paste straight into any printer screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Success check icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Supports up to 9,000 pages max",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { input ->
                            if (input.isEmpty()) {
                                pageInput = ""
                            } else if (input.all { char -> char.isDigit() }) {
                                val intVal = input.toIntOrNull() ?: 0
                                if (intVal <= 9000) {
                                    pageInput = input
                                } else {
                                    pageInput = "9000"
                                    Toast.makeText(context, "Supports up to 9,000 pages.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        label = { Text("Total Number of Pages") },
                        placeholder = { Text("e.g. 50") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_page_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }
        }

        if (totalNum > 0) {
            item {
                ManualCopyCard(
                    title = "☀️ Odd Pages Custom Selection",
                    rangeString = oddRangeString,
                    pagesCount = (totalNum + 1) / 2,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(oddRangeString))
                        Toast.makeText(context, "Odd range copied!", Toast.LENGTH_SHORT).show()
                    },
                    accentColor = MaterialTheme.colorScheme.primary,
                    tag = "odd"
                )
            }

            if (totalNum > 1) {
                item {
                    ManualCopyCard(
                        title = "🔄 Even Pages Custom Selection",
                        rangeString = evenRangeString,
                        pagesCount = totalNum / 2,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(evenRangeString))
                            Toast.makeText(context, "Even range copied!", Toast.LENGTH_SHORT).show()
                        },
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        tag = "even"
                    )
                }
            } else {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Only 1 page entered. Even printing is not required.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "💡 How to print using range selection:",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Under your computer or mobile print dialog, choose \"Custom Page Range\" or \"Pages\".\n" +
                                   "2. Copy the Odd range and paste it inside the text field, then tap Print.\n" +
                                   "3. Stack the printed papers back in the empty feed-tray.\n" +
                                   "4. Copy the Even range and paste it in, then trigger print. That's it!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ManualCopyCard(
    title: String,
    rangeString: String,
    pagesCount: Int,
    onCopy: () -> Unit,
    accentColor: Color,
    tag: String
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$pagesCount selected page range:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Output container with copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rangeString,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = onCopy,
                    modifier = Modifier.testTag("copy_${tag}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share, // acts as copy indicator
                        contentDescription = "Copy page range icon",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

// --- Footer Block ---
@Composable
fun FooterBlock() {
    Spacer(modifier = Modifier.height(16.dp))
}

// --- Preview Component ---
@Preview(showBackground = true)
@Composable
fun PreviewMainScreen() {
    MyApplicationTheme {
        MainScreen()
    }
}
