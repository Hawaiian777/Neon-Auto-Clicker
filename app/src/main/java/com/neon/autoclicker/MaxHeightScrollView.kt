package com.neon.autoclicker

import android.content.Context
import android.widget.ScrollView

/** A ScrollView that grows with its content up to [maxHeightPx], then scrolls. */
class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx: Int = 0
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, h)
    }
}
