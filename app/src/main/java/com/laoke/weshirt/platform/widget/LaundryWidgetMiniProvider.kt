package com.laoke.weshirt.platform.widget

import kotlin.math.roundToInt

/** A shallow two-column entry; it never switches to a narrow portrait layout. */
class LaundryWidgetMiniProvider : LaundryWidgetProvider() {
    internal override val widgetSize = WidgetSize.MINI
}

/** Fit the artwork and the content to the same centered card inside the host cell. */
internal data class MiniWidgetLayout(
    val width: Int,
    val cardHeight: Int,
    val horizontalInset: Int,
    val verticalInset: Int,
    val horizontalPadding: Int,
    val verticalPadding: Int,
    val labelInset: Int,
) {
    companion object {
        fun fit(reportedWidth: Int, reportedHeight: Int): MiniWidgetLayout {
            val width = reportedWidth.coerceIn(64, 640)
            val height = reportedHeight.coerceIn(32, 320)
            val horizontalInset = ((width - 236) / 2).coerceAtLeast(0)
            val cardWidth = width - 2 * horizontalInset
            val maxCardHeight = (cardWidth * .52f).roundToInt().coerceIn(44, 82)
            val verticalInset = ((height - maxCardHeight) / 2).coerceAtLeast(0)
            val cardHeight = height - 2 * verticalInset
            val horizontalPadding = if (cardWidth < 145) 7 else 9
            val verticalPadding = if (cardHeight < 58) 4 else 7
            val labelInset = ((cardHeight - 2 * verticalPadding - 49) / 2).coerceIn(0, 10)
            return MiniWidgetLayout(cardWidth, cardHeight, horizontalInset, verticalInset,
                horizontalPadding, verticalPadding, labelInset)
        }
    }
}
