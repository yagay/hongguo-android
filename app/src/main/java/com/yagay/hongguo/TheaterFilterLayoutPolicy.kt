package com.yagay.hongguo

/**
 * Collapsed theater filters preview two three-column rows. When the chosen
 * option is outside those rows, keep it visible in the final preview slot.
 * All conditions are still accessible by expanding the category.
 */
internal object TheaterFilterLayoutPolicy {
    const val PREVIEW_COUNT = 6

    fun hasMore(options: List<FilterOption>): Boolean =
        options.size > PREVIEW_COUNT

    fun visibleOptions(
        options: List<FilterOption>,
        selectedValue: String,
        expanded: Boolean,
    ): List<FilterOption> {
        if (expanded || options.size <= PREVIEW_COUNT) return options
        val preview = options.take(PREVIEW_COUNT)
        val selected = options.firstOrNull {
            it.value.isNotBlank() && it.value == selectedValue
        } ?: return preview
        return if (selected in preview) preview
        else options.take(PREVIEW_COUNT - 1) + selected
    }
}
