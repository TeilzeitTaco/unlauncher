package com.sduduzog.slimlauncher.adapters

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jkuester.unlauncher.datastore.UnlauncherApp
import com.sduduzog.slimlauncher.R
import com.sduduzog.slimlauncher.datasource.UnlauncherDataSource
import com.sduduzog.slimlauncher.ui.main.HomeFragment
import com.sduduzog.slimlauncher.utils.firstUppercase
import com.sduduzog.slimlauncher.utils.gravity
import com.sduduzog.slimlauncher.utils.isPinnedApp
import kotlin.math.ceil


class AppDrawerAdapter(
    private val listener: HomeFragment.AppDrawerListener,
    lifecycleOwner: LifecycleOwner,
    private val unlauncherDataSource: UnlauncherDataSource
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val workAppPrefix = "\uD83C\uDD46 " // Unicode for boxed w
    private val regex = Regex("[!@#\$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/? ]")
    private var apps: List<UnlauncherApp> = listOf()
    private var filteredApps: List<AppDrawerRow> = listOf()
    private var gravity = 3

    init {
        unlauncherDataSource.unlauncherAppsRepo.liveData().observe(
            lifecycleOwner
        ) { unlauncherApps ->
            apps = unlauncherApps.appsList
            updateFilteredApps()
        }
        unlauncherDataSource.corePreferencesRepo.liveData().observe(lifecycleOwner) { corePrefs ->
            gravity = corePrefs.alignmentFormat.gravity()
            updateFilteredApps()
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val drawerRow = filteredApps[position]) {
            is AppDrawerRow.Item -> {
                val unlauncherApp = drawerRow.app
                (holder as ItemViewHolder).bind(unlauncherApp, position)
                holder.itemView.setOnClickListener {
                    listener.onAppClicked(unlauncherApp)
                }
                holder.itemView.setOnLongClickListener {
                    listener.onAppLongClicked(unlauncherApp, it)
                }
            }

            is AppDrawerRow.Header -> (holder as HeaderViewHolder).bind(drawerRow.letter)
        }
    }

    fun getFirstApp(): UnlauncherApp {
        return filteredApps.filterIsInstance<AppDrawerRow.Item>().first().app
    }

    override fun getItemViewType(position: Int): Int = filteredApps[position].rowType.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (RowType.values()[viewType]) {
            RowType.App -> ItemViewHolder(
                inflater.inflate(R.layout.add_app_fragment_list_item, parent, false)
            )

            RowType.Header -> HeaderViewHolder(
                inflater.inflate(R.layout.app_drawer_fragment_header_item, parent, false)
            )
        }
    }

    private fun onlyFirstStringStartsWith(first: String, second: String, query: String): Boolean {
        return first.startsWith(query, true) and !second.startsWith(query, true)
    }

    fun setAppFilter(query: String = "") {
        val filterQuery = regex.replace(query, "")
        updateFilteredApps(filterQuery)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateFilteredApps(filterQuery: String = "") {
        val corePreferences = unlauncherDataSource.corePreferencesRepo.get()
        val showDrawerHeadings = corePreferences.showDrawerHeadings
        val searchAllApps = corePreferences.searchAllAppsInDrawer && filterQuery != ""
        val noQuery = filterQuery == ""
        if (!noQuery) {
            offset = 0
        }

        val displayableApps = apps
            .filter { app ->
                !(!noQuery && isPinnedApp(app.packageName)) && (app.displayInDrawer || searchAllApps) && regex.replace(app.displayName, "")
                    .contains(filterQuery, ignoreCase = true)
            }

        val includeHeadings = !showDrawerHeadings || !noQuery

        val updatedApps = when (includeHeadings) {
            true ->
                displayableApps
                    .sortedWith { a, b ->
                        val aIsSpecial = isPinnedApp(a.packageName)
                        val bIsSpecial = isPinnedApp(b.packageName)
                        when {
                            // pinned apps, implemented ksana7312.56206764119
                            noQuery && (aIsSpecial xor bIsSpecial) -> if (aIsSpecial) -1 else 1

                            // if an app's name starts with the query prefer it
                            onlyFirstStringStartsWith(
                                a.displayName,
                                b.displayName,
                                filterQuery
                            ) -> -1
                            onlyFirstStringStartsWith(
                                b.displayName,
                                a.displayName,
                                filterQuery
                            ) -> 1

                            // if both or none start with the query sort in normal oder
                            else -> a.displayName.compareTo(b.displayName, true)
                        }
                    }.map { AppDrawerRow.Item(it) }
            // building a list with each letter and filtered app resulting in a list of
            // [
            // Header<"G">, App<"Gmail">, App<"Google Drive">, Header<"Y">, App<"YouTube">, ...
            // ]
            false ->
                displayableApps
                    .groupBy { app ->
                        if (app.displayName.startsWith(workAppPrefix)) {
                            workAppPrefix
                        } else {
                            app.displayName.firstUppercase()
                        }
                    }.flatMap { entry ->
                        listOf(
                            AppDrawerRow.Header(entry.key),
                            *(entry.value.map { AppDrawerRow.Item(it) }).toTypedArray()
                        )
                    }
        }
        if (updatedApps != filteredApps) {
            filteredApps = updatedApps
            notifyDataSetChanged()
        }
    }

    val searchBoxListener: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            // Do nothing
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            // Do nothing
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            setAppFilter(s.toString())
        }
    }

    private var offset = 0
    private val categoryRegex = Regex("^([^ ]+):(.+)$")
    private val spaceRegex = Regex(" +")

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val item: TextView = itemView.findViewById(R.id.aa_list_item_app_name)

        override fun toString(): String {
            return "${super.toString()} '${item.text}'"
        }

        @SuppressLint("SetTextI18n")
        fun bind(item: UnlauncherApp, position: Int) {
            if (isPinnedApp(item.packageName)) {
                this.item.text = "*  [ ${item.displayName.lowercase()} ]"
                this.item.gravity = Gravity.LEFT
                if (position + 1 > offset)
                    offset = position + 1

            } else {
                val normalizedDisplayName = spaceRegex.replace(item.displayName.lowercase(), " ")
                    .trim().replace("[", "(").replace("]", ")")

                this.item.text = SpannableStringBuilder().apply {
                    (categoryRegex.find(normalizedDisplayName)?.also {
                        val (_, category, name) = it.groupValues
                        append(name.trim())
                        if (offset > 0) {
                            // don't show categories on query
                            append(" [ ")
                            append(category.trim().uppercase(), StyleSpan(Typeface.ITALIC), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }) ?: append(normalizedDisplayName)
                    append(" ${(position + 1 - offset).toString().padStart(3)}", MonospaceSpan(" 0123456789"), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(".")
                }

                this.item.gravity = Gravity.RIGHT
            }
        }
    }

    inner class HeaderViewHolder(headerView: View) : RecyclerView.ViewHolder(headerView) {
        private val header: TextView = itemView.findViewById(R.id.aa_list_header_letter)

        override fun toString(): String {
            return "${super.toString()} '${header.text}'"
        }

        fun bind(letter: String) {
            header.text = letter
        }
    }
}

enum class RowType {
    Header,
    App
}

sealed class AppDrawerRow(val rowType: RowType) {
    data class Item(val app: UnlauncherApp) : AppDrawerRow(RowType.App)

    data class Header(val letter: String) : AppDrawerRow(RowType.Header)
}


// taken from https://github.com/ChrisRenke/FixedSpans/blob/master/fixed-spans/src/com/chrisrenke/fixedspans/MonospaceSpan.java
/** A [ReplacementSpan] that monospaces single-line text.  */
class MonospaceSpan : ReplacementSpan {
    private val relativeCharacters: String?
    var squish = 0.945f  // push chars together a bit

    /**
     * Set the `relativeMonospace` flag to true to monospace based on the widest character
     * in the content string; false will base the monospace on the widest width of 'M' or 'W'.
     */
    constructor(relativeMonospace: Boolean) {
        this.relativeCharacters = if (relativeMonospace) null else REFERENCE_CHARACTERS
    }

    /** Use the widest character from `relativeCharacters` to determine monospace width.  */
    constructor(relativeCharacters: String = REFERENCE_CHARACTERS) {
        this.relativeCharacters = relativeCharacters
    }

    override fun getSize(
        paint: Paint, text: CharSequence,
        start: Int, end: Int,
        fm: FontMetricsInt?
    ): Int {
        if (fm != null)
            paint.getFontMetricsInt(fm)

        return ceil(((end - start) * getMonoWidth(paint, text.subSequence(start, end))).toDouble())
            .toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int,
        bottom: Int, paint: Paint
    ) {
        val actualText = text.subSequence(start, end)
        val monoWidth = getMonoWidth(paint, actualText)
        for (i in actualText.indices) {
            val textWidth = paint.measureText(actualText, i, i + 1)
            val halfFreeSpace = (textWidth - monoWidth) / 2f
            canvas.drawText(
                actualText,
                i, i + 1,
                x + (monoWidth * i) - halfFreeSpace,
                y.toFloat(),
                paint
            )
        }
    }

    private fun getMonoWidth(paint: Paint, text: CharSequence): Float {
        (relativeCharacters ?: text).also {
            return it.mapIndexed { i, _ -> paint.measureText(it, i, i + 1) * squish }.max()
        }
    }

    companion object {
        private const val REFERENCE_CHARACTERS = "MW"
    }
}
