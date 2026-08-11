package me.phie.tawc.licenses

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.R
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.verticalLp

/**
 * Index of the app's licensing: a short notice, then one tappable row
 * per license family, each opening a [LicenseSectionActivity].
 *
 * The full attribution text runs to hundreds of KB. Presented as a
 * single page it is unnavigable, so the generator groups components by
 * license family and this screen shows only the ~14 group headings.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_licenses))
        val pad = (16 * resources.displayMetrics.density).toInt()
        val doc = LicenseDoc.load(this)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildNoticeCard(doc), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        doc.sections.forEachIndexed { index, section ->
            column.addView(
                buildSectionRow(section, index),
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2),
            )
        }

        scaffold.content.addView(
            ScrollView(this).apply { addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        setContentView(scaffold.root)
    }

    private fun buildNoticeCard(doc: LicenseDoc.Doc): android.view.View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
        }
        for (paragraph in doc.intro) {
            column.addView(
                TextView(this).apply {
                    text = paragraph
                    textSize = 13f
                    setPadding(0, 0, 0, cardPad / 2)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        column.addView(
            TextView(this).apply {
                text = doc.sourceUrl
                textSize = 13f
                // Selectable rather than a link: no browser intent from a
                // screen that exists to be readable offline.
                setTextIsSelectable(true)
                setTypeface(Typeface.MONOSPACE)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        return tawcCard().apply { addView(column) }
    }

    private fun buildSectionRow(section: LicenseDoc.Section, index: Int): android.view.View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
        }
        column.addView(
            TextView(this).apply {
                text = section.title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        column.addView(
            TextView(this).apply {
                text = section.subtitle
                textSize = 13f
                alpha = 0.7f
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        return tawcCard().apply {
            addView(column)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@LicensesActivity, LicenseSectionActivity::class.java)
                        .putExtra(LicenseSectionActivity.EXTRA_SECTION, index),
                )
            }
        }
    }
}
