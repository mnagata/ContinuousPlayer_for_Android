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
    val label: Int = R.string.dlna_dialog_label,
    val hint: Int? = null,
    val path: String = "",
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

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_dlna_browser)
        setCanceledOnTouchOutside(false)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setWindowAnimations(0)
        setOnCancelListener { content.onClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val up = content.onUp
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
        content = next
        renderContent()
        if (isShowing) focusSelection()
    }

    private fun renderContent() {
        val current = content
        findViewById<TextView>(R.id.dlna_label).setText(current.label)
        findViewById<TextView>(R.id.dlna_title).text = current.title
        findViewById<TextView>(R.id.dlna_path).apply {
            text = current.path
            visibility = if (current.path.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.dlna_summary).apply {
            text = current.summary
            visibility = if (current.summary.isBlank()) View.GONE else View.VISIBLE
        }
        val up = findViewById<Button>(R.id.dlna_up).apply {
            visibility = if (current.onUp == null) View.GONE else View.VISIBLE
            setText(current.upLabel)
            setOnClickListener { current.onUp?.invoke() }
        }
        val refresh = findViewById<Button>(R.id.dlna_refresh).apply {
            visibility = if (current.onRefresh == null) View.GONE else View.VISIBLE
            setText(current.refreshLabel)
            setOnClickListener { current.onRefresh?.invoke() }
        }
        val close = findViewById<Button>(R.id.dlna_close).apply {
            setOnClickListener { dismiss(); current.onClose() }
        }
        val register = findViewById<Button>(R.id.dlna_register).apply {
            visibility = if (current.onRegister == null) View.GONE else View.VISIBLE
            setText(current.registerLabel)
            setOnClickListener { current.onRegister?.invoke() }
        }
        val actions = listOf(register, refresh, close).filter { it.visibility == View.VISIBLE }
        close.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = if (actions.size > 1) (12 * activity.resources.displayMetrics.density).toInt() else 0
        }
        list = findViewById<ListView>(R.id.dlna_list).apply {
            adapter = RowAdapter(current.rows)
            visibility = if (current.rows.isEmpty()) View.GONE else View.VISIBLE
            nextFocusUpId = if (current.onUp != null) up.id else actions.first().id
            nextFocusDownId = actions.first().id
            setOnItemClickListener { _, _, position, _ -> current.onSelected(position) }
            setOnItemLongClickListener { _, _, position, _ ->
                current.onLongSelected?.let { it(position); true } ?: false
            }
            isLongClickable = current.onLongSelected != null
        }
        findViewById<View>(R.id.dlna_message_container).visibility = if (current.rows.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.dlna_message).text = current.message
        findViewById<View>(R.id.dlna_progress).visibility = if (current.loading) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.dlna_hint).apply {
            visibility = if (current.rows.isEmpty() || activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
            setText(current.hint ?: if (current.onUp == null) R.string.dlna_server_hint else if (isTv()) R.string.dlna_tv_hint else R.string.dlna_touch_hint)
        }
        actions.forEachIndexed { index, button ->
            button.nextFocusLeftId = actions[(index - 1).coerceAtLeast(0)].id
            button.nextFocusRightId = actions[(index + 1).coerceAtMost(actions.lastIndex)].id
            button.nextFocusUpId = if (current.rows.isNotEmpty()) list.id else if (current.onUp != null) up.id else button.id
        }
        up.nextFocusDownId = if (current.rows.isNotEmpty()) list.id else actions.first().id
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

    private inner class RowAdapter(private val rows: List<DlnaRow>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_dlna_browser, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.dlna_row_title).text = row.title
            view.findViewById<TextView>(R.id.dlna_row_detail).apply {
                text = row.detail
                visibility = if (row.detail.isBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<ImageView>(R.id.dlna_row_icon).setImageResource(row.icon)
            view.contentDescription = if (row.detail.isBlank()) row.description else "${row.description}。${row.detail}"
            return view
        }
    }
}
