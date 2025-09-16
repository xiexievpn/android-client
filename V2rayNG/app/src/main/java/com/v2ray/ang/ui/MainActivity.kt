package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : BaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val countrySelectorAdapter by lazy { CountrySelectorAdapter() }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    // 换区相关变量
    private var isSwitching = false          // 换区状态标记
    private var wasVpnOn = false             // 记录换区前VPN状态
    private var currentRegion: String? = null // 当前区域
    private var targetRegion: String? = null  // 目标区域
    private var pollAttempts = 0             // 轮询次数
    private val maxPollAttempts = 120        // 最多轮询120次（10分钟）
    private var userUuid: String? = null     // 当前用户UUID
    private var maxProgress = 0              // 最大进度值，确保进度只增不减（参考Windows客户端）

    // 国家代码到AWS区域的映射
    private val FLAG_TO_REGION = mapOf(
        "jp" to "ap-northeast-2",  // 韩国
        "us" to "us-west-2",       // 美国
        "jj" to "ap-northeast-1",  // 日本
        "in" to "ap-south-1",      // 印度
        "si" to "ap-southeast-1",  // 新加坡
        "au" to "ap-southeast-2",  // 澳大利亚
        "ca" to "ca-central-1",    // 加拿大
        "ge" to "eu-central-1",    // 德国
        "ir" to "eu-west-1",       // 爱尔兰
        "ki" to "eu-west-2",       // 英国
        "fr" to "eu-west-3",       // 法国
        "sw" to "eu-north-1"       // 瑞典
    )




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }

        // 设置国家选择器RecyclerView
        binding.countryRecyclerView.setHasFixedSize(true)
        binding.countryRecyclerView.layoutManager = GridLayoutManager(this, 1)
        addCustomDividerToRecyclerView(binding.countryRecyclerView, this, R.drawable.custom_divider)
        countrySelectorAdapter.initializeCountries(this)
        binding.countryRecyclerView.adapter = countrySelectorAdapter

        // 设置国家点击监听器
        countrySelectorAdapter.setOnCountryClickListener(object : CountrySelectorAdapter.OnCountryClickListener {
            override fun onCountryClick(countryCode: String) {
                switchRegion(countryCode)
            }
        })

        // 原服务器列表RecyclerView（隐藏）
        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)


        initGroupTab()
        setupViewModel()
        migrateLegacy()

        // Get UUID from login intent and fetch configuration
        val userUuid = intent.getStringExtra("user_uuid")
        if (!userUuid.isNullOrEmpty()) {
            this.userUuid = userUuid  // 保存UUID供换区功能使用
            fetchAndImportConfig(userUuid)
        }


        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                } else {
                    //toast(getString(R.string.migration_fail))
                }
            }

        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = true
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }


    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            // 解析v2rayurl并高亮对应国家
                            server?.let { highlightCountryFromUrl(it) }
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }



    private fun setTestState(content: String?) {
        // Test state functionality removed
    }

    /**
     * Clear all existing configurations for fresh login
     */
    private fun clearAllConfigs() {
        // Get all server list and remove them
        val serverList = MmkvManager.decodeServerList()
        serverList.forEach { guid ->
            MmkvManager.removeServer(guid)
        }
        // Clear selected server
        MmkvManager.setSelectServer("")
        // Important: refresh UI display
        mainViewModel.reloadServerList()
        Log.d("MainActivity", "All configs cleared")
    }

    /**
     * Add user to the system (similar to Windows client do_adduser)
     */
    private fun addUser(uuid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://vvv.xiexievpn.com/adduser")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 2000
                connection.readTimeout = 2000

                val jsonObject = JSONObject()
                jsonObject.put("code", uuid)

                connection.outputStream.use { os ->
                    val input = jsonObject.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                Log.d("MainActivity", "AddUser response code: $responseCode")
            } catch (e: Exception) {
                Log.e("MainActivity", "AddUser error: $e")
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Poll user info until v2rayurl is available (similar to Windows client poll_getuserinfo)
     */
    private fun pollUserInfo(uuid: String) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    getUserInfoRequest(uuid)
                }

                withContext(Dispatchers.Main) {
                    result?.let { v2rayUrl ->
                        if (v2rayUrl.isNotEmpty()) {
                            // Successfully got configuration
                            val (count, countSub) = AngConfigManager.importBatchConfig(
                                v2rayUrl,
                                mainViewModel.subscriptionId,
                                false  // Replace instead of append
                            )

                            if (count > 0) {
                                mainViewModel.reloadServerList()
                                toast("配置获取成功")
                                Log.d("MainActivity", "Config poll success, count: $count")
                                // 解析v2rayurl并高亮对应国家
                                highlightCountryFromUrl(v2rayUrl)
                                // 恢复用户操作
                                enableUserInteraction()
                            } else {
                                Log.w("MainActivity", "Config poll failed - no configs imported")
                                toast("配置导入失败")
                                // 恢复用户操作
                                enableUserInteraction()
                            }
                        } else {
                            // Continue polling after 3 seconds
                            Log.d("MainActivity", "Config polling - v2rayurl still empty, retrying in 3s")
                            lifecycleScope.launch {
                                delay(3000)
                                pollUserInfo(uuid)
                            }
                        }
                    } ?: run {
                        // Continue polling on null response
                        Log.d("MainActivity", "Config polling - null response, retrying in 3s")
                        lifecycleScope.launch {
                            delay(3000)
                            pollUserInfo(uuid)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Config polling error: $e")
                // Continue polling on error
                lifecycleScope.launch {
                    delay(3000)
                    pollUserInfo(uuid)
                }
            }
        }
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun fetchAndImportConfig(uuid: String) {
        // 禁用用户操作，直到配置获取完成
        disableUserInteraction()

        // Clear all old configs first (similar to Windows client replacing config.json)
        clearAllConfigs()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    getUserInfoRequest(uuid)
                }

                withContext(Dispatchers.Main) {
                    result?.let { v2rayUrl ->
                        if (v2rayUrl.isNotEmpty()) {
                            // Use importBatchConfig with append=false to replace old configs
                            val (count, countSub) = AngConfigManager.importBatchConfig(
                                v2rayUrl,
                                mainViewModel.subscriptionId,
                                false  // Important: changed from true to false
                            )

                            if (count > 0) {
                                mainViewModel.reloadServerList()
                                toast("配置导入成功，共导入 $count 个服务器")
                                Log.d("MainActivity", "Config imported successfully, count: $count")
                                // 解析v2rayurl并高亮对应国家
                                highlightCountryFromUrl(v2rayUrl)
                                // 恢复用户操作
                                enableUserInteraction()
                            } else {
                                Log.w("MainActivity", "No configuration imported")
                                toast("配置导入失败，请检查配置格式")
                                // 恢复用户操作
                                enableUserInteraction()
                            }
                        } else {
                            // v2rayurl is empty, may need to call adduser (similar to Windows client)
                            Log.w("MainActivity", "V2ray URL is empty, calling adduser and starting polling")
                            addUser(uuid)
                            // Start polling after 10ms delay (similar to Windows: window.after(10, ...))
                            lifecycleScope.launch {
                                delay(10)
                                pollUserInfo(uuid)
                            }
                        }
                    } ?: run {
                        // Response is null, also try adduser and polling
                        Log.w("MainActivity", "No response, calling adduser and starting polling")
                        addUser(uuid)
                        lifecycleScope.launch {
                            delay(10)
                            pollUserInfo(uuid)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error fetching config", e)
                // On error also try adduser and polling
                addUser(uuid)
                lifecycleScope.launch {
                    delay(10)
                    pollUserInfo(uuid)
                }
            }
        }
    }

    private fun getUserInfoRequest(uuid: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://vvv.xiexievpn.com/getuserinfo")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            // Create JSON payload
            val jsonObject = JSONObject()
            jsonObject.put("code", uuid)
            val jsonInputString = jsonObject.toString()

            // Send request
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                return jsonResponse.optString("v2rayurl", "")
            } else {
                Log.e("MainActivity", "HTTP error: $responseCode")
                return null
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Request failed", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 从v2rayurl中解析国家代码并高亮对应国家
     * v2rayurl格式示例: vless://...@server.com:443?...#us
     */
    private fun highlightCountryFromUrl(v2rayUrl: String) {
        try {
            val index = v2rayUrl.lastIndexOf("#")
            if (index != -1 && index < v2rayUrl.length - 1) {
                val countryCode = v2rayUrl.substring(index + 1)
                Log.d("MainActivity", "Parsed country code from v2rayurl: $countryCode")
                // 更新适配器中对应国家的高亮状态
                countrySelectorAdapter.setSelectedCountry(countryCode)
                // 记录当前区域
                currentRegion = countryCode
            } else {
                Log.w("MainActivity", "No country code found in v2rayurl: $v2rayUrl")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing country code from v2rayurl", e)
        }
    }

    // ==================== 换区功能实现 ====================

    /**
     * 切换到新区域
     * @param newZone 目标国家代码
     */
    private fun switchRegion(newZone: String) {
        if (isSwitching || newZone == currentRegion) {
            Log.d("MainActivity", "Switch ignored: isSwitching=$isSwitching, newZone=$newZone, currentRegion=$currentRegion")
            return
        }

        Log.d("MainActivity", "Starting region switch to: $newZone")
        isSwitching = true
        targetRegion = newZone
        maxProgress = 0  // 重置最大进度值（参考Windows客户端）

        // 显示进度指示器
        showProgressIndicator("正在切换区域...", 0)

        // 禁用用户交互
        disableUserInteraction()

        // 检查并记录VPN状态
        wasVpnOn = (mainViewModel.isRunning.value == true)
        Log.d("MainActivity", "VPN was on before switch: $wasVpnOn")

        // 如果VPN开启，先关闭
        if (wasVpnOn) {
            Log.d("MainActivity", "Stopping VPN before region switch")
            binding.fab.performClick()
            // 等待VPN关闭后再发送换区请求
            lifecycleScope.launch {
                delay(1000)  // 等待1秒确保VPN完全关闭
                sendSwitchRequest(newZone)
            }
        } else {
            // 直接发送换区请求
            sendSwitchRequest(newZone)
        }
    }

    /**
     * 发送换区请求到后端
     */
    private fun sendSwitchRequest(newZone: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val maxRetries = 2
            for (attempt in 1..maxRetries) {
                try {
                    Log.d("MainActivity", "Sending switch request for $newZone (attempt $attempt/$maxRetries)")

                    val response = switchRegionRequest(newZone)

                    when (response.code) {
                        200 -> {
                            Log.d("MainActivity", "Switch request successful (200)")
                            withContext(Dispatchers.Main) {
                                startPollingStatus()
                            }
                            return@launch
                        }
                        202 -> {
                            Log.d("MainActivity", "Switch request accepted (202), starting polling")
                            withContext(Dispatchers.Main) {
                                startPollingStatus()
                            }
                            return@launch
                        }
                        504 -> {
                            Log.d("MainActivity", "Gateway timeout (504), starting polling anyway")
                            withContext(Dispatchers.Main) {
                                startPollingStatus()
                            }
                            return@launch
                        }
                        else -> {
                            if (attempt < maxRetries) {
                                Log.w("MainActivity", "Switch failed with code ${response.code}, retrying...")
                                delay(3000)
                                // 继续下一次重试
                            } else {
                                Log.e("MainActivity", "Switch failed with code ${response.code}")
                                withContext(Dispatchers.Main) {
                                    onSwitchFailed("切换失败 (HTTP ${response.code})")
                                }
                                return@launch
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("MainActivity", "Switch request error (attempt $attempt)", e)
                    if (attempt < maxRetries) {
                        delay(3000)
                        // 继续下一次重试
                    } else {
                        withContext(Dispatchers.Main) {
                            onSwitchFailed("网络请求失败: ${e.message}")
                        }
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * 发送换区HTTP请求
     */
    private fun switchRegionRequest(newZone: String): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://vvv.xiexievpn.com/switch")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000  // 30秒超时
            connection.readTimeout = 30000

            // 构建JSON请求体
            val jsonObject = JSONObject()
            jsonObject.put("code", getUserUuid())  // 使用当前用户UUID
            jsonObject.put("newZone", newZone)
            val jsonInputString = jsonObject.toString()

            // 发送请求
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            HttpResponse(responseCode, responseBody)

        } catch (e: Exception) {
            Log.e("MainActivity", "Switch request network error", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * HTTP响应数据类
     */
    private data class HttpResponse(val code: Int, val body: String)

    /**
     * 获取当前用户UUID
     */
    private fun getUserUuid(): String {
        return userUuid ?: ""
    }

    /**
     * 获取用户完整信息（返回JSONObject）
     */
    private fun getUserInfoFullRequest(uuid: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://vvv.xiexievpn.com/getuserinfo")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            // Create JSON payload
            val jsonObject = JSONObject()
            jsonObject.put("code", uuid)
            val jsonInputString = jsonObject.toString()

            // Send request
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(response)
            } else {
                Log.e("MainActivity", "HTTP error: $responseCode")
                return null
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Request failed", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 禁用用户交互
     */
    private fun disableUserInteraction() {
        binding.fab.isEnabled = false
        binding.countryRecyclerView.isEnabled = false
        countrySelectorAdapter.isSwitching = true
        binding.pbWaiting.isVisible = true
        toast("正在切换区域...")
        Log.d("MainActivity", "User interaction disabled")
    }

    /**
     * 恢复用户交互
     */
    private fun enableUserInteraction() {
        binding.fab.isEnabled = true
        binding.countryRecyclerView.isEnabled = true
        countrySelectorAdapter.isSwitching = false
        binding.pbWaiting.isVisible = false
        Log.d("MainActivity", "User interaction enabled")
    }

    /**
     * 开始轮询切换状态
     */
    private fun startPollingStatus() {
        pollAttempts = 0
        Log.d("MainActivity", "Starting polling for region switch to $targetRegion")
        pollSwitchStatus()
    }

    /**
     * 轮询切换状态
     */
    private fun pollSwitchStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonResponse = getUserInfoFullRequest(getUserUuid())

                withContext(Dispatchers.Main) {
                    jsonResponse?.let { json ->
                        val zone = json.optString("zone", "")
                        val vmname = json.optString("vmname", "")
                        val v2rayurl = json.optString("v2rayurl", "")

                        Log.d("MainActivity", "Poll attempt $pollAttempts: zone=$zone, vmname=$vmname, v2rayurl_empty=${v2rayurl.isEmpty()}")

                        // 检查切换完成条件
                        // 条件1：zone已更新到目标区域且v2rayurl可用
                        if (zone == targetRegion && v2rayurl.isNotEmpty()) {
                            Log.d("MainActivity", "Switch completed: zone matches target and v2rayurl available")
                            onSwitchSuccess(v2rayurl)
                            return@withContext
                        }

                        // 条件2：vmname包含目标区域代码
                        if (vmname.contains(targetRegion ?: "")) {
                            Log.d("MainActivity", "VM name contains target region: $vmname")

                            // 获取VM创建进度（为未来进度条准备）
                            fetchVmProgress(vmname)

                            // 如果v2rayurl也可用，则切换完成
                            if (v2rayurl.isNotEmpty()) {
                                Log.d("MainActivity", "Switch completed: vmname matches and v2rayurl available")
                                onSwitchSuccess(v2rayurl)
                                return@withContext
                            }
                        }

                        // 继续轮询
                        pollAttempts++

                        // 更新估算进度（参考Windows客户端逻辑）
                        if (pollAttempts % 2 == 0) { // 每10秒更新一次进度显示
                            val estimatedProgress = minOf(10 + pollAttempts, 90)
                            updateProgress("正在等待切换完成...", estimatedProgress)
                        }

                        if (pollAttempts < maxPollAttempts) {
                            delay(5000)  // 5秒后重试
                            pollSwitchStatus()
                        } else {
                            Log.e("MainActivity", "Polling timeout after ${maxPollAttempts * 5} seconds")
                            onSwitchFailed("切换超时，请检查网络连接")
                        }
                    } ?: run {
                        // 轮询失败，继续重试
                        pollAttempts++
                        if (pollAttempts < maxPollAttempts) {
                            delay(5000)
                            pollSwitchStatus()
                        } else {
                            onSwitchFailed("切换超时，请检查网络连接")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Polling error", e)
                withContext(Dispatchers.Main) {
                    pollAttempts++
                    if (pollAttempts < maxPollAttempts) {
                        delay(5000)
                        pollSwitchStatus()
                    } else {
                        onSwitchFailed("网络连接异常")
                    }
                }
            }
        }
    }

    /**
     * 获取VM创建进度（为未来进度指示器准备）
     */
    private fun fetchVmProgress(vmname: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vvv.xiexievpn.com/createvmloading")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val jsonObject = JSONObject()
                jsonObject.put("vmname", vmname)
                val jsonInputString = jsonObject.toString()

                connection.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val progress = jsonResponse.optInt("progress", 0)

                    Log.d("MainActivity", "VM creation progress: $progress%")

                    // 更新进度指示器
                    withContext(Dispatchers.Main) {
                        val displayProgress = maxOf(10, progress) // 确保至少显示10%
                        updateProgress("正在创建服务器...", displayProgress)
                    }
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error fetching VM progress", e)
            }
        }
    }

    /**
     * 切换成功处理
     */
    private fun onSwitchSuccess(v2rayurl: String) {
        Log.d("MainActivity", "Region switch successful, importing new config")

        // 清除旧配置并导入新配置
        clearAllConfigs()
        val (count, _) = AngConfigManager.importBatchConfig(
            v2rayurl,
            mainViewModel.subscriptionId,
            false  // 替换而不是追加
        )

        if (count > 0) {
            mainViewModel.reloadServerList()

            // 确保成功时达到100%（参考Windows客户端）
            maxProgress = 100
            updateProgress("切换成功", 100)

            // 更新高亮显示
            targetRegion?.let { region ->
                countrySelectorAdapter.setSelectedCountry(region)
                currentRegion = region
                Log.d("MainActivity", "Updated highlight to region: $region")
            }

            // 延迟隐藏进度指示器
            lifecycleScope.launch {
                delay(1000)  // 显示成功状态1秒
                hideProgressIndicator()

                // 恢复VPN状态
                if (wasVpnOn) {
                    Log.d("MainActivity", "Restoring VPN connection")
                    delay(500)  // 稍等一下确保配置加载完成
                    binding.fab.performClick()  // 重新开启VPN
                }
            }

            // 恢复用户交互
            enableUserInteraction()
            isSwitching = false

            toast("区域切换成功")
            Log.d("MainActivity", "Region switch completed successfully")
        } else {
            Log.e("MainActivity", "Failed to import new configuration")
            onSwitchFailed("配置导入失败")
        }
    }

    // ==================== 进度显示相关方法 ====================

    /**
     * 显示进度指示器
     */
    private fun showProgressIndicator(status: String, progress: Int) {
        runOnUiThread {
            binding.progressOverlay.visibility = android.view.View.VISIBLE
            binding.progressStatus.text = status
            binding.progressText.text = "${progress}%"
            binding.circularProgress.setProgressCompat(progress, true)
        }
    }

    /**
     * 更新进度（参考Windows客户端逻辑，确保进度只增不减）
     */
    private fun updateProgress(status: String, progress: Int) {
        runOnUiThread {
            // 只有当新进度大于当前最大进度时才更新（参考Windows客户端）
            if (progress > maxProgress) {
                maxProgress = progress
            }

            binding.progressStatus.text = status
            binding.progressText.text = "${maxProgress}%"
            binding.circularProgress.setProgressCompat(maxProgress, true)
        }
    }

    /**
     * 隐藏进度指示器
     */
    private fun hideProgressIndicator() {
        runOnUiThread {
            binding.progressOverlay.visibility = android.view.View.GONE
        }
    }

    /**
     * 切换失败处理
     */
    private fun onSwitchFailed(errorMsg: String) {
        Log.e("MainActivity", "Region switch failed: $errorMsg")

        // 隐藏进度指示器
        hideProgressIndicator()

        // 恢复原高亮状态
        currentRegion?.let { region ->
            countrySelectorAdapter.setSelectedCountry(region)
        }

        // 恢复用户交互
        enableUserInteraction()
        isSwitching = false

        toast("切换失败: $errorMsg")
    }

}