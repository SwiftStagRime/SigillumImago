package com.swifstagrime.feature_gallery.ui.decorators

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return
        }

        val layoutManager = parent.layoutManager
        if (layoutManager !is GridLayoutManager) {
            super.getItemOffsets(outRect, view, parent, state)
            return
        }

        val lp = view.layoutParams as? GridLayoutManager.LayoutParams
        if (lp == null) {
            super.getItemOffsets(outRect, view, parent, state)
            return
        }

        val column = lp.spanIndex

        outRect.left = column * spacing / spanCount
        outRect.right = spacing - (column + 1) * spacing / spanCount

        if (position >= spanCount) {
            outRect.top = spacing
        } else {
            outRect.top = 0
        }

        outRect.bottom = 0
    }
}