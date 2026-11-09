package com.xeniaone.templeapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xeniaone.templeapp.network.model.LoginResponse
import com.xeniaone.templeapp.repository.AuthRepository
import com.xeniaone.templeapp.repository.Resource
import com.xeniaone.templeapp.util.AuthManager
import com.xeniaone.templeapp.util.JwtDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val response: LoginResponse, val welcomeName: String) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(userName: String, password: String, rememberMe: Boolean) {
        if (userName.isBlank()) {
            _uiState.value = LoginUiState.Error("Please enter your Username / Phone number")
            return
        }
        if (password.isBlank()) {
            _uiState.value = LoginUiState.Error("Please enter your Password")
            return
        }

        _uiState.value = LoginUiState.Loading

        viewModelScope.launch {
            when (val result = authRepository.employeeLogin(userName, password)) {
                is Resource.Success -> {
                    val token = result.data.token.orEmpty()
                    val userInfo = JwtDecoder.decode(token)

                    // Persist authentication & Remember Me
                    authManager.saveLogin(userName, password, rememberMe)
                    authManager.authToken = token
                    authManager.employeeId = userInfo.employeeId
                    authManager.designation = userInfo.designation

                    // Fetch employee profile to obtain CompanyId and DefaultBranch for Socket connection
                    try {
                        val profileResult = authRepository.getEmployeeProfile(token)
                        if (profileResult is Resource.Success && profileResult.data.data != null) {
                            val data = profileResult.data.data!!
                            authManager.saveProfile(
                                companyId = data.companyId,
                                branchId = data.defaultBranch,
                                userId = data.userId,
                                userName = data.userName,
                                designation = data.designation,
                                imageUrl = data.image
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val displayName = if (authManager.designation.isNotBlank()) {
                        "${authManager.designation} (${userInfo.username.ifEmpty { userName }})"
                    } else {
                        userName
                    }

                    _uiState.value = LoginUiState.Success(result.data, displayName)
                }
                is Resource.Error -> {
                    _uiState.value = LoginUiState.Error(result.message)
                }
                is Resource.Loading -> {
                    _uiState.value = LoginUiState.Loading
                }
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}

class LoginViewModelFactory(
    private val authRepository: AuthRepository,
    private val authManager: AuthManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            return LoginViewModel(authRepository, authManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
