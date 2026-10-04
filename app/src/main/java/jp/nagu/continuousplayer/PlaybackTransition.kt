package jp.nagu.continuousplayer

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw

/** Keep the browser window over the activity until its updated content has been drawn. */
internal fun AppCompatActivity.afterNextContentFrame(action: () -> Unit) {
    val decor = window.decorView
    decor.doOnPreDraw { view ->
        if (view.isHardwareAccelerated) {
            view.viewTreeObserver.registerFrameCommitCallback { action() }
        } else {
            // Software rendering finishes in the traversal before this posted callback runs.
            view.post { action() }
        }
    }
    decor.invalidate()
}
