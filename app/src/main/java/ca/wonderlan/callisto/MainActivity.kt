package ca.wonderlan.callisto

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import ca.wonderlan.callisto.ui.ArticleListFragment
import ca.wonderlan.callisto.ui.ComposePostActivity
import ca.wonderlan.callisto.ui.ManageSubscriptionsFragment
import ca.wonderlan.callisto.ui.SettingsFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView

interface DrawerMenuHost {
    fun rebuildDrawerMenu()
}

class MainActivity : AppCompatActivity(), DrawerMenuHost {

    private lateinit var drawer: DrawerLayout
    private lateinit var nav: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size) // hamburger

        drawer = findViewById(R.id.drawer_layout)
        nav = findViewById(R.id.navigation_view)

        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }

        // Build the drawer now (fixed + dynamic items)
        rebuildDrawerMenu()

        nav.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_manage_subs -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, ManageSubscriptionsFragment.newInstance())
                        .addToBackStack(null)
                        .commit()
                }
                R.id.nav_settings -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, SettingsFragment())
                        .addToBackStack(null)
                        .commit()
                }
                else -> {
                    // Treat as a dynamic group entry (title = group name)
                    val selectedGroup = item.title?.toString()
                    if (!selectedGroup.isNullOrBlank()) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                        prefs.edit().putString("group", selectedGroup).apply()

                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, ArticleListFragment.newInstance())
                            .addToBackStack(null)
                            .commit()
                    }
                }
            }
            drawer.closeDrawer(GravityCompat.START)
            true
        }

        findViewById<FloatingActionButton>(R.id.fab_compose).setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val group = prefs.getString("group", getString(R.string.default_group))
            val intent = Intent(this, ComposePostActivity::class.java)
                .putExtra(ComposePostActivity.EXTRA_GROUP, group)
            startActivity(intent)
        }

        if (savedInstanceState == null) {
            // Default to showing the current/selected group
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ArticleListFragment.newInstance())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildDrawerMenu()
    }

    override fun rebuildDrawerMenu() {
        val menu = nav.menu
        menu.clear()

        // Fixed entries
        menu.add(0, R.id.nav_manage_subs, 0, getString(R.string.menu_subscriptions))
        menu.add(0, R.id.nav_settings, 1, getString(R.string.menu_settings))

        // Dynamic entries: subscribed groups
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val subs = prefs.getStringSet("subs", emptySet())!!.toMutableList().sorted()

        var order = 100 // dynamic entries start here
        subs.forEach { group ->
            menu.add(0, View.generateViewId(), order++, group)
        }
    }
}
