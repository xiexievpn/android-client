package com.v2ray.ang.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "LoginActivity"
        private const val PREF_NAME = "xiexie_vpn_prefs"
        private const val KEY_UUID = "saved_uuid"
        private const val KEY_AUTO_LOGIN = "auto_login"
        private const val LOGIN_URL = "https://vvv.xiexievpn.com/login"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        setupUI()
        checkAutoLogin()
    }

    private fun setupUI() {
        // Set up login button click listener
        binding.btnLogin.setOnClickListener {
            val uuid = binding.etUuid.text.toString().trim()
            if (uuid.isNotEmpty()) {
                performLogin(uuid)
            } else {
                Toast.makeText(this, getString(R.string.login_prompt), Toast.LENGTH_SHORT).show()
            }
        }

        // Load saved UUID if auto login is enabled
        val savedUuid = sharedPreferences.getString(KEY_UUID, "")
        if (!savedUuid.isNullOrEmpty()) {
            binding.etUuid.setText(savedUuid)
            binding.cbAutoLogin.isChecked = sharedPreferences.getBoolean(KEY_AUTO_LOGIN, false)
        }
    }

    private fun checkAutoLogin() {
        val autoLogin = sharedPreferences.getBoolean(KEY_AUTO_LOGIN, false)
        val savedUuid = sharedPreferences.getString(KEY_UUID, "")

        if (autoLogin && !savedUuid.isNullOrEmpty()) {
            binding.etUuid.setText(savedUuid)
            binding.cbAutoLogin.isChecked = true
            performLogin(savedUuid)
        }
    }

    private fun performLogin(uuid: String) {
        setLoading(true)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    loginRequest(uuid)
                }

                withContext(Dispatchers.Main) {
                    setLoading(false)

                    when (result) {
                        200 -> {
                            // Login successful
                            saveLoginInfo(uuid)
                            navigateToMainActivity()
                        }
                        401 -> {
                            Toast.makeText(this@LoginActivity, "无效的访问代码", Toast.LENGTH_SHORT).show()
                            clearSavedLogin()
                        }
                        403 -> {
                            Toast.makeText(this@LoginActivity, "访问代码已过期", Toast.LENGTH_SHORT).show()
                            clearSavedLogin()
                        }
                        else -> {
                            Toast.makeText(this@LoginActivity, "服务器错误，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Log.e(TAG, "Login failed", e)
                    Toast.makeText(this@LoginActivity, "网络连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loginRequest(uuid: String): Int {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(LOGIN_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Create JSON payload
            val jsonObject = JSONObject()
            jsonObject.put("code", uuid)
            val jsonInputString = jsonObject.toString()

            // Send request
            connection.outputStream.use { os: OutputStream ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            // Get response code
            val responseCode = connection.responseCode
            Log.d(TAG, "Login response code: $responseCode")

            return responseCode

        } catch (e: Exception) {
            Log.e(TAG, "Login request failed", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    private fun saveLoginInfo(uuid: String) {
        val editor = sharedPreferences.edit()
        if (binding.cbAutoLogin.isChecked) {
            editor.putString(KEY_UUID, uuid)
            editor.putBoolean(KEY_AUTO_LOGIN, true)
        } else {
            editor.remove(KEY_UUID)
            editor.putBoolean(KEY_AUTO_LOGIN, false)
        }
        editor.apply()
    }

    private fun clearSavedLogin() {
        val editor = sharedPreferences.edit()
        editor.remove(KEY_UUID)
        editor.putBoolean(KEY_AUTO_LOGIN, false)
        editor.apply()
        binding.cbAutoLogin.isChecked = false
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        binding.etUuid.isEnabled = !isLoading
        binding.cbAutoLogin.isEnabled = !isLoading
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        // Pass the UUID to MainActivity
        intent.putExtra("user_uuid", binding.etUuid.text.toString().trim())
        startActivity(intent)
        finish()
    }
}