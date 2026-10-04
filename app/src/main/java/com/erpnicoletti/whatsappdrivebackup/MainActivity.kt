package com.erpnicoletti.whatsappdrivebackup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_SOURCE = 1001
        private const val REQ_DESTINATION = 1002
    }

    private lateinit var sourceText: TextView
    private lateinit var destinationText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var copyButton: Button

    private var sourceUri: Uri? = null
    private var destinationUri: Uri? = null

    private val prefs by lazy { getSharedPreferences("backup_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourceUri = prefs.getString("source_uri", null)?.let(Uri::parse)
        destinationUri = prefs.getString("destination_uri", null)?.let(Uri::parse)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 42)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "WhatsApp → Drive"
            textSize = 26f
            setPadding(0, 0, 0, 16)
        }

        val explanation = TextView(this).apply {
            text = "Copia el respaldo local completo de WhatsApp (msgstore*.crypt14/crypt15) a una carpeta de Google Drive elegida por vos. No necesita root ni acceso a tu cuenta de WhatsApp."
            textSize = 16f
            setPadding(0, 0, 0, 28)
        }

        val sourceButton = Button(this).apply {
            text = "1. Elegir carpeta WhatsApp/Databases"
            setOnClickListener { openTreePicker(REQ_SOURCE, sourceUri) }
        }

        sourceText = TextView(this).apply { setPadding(0, 8, 0, 24) }

        val destinationButton = Button(this).apply {
            text = "2. Elegir carpeta destino en Drive"
            setOnClickListener { openTreePicker(REQ_DESTINATION, destinationUri) }
        }

        destinationText = TextView(this).apply { setPadding(0, 8, 0, 24) }

        copyButton = Button(this).apply {
            text = "3. Copiar respaldo completo"
            isEnabled = false
            setOnClickListener { startBackup() }
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, 20, 0, 12)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 8, 0, 0)
        }

        root.addView(title)
        root.addView(explanation)
        root.addView(sourceButton)
        root.addView(sourceText)
        root.addView(destinationButton)
        root.addView(destinationText)
        root.addView(copyButton)
        root.addView(progress)
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
        updateUi()
    }

    private fun openTreePicker(requestCode: Int, initialUri: Uri?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (initialUri != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                putExtra("android.provider.extra.INITIAL_URI", initialUri)
            }
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Android API but intentionally used to keep this APK dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        persistTreePermission(uri, data.flags)

        when (requestCode) {
            REQ_SOURCE -> {
                sourceUri = uri
                prefs.edit().putString("source_uri", uri.toString()).apply()
            }
            REQ_DESTINATION -> {
                destinationUri = uri
                prefs.edit().putString("destination_uri", uri.toString()).apply()
            }
        }

        updateUi()
    }

    private fun persistTreePermission(uri: Uri, returnedFlags: Int) {
        val takeFlags = returnedFlags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun updateUi() {
        sourceText.text = if (sourceUri != null) "Origen: " + sourceUri else "Origen no seleccionado"
        destinationText.text = if (destinationUri != null) "Destino: " + destinationUri else "Destino no seleccionado"
        copyButton.isEnabled = sourceUri != null && destinationUri != null
    }

    private fun startBackup() {
        val src = sourceUri ?: return
        val dst = destinationUri ?: return

        copyButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "Buscando respaldos de WhatsApp…"

        Thread {
            val result = runCatching { backupTree(src, dst) }

            runOnUiThread {
                progress.visibility = View.GONE
                copyButton.isEnabled = true
                statusText.text = result.fold(
                    onSuccess = { message -> message },
                    onFailure = { error -> "Error: " + (error.message ?: error.javaClass.simpleName) }
                )
            }
        }.start()
    }

    private fun backupTree(sourceTreeUri: Uri, destinationTreeUri: Uri): String {
        val source = DocumentFile.fromTreeUri(this, sourceTreeUri)
            ?: throw FileNotFoundException("No pude abrir la carpeta origen")

        val destination = DocumentFile.fromTreeUri(this, destinationTreeUri)
            ?: throw FileNotFoundException("No pude abrir la carpeta destino")

        val candidates = source.listFiles()
            .filter { file ->
                file.isFile &&
                    (
                        file.name?.startsWith("msgstore") == true ||
                            file.name == "wa.db"
                        )
            }
            .sortedByDescending { file -> file.lastModified() }

        if (candidates.isEmpty()) {
            throw FileNotFoundException(
                "No encontré msgstore*.crypt14/crypt15 en la carpeta elegida. Elegí exactamente WhatsApp/Databases."
            )
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val backupFolder = destination.createDirectory("WhatsApp_Backup_" + stamp)
            ?: throw IllegalStateException("No pude crear la carpeta destino")

        var copied = 0
        var bytes = 0L

        candidates.forEachIndexed { index, file ->
            runOnUiThread {
                statusText.text =
                    "Copiando " + (index + 1) + "/" + candidates.size + ": " + (file.name ?: "archivo")
            }

            val name = file.name ?: "backup_" + (index + 1)
            val mime = file.type ?: "application/octet-stream"
            val outFile = backupFolder.createFile(mime, name)
                ?: throw IllegalStateException("No pude crear " + name + " en Drive")

            contentResolver.openInputStream(file.uri).use { input ->
                if (input == null) throw FileNotFoundException(name)

                contentResolver.openOutputStream(outFile.uri, "w").use { output ->
                    if (output == null) throw FileNotFoundException("Destino " + name)

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                    }
                    output.flush()
                }
            }

            copied++
        }

        val mb = bytes / 1024.0 / 1024.0
        return String.format(
            Locale.US,
            "Listo. Copiados %d archivo(s), %.1f MB, en %s.\n\nEse respaldo contiene el historial de chats cifrado de WhatsApp.",
            copied,
            mb,
            backupFolder.name
        )
    }
}
