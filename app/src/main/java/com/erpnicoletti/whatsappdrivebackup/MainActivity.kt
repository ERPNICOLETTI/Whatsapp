package com.erpnicoletti.whatsappdrivebackup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_DESTINATION = 1002
        private const val REQ_ALL_FILES = 1003
        private const val REQ_STORAGE = 1004

        private const val TARGET_DRIVE_FOLDER_ID = "1mn9RTwI152c654n81RAEJ3UaQeRXJ0an"
        private const val TARGET_DRIVE_URL =
            "https://drive.google.com/drive/folders/1mn9RTwI152c654n81RAEJ3UaQeRXJ0an"
    }

    private lateinit var permissionText: TextView
    private lateinit var foundText: TextView
    private lateinit var destinationText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: Button
    private lateinit var copyButton: Button

    private var destinationUri: Uri? = null
    private var foundFiles: List<File> = emptyList()
    private var scanAfterPermission = false

    private val prefs by lazy { getSharedPreferences("backup_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        destinationUri = prefs.getString("destination_uri", null)?.let(Uri::parse)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 42)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "WhatsApp DB Finder"
            textSize = 26f
            setPadding(0, 0, 0, 16)
        }

        val explanation = TextView(this).apply {
            text = "Busca automáticamente respaldos locales de WhatsApp (msgstore*.crypt*) en el almacenamiento del teléfono y los copia a una carpeta de Google Drive."
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }

        permissionText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 12)
        }

        scanButton = Button(this).apply {
            text = "1. Dar permiso y buscar base de WhatsApp"
            setOnClickListener { ensurePermissionAndScan() }
        }

        foundText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 24)
        }

        val driveHint = TextView(this).apply {
            text = "Destino objetivo en Drive:\n$TARGET_DRIVE_URL\n\nAl tocar el botón siguiente, elegí esa carpeta. Android necesita que la autorices una vez."
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }

        val destinationButton = Button(this).apply {
            text = "2. Elegir carpeta destino en Drive"
            setOnClickListener { openDestinationPicker() }
        }

        destinationText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 24)
        }

        copyButton = Button(this).apply {
            text = "3. Subir bases encontradas a Drive"
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
        root.addView(permissionText)
        root.addView(scanButton)
        root.addView(foundText)
        root.addView(driveHint)
        root.addView(destinationButton)
        root.addView(destinationText)
        root.addView(copyButton)
        root.addView(progress)
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()

        if (scanAfterPermission && hasStorageAccess()) {
            scanAfterPermission = false
            scanForDatabases()
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensurePermissionAndScan() {
        if (hasStorageAccess()) {
            scanForDatabases()
            return
        }

        scanAfterPermission = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }

            try {
                startActivityForResult(intent, REQ_ALL_FILES)
            } catch (_: Exception) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQ_ALL_FILES
                )
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
        }
    }

    private fun scanForDatabases() {
        scanButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "Buscando msgstore*.crypt*…"
        foundText.text = ""

        Thread {
            val result = runCatching { findWhatsAppDatabases() }

            runOnUiThread {
                progress.visibility = View.GONE
                scanButton.isEnabled = true

                result.onSuccess { files ->
                    foundFiles = files
                    if (files.isEmpty()) {
                        foundText.text =
                            "No encontré ninguna base msgstore*.crypt* accesible."
                        statusText.text =
                            "WhatsApp puede tener la base en almacenamiento privado o todavía no haber generado un respaldo local."
                    } else {
                        val lines = files.joinToString("\n\n") { f ->
                            "• ${f.absolutePath}\n  ${(f.length() / 1024.0 / 1024.0).format1()} MB"
                        }
                        foundText.text =
                            "Encontré ${files.size} archivo(s):\n\n$lines"
                        statusText.text = "Bases encontradas. Ya podés copiarlas a Drive."
                    }
                    updateUi()
                }.onFailure { error ->
                    statusText.text =
                        "Error al buscar: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun findWhatsAppDatabases(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val found = linkedMapOf<String, File>()

        val knownDirs = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Databases"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases"),
            File(root, "WhatsApp/Databases"),
            File(root, "WhatsApp Business/Databases")
        )

        knownDirs.forEach { dir ->
            collectDatabaseFiles(dir, found, recursive = false)
        }

        scanRecursively(root, found, depth = 0, maxDepth = 9)

        return found.values.sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenBy { it.absolutePath }
        )
    }

    private fun collectDatabaseFiles(
        dir: File,
        found: MutableMap<String, File>,
        recursive: Boolean
    ) {
        val files = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return

        files.forEach { file ->
            if (file.isFile && isWhatsAppDatabase(file)) {
                val key = try {
                    file.canonicalPath
                } catch (_: Exception) {
                    file.absolutePath
                }
                found[key] = file
            } else if (recursive && file.isDirectory) {
                collectDatabaseFiles(file, found, recursive = true)
            }
        }
    }

    private fun scanRecursively(
        dir: File,
        found: MutableMap<String, File>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return

        children.forEach { child ->
            if (child.isFile) {
                if (isWhatsAppDatabase(child)) {
                    val key = try {
                        child.canonicalPath
                    } catch (_: Exception) {
                        child.absolutePath
                    }
                    found[key] = child
                }
            } else if (child.isDirectory) {
                val name = child.name.lowercase(Locale.ROOT)

                if (
                    name == "dcim" ||
                    name == "pictures" ||
                    name == "movies" ||
                    name == "music" ||
                    name == "download"
                ) {
                    return@forEach
                }

                scanRecursively(child, found, depth + 1, maxDepth)
            }
        }
    }

    private fun isWhatsAppDatabase(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        return name.startsWith("msgstore") &&
            (
                name.contains(".crypt") ||
                    name.endsWith(".db")
                )
    }

    private fun openDestinationPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            destinationUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra("android.provider.extra.INITIAL_URI", uri)
                }
            }
        }
        startActivityForResult(intent, REQ_DESTINATION)
    }

    @Deprecated("Kept for compatibility with a dependency-light Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_DESTINATION && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            persistTreePermission(uri, data.flags)
            destinationUri = uri
            prefs.edit().putString("destination_uri", uri.toString()).apply()
            updateUi()
        }

        if (requestCode == REQ_ALL_FILES) {
            updateUi()
            if (hasStorageAccess()) {
                scanAfterPermission = false
                scanForDatabases()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_STORAGE) {
            scanAfterPermission = false
            if (hasStorageAccess()) {
                scanForDatabases()
            } else {
                statusText.text = "Sin permiso de almacenamiento no puedo buscar la base."
            }
        }
    }

    private fun persistTreePermission(uri: Uri, returnedFlags: Int) {
        val takeFlags = returnedFlags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
        }
    }

    private fun updateUi() {
        permissionText.text =
            if (hasStorageAccess()) {
                "✓ Acceso al almacenamiento concedido"
            } else {
                "Falta permiso de acceso al almacenamiento"
            }

        destinationText.text =
            destinationUri?.let { "Destino autorizado: $it" }
                ?: "Todavía no elegiste la carpeta de Drive"

        copyButton.isEnabled =
            foundFiles.isNotEmpty() && destinationUri != null
    }

    private fun startBackup() {
        val dst = destinationUri ?: return
        if (foundFiles.isEmpty()) return

        copyButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "Copiando bases encontradas a Drive…"

        Thread {
            val result = runCatching { copyFoundFiles(dst) }

            runOnUiThread {
                progress.visibility = View.GONE
                copyButton.isEnabled = true
                statusText.text = result.fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        "Error: ${error.message ?: error.javaClass.simpleName}"
                    }
                )
            }
        }.start()
    }

    private fun copyFoundFiles(destinationTreeUri: Uri): String {
        val destination = DocumentFile.fromTreeUri(this, destinationTreeUri)
            ?: throw FileNotFoundException("No pude abrir la carpeta destino")

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val backupFolder = destination.createDirectory("WhatsApp_DB_Backup_$stamp")
            ?: throw IllegalStateException("No pude crear la carpeta destino")

        var copied = 0
        var bytes = 0L

        foundFiles.forEachIndexed { index, file ->
            runOnUiThread {
                statusText.text =
                    "Copiando ${index + 1}/${foundFiles.size}: ${file.name}"
            }

            val outFile = backupFolder.createFile(
                "application/octet-stream",
                file.name
            ) ?: throw IllegalStateException("No pude crear ${file.name}")

            FileInputStream(file).use { input ->
                contentResolver.openOutputStream(outFile.uri, "w").use { output ->
                    if (output == null) {
                        throw FileNotFoundException("No pude escribir ${file.name}")
                    }

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

        return String.format(
            Locale.US,
            "Listo. Copié %d archivo(s), %.1f MB, a %s.",
            copied,
            bytes / 1024.0 / 1024.0,
            backupFolder.name
        )
    }

    private fun Double.format1(): String =
        String.format(Locale.US, "%.1f", this)
}
