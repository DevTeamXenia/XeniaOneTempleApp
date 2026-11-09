package com.xeniaone.templeapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xeniaone.templeapp.util.AuthManager
import com.xeniaone.templeapp.viewmodel.LoginUiState
import com.xeniaone.templeapp.viewmodel.LoginViewModel
import com.xeniaone.templeapp.viewmodel.LoginViewModelFactory
import com.xeniaone.templeapp.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager

    // ViewModel injected with Repository via Factory & Application DI Container
    private val viewModel: LoginViewModel by viewModels {
        val app = application as TempleApp
        LoginViewModelFactory(app.container.authRepository, app.container.authManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLogin) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootLogin.setPadding(systemBars.left, 0, systemBars.right, 0)
            binding.llLoginFooter.setPadding(0, 0, 0, systemBars.bottom.coerceAtLeast(16))
            insets
        }

        val app = application as TempleApp
        authManager = app.container.authManager

        // If already logged in, navigate directly to Billing screen as requested
        if (authManager.isLoggedIn) {
            startActivity(Intent(this, BillingActivity::class.java))
            finish()
            return
        }

        // Restore Remember Me state
        if (authManager.isRememberMe && authManager.savedUsername.isNotEmpty() && authManager.savedUsername != "CounterUser") {
            binding.cbRememberMe.isChecked = true
            binding.etUsername.setText(authManager.savedUsername)
            binding.etPassword.setText(authManager.savedPassword)
        } else {
            // Default prefill for live employee login
            binding.etUsername.setText("")
            binding.etPassword.setText("")
            binding.cbRememberMe.isChecked = true
        }



        // Sign In button click
        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()
            val rememberMe = binding.cbRememberMe.isChecked

            viewModel.login(username, password, rememberMe)
        }

        // Observe ViewModel state
        observeLoginState()
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is LoginUiState.Idle -> {
                            binding.pbLoading.visibility = View.GONE
                            binding.btnLogin.text = "SIGN IN"
                            binding.btnLogin.isEnabled = true
                            binding.tilUsername.error = null
                            binding.tilPassword.error = null
                        }
                        is LoginUiState.Loading -> {
                            binding.pbLoading.visibility = View.VISIBLE
                            binding.btnLogin.text = ""
                            binding.btnLogin.isEnabled = false
                            binding.tilUsername.error = null
                            binding.tilPassword.error = null
                        }
                        is LoginUiState.Success -> {
                            binding.pbLoading.visibility = View.GONE
                            binding.btnLogin.text = "SIGN IN"
                            binding.btnLogin.isEnabled = true

                            // Navigate directly to Billing screen without any toast as requested
                            val intent = Intent(this@LoginActivity, BillingActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                        is LoginUiState.Error -> {
                            binding.pbLoading.visibility = View.GONE
                            binding.btnLogin.text = "SIGN IN"
                            binding.btnLogin.isEnabled = true
                            binding.tilPassword.error = state.message
                            Toast.makeText(this@LoginActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
