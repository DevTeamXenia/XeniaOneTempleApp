package com.xeniaone.templeapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
        }
    }
}
