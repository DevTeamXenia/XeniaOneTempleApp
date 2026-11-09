package com.xeniaone.templeapp.adapter

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
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
            val density = context.resources.displayMetrics.density
            var currentInput = if (item.quantity > 0) item.quantity.toString() else ""
            var isFreshInput = true

            val rootLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            // Display Box showing the current entered number
            val tvDisplay = TextView(context).apply {
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                text = if (currentInput.isEmpty()) "0" else currentInput
                setBackgroundResource(R.drawable.bg_total_box)
                val padBox = (12 * density).toInt()
                setPadding(padBox, padBox, padBox, padBox)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (14 * density).toInt()
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
                        bottomMargin = (6 * density).toInt()
                    }
                }

                rowKeys.forEach { key ->
                    val btn = AppCompatButton(context).apply {
                        text = key
                        textSize = 20f
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
                            (48 * density).toInt(),
                            1f
                        ).apply {
                            val margin = (4 * density).toInt()
                            setMargins(margin, margin, margin, margin)
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

            // Bottom Action Buttons Row (Cancel & Set) inside the box
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * density).toInt()
                }
            }

            val btnCancel = AppCompatButton(context).apply {
                text = "Cancel"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                stateListAnimator = null
                setTextColor(context.getColor(R.color.text_muted))
                setBackgroundResource(R.drawable.bg_payment_card_selector)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (44 * density).toInt(),
                    1f
                ).apply {
                    val m = (4 * density).toInt()
                    setMargins(0, 0, m, 0)
                }
            }

            val btnSet = AppCompatButton(context).apply {
                text = "Set"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                stateListAnimator = null
                setTextColor(context.getColor(R.color.white))
                setBackgroundResource(R.drawable.bg_pay_button)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (44 * density).toInt(),
                    1f
                ).apply {
                    val m = (4 * density).toInt()
                    setMargins(m, 0, 0, 0)
                }
            }

            actionRow.addView(btnCancel)
            actionRow.addView(btnSet)
            rootLayout.addView(actionRow)

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("Set Quantity for ${item.name}")
                .setView(rootLayout)
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
        }
    }
}
