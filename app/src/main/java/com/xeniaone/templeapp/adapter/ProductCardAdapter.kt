package com.xeniaone.templeapp.adapter

import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xeniaone.templeapp.model.PrasadamItem
import com.xeniaone.templeapp.R
import com.xeniaone.templeapp.databinding.ItemProductCardBinding
import com.xeniaone.templeapp.pinelabs.PineLabsManager

class ProductCardAdapter(
    private var items: List<PrasadamItem>,
    private val onQuantityChanged: (item: PrasadamItem, newQty: Int) -> Unit
) : RecyclerView.Adapter<ProductCardAdapter.ProductViewHolder>() {

    fun updateItems(newItems: List<PrasadamItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val context = parent.context
        val isPineLabs = PineLabsManager.isPineLabsAvailable(context)
        val layoutRes = if (isPineLabs) R.layout.item_product_card_pinelabs else R.layout.item_product_card
        val view = LayoutInflater.from(context).inflate(layoutRes, parent, false)
        val binding = ItemProductCardBinding.bind(view)

        // If exactly 3 items on standard Kiosk, proportionally stretch each card; on Pine Labs use MATCH_PARENT in grid
        if (items.size == 3 && !isPineLabs) {
            val displayWidth = context.resources.displayMetrics.widthPixels
            val density = context.resources.displayMetrics.density
            val available = if (parent.measuredWidth > 0) parent.measuredWidth else displayWidth
            val totalMargins = (36 * density).toInt()
            val cardWidth = ((available - totalMargins) / 3).coerceAtLeast((105 * density).toInt())
            val lp = binding.root.layoutParams
            lp.width = cardWidth
            binding.root.layoutParams = lp
        } else if (isPineLabs) {
            val lp = binding.root.layoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
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
            updateCountBadge(item.quantity)

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

            binding.root.setOnClickListener {
                showQuantityKeypadDialog(item)
            }

            binding.btnAdd.setOnClickListener {
                showQuantityKeypadDialog(item)
            }

            binding.btnPlus.setOnClickListener {
                increaseQuantity(item)
            }

            binding.btnMinus.setOnClickListener {
                if (item.quantity > 0) {
                    item.quantity--
                    updateCountBadge(item.quantity)
                    onQuantityChanged(item, item.quantity)
                }
            }

            // Tapping tvCount opens a custom numeric keypad popup dialog
            binding.tvCount.setOnClickListener {
                showQuantityKeypadDialog(item)
            }
        }

        private fun updateCountBadge(quantity: Int) {
            binding.tvCount.text = quantity.toString()
            if (quantity > 0) {
                binding.tvCount.visibility = View.VISIBLE
                binding.btnAdd.visibility = View.GONE
                binding.layoutStepper.visibility = View.VISIBLE
            } else {
                binding.tvCount.visibility = View.GONE
                binding.btnAdd.visibility = View.VISIBLE
                binding.layoutStepper.visibility = View.GONE
            }
        }

        private fun increaseQuantity(item: PrasadamItem) {
            val maxLimit = item.maxQtyPerOrder
            val stockLimit = item.stock?.toInt() ?: 0
            Log.d("ProductCardAdapter", "Product: ${item.name}, currentQty: ${item.quantity}, maxLimit: $maxLimit, stockLimit: $stockLimit")

            if (stockLimit > 0 && item.quantity >= stockLimit) {
                Log.w("ProductCardAdapter", "Stock limit reached for ${item.name}: stock=$stockLimit")
                Toast.makeText(
                    binding.root.context,
                    "Maximum available stock is $stockLimit",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (maxLimit != null && maxLimit > 0 && item.quantity >= maxLimit) {
                Log.w("ProductCardAdapter", "Max limit reached for ${item.name}: limit=$maxLimit")
                Toast.makeText(
                    binding.root.context,
                    "Maximum allowed quantity per order is $maxLimit",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                item.quantity++
                updateCountBadge(item.quantity)
                onQuantityChanged(item, item.quantity)
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
                                        currentInput = if (currentInput == "0") key else currentInput + key
                                        updateDisplay()
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
                val maxLimit = item.maxQtyPerOrder
                val stockLimit = item.stock?.toInt() ?: 0
                Log.d("ProductCardAdapter", "Keypad Dialog Set for ${item.name}: enteredQty=$newQty, maxLimit=$maxLimit, stockLimit=$stockLimit")

                val effectiveLimit = when {
                    stockLimit > 0 && maxLimit != null && maxLimit > 0 -> minOf(stockLimit, maxLimit)
                    stockLimit > 0 -> stockLimit
                    maxLimit != null && maxLimit > 0 -> maxLimit
                    else -> Int.MAX_VALUE
                }

                val finalQty = if (newQty > effectiveLimit) {
                    val msg = if (stockLimit > 0 && (maxLimit == null || stockLimit <= maxLimit)) {
                        "Maximum available stock is $stockLimit"
                    } else {
                        "Maximum allowed quantity per order is $effectiveLimit"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    effectiveLimit
                } else {
                    newQty
                }
                val clampedQty = finalQty.coerceAtLeast(0)
                item.quantity = clampedQty
                updateCountBadge(clampedQty)
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