package com.xeniaone.templeapp.model

import java.io.Serializable

data class ReceiptItem(
    val no: Int,
    val name: String,
    val quantity: Int,
    val rate: Double,
    val total: Double
) : Serializable
