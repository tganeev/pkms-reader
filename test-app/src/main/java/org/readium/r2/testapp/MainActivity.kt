/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.readium.r2.testapp.sync.SyncManager

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private val viewModel: MainViewModel by viewModels()
    private lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(statusBars.left, statusBars.top, statusBars.right, statusBars.bottom)
            insets
        }

        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_bookshelf,
                R.id.navigation_catalog_list,
                R.id.navigation_about
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        viewModel.channel.receive(this) { handleEvent(it) }
        syncManager = SyncManager(this, (application as Application).bookRepository)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sync -> {
                performSync()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performSync() {
        lifecycleScope.launch {
            val snackbar = Snackbar.make(
                findViewById(android.R.id.content),
                "Синхронизация...",
                Snackbar.LENGTH_INDEFINITE
            )
            snackbar.show()

            try {
                val app = application as Application
                val result = app.syncManager.syncAllBooks()

                snackbar.dismiss()

                result.onSuccess { response ->
                    val message = "Синхронизация завершена:\n" +
                        "📚 Создано книг: ${response.booksCreated}\n" +
                        "🔄 Обновлено книг: ${response.booksUpdated}\n" +
                        "📊 Создано записей: ${response.statsCreated}\n" +
                        "🔄 Обновлено записей: ${response.statsUpdated}"

                    Snackbar.make(
                        findViewById(android.R.id.content),
                        message,
                        Snackbar.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Ошибка: ${error.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                snackbar.dismiss()
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Ошибка: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Запрещаем автоматическое выключение экрана
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        // Восстанавливаем стандартное поведение
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun handleEvent(event: MainViewModel.Event) {
        when (event) {
            is MainViewModel.Event.ImportPublicationSuccess ->
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.import_publication_success),
                    Snackbar.LENGTH_LONG
                ).show()

            is MainViewModel.Event.ImportPublicationError -> {
                event.error.toUserError().show(this)
            }
        }
    }
}
