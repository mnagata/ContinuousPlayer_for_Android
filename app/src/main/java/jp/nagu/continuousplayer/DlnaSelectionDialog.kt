package jp.nagu.continuousplayer

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import kotlin.math.min

internal data class DlnaRow(
    val title: String,
    val icon: Int,
    val detail: String = "",
    val description: String = title
)

internal data class DlnaDialogContent(
    val title: String,
    val label: Int? = R.string.dlna_dialog_label,
    val hint: Int? = null,
    val fileSelection: Boolean = false,
    val summary: String = "",
    val rows: List<DlnaRow> = emptyList(),
    val message: String = "",
    val loading: Boolean = false,
    val initialSelection: Int = 0,
    val upLabel: Int = R.string.usb_parent,
    val onUp: (() -> Unit)? = null,
    val onRefresh: (() -> Unit)? = null,
    val refreshLabel: Int = R.string.usb_refresh,
    val registerLabel: Int = R.string.folder_register,
    val onRegister: (() -> Unit)? = null,
    val onClose: () -> Unit,
    val onSelected: (Int) -> Unit = {},
    val onLongSelected: ((Int) -> Unit)? = null
)

/** One persistent window whose content changes during navigation and loading. */
internal class DlnaSelectionDialog(
    private val activity: AppCompatActivity,
    private var content: DlnaDialogContent
) : ComponentDialog(activity) {
    private lateinit var list: ListView
    private var awaitingDirectory = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_dlna_browser)
        setCanceledOnTouchOutside(false)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setWindowAnimations(0)
        setOnCancelListener { content.onClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val up = content.onUp.takeUnless { awaitingDirectory }
                if (up != null) up() else {
                    dismiss()
                    content.onClose()
                }
            }
        })
        renderContent()
        setOnShowListener { focusSelection() }
    }

    fun updateContent(next: DlnaDialogContent) {
        awaitingDirectory = false
        content = next
        renderContent()
        if (isShowing) focusSelection()
    }

    /** Keep the current list and scroll position visible until navigation finishes. */
    fun keepFileListWhileLoading(): Boolean {
        if (!isShowing || !content.fileSelection) return false
        awaitingDirectory = true
        updateInteractionState()
        return true
    }

    private fun updateInteractionState() {
        list.isEnabled = !awaitingDirectory
        for (id in listOf(R.id.dlna_up, R.id.dlna_refresh, R.id.dlna_register)) {
            findViewById<View>(id).isEnabled = !awaitingDirectory
        }
    }

    private fun renderContent() {
        val current = content
        findViewById<TextView>(R.id.dlna_label).apply {
            visibility = if (current.label == null) View.GONE else View.VISIBLE
            current.label?.let { setText(it) }
        }
        val title = findViewById<TextView>(R.id.dlna_title).apply {
            text = current.title
            contentDescription = current.title
        }
        findViewById<TextView>(R.id.dlna_summary).apply {
            text = current.summary
            visibility = if (current.summary.isBlank()) View.GONE else View.VISIBLE
        }
        val up = findViewById<Button>(R.id.dlna_up).apply {
            visibility = if (current.onUp == null) View.GONE else View.VISIBLE
            text = if (current.fileSelection) "" else activity.getString(current.upLabel)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (current.fileSelection) R.drawable.ic_browser_back else 0, 0, 0, 0)
            contentDescription = activity.getString(current.upLabel)
            updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (current.fileSelection) (48 * activity.resources.displayMetrics.density).toInt()
                    else ViewGroup.LayoutParams.MATCH_PARENT
            }
            setOnClickListener { current.onUp?.invoke() }
        }
        val refresh = findViewById<Button>(R.id.dlna_refresh).apply {
            visibility = if (current.onRefresh != null) View.VISIBLE else View.GONE
            text = if (current.fileSelection) "" else activity.getString(current.refreshLabel)
            contentDescription = activity.getString(current.refreshLabel)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (current.fileSelection) R.drawable.ic_browser_refresh else 0, 0, 0, 0)
            setOnClickListener { current.onRefresh?.invoke() }
        }
        val close = findViewById<Button>(R.id.dlna_close).apply {
            setText(if (current.fileSelection) R.string.file_browser_cancel else R.string.dlna_close)
            setOnClickListener { dismiss(); current.onClose() }
        }
        val register = findViewById<Button>(R.id.dlna_register).apply {
            visibility = if (current.onRegister == null) View.GONE else View.VISIBLE
            setText(current.registerLabel)
            setOnClickListener { current.onRegister?.invoke() }
        }
        val topRow = findViewById<LinearLayout>(R.id.dlna_top_actions)
        val bottomRow = findViewById<LinearLayout>(R.id.dlna_bottom_actions)
        val heading = findViewById<LinearLayout>(R.id.dlna_heading)
        val density = activity.resources.displayMetrics.density
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fun place(view: View, parent: LinearLayout, index: Int) {
            if (view.parent !== parent || parent.indexOfChild(view) != index) {
                (view.parent as ViewGroup).removeView(view)
                parent.addView(view, index)
            }
        }
        if (current.fileSelection) {
            place(close, topRow, 1)
            place(title, topRow, 2)
            place(refresh, topRow, 3)
        } else {
            place(title, heading, 1)
            place(refresh, bottomRow, 1)
            place(close, bottomRow, 2)
        }
        heading.visibility = if (current.fileSelection) View.GONE else View.VISIBLE
        title.textSize = if (current.fileSelection) 18f else if (landscape) 22f else 24f
        title.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (current.fileSelection) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            weight = if (current.fileSelection) 1f else 0f
            marginStart = if (current.fileSelection) (12 * density).toInt() else 0
            marginEnd = if (current.fileSelection && current.onRefresh != null) (12 * density).toInt() else 0
            topMargin = if (!current.fileSelection && current.label != null && !landscape) (6 * density).toInt() else 0
        }
        topRow.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = if (current.fileSelection) 0 else ((if (landscape) 8 else 12) * density).toInt()
        }
        listOf(up, refresh, close).forEach { button ->
            val iconButton = button !== close
            button.setBackgroundResource(if (current.fileSelection) R.drawable.browser_toolbar_button else R.drawable.home_secondary_button)
            button.setTextColor(if (current.fileSelection) Color.rgb(102, 223, 255) else Color.WHITE)
            button.minimumWidth = if (current.fileSelection && iconButton) 0 else (88 * density).toInt()
            val padding = ((if (current.fileSelection && iconButton) 12 else 8) * density).toInt()
            button.setPaddingRelative(padding, 0, padding, 0)
            button.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (current.fileSelection) ((if (iconButton) 48 else 96) * density).toInt()
                    else if (button === up) ViewGroup.LayoutParams.MATCH_PARENT else 0
                weight = if (current.fileSelection || button === up) 0f else 1f
            }
        }
        val topActions = listOf(up, close, refresh).filter { it.parent === topRow && it.visibility == View.VISIBLE }
        val bottomActions = listOf(register, refresh, close).filter { it.parent === bottomRow && it.visibility == View.VISIBLE }
        val spacing = (12 * activity.resources.displayMetrics.density).toInt()
        listOf(topRow to topActions, bottomRow to bottomActions).forEach { (row, actions) ->
            row.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
            val slots = if (row === topRow) listOf(up, close, refresh) else listOf(register, refresh, close)
            val layoutButtons = slots.filter { it.parent === row && it.visibility != View.GONE }
            layoutButtons.forEachIndexed { index, button ->
                button.updateLayoutParams<LinearLayout.LayoutParams> {
                    marginStart = when {
                        index == 0 -> 0
                        current.fileSelection -> if (button === close && current.onUp != null) spacing else 0
                        else -> spacing
                    }
                    marginEnd = 0
                }
            }
            actions.forEachIndexed { index, button ->
                button.nextFocusLeftId = actions[(index - 1).coerceAtLeast(0)].id
                button.nextFocusRightId = actions[(index + 1).coerceAtMost(actions.lastIndex)].id
            }
        }
        list = findViewById<ListView>(R.id.dlna_list).apply {
            adapter = RowAdapter(current.rows, compact = current.fileSelection)
            val gap = when {
                !current.fileSelection -> 6
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> 1
                else -> 2
            }
            dividerHeight = (gap * activity.resources.displayMetrics.density).toInt()
            visibility = if (current.rows.isEmpty()) View.GONE else View.VISIBLE
            nextFocusUpId = (topActions.firstOrNull() ?: bottomActions.first()).id
            nextFocusDownId = bottomActions.firstOrNull()?.id ?: id
            setOnItemClickListener { _, _, position, _ ->
                if (!awaitingDirectory) current.onSelected(position)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (awaitingDirectory) false else current.onLongSelected?.let { it(position); true } ?: false
            }
            isLongClickable = current.onLongSelected != null
        }
        findViewById<View>(R.id.dlna_message_container).visibility = if (current.rows.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.dlna_message).text = current.message
        findViewById<View>(R.id.dlna_progress).visibility = if (current.loading) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.dlna_hint).apply {
            visibility = if (current.rows.isEmpty() || activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
            setText(if (current.fileSelection) {
                if (isTv()) R.string.dlna_tv_hint else R.string.file_browser_hint
            } else current.hint ?: if (current.onUp == null) R.string.dlna_server_hint else if (isTv()) R.string.dlna_tv_hint else R.string.dlna_touch_hint)
            setBackgroundColor(if (current.fileSelection) Color.rgb(14, 22, 40) else Color.TRANSPARENT)
            val padding = if (current.fileSelection) (12 * density).toInt() else 0
            setPadding(padding, padding, padding, padding)
        }
        topActions.forEach { button ->
            button.nextFocusUpId = button.id
            button.nextFocusDownId = if (current.rows.isNotEmpty()) list.id else bottomActions.firstOrNull()?.id ?: button.id
        }
        bottomActions.forEach { button ->
            button.nextFocusUpId = if (current.rows.isNotEmpty()) list.id else topActions.firstOrNull()?.id ?: button.id
            button.nextFocusDownId = button.id
        }
        updateInteractionState()
    }

    private fun focusSelection() {
        val current = content
        val currentList = list
        if (current.rows.isNotEmpty()) {
            currentList.post {
                if (isShowing && content === current && list === currentList) {
                    // Ignore focus requests left over from a previous directory/load state.
                    if (isTv() || !currentList.isInTouchMode) currentList.requestFocus()
                    currentList.setSelectionFromTop(current.initialSelection.coerceIn(current.rows.indices), 0)
                }
            }
        } else {
            findViewById<Button>(if (current.onRegister != null) R.id.dlna_register else if (current.onRefresh != null) R.id.dlna_refresh else R.id.dlna_close).requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        updateWindowSize()
    }

    private fun updateWindowSize() {
        // Establish bounds before the first window attachment; navigation does not resize it.
        val metrics = activity.windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val width = metrics.bounds.width() - insets.left - insets.right
        val height = metrics.bounds.height() - insets.top - insets.bottom
        val density = activity.resources.displayMetrics.density
        window?.setLayout(min(width - (24 * density).toInt(), (880 * density).toInt()),
            min((height * 0.92f).toInt(), (800 * density).toInt()))
    }

    fun refreshForConfiguration() {
        val position = list.selectedItemPosition.takeIf { it >= 0 } ?: list.firstVisiblePosition
        content = content.copy(initialSelection = position.coerceAtLeast(0))
        setContentView(R.layout.dialog_dlna_browser)
        renderContent()
        updateWindowSize()
        focusSelection()
    }

    private fun isTv() = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION

    private inner class RowAdapter(private val rows: List<DlnaRow>, private val compact: Boolean) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_dlna_browser, parent, false)
            val density = activity.resources.displayMetrics.density
            val landscape = compact && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val minHeight = when {
                landscape -> 40
                compact -> 48
                else -> 56
            }
            view.minimumHeight = (minHeight * density).toInt()
            val padding = when {
                landscape -> 2
                compact -> 4
                else -> 8
            }
            val verticalPadding = (padding * density).toInt()
            view.setPadding(view.paddingLeft, verticalPadding, view.paddingRight, verticalPadding)
            val row = rows[position]
            view.findViewById<TextView>(R.id.dlna_row_title).apply {
                text = row.title
                maxLines = if (landscape) 1 else 2
            }
            view.findViewById<TextView>(R.id.dlna_row_chevron).apply {
                textSize = if (landscape) 24f else 28f
                visibility = if (compact && row.icon != R.drawable.ic_folder_open) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.dlna_row_detail).apply {
                text = row.detail
                visibility = if (row.detail.isBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<ImageView>(R.id.dlna_row_icon).setImageResource(
                if (compact && row.icon == R.drawable.ic_play) R.drawable.ic_browser_play else row.icon)
            view.contentDescription = if (row.detail.isBlank()) row.description else "${row.description}。${row.detail}"
            return view
        }
    }
}
