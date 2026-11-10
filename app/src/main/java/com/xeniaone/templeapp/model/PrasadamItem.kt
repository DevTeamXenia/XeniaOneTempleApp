package com.xeniaone.templeapp.model

import java.io.Serializable

data class PrasadamItem(
    val id: String,
    val name: String,
    val unitPrice: Double,
    val imageResId: Int = 0,
    val imageUrl: String? = null,
    var quantity: Int = 0,
    val maxQtyPerOrder: Int? = null
) : Serializable {
    val totalAmount: Double
        get() = quantity * unitPrice

    val formattedPrice: String
        get() = "₹%.2f".format(unitPrice)

    val formattedTotal: String
        get() = "%.2f".format(totalAmount)
}
