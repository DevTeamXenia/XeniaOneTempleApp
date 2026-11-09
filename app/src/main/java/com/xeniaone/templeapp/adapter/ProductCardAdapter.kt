package com.xeniaone.templeapp.adapter

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xeniaone.templeapp.model.PrasadamItem
import com.xeniaone.templeapp.R
import com.xeniaone.templeapp.databinding.ItemProductCardBinding

class ProductCardAdapter(
    private var items: List<PrasadamItem>,
    private val onQuantityChanged: (item: PrasadamItem, newQty: Int) -> Unit
) : RecyclerView.Adapter<ProductCardAdapter.ProductViewHolder>() {

    fun updateItems(newItems: List<PrasadamItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val binding = ItemProductCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        // If exactly 3 items, proportionally stretch each card to fill the container width evenly
        if (items.size == 3) {
            val displayWidth = parent.context.resources.displayMetrics.widthPixels
            val density = parent.context.resources.displayMetrics.density
            val available = if (parent.measuredWidth > 0) parent.measuredWidth else displayWidth
            val totalMargins = (36 * density).toInt()
            val cardWidth = ((available - totalMargins) / 3).coerceAtLeast((105 * density).toInt())
            val lp = binding.root.layoutParams
            lp.width = cardWidth
            binding.root.layoutParams = lp
        }

        return ProductViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ProductViewHolder(
        private val binding: ItemProductCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PrasadamItem) {
            binding.tvProductName.text = item.name
            binding.tvProductPrice.text = item.formattedPrice
            binding.tvCount.text = item.quantity.toString()

            // Image loading with Glide and fallback
            val fallbackRes = if (item.imageResId != 0) item.imageResId else R.drawable.item_appam
            if (!item.imageUrl.isNullOrBlank()) {
                Glide.with(binding.ivProduct.context)
                    .load(item.imageUrl)
                    .placeholder(fallbackRes)
                    .error(fallbackRes)
                    .centerCrop()
                    .into(binding.ivProduct)
            } else {
                binding.ivProduct.setImageResource(fallbackRes)
            }

            binding.btnPlus.setOnClickListener {
                item.quantity++
                binding.tvCount.text = item.quantity.toString()
                onQuantityChanged(item, item.quantity)
            }

            binding.btnMinus.setOnClickListener {
                if (item.quantity > 0) {
                    item.quantity--
                    binding.tvCount.text = item.quantity.toString()
                    onQuantityChanged(item, item.quantity)
                }
            }

            // Tapping tvCount opens a custom numeric keypad popup dialog
            binding.tvCount.setOnClickListener {
                showQuantityKeypadDialog(item)
            }
        }

        private fun showQuantityKeypadDialog(item: PrasadamItem) {
            val context = binding.root.context
            val res = context.resources

            var currentInput = if (item.quantity > 0) item.quantity.toString() else ""
            var isFreshInput = true

            // Sizes from dimens.xml (already in px, scaled for the screen)
            val keyHeightPx = res.getDimensionPixelSize(R.dimen.keypad_key_height)
            val keyTextPx = res.getDimension(R.dimen.keypad_key_text_size)
            val keyMarginPx = res.getDimensionPixelSize(R.dimen.keypad_key_margin)
            val displayTextPx = res.getDimension(R.dimen.keypad_display_text_size)
            val displayPadPx = res.getDimensionPixelSize(R.dimen.keypad_display_padding)
            val displayMarginBottomPx = res.getDimensionPixelSize(R.dimen.keypad_display_margin_bottom)
            val rowMarginBottomPx = res.getDimensionPixelSize(R.dimen.keypad_row_margin_bottom)
            val actionHeightPx = res.getDimensionPixelSize(R.dimen.keypad_action_height)
            val actionTextPx = res.getDimension(R.dimen.keypad_action_text_size)
            val actionMarginTopPx = res.getDimensionPixelSize(R.dimen.keypad_action_margin_top)
            val actionGapPx = res.getDimensionPixelSize(R.dimen.keypad_action_button_gap)
            val rootPadPx = res.getDimensionPixelSize(R.dimen.keypad_root_padding)
            val dialogWidthPercent = TypedValue().also {
                res.getValue(R.dimen.keypad_dialog_width_percent, it, true)
            }.float

            val rootLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(rootPadPx, rootPadPx, rootPadPx, rootPadPx)
            }

            // Display Box showing the current entered number
            val tvDisplay = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, displayTextPx)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                text = if (currentInput.isEmpty()) "0" else currentInput
                setBackgroundResource(R.drawable.bg_total_box)
                setPadding(displayPadPx, displayPadPx, displayPadPx, displayPadPx)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = displayMarginBottomPx
                }
            }
            rootLayout.addView(tvDisplay)

            fun updateDisplay() {
                tvDisplay.text = if (currentInput.isEmpty()) "0" else currentInput
            }

            // Custom Keypad Grid (Rows 1-4)
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("C", "0", "⌫")
            )

            rows.forEach { rowKeys ->
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    weightSum = 3f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = rowMarginBottomPx
                    }
                }

                rowKeys.forEach { key ->
                    val btn = AppCompatButton(context).apply {
                        text = key
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, keyTextPx)
                        typeface = Typeface.DEFAULT_BOLD
                        stateListAnimator = null
                        when (key) {
                            "C" -> {
                                setTextColor(context.getColor(R.color.delete_red))
                                setBackgroundResource(R.drawable.bg_keypad_key_clear)
                            }
                            "⌫" -> {
                                setTextColor(context.getColor(R.color.pay_button_orange))
                                setBackgroundResource(R.drawable.bg_keypad_action)
                            }
                            else -> {
                                setTextColor(context.getColor(R.color.text_dark))
                                setBackgroundResource(R.drawable.bg_keypad_button)
                            }
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            keyHeightPx,
                            1f
                        ).apply {
                            setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)
                        }

                        setOnClickListener {
                            when (key) {
                                "C" -> {
                                    currentInput = ""
                                    isFreshInput = false
                                    updateDisplay()
                                }
                                "⌫" -> {
                                    if (currentInput.isNotEmpty()) {
                                        currentInput = currentInput.dropLast(1)
                                        isFreshInput = false
                                        updateDisplay()
                                    }
                                }
                                else -> {
                                    if (isFreshInput) {
                                        currentInput = key
                                        isFreshInput = false
                                        updateDisplay()
                                    } else {
                                        if (currentInput.length < 3) {
                                            currentInput = if (currentInput == "0") key else currentInput + key
                                            updateDisplay()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    rowLayout.addView(btn)
                }
                rootLayout.addView(rowLayout)
            }

            // Bottom Action Buttons Row (Cancel & Add)
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = actionMarginTopPx
                }
            }

            val btnCancel = AppCompatButton(context).apply {
                text = "Cancel"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, actionTextPx)
                typeface = Typeface.DEFAULT_BOLD
                stateListAnimator = null
                setTextColor(context.getColor(R.color.text_muted))
                setBackgroundResource(R.drawable.bg_payment_card_selector)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    actionHeightPx,
                    1f
                ).apply {
                    setMargins(0, 0, actionGapPx, 0)
                }
            }

            val btnSet = AppCompatButton(context).apply {
                text = "Add"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, actionTextPx)
                typeface = Typeface.DEFAULT_BOLD
                stateListAnimator = null
                setTextColor(context.getColor(R.color.white))
                setBackgroundResource(R.drawable.bg_pay_button)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    actionHeightPx,
                    1f
                ).apply {
                    setMargins(actionGapPx, 0, 0, 0)
                }
            }

            actionRow.addView(btnCancel)
            actionRow.addView(btnSet)
            rootLayout.addView(actionRow)

            // ScrollView so the dialog still works on short screens
            val scrollView = ScrollView(context).apply {
                isFillViewport = true
                addView(rootLayout)
            }

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("Quantity for ${item.name}")
                .setView(scrollView)
                .create()

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            btnSet.setOnClickListener {
                val newQty = currentInput.toIntOrNull() ?: 0
                val clampedQty = newQty.coerceIn(0, 999)
                item.quantity = clampedQty
                binding.tvCount.text = item.quantity.toString()
                onQuantityChanged(item, clampedQty)
                dialog.dismiss()
            }

            dialog.show()

            // Widen the dialog window (must be called after show())
            val screenWidth = res.displayMetrics.widthPixels
            dialog.window?.setLayout(
                (screenWidth * dialogWidthPercent).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
}