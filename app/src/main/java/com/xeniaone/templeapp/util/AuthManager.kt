package com.xeniaone.templeapp.util

import android.content.Context
import android.content.SharedPreferences

class AuthManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("prasadam_auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_SAVED_USERNAME = "saved_username"
        private const val KEY_SAVED_PASSWORD = "saved_password"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_EMPLOYEE_ID = "employee_id"
        private const val KEY_DESIGNATION = "designation"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_DEFAULT_BRANCH = "default_branch"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMPLOYEE_NAME = "employee_name"
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var isRememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_ME, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_ME, value).apply()

    var savedUsername: String
        get() = prefs.getString(KEY_SAVED_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SAVED_USERNAME, value).apply()

    var savedPassword: String
        get() = prefs.getString(KEY_SAVED_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SAVED_PASSWORD, value).apply()

    var selectedLanguage: String
        get() = prefs.getString(KEY_SELECTED_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_SELECTED_LANGUAGE, value).apply()

    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var employeeId: String
        get() = prefs.getString(KEY_EMPLOYEE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMPLOYEE_ID, value).apply()

    var designation: String
        get() = prefs.getString(KEY_DESIGNATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DESIGNATION, value).apply()

    var companyId: Int
        get() = prefs.getInt(KEY_COMPANY_ID, 11)
        set(value) = prefs.edit().putInt(KEY_COMPANY_ID, value).apply()

    var defaultBranch: Int
        get() = prefs.getInt(KEY_DEFAULT_BRANCH, 3)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_BRANCH, value).apply()

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, 23)
        set(value) = prefs.edit().putInt(KEY_USER_ID, value).apply()

    var employeeName: String
        get() = prefs.getString(KEY_EMPLOYEE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMPLOYEE_NAME, value).apply()

    fun saveProfile(companyId: Int, branchId: Int, userId: Int, userName: String, designation: String) {
        this.companyId = companyId
        this.defaultBranch = branchId
        this.userId = userId
        this.employeeName = userName
        this.designation = designation
    }

    fun saveLogin(username: String, password: String, rememberMe: Boolean) {
        isLoggedIn = true
        isRememberMe = rememberMe
        if (rememberMe) {
            savedUsername = username
            savedPassword = password
        } else {
            savedUsername = ""
            savedPassword = ""
        }
    }

    fun logout() {
        isLoggedIn = false
        authToken = ""
        employeeId = ""
        designation = ""
    }
}
