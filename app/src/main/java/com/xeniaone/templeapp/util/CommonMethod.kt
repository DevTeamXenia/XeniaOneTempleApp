package com.xeniaone.templeapp.util

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.xeniaone.templeapp.R

object CommonMethod {
    private var loader: AlertDialog? = null

    fun showLoader(context: Context, message: String = "Loading products...") {
        if (context is AppCompatActivity && context.isFinishing) return

        dismissLoader()

        try {
            val builder = AlertDialog.Builder(context)
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.custom_loader, null)

            view.findViewById<TextView>(R.id.loaderMessage).text = message

            builder.setView(view)
            builder.setCancelable(false)

            loader = builder.create()
            loader?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            loader?.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismissLoader() {
        try {
            loader?.dismiss()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loader = null
    }
}
