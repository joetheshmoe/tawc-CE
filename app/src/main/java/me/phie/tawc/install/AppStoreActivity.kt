package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import me.phie.tawc.R
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp

/**
 * In-app "app store": a curated list of common packages ([PackageStore])
 * installable into a chosen distro with one tap. Tapping Install hands the
 * apt step to [InstallationService.startInstallPackages] (foreground service
 * + live log), then the user watches progress in [LogScreenActivity] and the
 * item flips to Installed on return.
 */
class AppStoreActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var installId: String? = null
    private lateinit var content: LinearLayout
    private lateinit var targetHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_app_store))
        val pad = (16 * resources.displayMetrics.density).toInt()

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(content, verticalLp(MATCH_PARENT, WRAP_CONTENT))

        // Pick the target install: explicit extra, else the first READY one.
        installId = intent?.getStringExtra(EXTRA_ID)
            ?: store.list().firstOrNull { it.state == Installation.State.READY }?.id

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
            buildSections(pad)
        }
        setContentView(scaffold.root)
    }

    override fun onResume() {
        super.onResume()
        if (installId != null) {
            refresh()
        }
    }

    private fun buildSections(pad: Int) {
        content.removeAllViews()
        val inst = installId?.let { store.load(it) }
        val label = inst?.label ?: inst?.id ?: ""
        targetHint.text = getString(R.string.store_target_hint, label)
        content.addView(targetHint, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        addSection(getString(R.string.store_section_apps), PackageStore.APPS, pad)
        addSection(getString(R.string.store_section_tools), PackageStore.TOOLS, pad)
    }

    private fun addSection(title: String, items: List<StoreItem>, pad: Int) {
        content.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2),
        )
        for (item in items) {
            content.addView(buildItemCard(item, pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        }
    }

    private fun buildItemCard(item: StoreItem, pad: Int): MaterialCardView {
        val card = tawcCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = item.name
            textSize = 16f
        })
        col.addView(TextView(this).apply {
            text = item.description
            textSize = 13f
            alpha = 0.7f
        })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val rootfs = installId?.let { store.rootfsDir(it).absolutePath } ?: ""
        val installed = rootfs.isNotEmpty() && PackageStore.isInstalled(item, rootfs)
        val button: MaterialButton = if (installed) {
            tonalButton(getString(R.string.store_button_installed)) {}.apply { isEnabled = false }
        } else {
            tonalButton(getString(R.string.store_button_install)) { startInstall(item) }
        }
        row.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
            it.marginStart = pad
        })
        card.addView(row)
        return card
    }

    private fun startInstall(item: StoreItem) {
        val id = installId ?: return
        InstallationService.startInstallPackages(
            this, id, item.packages, item.name,
        )
        startActivity(LogScreenActivity.intentFor(this, "install-packages:$id"))
    }

    private fun refresh() {
        if (::content.isInitialized) {
            buildSections((16 * resources.displayMetrics.density).toInt())
        }
    }

    companion object {
        const val EXTRA_ID = "id"

        fun intentFor(id: String): Intent =
            Intent().putExtra(EXTRA_ID, id)
    }
}
