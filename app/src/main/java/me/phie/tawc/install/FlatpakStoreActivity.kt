package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.R
import me.phie.tawc.launcher.EntryLauncher
import me.phie.tawc.launcher.LauncherEntry
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.verticalLp

/**
 * Flatpak store: a Flathub-backed shelf + search. Nothing is bundled —
 * the featured list is fetched on demand (the "Load catalog" button) and
 * search hits Flathub's API directly; icons download + cache at runtime
 * (see [FlatpakCatalog] / [FlatpakIconLoader] / notes/flatpak.md).
 * Installing hands off to [InstallationService.startInstallFlatpak];
 * once installed an app is launchable here and from the launcher (its
 * `.desktop` is written to the managed dir).
 */
class FlatpakStoreActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var installId: String? = null
    private var installation: Installation? = null
    private lateinit var content: LinearLayout
    private lateinit var listColumn: LinearLayout
    private lateinit var targetHint: TextView
    private lateinit var searchField: EditText
    private lateinit var iconLoader: FlatpakIconLoader
    private var featured: List<FlatpakApp> = emptyList()
    private var current: List<FlatpakApp> = emptyList()
    private var searchJob: Job? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_flatpak_store))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(content, verticalLp(MATCH_PARENT, WRAP_CONTENT))

        installId = intent?.getStringExtra(EXTRA_ID)
            ?: store.list().firstOrNull { it.state == Installation.State.READY }?.id
        installation = installId?.let { store.load(it) }

        targetHint = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
        }
        content.addView(targetHint, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        if (installId == null) {
            content.addView(
                TextView(this).apply {
                    text = getString(R.string.store_no_distro_hint)
                    textSize = 14f
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT),
            )
        } else {
            iconLoader = FlatpakIconLoader(this, uiScope, iconPx())
            buildControls()
        }
        setContentView(scaffold.root)
    }

    override fun onResume() {
        super.onResume()
        if (installId != null && ::listColumn.isInitialized) {
            render(current)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.coroutineContext[Job]?.cancel()
    }

    private fun buildControls() {
        val label = installation?.label ?: installation?.id ?: ""
        targetHint.text = getString(R.string.store_target_hint, label)

        content.addView(
            EditText(this).apply {
                hint = getString(R.string.flatpak_search_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        onQuery(s?.toString().orEmpty())
                    }
                })
            }.also { searchField = it },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        content.addView(
            primaryButton(getString(R.string.flatpak_load_catalog)) { loadFeatured() },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        val scroll = ScrollView(this)
        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listColumn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        render(emptyList())
    }

    private fun loadFeatured() {
        if (featured.isNotEmpty()) {
            render(featured)
            return
        }
        renderMessage(getString(R.string.flatpak_loading))
        uiScope.launch {
            val apps = withContext(Dispatchers.IO) { FlatpakCatalog.fetchFeatured() }
            featured = apps
            if (searchField.text.toString().trim().isEmpty()) {
                if (apps.isEmpty()) renderMessage(getString(R.string.flatpak_load_failed)) else render(apps)
            }
        }
    }

    private fun onQuery(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            render(if (featured.isEmpty()) emptyList() else featured)
            return
        }
        searchJob = uiScope.launch {
            delay(300)
            val apps = withContext(Dispatchers.IO) { FlatpakCatalog.search(q) }
            if (q == searchField.text.toString().trim()) render(apps)
        }
    }

    private fun renderMessage(text: String) {
        current = emptyList()
        if (!::listColumn.isInitialized) return
        listColumn.removeAllViews()
        listColumn.addView(
            TextView(this).apply {
                this.text = text
                textSize = 14f
                alpha = 0.7f
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    private fun render(apps: List<FlatpakApp>) {
        current = apps
        if (!::listColumn.isInitialized) return
        listColumn.removeAllViews()
        when {
            apps.isEmpty() -> listColumn.addView(
                TextView(this).apply {
                    text = getString(R.string.flatpak_empty)
                    textSize = 14f
                    alpha = 0.7f
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT),
            )
            else -> for (app in apps) {
                listColumn.addView(buildRow(app), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
            }
        }
    }

    private fun buildRow(app: FlatpakApp): MaterialCardView {
        val card = tawcCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val icon = ImageView(this).apply {
            val s = iconPx()
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        iconLoader.load(app, icon)
        row.addView(icon)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = app.name
            textSize = 16f
        })
        if (app.summary.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = app.summary
                textSize = 13f
                alpha = 0.7f
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.marginStart = pad })

        val rootfs = installId?.let { store.rootfsDir(it).absolutePath } ?: ""
        val installed = rootfs.isNotEmpty() && FlatpakInstaller.isInstalled(rootfs, app.appId)
        val button: MaterialButton = if (installed) {
            primaryButton(getString(R.string.flatpak_open)) { launch(app) }
        } else {
            primaryButton(getString(R.string.store_button_install)) { startInstall(app) }
        }
        row.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginStart = pad })
        card.addView(row)
        return card
    }

    private fun startInstall(app: FlatpakApp) {
        val id = installId ?: return
        InstallationService.startInstallFlatpak(this, id, app.appId, app.name)
        startActivity(LogScreenActivity.intentFor(this, "install-flatpak:$id"))
    }

    private fun launch(app: FlatpakApp) {
        val inst = installation ?: return
        val entry = LauncherEntry(
            id = app.appId,
            name = app.name,
            comment = "",
            exec = "/usr/local/bin/tawc-flatpak-run ${app.appId}",
            terminal = false,
            iconPath = "",
            path = "",
        )
        EntryLauncher.launch(applicationContext, inst, entry)
    }

    private fun iconPx(): Int = (48 * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "id"

        fun intentFor(id: String): Intent =
            Intent().putExtra(EXTRA_ID, id)
    }
}
