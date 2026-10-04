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
    private var matchedFiles: List<File> = emptyList()
    private var filesToCopy: List<File> = emptyList()
    private var diagnosticText: String = ""
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
            text = "WhatsApp DB Finder v3"
            textSize = 26f
            setPadding(0, 0, 0, 16)
        }

        val explanation = TextView(this).apply {
            text = "Busca msgstore*.crypt*, identifica la carpeta exacta donde están y revisa todos los archivos vecinos para encontrar el respaldo principal."
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }

        permissionText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 12)
        }

        scanButton = Button(this).apply {
            text = "1. Buscar bases y carpetas relacionadas"
            setOnClickListener { ensurePermissionAndScan() }
        }

        foundText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 12, 0, 24)
            setTextIsSelectable(true)
        }

        val driveHint = TextView(this).apply {
            text = "Destino:\n$TARGET_DRIVE_URL\n\nLa carpeta ya autorizada de la v2 debería seguir guardada. Si no, elegila otra vez."
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }

        val destinationButton = Button(this).apply {
            text = "2. Elegir/revisar carpeta destino en Drive"
            setOnClickListener { openDestinationPicker() }
        }

        destinationText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 24)
        }

        copyButton = Button(this).apply {
            text = "3. Subir diagnóstico + archivos relacionados"
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
        statusText.text = "Buscando respaldos y revisando carpetas…"
        foundText.text = ""

        Thread {
            val result = runCatching { buildScanResult() }

            runOnUiThread {
                progress.visibility = View.GONE
                scanButton.isEnabled = true

                result.onSuccess { scan ->
                    matchedFiles = scan.matches
                    filesToCopy = scan.filesToCopy
                    diagnosticText = scan.report
                    foundText.text = scan.report

                    statusText.text =
                        if (scan.matches.isEmpty()) {
                            "No encontré msgstore*.crypt* accesibles."
                        } else {
                            "Encontré ${scan.matches.size} respaldo(s) y ${scan.filesToCopy.size} archivo(s) relacionados para copiar."
                        }

                    updateUi()
                }.onFailure { error ->
                    statusText.text =
                        "Error al buscar: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    data class ScanResult(
        val matches: List<File>,
        val filesToCopy: List<File>,
        val report: String
    )

    private fun buildScanResult(): ScanResult {
        val root = Environment.getExternalStorageDirectory()
        val matches = linkedMapOf<String, File>()

        val knownDirs = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Databases"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Backups"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Backups"),
            File(root, "WhatsApp/Databases"),
            File(root, "WhatsApp/Backups"),
            File(root, "WhatsApp Business/Databases"),
            File(root, "WhatsApp Business/Backups")
        )

        knownDirs.forEach { dir -> collectMatches(dir, matches) }
        scanRecursively(root, matches, depth = 0, maxDepth = 10)

        val matchList = matches.values.sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenBy { it.absolutePath }
        )

        val relatedDirs = linkedSetOf<File>()
        matchList.forEach { file ->
            file.parentFile?.let { relatedDirs.add(it) }
            file.parentFile?.parentFile?.let { parent ->
                val db = File(parent, "Databases")
                val backups = File(parent, "Backups")
                if (db.exists()) relatedDirs.add(db)
                if (backups.exists()) relatedDirs.add(backups)
            }
        }

        knownDirs.filter { it.exists() }.forEach { relatedDirs.add(it) }

        val allRelated = linkedMapOf<String, File>()
        val report = StringBuilder()

        report.appendLine("=== WHATSAPP DB FINDER v3 ===")
        report.appendLine("Fecha: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        report.appendLine()

        if (matchList.isEmpty()) {
            report.appendLine("No se encontraron archivos msgstore*.crypt* accesibles.")
        } else {
            report.appendLine("RESPALDOS ENCONTRADOS: ${matchList.size}")
            matchList.forEach { file ->
                report.appendLine("• ${file.absolutePath}")
                report.appendLine("  Tamaño: ${formatBytes(file.length())}")
                report.appendLine("  Modificado: ${formatDate(file.lastModified())}")
            }
        }

        report.appendLine()
        report.appendLine("CARPETAS RELACIONADAS:")

        relatedDirs
            .sortedBy { it.absolutePath }
            .forEach { dir ->
                report.appendLine()
                report.appendLine(">>> ${dir.absolutePath}")

                val children = safeListFiles(dir)
                    .filter { it.isFile }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }

                if (children.isEmpty()) {
                    report.appendLine("(sin archivos visibles)")
                } else {
                    children.forEach { file ->
                        report.appendLine(
                            "- ${file.name} | ${formatBytes(file.length())} | ${formatDate(file.lastModified())}"
                        )

                        if (isRelatedDatabaseFile(file)) {
                            val key = canonicalKey(file)
                            allRelated[key] = file
                        }
                    }
                }
            }

        report.appendLine()
        report.appendLine("ARCHIVOS QUE SE COPIARÁN: ${allRelated.size}")
        allRelated.values.forEach { report.appendLine("• ${it.absolutePath}") }

        return ScanResult(
            matches = matchList,
            filesToCopy = allRelated.values.toList(),
            report = report.toString()
        )
    }

    private fun collectMatches(
        dir: File,
        found: MutableMap<String, File>
    ) {
        safeListFiles(dir).forEach { file ->
            if (file.isFile && isWhatsAppDatabase(file)) {
                found[canonicalKey(file)] = file
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

        safeListFiles(dir).forEach { child ->
            if (child.isFile) {
                if (isWhatsAppDatabase(child)) {
                    found[canonicalKey(child)] = child
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

    private fun safeListFiles(dir: File): List<File> {
        return try {
            dir.listFiles()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun canonicalKey(file: File): String {
        return try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
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

    private fun isRelatedDatabaseFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.ROOT)
        return n.startsWith("msgstore") ||
            n == "wa.db" ||
            n.startsWith("wa.db-") ||
            n.startsWith("chatsettings") ||
            n.startsWith("axolotl") ||
            n.startsWith("companion_devices") ||
            n.endsWith(".crypt14") ||
            n.endsWith(".crypt15") ||
            n.endsWith(".crypt16")
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L ->
                String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L ->
                String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) return "sin fecha"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
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
            filesToCopy.isNotEmpty() && destinationUri != null
    }

    private fun startBackup() {
        val dst = destinationUri ?: return
        if (filesToCopy.isEmpty()) return

        copyButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "Subiendo diagnóstico y archivos relacionados…"

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
        val backupFolder = destination.createDirectory("WhatsApp_DB_Diagnostic_$stamp")
            ?: throw IllegalStateException("No pude crear la carpeta destino")

        val reportFile = backupFolder.createFile(
            "text/plain",
            "diagnostico_whatsapp.txt"
        ) ?: throw IllegalStateException("No pude crear el diagnóstico")

        contentResolver.openOutputStream(reportFile.uri, "w").use { output ->
            if (output == null) throw FileNotFoundException("diagnostico_whatsapp.txt")
            output.write(diagnosticText.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        var copied = 0
        var bytes = 0L

        filesToCopy.forEachIndexed { index, file ->
            runOnUiThread {
                statusText.text =
                    "Copiando ${index + 1}/${filesToCopy.size}: ${file.name}"
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
            "Listo. Subí diagnóstico + %d archivo(s), %.2f MB, a %s.",
            copied,
            bytes / 1024.0 / 1024.0,
            backupFolder.name
        )
    }
}
