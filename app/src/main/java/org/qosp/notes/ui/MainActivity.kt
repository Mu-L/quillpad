package org.qosp.notes.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.size
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.qosp.notes.R
import org.qosp.notes.components.backup.BackupService
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.core.SyncManager
import org.qosp.notes.databinding.ActivityMainBinding
import org.qosp.notes.preferences.SortNavdrawerNotebooksMethod
import org.qosp.notes.ui.attachments.fromUri
import org.qosp.notes.ui.utils.closeAndThen
import org.qosp.notes.ui.utils.collect
import org.qosp.notes.ui.utils.hideKeyboard
import org.qosp.notes.ui.utils.navigateSafely
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    lateinit var appBarConfiguration: AppBarConfiguration
    lateinit var navController: NavController

    private lateinit var binding: ActivityMainBinding
    private val activityModel: ActivityViewModel by viewModels()

    private val topLevelMenu get() = binding.navigationView.menu
    private val notebooksMenu get() = topLevelMenu.findItem(R.id.menu_notebooks).subMenu

    private val primaryDestinations = setOf(
        R.id.fragment_main,
        R.id.fragment_archive,
        R.id.fragment_deleted,
        R.id.fragment_notebook
    )
    private val secondaryDestinations = setOf(
        R.id.fragment_about,
        R.id.fragment_editor,
        R.id.fragment_manage_notebooks,
        R.id.fragment_search,
        R.id.fragment_sync_settings,
        R.id.fragment_settings,
        R.id.fragment_tags,
    )

    @Inject
    lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()

        // androidx.fragment:1.3.3 caused the FragmentContainerView to apply padding to itself when
        // the attribute fitsSystemWindows is enabled. We override it here and let the fragments decide their padding
        ViewCompat.setOnApplyWindowInsetsListener(binding.navHostFragment) { view, insets ->
            insets
        }

        // Apply insets to the NavigationView to prevent it from overlapping with the status bar
        // This is to fix the insets after Android 15 enforcing edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(binding.navigationView) { view, insets ->
            // Reduce padding but keep it below the status bar
            view.setPadding(0, 0, 0, 0)
            insets
        }

        setupDrawerHeader()

        if (intent != null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        } else if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            moveTaskToBack(true)
        }
    }

    /**
     * Copies shared media from a URI to the app's private storage.
     * @param uri The source URI of the media file to copy
     * @return The new URI in app's private storage, or null if copy failed
     */
    private suspend fun copySharedMedia(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val mimeType = contentResolver.getType(uri) ?: return@withContext null
            val newUri = activityModel.copyMediaToPrivateStorage(uri, mimeType) ?: return@withContext null

            contentResolver.openInputStream(uri)?.use { input ->
                contentResolver.openOutputStream(newUri)?.use { output ->
                    input.copyTo(output)
                }
            }
            newUri
        } catch (e: Exception) {
            null
        }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val title = intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""
                val content = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                var uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                // Try getting URI from ClipData if not in EXTRA_STREAM
                if (uri == null && intent.clipData != null && intent.clipData!!.itemCount > 0) {
                    uri = intent.clipData!!.getItemAt(0).uri
                }

                lifecycleScope.launch {
                    val args = bundleOf(
                        "transitionName" to "",
                        "newNoteTitle" to title,
                        "newNoteContent" to content,
                    )

                    if (uri != null) {
                        val newUri = copySharedMedia(uri)
                        if (newUri != null) {
                            withContext(Dispatchers.IO) {
                                val attachment = Attachment.fromUri(this@MainActivity, newUri)
                                args.putParcelableArray("newNoteAttachments", arrayOf(attachment))
                            }
                        }
                    }

                    val link = NavDeepLinkBuilder(this@MainActivity)
                        .setGraph(R.navigation.nav_graph)
                        .setDestination(R.id.fragment_editor)
                        .setArguments(args)
                        .createTaskStackBuilder()
                        .first()

                    navController.handleDeepLink(link)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                var uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                // Try getting URIs from ClipData if not in EXTRA_STREAM
                if (uris == null && intent.clipData != null) {
                    uris = ArrayList()
                    for (i in 0 until intent.clipData!!.itemCount) {
                        intent.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
                    }
                }

                if (!uris.isNullOrEmpty()) {
                    lifecycleScope.launch {
                        val args = bundleOf(
                            "transitionName" to "",
                            "newNoteTitle" to (intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""),
                            "newNoteContent" to (intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""),
                        )

                        withContext(Dispatchers.IO) {
                            val attachments = uris.mapNotNull { uri ->
                                copySharedMedia(uri)?.let { newUri ->
                                    Attachment.fromUri(this@MainActivity, newUri)
                                }
                            }.toTypedArray()

                            if (attachments.isNotEmpty()) {
                                args.putParcelableArray("newNoteAttachments", attachments)
                            }
                        }

                        val link = NavDeepLinkBuilder(this@MainActivity)
                            .setGraph(R.navigation.nav_graph)
                            .setDestination(R.id.fragment_editor)
                            .setArguments(args)
                            .createTaskStackBuilder()
                            .first()

                        navController.handleDeepLink(link)
                    }
                }
            }
            else -> navController.handleDeepLink(intent)
        }
    }

    private fun setupDrawerHeader() {
        val header = binding.navigationView.getHeaderView(0)
        val syncSettingsButton =
            header.findViewById<AppCompatImageButton>(R.id.button_sync_settings)
        val textViewUsername = header.findViewById<AppCompatTextView>(R.id.text_view_username)
        val textViewProvider = header.findViewById<AppCompatTextView>(R.id.text_view_provider)

        // Fixes bug that causes the header to have large padding when the keyboard is open
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            header.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }

        syncSettingsButton.setOnClickListener {
            binding.drawer.closeAndThen {
                navController.navigateSafely(R.id.fragment_sync_settings)
            }
        }

        syncManager.config
            .collect(this@MainActivity) { config ->
                textViewUsername.text =
                    config?.username ?: getString(R.string.indicator_offline_account)
                textViewProvider.text = getString(
                    config?.provider?.nameResource ?: R.string.preferences_currently_not_syncing
                )
            }
    }

    private fun selectCurrentDestinationMenuItem(
        destinationId: Int? = null,
        arguments: Bundle? = null
    ) {
        val destinationId =
            when (val id = destinationId ?: navController.currentDestination?.id ?: return) {
                // Assign destinations that do not have a drawer entry to an existing entry
                R.id.fragment_sync_settings -> R.id.fragment_settings
                R.id.fragment_search -> R.id.fragment_main
                else -> id
            }

        val arguments = arguments ?: navController.currentBackStackEntry?.arguments
        val notebookId = arguments?.getLong("notebookId", -1L)?.takeIf { it >= 0L }

        binding.navigationView.post {
            ((notebooksMenu?.children ?: emptySequence()) + topLevelMenu.children)
                .forEach { item ->
                    item.isChecked = when (notebookId) {
                        null -> item.itemId == destinationId
                        else -> item.itemId == notebookId.toInt()
                    }
                }
        }
    }

    private fun setupDrawerMenuItems() {
        // Alternative of setupWithNavController(), NavigationUI.java
        // Sets up click listeners for all drawer menu items except from notebooks.
        // Those are handled in createNotebookMenuItems()
        (topLevelMenu.children + listOfNotNull(notebooksMenu?.findItem(R.id.fragment_manage_notebooks)))
            .forEach { item ->
                if (item.itemId !in primaryDestinations + secondaryDestinations) return@forEach

                item.setOnMenuItemClickListener {
                    binding.drawer.closeAndThen {
                        navController.navigateSafely(item.itemId)
                    }
                    false // Returning true would cause the menu item to become checked.
                    // We check the menu items only when the destination changes.
                }
            }

        notebooksMenu?.findItem(R.id.nav_default_notebook)?.setOnMenuItemClickListener {
            binding.drawer.closeAndThen {
                navController.navigateSafely(
                    R.id.fragment_notebook,
                    bundleOf(
                        "notebookId" to R.id.nav_default_notebook.toLong(),
                        "notebookName" to getString(R.string.default_notebook),
                    )
                )
            }
            false // Returning true would cause the menu item to become checked.
            // We check the menu items only when the destination changes.
        }
    }

    private fun setupNavigation() {
        fun createNotebookMenuItems(notebooks: List<Notebook>) {

            // Obtaining the current setting of notebook sorting order.
            val sort = runBlocking {
                return@runBlocking preferenceRepository
                    .getAll()
                    .map { it.sortNavdrawerNotebooksMethod }
                    .first()
                    .name
            }

            // Sorting the notebooks.
            val sortedNotebooks: List<Notebook> = when (sort) {
                SortNavdrawerNotebooksMethod.CREATION_ASC.name -> notebooks.sortedBy { it.id }
                SortNavdrawerNotebooksMethod.CREATION_DESC.name -> notebooks.sortedByDescending { it.id }
                SortNavdrawerNotebooksMethod.TITLE_ASC.name -> notebooks.sortedBy { it.name }
                SortNavdrawerNotebooksMethod.TITLE_DESC.name -> notebooks.sortedByDescending { it.name }
                else -> notebooks.sortedBy { it.name }
            }

            // Displaying the notebooks.
            sortedNotebooks.forEach { notebook ->
                val menuItem = notebooksMenu?.findItem(notebook.id.toInt())
                if (menuItem != null && notebook.name != menuItem.title) {
                    menuItem.title = notebook.name
                }
                if (menuItem == null) {
                    notebooksMenu?.add(
                        R.id.section_notebooks,
                        notebook.id.toInt(),
                        0,
                        notebook.name
                    )
                        ?.setIcon(R.drawable.ic_notebook)
                        ?.setCheckable(true)
                        ?.setOnMenuItemClickListener {
                            binding.drawer.closeAndThen {
                                navController.navigateSafely(
                                    R.id.fragment_notebook,
                                    bundleOf(
                                        "notebookId" to notebook.id,
                                        "notebookName" to notebook.name,
                                    )
                                )
                            }
                            false // Returning true would cause the menu item to become checked.
                            // We check the menu items only when the destination changes.
                        }
                }
            }

            selectCurrentDestinationMenuItem()
        }

        appBarConfiguration = AppBarConfiguration(
            primaryDestinations,
            binding.drawer
        )

        navController =
            (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController

        setupDrawerMenuItems()

        navController.addOnDestinationChangedListener { controller, destination, arguments ->
            currentFocus?.hideKeyboard()
            selectCurrentDestinationMenuItem(destination.id, arguments)

            setDrawerEnabled(destination.id != R.id.fragment_editor)
        }

        activityModel.notebooks.collect(this) { (showDefaultNotebook, notebooks) ->
            val notebookIds = (notebooks.map { it.id.toInt() } + R.id.nav_default_notebook).toSet()

            // Remove deleted notebooks from the menu
            (primaryDestinations + secondaryDestinations + notebookIds).let { dests ->
                notebooksMenu?.let { nbMenu ->
                    var index = 0
                    while (index < nbMenu.size) {
                        val item = nbMenu[index]
                        if (item.itemId !in dests) nbMenu.removeItem(item.itemId) else index++
                    }
                }
            }

            createNotebookMenuItems(notebooks)

            val defaultTitle = getString(R.string.default_notebook)
            notebooksMenu?.findItem(R.id.nav_default_notebook)?.apply {
                isVisible = showDefaultNotebook
                title =
                    defaultTitle + " (${getString(R.string.default_string)})".takeIf { notebooks.any { it.name == defaultTitle } }
                        .orEmpty()
            }
        }
    }

    private fun setDrawerEnabled(enabled: Boolean) {
        binding.drawer.setDrawerLockMode(
            if (enabled) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        )
    }

    fun startBackup(backupUri: Uri) {
        BackupService.backupNotes(this, activityModel.notesToBackup, backupUri)
    }

    fun restoreNotes(backupUri: Uri) {
        BackupService.restoreNotes(this, backupUri)
    }
}
