package me.phie.tawc.onboarding

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import me.phie.tawc.BuildConfig
import me.phie.tawc.R
import me.phie.tawc.Settings
import me.phie.tawc.install.InstallationService
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp

/**
 * First-launch onboarding: Welcome → pick a distro → create the environment
 * (optional XFCE) → congrats. Runs once; [Settings.onboardingComplete] gates
 * it. The "Create" step starts the real distro install via
 * [InstallationService] and hands the user to the live log screen.
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = 0
    private var selectedDistroKey: String? = "debian-sid"
    private var selectedDistroLabel: String = "Debian"
    private var wantXfce = true
    private var createdInstallId: String? = null

    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout

    private data class DistroChoice(
        val key: String,
        val label: String,
        val subtitle: String,
        val color: Int,
    )

    private val distros = listOf(
        DistroChoice("debian-sid", "Debian", "Stable & lightweight", 0xFF1976D2.toInt()),
        DistroChoice("arch", "Arch", "Advanced users", 0xFF7B1FA2.toInt()),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                this@OnboardingActivity, R.color.tawc_window_bg,
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val pad = (20 * resources.displayMetrics.density).toInt()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        render()
    }

    private fun render() {
        content.removeAllViews()
        footer.removeAllViews()
        val pad = (20 * resources.displayMetrics.density).toInt()
        when (step) {
            0 -> {
                content.addView(header(getString(R.string.onboarding_welcome_title)), verticalLp(MATCH_PARENT, WRAP_CONTENT))
                content.addView(
                    TextView(this).apply {
                        text = getString(R.string.onboarding_welcome_desc)
                        textSize = 16f
                        alpha = 0.8f
                    },
                    verticalLp(MATCH_PARENT, WRAP_CONTENT),
                )
                footer.addView(primaryButton(getString(R.string.onboarding_get_started)) {
                    step = 1; render()
                }, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
                footer.addView(tonalButton(getString(R.string.onboarding_skip)) { finishOnboarding() })
            }
            1 -> {
                content.addView(header(getString(R.string.onboarding_pick_distro_title)), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
                for (d in distros) {
                    content.addView(distroCard(d, pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
                }
                content.addView(
                    TextView(this).apply {
                        text = getString(R.string.onboarding_distro_recommend)
                        textSize = 13f
                        alpha = 0.7f
                    },
                    verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
                )
                footer.addView(primaryButton(getString(R.string.onboarding_next)) { next() }, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
                footer.addView(backButton())
            }
            2 -> {
                content.addView(header(getString(R.string.onboarding_create_title)), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
                val xfce = CheckBox(this).apply {
                    text = getString(R.string.onboarding_xfce)
                    textSize = 16f
                    isChecked = wantXfce
                    setOnCheckedChangeListener { _, checked -> wantXfce = checked }
                }
                content.addView(xfce, verticalLp(MATCH_PARENT, WRAP_CONTENT))
                content.addView(
                    TextView(this).apply {
                        text = getString(R.string.onboarding_xfce_desc)
                        textSize = 13f
                        alpha = 0.7f
                    },
                    verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
                )
                content.addView(hintRow(getString(R.string.onboarding_apps_hint), pad))
                content.addView(hintRow(getString(R.string.onboarding_homescreen_hint), pad))
                footer.addView(primaryButton(getString(R.string.onboarding_create_button)) { createEnvironment() }, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
                footer.addView(backButton())
            }
            3 -> {
                content.addView(header(getString(R.string.onboarding_congrats_title)), verticalLp(MATCH_PARENT, WRAP_CONTENT))
                content.addView(
                    TextView(this).apply {
                        text = getString(R.string.onboarding_congrats_desc)
                        textSize = 16f
                        alpha = 0.8f
                    },
                    verticalLp(MATCH_PARENT, WRAP_CONTENT),
                )
                footer.addView(tonalButton(getString(R.string.onboarding_view_progress)) {
                    val id = createdInstallId ?: installIdFor(selectedDistroKey)
                    startActivity(LogScreenActivity.intentFor(this@OnboardingActivity, "install:$id"))
                }, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
                footer.addView(primaryButton(getString(R.string.onboarding_start)) { finishOnboarding() }, verticalLp(MATCH_PARENT, WRAP_CONTENT))
            }
        }
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun hintRow(text: String, pad: Int): View = TextView(this).apply {
        this.text = "•  $text"
        textSize = 14f
        alpha = 0.8f
        setPadding(0, 0, 0, pad / 2)
    }

    private fun distroCard(d: DistroChoice, pad: Int): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            setCardBackgroundColor(d.color)
            strokeWidth = 0
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = d.label
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = d.subtitle
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
        })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (d.key == selectedDistroKey) {
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
        }
        card.addView(row)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener {
            selectedDistroKey = d.key
            selectedDistroLabel = d.label
            render()
        }
        return card
    }

    private fun backButton(): MaterialButton = tonalButton(getString(R.string.onboarding_back)) {
        step = (step - 1).coerceAtLeast(0)
        render()
    }

    private fun next() {
        step = (step + 1).coerceAtMost(3)
        render()
    }

    /** Kick off the real distro install and advance to the congrats step. */
    private fun createEnvironment() {
        val distroKey = selectedDistroKey ?: return
        val id = installIdFor(distroKey)
        createdInstallId = id
        val desktops = if (wantXfce) "xfce" else null
        // Debug builds route the mirror through the dev cache proxy, like
        // InstallActivity's default; release installs hit real mirrors.
        val proxy = if (BuildConfig.DEBUG) "http://127.0.0.1:8080/proxy/" else null
        InstallationService.startInstall(
            this, id, null, distroKey, selectedDistroLabel, proxy,
            null, false, null, desktops,
        )
        step = 3
        render()
    }

    private fun installIdFor(distroKey: String?): String = when (distroKey) {
        "debian-sid" -> uniqueId("sid")
        else -> uniqueId(distroKey ?: "linux")
    }

    /** "debian" unless taken, then "debian2", … */
    private fun uniqueId(base: String): String {
        val store = InstallationStore(this)
        var id = base
        var n = 2
        while (store.load(id) != null) {
            id = base + n
            n++
        }
        return id
    }

    private fun finishOnboarding() {
        Settings.onboardingComplete = true
        // MainActivity finished itself to hand off to us, so relaunch it
        // fresh (it now sees onboardingComplete and renders the home screen).
        startActivity(
            Intent(this, me.phie.tawc.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
