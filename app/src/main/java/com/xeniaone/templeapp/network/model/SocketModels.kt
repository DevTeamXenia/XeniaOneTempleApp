package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class ProductResponse(
    @SerializedName("products")
    val products: ProductWrapper? = null
)

data class ProductWrapper(
    @SerializedName("products")
    val products: List<SocketProduct> = emptyList()
)

data class SocketProduct(
    @SerializedName("productId")
    val productId: Int = 0,
    @SerializedName("categoryId")
    val categoryId: Int = 0,
    @SerializedName("productName")
    val productName: String = "",
    @SerializedName("oRate")
    val oRate: Double = 0.0,
    @SerializedName("sRate")
    val sRate: Double = 0.0,
    @SerializedName("mrp")
    val mrp: Double = 0.0,
    @SerializedName("unit")
    val unit: String? = null,
    @SerializedName("image")
    val image: String? = null,
    @SerializedName("stock")
    val stock: Double? = null,
    @SerializedName("isStockAvailable")
    val isStockAvailable: Boolean = true,
    @SerializedName("isVariantEnabled")
    val isVariantEnabled: Boolean? = null,
    @SerializedName("isComboEnabled")
    val isComboEnabled: Boolean? = null,
    @SerializedName(value = "maxQtyPerOrder", alternate = ["MaxQtyPerOrder", "max_qty_per_order", "MaxQty", "maxQty", "quantityLimit", "Limit"])
    val maxQtyPerOrder: Int? = null

) {
    val effectivePrice: Double
        get() = when {
            sRate > 0.0 -> sRate
            mrp > 0.0 -> mrp
            oRate > 0.0 -> oRate
            else -> 0.0
        }
}
