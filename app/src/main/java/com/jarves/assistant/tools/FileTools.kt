package com.jarves.assistant.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Downloads and file management in the phone's shared storage. */
class FileTools(private val ctx: Context) {

    private val root: File get() = Environment.getExternalStorageDirectory()

    private fun hasAllFiles(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private val needPermission = ToolOutput.error(
        "Jarves does not have 'All files access'. Ask the user to open Jarves > Setup and allow All files access " +
            "(or call open_settings with page all_files_access)."
    )

    /** Turns "Pictures/Cars", "/sdcard/Download", "Internal storage/DCIM" into a File. */
    fun resolve(path: String): File {
        var p = path.trim().replace('\\', '/')
        // Other volumes (SD card, USB) are used as absolute paths.
        if (p.startsWith("/storage/") && !p.startsWith("/storage/emulated/0") || p.startsWith("/mnt/")) return File(p)
        val prefixes = listOf("/storage/emulated/0", "/sdcard", "sdcard", "/internal storage", "internal storage")
        for (pre in prefixes) if (p.startsWith(pre, ignoreCase = true)) { p = p.substring(pre.length); break }
        p = p.trimStart('/')
        // Match folder names case-insensitively to existing folders (e.g. "downloads" -> "Download").
        var cur = root
        for (seg in p.split('/').filter { it.isNotBlank() }) {
            val aliasSeg = when (seg.lowercase()) {
                "downloads" -> "Download"
                "gallery", "photos", "images" -> if (cur == root) "Pictures" else seg
                "camera" -> if (cur == root) "DCIM" else seg
                else -> seg
            }
            val existing = cur.listFiles()?.firstOrNull { it.name.equals(aliasSeg, ignoreCase = true) }
            cur = existing ?: File(cur, aliasSeg)
        }
        return cur
    }

    private fun display(f: File): String = f.absolutePath.removePrefix(root.absolutePath).trimStart('/').ifEmpty { "(internal storage)" }

    // ------------------------------------------------------------------ download

    fun download(url: String, folder: String, fileName: String): ToolOutput {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ToolOutput.error("URL must start with http(s)://")
        var conn: HttpURLConnection? = null
        try {
            var current = URL(url)
            var redirects = 0
            while (true) {
                val c = current.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Referer", "${current.protocol}://${current.host}/")
                val code = c.responseCode
                if (code in 300..399 && redirects < 8) {
                    val loc = c.getHeaderField("Location") ?: return ToolOutput.error("Redirect without location ($code)")
                    current = URL(current, loc)
                    redirects++
                    c.disconnect()
                    continue
                }
                if (code !in 200..299) {
                    c.disconnect()
                    return ToolOutput.error("Server answered HTTP $code for this URL. Try another source.")
                }
                conn = c
                break
            }
            val c = conn!!
            val mime = c.contentType?.substringBefore(';')?.trim()?.lowercase() ?: guessMimeFromName(current.path)
            if (mime == "text/html" || mime == "application/xhtml+xml") {
                return ToolOutput.error(
                    "This URL is a web page (text/html), not a downloadable file. Use web_fetch on the page to find the direct file/image URL, " +
                        "or open it in Chrome and tap the site's download button."
                )
            }
            val name = buildFileName(fileName, c.getHeaderField("Content-Disposition"), current, mime)

            val saved: String
            val savedFile: File?
            if (hasAllFiles()) {
                val dir = resolve(folder)
                if (!dir.exists() && !dir.mkdirs()) return ToolOutput.error("Could not create folder ${display(dir)}")
                val f = uniqueFile(dir, name)
                c.inputStream.use { input -> f.outputStream().use { out -> copy(input, out) } }
                MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
                saved = display(f)
                savedFile = f
            } else if (Build.VERSION.SDK_INT >= 29) {
                val (uri, rel) = mediaStoreInsert(folder, name, mime) ?: return needPermission
                ctx.contentResolver.openOutputStream(uri)?.use { out -> c.inputStream.use { input -> copy(input, out) } }
                    ?: return ToolOutput.error("Could not open output file.")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, done, null, null)
                saved = "$rel$name (saved via MediaStore — grant All files access to save in any folder)"
                savedFile = File(root, rel + name)
            } else {
                return needPermission
            }

            val size = savedFile?.takeIf { it.exists() }?.length() ?: -1L
            val sizeText = if (size >= 0) " (${humanSize(size)})" else ""
            val text = StringBuilder("Downloaded and saved: $saved$sizeText, type $mime.")
            var preview: String? = null
            if (mime.startsWith("image/") && savedFile != null && savedFile.exists()) {
                val p = imagePreview(savedFile)
                if (p != null) {
                    text.append(" Image is ${p.second}. Preview attached — check it matches what the user wanted.")
                    preview = p.first
                }
            }
            return ToolOutput(text.toString(), imageJpegBase64 = preview)
        } catch (e: Exception) {
            return ToolOutput.error("Download failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun mediaStoreInsert(folder: String, name: String, mime: String): Pair<Uri, String>? {
        if (Build.VERSION.SDK_INT < 29) return null
        val segs = folder.trim().replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }.toMutableList()
        val top = segs.firstOrNull()?.lowercase() ?: "download"
        val (collection, rel) = when {
            (top == "pictures" || top == "dcim") && mime.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to segs.joinToString("/")
            (top == "movies" || top == "dcim") && mime.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to segs.joinToString("/")
            top == "music" && mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to segs.joinToString("/")
            top == "documents" ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to segs.joinToString("/")
            top == "download" || top == "downloads" -> {
                segs[0] = Environment.DIRECTORY_DOWNLOADS
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to segs.joinToString("/")
            }
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                (listOf(Environment.DIRECTORY_DOWNLOADS) + segs).joinToString("/")
        }
        val relPath = "$rel/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return null
        return uri to relPath
    }

    private fun buildFileName(requested: String, disposition: String?, url: URL, mime: String): String {
        var name = requested.trim()
        if (name.isEmpty() && disposition != null) {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = try { URLDecoder.decode(it.groupValues[1], "UTF-8") } catch (e: Exception) { it.groupValues[1] }
            }
        }
        if (name.isEmpty()) {
            name = try { URLDecoder.decode(url.path.substringAfterLast('/'), "UTF-8") } catch (e: Exception) { url.path.substringAfterLast('/') }
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(120)
        if (name.isEmpty() || name == "_") name = "download_${System.currentTimeMillis()}"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val currentExt = name.substringAfterLast('.', "").lowercase()
        if (ext != null) {
            val ok = currentExt == ext || (ext == "jpg" && currentExt == "jpeg") || (ext == "jpeg" && currentExt == "jpg")
            if (!ok) name = "$name.$ext"
        }
        return name
    }

    private fun guessMimeFromName(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun imagePreview(f: File): Pair<String, String>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) null else {
            val scale = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val bos = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 75, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP) to "${bounds.outWidth}x${bounds.outHeight}px"
        }
    } catch (e: Throwable) { null }

    // ------------------------------------------------------------------ manage files

    fun list(path: String): ToolOutput {
        val dir = resolve(path)
        if (!dir.exists()) return ToolOutput.error("Folder not found: ${display(dir)}")
        if (!dir.isDirectory) return ToolOutput.ok("${display(dir)} is a file (${humanSize(dir.length())}).")
        val items = dir.listFiles() ?: return if (hasAllFiles()) ToolOutput.error("Cannot read ${display(dir)}") else needPermission
        val sorted = items.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val sb = StringBuilder("${display(dir)} — ${items.size} items\n")
        for (f in sorted.take(200)) {
            if (f.isDirectory) sb.append("📁 ").append(f.name).append("/\n")
            else sb.append("   ").append(f.name).append("  (").append(humanSize(f.length())).append(")\n")
        }
        if (items.size > 200) sb.append("… and ${items.size - 200} more\n")
        return ToolOutput.ok(sb.toString())
    }

    fun search(query: String, path: String): ToolOutput {
        if (query.isBlank()) return ToolOutput.error("query is empty")
        if (!hasAllFiles()) return needPermission
        val start = if (path.isBlank()) root else resolve(path)
        val q = query.lowercase()
        val hits = ArrayList<File>()
        var scanned = 0
        fun walk(d: File, depth: Int) {
            if (hits.size >= 50 || scanned > 40_000 || depth > 12) return
            val kids = d.listFiles() ?: return
            for (k in kids) {
                scanned++
                if (k.name.startsWith(".")) continue
                if (k.name.lowercase().contains(q)) hits.add(k)
                if (k.isDirectory && !(d == root && k.name == "Android")) walk(k, depth + 1)
                if (hits.size >= 50) return
            }
        }
        walk(start, 0)
        if (hits.isEmpty()) return ToolOutput.ok("No files matching \"$query\" in ${display(start)}.")
        return ToolOutput.ok(hits.joinToString("\n") { (if (it.isDirectory) "📁 " else "") + display(it) + if (it.isFile) " (${humanSize(it.length())})" else "" })
    }

    fun mkdir(path: String): ToolOutput {
        if (!hasAllFiles()) return needPermission
        val d = resolve(path)
        return if (d.isDirectory || d.mkdirs()) ToolOutput.ok("Folder ready: ${display(d)}") else ToolOutput.error("Could not create ${display(d)}")
    }

    fun move(from: String, to: String, copy: Boolean): ToolOutput {
        if (!hasAllFiles()) return needPermission
        val src = resolve(from)
        if (!src.exists()) return ToolOutput.error("Not found: ${display(src)}")
        var dst = resolve(to)
        if (dst.isDirectory || to.endsWith("/") || (!dst.exists() && !dst.name.contains('.') && src.isFile)) {
            dst.mkdirs()
            dst = File(dst, src.name)
        } else {
            dst.parentFile?.mkdirs()
        }
        if (dst.exists()) dst = uniqueFile(dst.parentFile ?: root, dst.name)
        return try {
            if (copy) {
                src.copyRecursively(dst, overwrite = false)
            } else if (!src.renameTo(dst)) {
                src.copyRecursively(dst, overwrite = false)
                src.deleteRecursively()
            }
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath, src.absolutePath), null, null)
            ToolOutput.ok("${if (copy) "Copied" else "Moved"} to ${display(dst)}")
        } catch (e: Exception) {
            ToolOutput.error("Failed: ${e.message}")
        }
    }

    fun rename(path: String, newName: String): ToolOutput {
        if (!hasAllFiles()) return needPermission
        val src = resolve(path)
        if (!src.exists()) return ToolOutput.error("Not found: ${display(src)}")
        val dst = File(src.parentFile, newName.replace('/', '_'))
        if (dst.exists()) return ToolOutput.error("${display(dst)} already exists")
        return if (src.renameTo(dst)) {
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath, src.absolutePath), null, null)
            ToolOutput.ok("Renamed to ${display(dst)}")
        } else ToolOutput.error("Rename failed")
    }

    fun delete(path: String, confirmed: Boolean): ToolOutput {
        if (!confirmed) return ToolOutput.error("Not deleted. Ask the user to confirm, then call again with confirmed_by_user=true.")
        if (!hasAllFiles()) return needPermission
        val f = resolve(path)
        if (!f.exists()) return ToolOutput.error("Not found: ${display(f)}")
        val protectedDirs = setOf(root.absolutePath, File(root, "Android").absolutePath, File(root, "DCIM").absolutePath,
            File(root, "Pictures").absolutePath, File(root, "Download").absolutePath, File(root, "Documents").absolutePath)
        if (f.absolutePath in protectedDirs) return ToolOutput.error("Refusing to delete a main system folder (${display(f)}).")
        return if (f.deleteRecursively()) {
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
            ToolOutput.ok("Deleted ${display(f)}")
        } else ToolOutput.error("Could not delete ${display(f)}")
    }

    fun readText(path: String): ToolOutput {
        val f = resolve(path)
        if (!f.isFile) return ToolOutput.error("Not a file: ${display(f)}")
        return try {
            val text = f.bufferedReader().use { r ->
                val buf = CharArray(20_000)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
            ToolOutput.ok(text + if (f.length() > 20_000) "\n…(truncated)" else "")
        } catch (e: Exception) {
            if (!hasAllFiles()) needPermission else ToolOutput.error("Read failed: ${e.message}")
        }
    }

    fun writeText(path: String, content: String, append: Boolean): ToolOutput {
        if (!hasAllFiles()) return needPermission
        val f = resolve(path)
        return try {
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
            ToolOutput.ok("${if (append) "Appended to" else "Wrote"} ${display(f)} (${humanSize(f.length())})")
        } catch (e: Exception) {
            ToolOutput.error("Write failed: ${e.message}")
        }
    }

    /** Gets a content:// Uri other apps can open, via the media scanner. */
    private fun contentUri(f: File): Pair<Uri, String>? {
        val mime = guessMimeFromName(f.name)
        val ref = AtomicReference<Uri?>(null)
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime)) { _, uri -> ref.set(uri); latch.countDown() }
        latch.await(6, TimeUnit.SECONDS)
        val uri = ref.get() ?: return null
        return uri to mime
    }

    fun open(path: String): ToolOutput {
        val f = resolve(path)
        if (!f.exists()) return ToolOutput.error("Not found: ${display(f)}")
        if (f.isDirectory) return list(path)
        val (uri, mime) = contentUri(f) ?: return ToolOutput.error("Could not get a shareable link for this file.")
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            ToolOutput.ok("Opened ${display(f)}")
        } catch (e: Exception) {
            ToolOutput.error("No app can open this file type ($mime).")
        }
    }

    fun share(path: String, app: String): ToolOutput {
        val f = resolve(path)
        if (!f.isFile) return ToolOutput.error("Not a file: ${display(f)}")
        val (uri, mime) = contentUri(f) ?: return ToolOutput.error("Could not get a shareable link for this file.")
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pkg = if (app.isNotBlank()) DeviceTools.findPackage(ctx, app) else null
        return try {
            if (pkg != null) {
                send.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(send)
            } else {
                ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ToolOutput.ok("Share screen opened for ${display(f)}${if (pkg != null) " in $app" else ""}. Use read_screen to continue (pick chat, tap send).")
        } catch (e: Exception) {
            ToolOutput.error("Share failed: ${e.message}")
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        fun humanSize(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
            else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
