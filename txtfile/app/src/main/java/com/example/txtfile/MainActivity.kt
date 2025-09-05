package com.example.txtfile

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import java.io.*
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import kotlinx.coroutines.*
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import android.text.TextWatcher
import android.text.Editable
import java.text.SimpleDateFormat
import androidx.core.content.ContextCompat
import org.xmlpull.v1.XmlPullParser
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private lateinit var btnSave: Button
    private lateinit var btnOpen: Button
    private lateinit var word_count: TextView
    private lateinit var char_count: TextView
    private lateinit var line_count: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnCopy: ImageButton
    private lateinit var btnPaste: ImageButton
    private lateinit var btnEdit: ImageButton
    private lateinit var btnfind_replace: ImageButton
    private lateinit var btnNewFile: Button
    private lateinit var btnCompile: Button
    private lateinit var languageText: TextView
    private lateinit var outputText: TextView

    private val undoStack: Stack<String> = Stack()
    private val redoStack: Stack<String> = Stack()
    private var isUndoOrRedo: Boolean = false
    private var currentLanguage = "Text"
    private var currentFileName: String? = null // Track current filename
    private var isHighlighting = false

    private val PHONE_CODE_DIR get() = File(getExternalFilesDir(null), "codes").absolutePath

    private lateinit var syntaxConfig: SyntaxConfig

    private val extensionToLanguage = mapOf(
        "txt" to "Text", "kt" to "Kotlin", "c" to "C", "cpp" to "C++",
        "py" to "Python", "java" to "Java", "js" to "JavaScript"
    )

    private fun saveFileDirectly(fileName: String) {
        try {
            val codeDir = File(PHONE_CODE_DIR)
            if (!codeDir.exists()) {
                val created = codeDir.mkdirs()
                if (!created) {
                    showErrorDialog("Failed to create directory", "Could not create directory: $PHONE_CODE_DIR")
                    return
                }
                Log.d("SaveFile", "Created directory: $PHONE_CODE_DIR")
            }

            val file = File(codeDir, fileName)
            if (!codeDir.canWrite()) {
                showErrorDialog("Permission Denied", "Cannot write to directory: $PHONE_CODE_DIR")
                return
            }

            file.writeText(editText.text.toString())
            currentFileName = fileName // Update current filename

            Toast.makeText(this, "✅ File saved: $fileName", Toast.LENGTH_SHORT).show()
            Log.d("SaveFile", "File saved: ${file.absolutePath}")

        } catch (e: SecurityException) {
            showErrorDialog("Security Error", "Permission denied to write file:\n${e.message}")
            Log.e("SaveFile", "Security error saving file", e)
        } catch (e: java.io.IOException) {
            showErrorDialog("I/O Error", "Failed to write file:\n${e.message}")
            Log.e("SaveFile", "IO error saving file", e)
        } catch (e: Exception) {
            showErrorDialog("Save Error", "Unexpected error occurred:\n${e.message}")
            Log.e("SaveFile", "Error saving file", e)
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("❌ $title")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setCancelable(true)
            .show()
    }

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                detectFileType(it)
                contentResolver.openInputStream(it)?.bufferedReader().use { reader ->
                    val content = reader?.readText() ?: ""
                    editText.setText(content)
                    // Push initial state to undo stack
                    if (undoStack.isEmpty()) {
                        undoStack.push("")
                    }
                }
                // Set current filename when opening a file
                currentFileName = getFileName(it)
                Toast.makeText(this, "File opened: $currentFileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Open error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeViews()
        setupEventListeners()

        // Initialize syntaxConfig with default configuration
        initSyntax("Text")
    }

    private fun initializeViews() {
        editText = findViewById(R.id.editText)
        btnSave = findViewById(R.id.btnSave)
        btnOpen = findViewById(R.id.btnOpen)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnCopy = findViewById(R.id.btnCopy)
        btnPaste = findViewById(R.id.btnPaste)
        btnEdit = findViewById(R.id.btnEdit)
        btnfind_replace = findViewById(R.id.btnfind_replace)
        btnNewFile = findViewById(R.id.btnNewFile)
        btnCompile = findViewById(R.id.btnCompile)
        word_count = findViewById(R.id.word_count)
        char_count = findViewById(R.id.char_count)
        line_count = findViewById(R.id.line_count)
        languageText = findViewById(R.id.language)
        outputText = findViewById(R.id.outputText)
    }

    private fun setupEventListeners() {
        btnSave.setOnClickListener {
            if (editText.text.toString().isNotEmpty()) {
                showSaveDialog()
            } else {
                Toast.makeText(this, "Text is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpen.setOnClickListener { openFileLauncher.launch(arrayOf("*/*")) }

        editText.addTextChangedListener(object : TextWatcher {
            private var previousText = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isUndoOrRedo && !isHighlighting) previousText = s.toString()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isHighlighting) return
                val currentText = s.toString()
                if (!isUndoOrRedo && previousText != currentText) {
                    undoStack.push(previousText)
                    if (undoStack.size > 50) undoStack.removeElementAt(0)
                    redoStack.clear()
                }
                updateCounters()
                applySyntaxHighlighting()
            }
        })

        btnUndo.setOnClickListener {
            if (undoStack.isNotEmpty()) {
                isUndoOrRedo = true
                redoStack.push(editText.text.toString())
                editText.setText(undoStack.pop())
                isUndoOrRedo = false
            }
        }

        btnRedo.setOnClickListener {
            if (redoStack.isNotEmpty()) {
                isUndoOrRedo = true
                undoStack.push(editText.text.toString())
                editText.setText(redoStack.pop())
                isUndoOrRedo = false
            }
        }

        btnCopy.setOnClickListener {
            val text = if (editText.hasSelection()) {
                editText.text.substring(editText.selectionStart, editText.selectionEnd)
            } else editText.text.toString()

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Text", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                val start = editText.selectionStart
                val end = editText.selectionEnd
                editText.text.replace(minOf(start, end), maxOf(start, end), text)
            }
        }

        btnEdit.setOnClickListener {
            editText.isEnabled = !editText.isEnabled
            Toast.makeText(this, if (editText.isEnabled) "Edit enabled" else "Edit disabled", Toast.LENGTH_SHORT).show()
        }

        btnfind_replace.setOnClickListener { showFindReplaceDialog() }
        btnNewFile.setOnClickListener { createNewFile() }
        btnCompile.setOnClickListener { compileCode() }
    }

    private fun updateCounters() {
        val text = editText.text.toString()
        val words = text.trim().split("\\s+".toRegex())
        val wordCount = if (text.trim().isEmpty()) 0 else words.size

        word_count.text = "Words: $wordCount"
        char_count.text = "Chars: ${text.length}"
        line_count.text = "Lines: ${if (text.isEmpty()) 1 else text.count { it == '\n' } + 1}"
    }

    private fun highlightSyntax(spannable: SpannableStringBuilder, config: SyntaxConfig) {
        applyPattern(spannable, "\\b(${config.keywords.joinToString("|")})\\b", R.color.keywordColor)
        applyPattern(spannable, "(${config.operators.joinToString("|")})", R.color.operatorColor)
        config.numbers?.let { applyPattern(spannable, it, R.color.numberColor) }
        config.strings?.let { applyPattern(spannable, it, R.color.stringColor) }
        config.comments?.let { applyPattern(spannable, it, R.color.commentColor) }
        config.functions?.let { applyPattern(spannable, it, R.color.syntax_function) }
    }

    private fun applyPattern(spannable: SpannableStringBuilder, patternStr: String, colorRes: Int) {
        val pattern = Pattern.compile(patternStr, Pattern.MULTILINE)
        val matcher = pattern.matcher(spannable)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, colorRes)),
                matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }


    fun initSyntax(language: String) {
        val resId = when (language) {
            "Kotlin" -> R.xml.kotlin_syntax
            "Java" -> R.xml.java_syntax
            "C" -> R.xml.c_syntax
            "C++" -> R.xml.cpp_syntax
            "Python" -> R.xml.python_syntax
            "JavaScript" -> R.xml.javascript_syntax
            else -> R.xml.default_syntax
        }
        syntaxConfig = loadSyntaxConfig(this, resId)
    }

    private fun applySyntaxHighlighting() {
        // Add null check for syntaxConfig
        if (!::syntaxConfig.isInitialized) {
            initSyntax(currentLanguage)
        }

        try {
            CoroutineScope(Dispatchers.Main).launch {
                delay(100)
                if (!isHighlighting) {
                    isHighlighting = true
                    val spannable = SpannableStringBuilder(editText.text)
                    highlightSyntax(spannable, syntaxConfig)
                    val cursorPos = editText.selectionStart
                    editText.setText(spannable, TextView.BufferType.SPANNABLE)
                    if (cursorPos <= editText.text.length) editText.setSelection(cursorPos)
                    isHighlighting = false
                }
            }
        } catch (e: Exception) {
            Log.e("SyntaxHighlight", "Error in syntax highlighting: ${e.message}")
            isHighlighting = false
        }
    }


    private fun createNewFile() {
        editText.setText("")
        undoStack.clear()
        redoStack.clear()
        outputText.text = ""
        currentLanguage = "Text"
        currentFileName = null // Reset filename
        languageText.text = "Language: $currentLanguage"
        updateCounters()
        initSyntax("Text")
    }

    private fun detectFileType(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            val extension = fileName?.substringAfterLast('.', "")?.lowercase()
            currentLanguage = extensionToLanguage[extension] ?: "Text"
            languageText.text = "Language: $currentLanguage"
            initSyntax(currentLanguage)
        } catch (e: Exception) {
            currentLanguage = "Text"
            languageText.text = "Language: $currentLanguage"
            initSyntax(currentLanguage) // Default
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            uri.path?.split("/")?.lastOrNull()
        }
    }

    private fun compileCode() {
        val code = editText.text.toString()
        if (code.isEmpty()) {
            Toast.makeText(this, "No code to compile", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if file has been saved
        if (currentFileName == null) {
            showSaveBeforeCompileDialog()
            return
        }

        try {
            val codeDir = File(PHONE_CODE_DIR)
            if (!codeDir.exists()) {
                codeDir.mkdirs()
                Log.d("CompileCode", "Created directory: $PHONE_CODE_DIR")
            }

            // Write the main code file using the saved filename
            val codeFile = File(codeDir, currentFileName!!)
            codeFile.writeText(code)

            // Create request.txt file containing the filename
            val requestFile = File(codeDir, "request.txt")
            requestFile.writeText(currentFileName!!)

            outputText.text = "🔄 Compiling ${currentFileName}...\nWaiting for ADB response..."
            Toast.makeText(this, "Code saved as ${currentFileName}", Toast.LENGTH_SHORT).show()

            // Wait 2 seconds before starting monitoring
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds
                startOutputMonitoring(currentFileName!!)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            outputText.text = "Error: ${e.message}"
        }
    }

    private fun showSaveBeforeCompileDialog() {
        AlertDialog.Builder(this)
            .setTitle("Save Required")
            .setMessage("You need to save the file before compiling. Would you like to save now?")
            .setPositiveButton("Save") { _, _ ->
                showSaveDialogForCompile()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSaveDialogForCompile() {
        val input = EditText(this)
        input.hint = "Enter filename (e.g., myfile.c)"

        AlertDialog.Builder(this)
            .setTitle("Save File for Compilation")
            .setMessage("Enter filename with extension:")
            .setView(input)
            .setPositiveButton("Save & Compile") { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isNotEmpty()) {
                    saveFileDirectly(fileName)
                    // Update language based on extension
                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    currentLanguage = extensionToLanguage[extension] ?: "Text"
                    languageText.text = "Language: $currentLanguage"
                    initSyntax(currentLanguage)

                    // Now compile after saving
                    compileCode()
                } else {
                    Toast.makeText(this, "Please enter a filename", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOutputMonitoring(fileName: String) {
        val nameOnly = fileName.substringBeforeLast(".")
        val outputFileName = "$nameOnly.txt"

        CoroutineScope(Dispatchers.Main).launch {
            repeat(30) {
                delay(4000)
                val outputFile = File(PHONE_CODE_DIR, outputFileName)
                if (outputFile.exists()) {
                    try {
                        val output = outputFile.readText()
                        displayOutput(output, fileName)
                        return@launch
                    } catch (e: Exception) {
                        outputText.text = "Error reading output: ${e.message}"
                        return@launch
                    }
                }
            }
            outputText.text = "⏱️ Compilation timeout - no response after 30 seconds"
        }
    }

    private fun displayOutput(output: String, fileName: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val status = when {
            output.contains("❌") -> "🔴 COMPILATION FAILED"
            output.contains("💥") -> "🔴 RUNTIME ERROR"
            else -> "✅ COMPILATION SUCCESSFUL"
        }

        outputText.text = buildString {
            append("📁 File: $fileName\n")
            append("🕐 Time: $timestamp\n")
            append("💻 Language: $currentLanguage\n")
            append("=".repeat(30) + "\n")
            append("$status:\n")
            append(output)
        }

        Toast.makeText(this, if (output.contains("❌") || output.contains("💥")) "❌ Failed" else "✅ Success", Toast.LENGTH_SHORT).show()
    }

    private fun showSaveDialog() {
        val input = EditText(this)
        input.hint = "Enter filename (e.g., myfile.c)"
        // Pre-fill with current filename if it exists
        currentFileName?.let { input.setText(it) }

        AlertDialog.Builder(this)
            .setTitle("Save File")
            .setMessage("Enter filename with extension:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isNotEmpty()) {
                    saveFileDirectly(fileName)
                    // Update language based on extension
                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    currentLanguage = extensionToLanguage[extension] ?: "Text"
                    languageText.text = "Language: $currentLanguage"
                    initSyntax(currentLanguage)
                } else {
                    Toast.makeText(this, "Please enter a filename", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFindReplaceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val findEdit = dialogView.findViewById<EditText>(R.id.editFind)
        val replaceEdit = dialogView.findViewById<EditText>(R.id.editReplace)

        AlertDialog.Builder(this)
            .setTitle("Find & Replace")
            .setView(dialogView)
            .setPositiveButton("Replace") { _, _ ->
                val findText = findEdit.text.toString()
                val replaceText = replaceEdit.text.toString()
                if (findText.isNotEmpty()) {
                    val newContent = editText.text.toString().replace(findText, replaceText)
                    editText.setText(newContent)
                    Toast.makeText(this, "Replaced", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}

data class SyntaxConfig(
    val keywords: List<String>,
    val operators: List<String>,
    val numbers: String?,
    val strings: String?,
    val comments: String?,
    val functions: String?
)

fun loadSyntaxConfig(context: Context, xmlResId: Int): SyntaxConfig {
    val parser = context.resources.getXml(xmlResId)
    val keywords = mutableListOf<String>()
    val operators = mutableListOf<String>()
    var numbers: String? = null
    var strings: String? = null
    var comments: String? = null
    var functions: String? = null

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
            when (parser.name) {
                "keyword" -> keywords.add(parser.nextText())
                "operator" -> operators.add(parser.nextText())
                "numbers" -> numbers = parser.nextText()
                "strings" -> strings = parser.nextText()
                "comments" -> comments = parser.nextText()
                "functions" -> functions = parser.nextText()
            }
        }
        eventType = parser.next()
    }

    return SyntaxConfig(keywords, operators, numbers, strings, comments, functions)
}