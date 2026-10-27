package com.example.localproplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val openVideoRequest = 1001
    private val openFolderRequest = 1002
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var mediaLibraryButton: Button
    private var videos: List<VideoEntry> = emptyList()
    private var folderScanGeneration = 0
    private var sortMode = SortMode.NAME
    private var favoritesOnly = false
    private var librarySource = LibrarySource.FOLDER
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val accent = Color.rgb(115, 226, 194)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        prefs.getString(KEY_FOLDER, null)?.let { loadFolder(Uri.parse(it)) }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) {
            if (librarySource == LibrarySource.MEDIA_STORE) {
                if (hasMediaLibraryAccess()) loadMediaStoreLibrary() else {
                    videos = emptyList()
                    showVideos()
                }
            } else {
                showVideos()
            }
            updateMediaLibraryButton()
        }
    }
    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(12))
            setBackgroundColor(Color.rgb(12, 15, 21))
        }
        root.addView(TextView(this).apply {
            text = "PRO PLAYER"
            textSize = 12f
            letterSpacing = .16f
            setTextColor(accent)
        })
        root.addView(TextView(this).apply {
            text = "Your library"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Private, on-device playback. Your files stay on your device."
            textSize = 14f
            setTextColor(Color.rgb(165, 174, 187))
            setPadding(0, 0, 0, dp(16))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("Add folder") { pickFolder() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(actionButton("Open video") { pickVideo() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        mediaLibraryButton = actionButton("All videos") { requestMediaLibraryAccess() }
        actions.addView(mediaLibraryButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(actions)
        search = EditText(this).apply {
            hint = "Search videos"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(125, 136, 151))
            addTextChangedListener(SimpleTextWatcher { showVideos() })
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        val libraryControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        libraryControls.addView(actionButton("Sort: Name") { showSortDialog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        libraryControls.addView(actionButton("Favorites") { favoritesOnly = !favoritesOnly; showVideos() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        root.addView(libraryControls, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        root.addView(TextView(this).apply {
            text = if (librarySource == LibrarySource.MEDIA_STORE) "VIDEOS ON THIS DEVICE" else "FOLDER LIBRARY"
            textSize = 11f
            letterSpacing = .12f
            setTextColor(accent)
            setPadding(0, dp(18), 0, dp(8))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showVideos()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.rgb(12, 15, 21))
        
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(8f).toFloat()
        }
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
            shape,
            null
        )
        setOnClickListener { action() }
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, openFolderRequest)
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, openVideoRequest)
    }

    @Deprecated("Kept compatible with the initial Activity-only project.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (requestCode) {
            openFolderRequest -> {
                librarySource = LibrarySource.FOLDER
                prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
                loadFolder(uri)
            }
            openVideoRequest -> startPlayer(uri)
        }
    }

    private fun requestMediaLibraryAccess() {
        val hasAccess = hasMediaLibraryAccess()
        val partialAccess = Build.VERSION.SDK_INT >= 34 && hasAccess && !hasFullMediaLibraryAccess()
        if (hasAccess && !partialAccess) {
            librarySource = LibrarySource.MEDIA_STORE
            loadMediaStoreLibrary()
            return
        }
        val permissions = mediaPermissionsToRequest()
        AlertDialog.Builder(this)
            .setTitle(if (partialAccess) "Add more videos" else "Browse device videos")
            .setMessage(if (partialAccess) "Choose additional videos to include in your library. Your existing folder access remains available." else "Allow Pro Player to list videos on this device. On Android 14 or newer, you can grant access to selected videos only, or keep using a folder you choose.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> requestPermissions(permissions, REQUEST_MEDIA_ACCESS) }
            .show()
    }
    private fun mediaPermissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private fun hasMediaLibraryAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 -> checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasFullMediaLibraryAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun updateMediaLibraryButton() {
        if (!::mediaLibraryButton.isInitialized) return
        mediaLibraryButton.text = if (Build.VERSION.SDK_INT >= 34 && hasMediaLibraryAccess() && !hasFullMediaLibraryAccess()) "Add more" else "All videos"
    }

    @Deprecated("Kept compatible with the initial Activity-only project.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA_ACCESS) return
        updateMediaLibraryButton()
        if (hasMediaLibraryAccess()) {
            librarySource = LibrarySource.MEDIA_STORE
            loadMediaStoreLibrary()
        }
    }

    private fun loadMediaStoreLibrary() {
        librarySource = LibrarySource.MEDIA_STORE
        updateMediaLibraryButton()
        val generation = ++folderScanGeneration
        Thread {
            val found = mutableListOf<VideoEntry>()
            runCatching {
                val collections = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.getExternalVolumeNames(this).map { volume -> MediaStore.Video.Media.getContentUri(volume) }
                } else {
                    listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                }
                val projection = mutableListOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION
                ).apply {
                    if (Build.VERSION.SDK_INT >= 29) add(2, MediaStore.Video.Media.RELATIVE_PATH)
                }.toTypedArray()
                collections.forEach { collection ->
                    if (found.size >= MAX_LIBRARY_ITEMS) return@forEach
                    runCatching {
                        contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE")?.use { cursor ->
                            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                            val pathColumn = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                            val dateColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                            val sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                            val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                            while (cursor.moveToNext() && found.size < MAX_LIBRARY_ITEMS) {
                                val id = cursor.getLong(idColumn)
                                val name = cursor.getString(nameColumn) ?: "Video"
                                val path = if (pathColumn >= 0) cursor.getString(pathColumn).orEmpty().trimEnd('/') else ""
                                val modified = if (dateColumn >= 0 && !cursor.isNull(dateColumn)) cursor.getLong(dateColumn) * 1_000L else 0L
                                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L
                                val duration = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn) else 0L
                                val itemUri = ContentUris.withAppendedId(collection, id)
                                found += VideoEntry(name, itemUri, path, modified, size, duration)
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                if (generation == folderScanGeneration && librarySource == LibrarySource.MEDIA_STORE) {
                    videos = found
                    showVideos()
                }
            }
        }.start()
    }
    private fun loadFolder(treeUri: Uri) {
        librarySource = LibrarySource.FOLDER
        updateMediaLibraryButton()
        val generation = ++folderScanGeneration
        Thread {
            val found = mutableListOf<VideoEntry>()
            runCatching {
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                val pending = ArrayDeque<FolderNode>()
                val visited = mutableSetOf<String>()
                pending.addLast(FolderNode(rootId, ""))
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                )
                while (pending.isNotEmpty() && found.size < MAX_LIBRARY_ITEMS) {
                    val folder = pending.removeFirst()
                    if (!visited.add(folder.documentId)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId)
                    contentResolver.query(
                        children,
                        projection,
                        null,
                        null,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        while (cursor.moveToNext() && found.size < MAX_LIBRARY_ITEMS) {
                            val documentId = cursor.getString(idCol) ?: continue
                            val name = cursor.getString(nameCol) ?: "Video"
                            val mime = cursor.getString(mimeCol).orEmpty()
                            if (mime.startsWith("video/")) {
                                val modified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                                found += VideoEntry(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), folder.relativePath, modified, size)
                            } else if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                val path = listOf(folder.relativePath, name).filter { it.isNotBlank() }.joinToString("/")
                                pending.addLast(FolderNode(documentId, path))
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                if (generation == folderScanGeneration) {
                    videos = found
                    showVideos()
                }
            }
        }.start()
    }
    private fun showVideos() {
        if (!::list.isInitialized) return
        val query = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val favorites = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        
        Thread {
            val matching = videos.filter { entry ->
                "${entry.name} ${entry.folderPath}".lowercase().contains(query) && (!favoritesOnly || entry.uri.toString() in favorites)
            }
            val filtered = when (sortMode) {
                SortMode.NAME -> matching.sortedWith(compareBy({ it.folderPath.lowercase() }, { it.name.lowercase() }))
                SortMode.RECENT -> matching.sortedByDescending { it.lastModified }
                SortMode.SIZE -> matching.sortedByDescending { it.sizeBytes }
            }
            
            val continuing = filtered.filter { entry ->
                prefs.getLong("resume_position_${Uri.encode(entry.uri.toString())}", 0L) > 5_000L
            }

            runOnUiThread {
                populateList(filtered, continuing, favorites)
            }
        }.start()
    }

    private fun populateList(filtered: List<VideoEntry>, continuing: List<VideoEntry>, favorites: Set<String>) {
        if (!::list.isInitialized) return
        list.removeAllViews()
        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (videos.isEmpty()) "No folder selected yet. Add a folder to browse its videos, or open one video directly." else "No videos match these filters."
                textSize = 15f
                setTextColor(Color.rgb(165, 174, 187))
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(36), dp(18), dp(36))
            })
        }
        if (continuing.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "CONTINUE WATCHING"
                textSize = 11f
                letterSpacing = .12f
                setTextColor(accent)
                setPadding(dp(8), dp(16), dp(8), dp(8))
            })
            val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            continuing.take(10).forEach { entry ->
                val key = Uri.encode(entry.uri.toString())
                val position = prefs.getLong("resume_position_$key", 0L)
                val duration = prefs.getLong("duration_$key", entry.durationMs)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(24, 29, 38))
                        cornerRadius = dp(8f).toFloat()
                    }
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
                        shape,
                        null
                    )
                    setOnClickListener { startPlayer(entry.uri) }
                }
                card.addView(TextView(this).apply {
                    text = entry.name
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 2
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                card.addView(TextView(this).apply {
                    text = "Continue at ${formatTime(position)}"
                    textSize = 12f
                    setTextColor(Color.rgb(165, 174, 187))
                })
                card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = if (duration > 0L) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(6) })
                cards.addView(card, LinearLayout.LayoutParams(dp(240), dp(106)).apply { rightMargin = dp(10) })
            }
            list.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(8), 0, dp(8), 0)
                clipToPadding = false
                addView(cards)
            }, LinearLayout.LayoutParams(-1, dp(110)))
        }
        filtered.take(300).forEach { entry ->
            val resumeAt = prefs.getLong("resume_position_${Uri.encode(entry.uri.toString())}", 0L)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val location = entry.folderPath.takeIf { it.isNotBlank() }?.let { "\n      $it" }.orEmpty()
            val video = TextView(this).apply {
                text = if (resumeAt > 5_000L) "▶   ${entry.name}$location\n      Continue at ${formatTime(resumeAt)}" else "▶   ${entry.name}$location"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(14), dp(8), dp(14))
                contentDescription = "Play ${entry.name}${if (resumeAt > 5_000L) ", continue at ${formatTime(resumeAt)}" else ""}"
                
                val ripple = android.util.TypedValue().apply {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                }
                background = getDrawable(ripple.resourceId)
                setOnClickListener { startPlayer(entry.uri) }
            }
            row.addView(video, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val isFavorite = entry.uri.toString() in favorites
            row.addView(Button(this).apply {
                text = if (isFavorite) "★" else "☆"
                contentDescription = if (isFavorite) "Remove ${entry.name} from favorites" else "Add ${entry.name} to favorites"
                isAllCaps = false
                textSize = 20f
                setTextColor(accent)
                
                val ripple = android.util.TypedValue().apply {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
                }
                background = getDrawable(ripple.resourceId)
                
                setOnClickListener {
                    val updated = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toMutableSet()
                    if (!updated.add(entry.uri.toString())) updated.remove(entry.uri.toString())
                    prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
                    showVideos()
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (filtered.size > 300) {
            list.addView(TextView(this).apply {
                text = "+ ${filtered.size - 300} more not shown. Use search to refine."
                textSize = 13f
                setTextColor(Color.rgb(165, 174, 187))
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(24))
            })
        }
    }

    private fun showSortDialog() {
        val choices = arrayOf("Name A–Z", "Recently modified", "Largest first")
        AlertDialog.Builder(this)
            .setTitle("Sort videos")
            .setSingleChoiceItems(choices, sortMode.ordinal) { dialog, selected ->
                sortMode = SortMode.entries[selected]
                dialog.dismiss()
                showVideos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun startPlayer(uri: Uri) {
        val intent = Intent(this, PlayerActivity::class.java).setData(uri)
        val selected = videos.firstOrNull { it.uri == uri }
        val queue = selected?.let { current -> videos.filter { it.folderPath == current.folderPath } }.orEmpty()
        val queueUris = queue.map { it.uri.toString() }
        val startIndex = queueUris.indexOf(uri.toString())
        if (startIndex >= 0) {
            intent.putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_URIS, ArrayList(queueUris))
            intent.putExtra(PlayerActivity.EXTRA_QUEUE_INDEX, startIndex)
        }
        startActivity(intent)
    }
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private data class VideoEntry(val name: String, val uri: Uri, val folderPath: String, val lastModified: Long, val sizeBytes: Long, val durationMs: Long = 0L)
    private data class FolderNode(val documentId: String, val relativePath: String)
    private enum class SortMode { NAME, RECENT, SIZE }
    private enum class LibrarySource { FOLDER, MEDIA_STORE }
    private companion object {
        const val KEY_FOLDER = "folder_uri"
        const val MAX_LIBRARY_ITEMS = 10_000
        const val REQUEST_MEDIA_ACCESS = 1004
        const val KEY_FAVORITES = "favorite_video_uris"
    }
}
