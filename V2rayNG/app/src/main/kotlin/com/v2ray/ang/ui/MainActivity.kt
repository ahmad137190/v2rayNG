package com.v2ray.ang.ui

import android.Manifest

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils


import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.telephony.TelephonyManager

import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.LayoutProgressBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.drakeet.support.toast.ToastCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMainBinding

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                Utils.stopVService(this)
            } else if ((settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN") == "VPN") {
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
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)


        val toggle = ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)


        setupViewModel()
        copyAssets()
        //migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RxPermissions(this)
                .request(Manifest.permission.POST_NOTIFICATIONS)
                .subscribe {
                    if (!it)
                        toast(R.string.toast_permission_denied)
                }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    //super.onBackPressed()
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
            }
        }
        mainViewModel.startListenBroadcast()
    }

    private fun copyAssets() {
        val extFolder = Utils.userAssetPath(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geo = arrayOf("geosite.dat", "geoip.dat")
                assets.list("")
                        ?.filter { geo.contains(it) }
                        ?.filter { !File(extFolder, it).exists() }
                        ?.forEach {
                            val target = File(extFolder, it)
                            assets.open(it).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            Log.i(ANG_PACKAGE, "Copied from apk assets folder to ${target.absolutePath}")
                        }
            } catch (e: Exception) {
                Log.e(ANG_PACKAGE, "asset copy failed", e)
            }
        }
    }

//    private fun migrateLegacy() {
//        lifecycleScope.launch(Dispatchers.IO) {
//            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
//            if (result != null) {
//                launch(Dispatchers.Main) {
//                    if (result) {
//                        toast(getString(R.string.migration_success))
//                        mainViewModel.reloadServerList()
//                    } else {
//                        toast(getString(R.string.migration_fail))
//                    }
//                }
//            }
//        }
//    }

    fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            return
        }
        V2RayServiceManager.startV2Ray(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            Utils.stopVService(this)
        }
        Observable.timer(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    startV2Ray()
                }
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode(true)
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }
        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }
        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }
        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }
        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }
        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }
        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }
        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }
        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }
        R.id.import_config_custom_url_scan -> {
            importQRcode(false)
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(this, mainViewModel.serverList) == 0) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        MmkvManager.removeAllServer()
                        mainViewModel.reloadServerList()
                    }
                    .setNegativeButton(android.R.string.no) {_, _ ->
                        //do noting
                    }
                    .show()
            true
        }
        R.id.del_duplicate_config-> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mainViewModel.removeDuplicateServer()
                }
                .setNegativeButton(android.R.string.no) {_, _ ->
                    //do noting
                }
                .show()
            true
        }
        R.id.del_invalid_config -> {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeInvalidServer()
                    mainViewModel.reloadServerList()
                }
                .setNegativeButton(android.R.string.no) {_, _ ->
                    //do noting
                }
                .show()
            true
        }
        R.id.sort_by_test_results -> {
            MmkvManager.sortByTestResults()
            mainViewModel.reloadServerList()
            true
        }
        R.id.filter_config -> {
            mainViewModel.filterConfig(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType : Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    fun importQRcode(forConfig: Boolean): Boolean {
//        try {
//            startActivityForResult(Intent("com.google.zxing.client.android.SCAN")
//                    .addCategory(Intent.CATEGORY_DEFAULT)
//                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)
//        } catch (e: Exception) {
        RxPermissions(this)
                .request(Manifest.permission.CAMERA)
                .subscribe {
                    if (it)
                        if (forConfig)
                            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
                        else
                            scanQRCodeForUrlToCustomConfig.launch(Intent(this, ScannerActivity::class.java))
                    else
                        toast(R.string.toast_permission_denied)
                }
//        }
        return true
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    private val scanQRCodeForUrlToCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importConfigCustomUrl(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    /**
     * import config from clipboard
     */
    fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importBatchConfig(server: String?) {
        val dialog = AlertDialog.Builder(this)
            .setView(LayoutProgressBinding.inflate(layoutInflater).root)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(R.string.toast_success)
                    mainViewModel.reloadServerList()
                } else {
                    toast(R.string.toast_failure)
                }
                dialog.dismiss()
            }
        }
    }

    fun importConfigCustomClipboard()
            : Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard()
            : Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    Utils.getUrlContentWithCustomUserAgent(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    fun importConfigViaSub() : Boolean {
        val dialog = AlertDialog.Builder(this)
            .setView(LayoutProgressBinding.inflate(layoutInflater).root)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = AngConfigManager.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(R.string.toast_success)
                    mainViewModel.reloadServerList()
                } else {
                    toast(R.string.toast_failure)
                }
                dialog.dismiss()
            }
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } catch (ex: ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        RxPermissions(this)
                .request(permission)
                .subscribe {
                    if (it) {
                        try {
                            contentResolver.openInputStream(uri).use { input ->
                                importCustomizeConfig(input?.bufferedReader()?.readText())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else
                        toast(R.string.toast_permission_denied)
                }
    }

    /**
     * import customize config
     */
    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            if (mainViewModel.appendCustomConfigServer(server)) {
                mainViewModel.reloadServerList()
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            //adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(this, "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            return
        }
    }

    fun setTestState(content: String?) {
        binding.tvTestState.text = content
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            //R.id.server_profile -> activityClass = MainActivity::class.java
            R.id.sub_setting -> {
                startActivity(Intent(this, SubSettingActivity::class.java))
            }
            R.id.settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)
                        .putExtra("isRunning", mainViewModel.isRunning.value == true))
            }
            R.id.user_asset_setting -> {
                startActivity(Intent(this, UserAssetActivity::class.java))
            }
            R.id.promotion -> {
                Utils.openUri(this, "${Utils.decode(AppConfig.PromotionUrl)}?t=${System.currentTimeMillis()}")
            }
            R.id.logcat -> {
                startActivity(Intent(this, LogcatActivity::class.java))
            }
            R.id.about-> {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }



/*    private fun getListHologateFromServer() {
        // dialogLoading?.show()

        var token: String? = try {
            GetAutUser("token")

        } catch (e: Exception) {
            GetAutUser("token")
        }
        val entities = ArrayList<AbstractBean>()
        val payload = "test payload"

        //   val okHttpClient = OkHttpClient()
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient().build()

        val jsonObject = JSONObject()

        try {
            jsonObject.put("token_fb", ThemedActivity.GlobalStuff.token_fb)
            jsonObject.put("mobile_uid", DataStore.deviceID)
            println(" oghab DataStore.deviceID   ${DataStore.deviceID}")

        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(), jsonObject.toString()
        )

        //  val requestBody = payload.toRequestBody()
        val request = Request.Builder().header("Authorization", token.toString())
            //.header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
            .method("POST", requestBody).url(base_url + "/api/accounts").build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try {
                    runOnUiThread {


                        dialogLoading?.dismiss()


                        Toast.makeText(
                            this, "The server encountered a problem", Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    // dialogLoading?.dismiss()
                }
                // Handle this
                println("onFailure : $e")

            }

            override fun onResponse(call: Call, response: Response) {
                // Handle this
                response.use {


                    try {
                        dialogLoading?.dismiss()

                    } catch (e: Exception) {
                        // dialogLoading?.dismiss()
                    }
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")

                    *//*  for ((name, value) in response.headers) {
                          println("$name: $value")
                      }*//*

                    try {
                        val jsonData: String = response.body!!.string()
                        println("onResponse111   $jsonData")
                        //activity.SetAutUser("token", "").apply();
                        //  token = GetAutUser("token");
                        val Jobject = JSONObject(jsonData)
                        val Jarray: JSONArray = Jobject.getJSONArray("accounts")

                        for (i in 0 until Jarray.length()) {
                            val object22: JSONObject = Jarray.getJSONObject(i)
                            if (object22.has("type") && object22.getString("type") == "v2ray") {
                                val link: String = object22.getString("link")
                                println("onResponse1114444444   $link")

                                //    if (i==Jarray.length()-1) {
//                                entities.add(
//                                    parseV2Ray("vmess://eyJ2IjoiMiIsInBzIjoiVWFfNTk5IiwiYWRkIjoiMTA0LjIxLjUzLjEyIiwicG9ydCI6NDQzLCJpZCI6IjAzZmNjNjE4LWI5M2QtNjc5Ni02YWVkLThhMzhjOTc1ZDU4MSIsImFpZCI6MSwic2N5IjoiYXV0byIsIm5ldCI6IndzIiwiaG9zdCI6Im9waGVsaWEubW9tIiwicGF0aCI6Imxpbmt2d3MiLCJ0bHMiOiJ0bHMifQ==")
//                                )
                                if (link !== "null") {
                                    if (link.contains("vmess") || link.contains("vless")) {
                                        entities.add(
                                            parseV2Ray(
                                                link,
                                                object22.getString("expiration_date")
                                            )
                                        )
                                    } else {
                                        runOnDefaultDispatcher {
                                            onMainDispatcher {
                                                try {
                                                    val proxies = RawUpdater.parseRaw(link)
                                                    if (proxies.isNullOrEmpty()) onMainDispatcher {
                                                        snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                                    } else import(proxies)
                                                } catch (e: SubscriptionFoundException) {
                                                    importSubscription(
                                                        Uri.parse(e.link)
                                                    )
                                                } catch (e: Exception) {
                                                    Logs.w(e)

                                                    onMainDispatcher {
                                                        snackbar(e.readableMessage).show()
                                                    }
                                                }
                                            }
//                                lifecycleScope.launch {
//                                importSubscription(Uri.parse(object22.getString("config")))
//                               }

                                        }
                                    }


                                }


                            } else if (object22.has("type") && object22.getString("type") == "ssh") {
//                                val entitie = SSHBean()
//
//                                entitie.authType = 1
//                                entitie.privateKey = ""
//                                entitie.publicKey = ""
//                                entitie.privateKeyPassphrase = ""
//                                entitie.customConfigJson = ""
//                                entitie.customOutboundJson = ""
//                                entitie.password = object22.getString("password")
//                                entitie.username = object22.getString("username")
//                                entitie.name = object22.getString("name")
//                                entitie.serverAddress = object22.getString("host")
//                                entitie.serverPort = object22.getInt("port")
//                                entitie.expireDate = object22.getString("expiration_date")
//                                entitie.check_secure = if(object22.has("secure")) object22.getBoolean("secure") else false
//                                entities.add(entitie)

                            } else {
                                if (object22.getString("link") !== "null") {
                                    var text = object22.getString("link")
                                    runOnDefaultDispatcher {
                                        onMainDispatcher {
                                            try {
                                                val proxies = RawUpdater.parseRaw(text)
                                                if (proxies.isNullOrEmpty()) onMainDispatcher {
                                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                                } else import(proxies)
                                            } catch (e: SubscriptionFoundException) {
                                                importSubscription(
                                                    Uri.parse(e.link)
                                                )
                                            } catch (e: Exception) {
                                                Logs.w(e)

                                                onMainDispatcher {
                                                    snackbar(e.readableMessage).show()
                                                }
                                            }
                                        }
//                                lifecycleScope.launch {
//                                importSubscription(Uri.parse(object22.getString("config")))
//                               }

                                    }
                                }
                            }


                            //  import(proxies)
                            println("onResponse222   $object22")
                            println("onResponse333  " + object22.getString("name"))
                        }

                        runOnDefaultDispatcher {
                            try {
                                val proxies = entities
                                if (proxies.isNullOrEmpty()) onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                                else {
                                    import(proxies)
                                }

                            } catch (e: SubscriptionFoundException) {

                                importSubscription(Uri.parse(e.link))
                            } catch (e: Exception) {
                                Logs.w(e)

                                onMainDispatcher {
                                    snackbar(e.readableMessage).show()
                                }
                            }
                        }


                    } catch (e: Exception) {
                        println("onResponse Exception  $e")
                        //Toast.makeText(requireActivity(), "aaaaa", Toast.LENGTH_LONG).show()
                        runOnUiThread {
                            Toast.makeText(
                                this, "The server encountered a problem", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }


//                            for ((name, value) in response.headers) {
//                                println("$name: $value")
//                            }
//                            println("onResponse   "+response.body!!.string())

                }

            }
        })
    }*/

/*    private fun checkHologateFromServer() {
        var token: String? = try {
            GetAutUser("token")

        } catch (e: Exception) {
            GetAutUser("token")
        }
        println("onResponse token   $token")
        val payload = "test payload"

        // val okHttpClient = OkHttpClient()
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient().build()

        val requestBody = payload.toRequestBody()
        val request = Request.Builder().header("Authorization", token.toString())
            //.header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
            .method("POST", requestBody).url(base_url + "/api/accounts/gift/can").build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                (requireActivity() as MainActivity)!!.runOnUiThread {
                    try {
                        dialogLoading?.dismiss()

                    } catch (e: Exception) {
                        // dialogLoading?.dismiss()
                    }
                    Toast.makeText(
                        (requireActivity() as MainActivity),
                        "The server encountered a problem",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // Handle this
                println("onFailure : $e")

            }

            override fun onResponse(call: Call, response: Response) {
                // Handle this
                response.use {
                    try {
                        dialogLoading?.dismiss()

                    } catch (e: Exception) {
                        // dialogLoading?.dismiss()
                    }
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")

                    *//*  for ((name, value) in response.headers) {
                          println("$name: $value")
                      }*//*

                    try {
                        val jsonData: String = response.body!!.string()
                        println("onResponse checkHologateFromServer   $jsonData")

                        val Jobject = JSONObject(jsonData)
                        val _can: Boolean = Jobject.getBoolean("can")
                        println("onResponse checkHologateFromServer object22  $jsonData")
                        if (_can) {
                            runOnUiThread {
                                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.free_account)
                                    .setMessage(R.string.free_account_message)
                                    .setPositiveButton(R.string.yes) { _, _ ->
//                                        println("onResponse checkHologateFromServer setPositiveButton  ")
//                                        Toast.makeText(
//                                            activity,
//                                            "The server encountered a problem",
//                                            Toast.LENGTH_SHORT
//                                        ).show()
                                        // token?.let { it1 -> createAccountHologateFromServer(it1) }
                                        createAccountHologateFromServer("")
                                    }.setNegativeButton(android.R.string.cancel, null).show()
                            }

                        } else runOnUiThread {
                            if (Jobject.has("message")) Toast.makeText(
                                activity, Jobject.getString("message"), Toast.LENGTH_SHORT
                            ).show()
                            else Toast.makeText(
                                activity,
                                getString(R.string.free_account_message_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                    } catch (e: Exception) {
                        println("onResponse Exception  $e")
                        //Toast.makeText(requireActivity(), "aaaaa", Toast.LENGTH_LONG).show()
                        runOnUiThread {
                            Toast.makeText(
                                activity, "The server encountered a problem", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }


//                            for ((name, value) in response.headers) {
//                                println("$name: $value")
//                            }
//                            println("onResponse   "+response.body!!.string())

                }

            }
        })
    }*/

/*    private fun createAccountHologateFromServer(url: String) {


        try {
            dialogLoading?.show()

        } catch (e: Exception) {
            // dialogLoading?.dismiss()
        }
        var url_Server = ""
        val jsonObject = JSONObject()
        if (url == "") {
            url_Server = base_url + "/api/accounts/gift/create"
        } else {
            url_Server = url
            val username = usernameDialog!!.text.toString()
            val password = passwordDialog!!.text.toString()


            try {
//            jsonObject.put("username", "majidlx@gmail.com")
//            jsonObject.put("password", "123456789")
                jsonObject.put("username", username)
                jsonObject.put("password", password)
                jsonObject.put("token_fb", ThemedActivity.GlobalStuff.token_fb)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        //  if (token.equals(""))


        *//*  val email = _emailText!!.text.toString()
        val password = _passwordText!!.text.toString()

        //val payload = "username=majidlx@gmail.com&&password=123456789"

        val okHttpClient = OkHttpClient()
        // val requestBody = payload.toRequestBody()

        val jsonObject = JSONObject()
        try {
//            jsonObject.put("username", "majidlx@gmail.com")
//            jsonObject.put("password", "123456789")
            jsonObject.put("username", email)
            jsonObject.put("password", password)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val body: RequestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(),
            jsonObject.toString()
        )

        //val mediaType = "application/json; charset=utf-8".toMediaType()
        //  val body = jsonObject.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            // .header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
            .method("POST", body)
            // .method("POST", requestBody)
            // .post(body)

            .url("https://shop.holoo.pro/api/login")
            .build()*//*
        var token: String? = try {
            GetAutUser("token")

        } catch (e: Exception) {
            GetAutUser("token")
        }
        println("onResponse token   $token")
        val payload = "test payload"

        // val okHttpClient = OkHttpClient()
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient().build()

        var requestBody: RequestBody? = null
        if (url == "") {
            requestBody = payload.toRequestBody()
        } else {
            requestBody = RequestBody.create(
                "application/json; charset=utf-8".toMediaType(), jsonObject.toString()
            )
            //requestBody =payload.toRequestBody()
        }

        val request = Request.Builder().header("Authorization", token.toString())
            //.header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
            .method("POST", requestBody).url(url_Server).build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {

                (requireActivity() as MainActivity)!!.runOnUiThread {
                    try {
                        dialogLoading?.dismiss()

                    } catch (e: Exception) {
                        // dialogLoading?.dismiss()
                    }
                    Toast.makeText(
                        (requireActivity() as MainActivity),
                        "The server encountered a problem",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Handle this
                println("onFailure : $e")

            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    dialogLoading?.dismiss()

                } catch (e: Exception) {
                    // dialogLoading?.dismiss()
                }                // Handle this
                response.use {
                    // if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    if (!response.isSuccessful) {
                        val jsonData: String = response.body!!.string()
                        val Jobject = JSONObject(jsonData)
                        (requireActivity() as MainActivity)!!.runOnUiThread {
                            Toast.makeText(
                                (requireActivity() as MainActivity),
                                Jobject.getString("message"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        throw IOException("Unexpected code $response")
                    }*//*  for ((name, value) in response.headers) {
                          println("$name: $value")
                      }*//*

                    try {
                        val entities = ArrayList<AbstractBean>()

                        val jsonData: String = response.body!!.string()
                        println("onResponse111222   $jsonData")
                        //activity.SetAutUser("token", "").apply();
                        //  token = GetAutUser("token");
                        val Jobject = JSONObject(jsonData)
                        val account: JSONObject = Jobject.getJSONObject("account")
                        val entitie = SSHBean()
                        val object22: JSONObject = account

                        if (Jobject.isNull("account")) {

                        } else {
                            entitie.authType = 1
                            entitie.privateKey = ""
                            entitie.publicKey = ""
                            entitie.privateKeyPassphrase = ""
                            entitie.customConfigJson = ""
                            entitie.customOutboundJson = ""
                            entitie.password = object22.getString("password")
                            entitie.username = object22.getString("username")
                            entitie.name = object22.getString("name")
                            entitie.serverAddress = object22.getString("host")
                            entitie.serverPort = object22.getInt("port")
                            entitie.expireDate = object22.getString("expiration_date")
                            entitie.check_secure = if(object22.has("secure")) object22.getBoolean("secure") else false

                            //  import(proxies)
                            entities.add(entitie)
                            println("onResponse222   $object22")
                            println("onResponse333  " + object22.getString("name"))

                            runOnDefaultDispatcher {
                                try {
                                    val proxies = entities
                                    if (proxies.isNullOrEmpty()) onMainDispatcher {
                                        snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                    }
                                    else {
                                        import(proxies)
                                    }

                                } catch (e: SubscriptionFoundException) {
                                    importSubscription(
                                        Uri.parse(
                                            e.link
                                        )
                                    )
                                } catch (e: Exception) {
                                    Logs.w(e)

                                    onMainDispatcher {
                                        snackbar(e.readableMessage).show()
                                    }
                                }
                            }

                        }
                        if (url != "") {
                            myDialog_add_profile_hologate.dismiss()
                        }

                    } catch (e: Exception) {
                        println("onResponse Exception  $e")
                        //Toast.makeText(requireActivity(), "aaaaa", Toast.LENGTH_LONG).show()
                        runOnUiThread {
                            Toast.makeText(
                                activity, "The server encountered a problem", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }


//                            for ((name, value) in response.headers) {
//                                println("$name: $value")
//                            }
//                            println("onResponse   "+response.body!!.string())

                }

            }
        })
    }*/

/*    fun LogHologateFromServer(
        url: String, entities: ArrayList<ProxyEntity>?, main_act: MainActivity?
    ) {

//        (requireActivity() as MainActivity)!!.runOnUiThread {
//            dialogLoading?.show()
//        }
        var url_Server = ""
        val jsonObject = JSONObject()
        ///////////TypeConnection////////
//        var resultTypeConnect = activity?.let { it1 ->
//            isInternetAvailable(
//                it1
//            )
//        }
        var resultTypeConnect = main_act?.let { isInternetAvailable1(it) }
        if (resultTypeConnect == "CELLULAR") {
            val manager = main_act!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val carrierName = manager.networkOperatorName
            resultTypeConnect = carrierName
            println("oghab 3@ " + carrierName)
        } else {
            println("oghab 4@ " + "nnooo")
        }
        ///////////TypeConnection////////

        if (url == "") {
            url_Server = base_url + "/api/log/connection"
        } else {
            url_Server = url
//            val username = usernameDialog!!.text.toString()
//            val password = passwordDialog!!.text.toString()


        }
        try {

            val result_entities = entities?.get(0)
            val sshBeean = result_entities?.sshBean

            println(
                "oghab @@@@ " + "url=> " + result_entities?.displayAddress() + "  username=> " + sshBeean?.username + "  password=> " + sshBeean?.password + "  serverPort=> " + sshBeean?.serverPort + "  type_network=> " + resultTypeConnect + "  type_connection=> " + result_entities?.displayType()
            )
            jsonObject.put("address", result_entities?.displayAddress())
            jsonObject.put("username", sshBeean?.username)
            jsonObject.put("password", sshBeean?.password)
            jsonObject.put("port", sshBeean?.serverPort)
            jsonObject.put("type_connection", result_entities?.displayType())
            jsonObject.put("type_network", resultTypeConnect)
            jsonObject.put("token_fb", ThemedActivity.GlobalStuff.token_fb)
            jsonObject.put("mobile_uid", DataStore.deviceID)
        } catch (e: JSONException) {
            println("error   $e")
            e.printStackTrace()
        }
//        val prefs = main_act?.getSharedPreferences("Wedding", AppCompatActivity.MODE_PRIVATE)
//        val restoredText = prefs?.getString("token", "")
//        var token: String? =""
//         if (restoredText != "") {
//             token= prefs?.getString("token", "")
//        }
        val token: String? = main_act?.GetAutUser("token")

        println("onResponse token   $token")
        val payload = "test payload"

        // val okHttpClient = OkHttpClient()
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient().build()

        var requestBody: RequestBody? = null

        requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(), jsonObject.toString()
        )
        //requestBody =payload.toRequestBody()


        val request = Request.Builder().header("Authorization", token.toString())
            //.header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
            .method("POST", requestBody).url(url_Server).build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {

//                (requireActivity() as MainActivity)!!.runOnUiThread {
//                    dialogLoading?.dismiss()
//                    Toast.makeText(
//                        (requireActivity() as MainActivity),
//                        "The server encountered a problem",
//                        Toast.LENGTH_SHORT
//                    ).show()
//                }

                // Handle this
                println("onFailure : $e")

            }

            override fun onResponse(call: Call, response: Response) {
//                dialogLoading?.dismiss()
                // Handle this
                response.use {
                    // if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    if (!response.isSuccessful) {
                        //  val jsonData: String = response.body!!.string()
                        //   val Jobject = JSONObject(jsonData)
//                        (requireActivity() as MainActivity)!!.runOnUiThread {
//                            Toast.makeText(
//                                (requireActivity() as MainActivity),
//                                Jobject.getString("message"),
//                                Toast.LENGTH_SHORT
//                            ).show()
//                        }
                        throw IOException("Unexpected code $response")
                    }*//*  for ((name, value) in response.headers) {
                          println("$name: $value")
                      }*//*

                    try {

                        val jsonData: String = response.body!!.string()
                        println("onResponse111222   $jsonData")


                    } catch (e: Exception) {
                        println("onResponse Exception  $e")
                        //Toast.makeText(requireActivity(), "aaaaa", Toast.LENGTH_LONG).show()
//                        runOnUiThread {
//                            Toast.makeText(
//                                activity,
//                                "The server encountered a problem",
//                                Toast.LENGTH_SHORT
//                            ).show()
//                        }
                    }


//                            for ((name, value) in response.headers) {
//                                println("$name: $value")
//                            }
//                            println("onResponse   "+response.body!!.string())

                }

            }
        })
    }*/

/*    suspend fun CreateAccountNotConnectHologateFromServer(
        url: String, main_act: MainActivity?
    ) {
        *//*     main_act?.runOnUiThread {
                 main_act.dialogLoading?.dismiss()
                 Toast.makeText(
                     main_act,
                     "لطفا صبر نمایید در حال ساختن سرور جدید ...",
                     Toast.LENGTH_SHORT
                 ).show()
             }*//*
        var serverOld = DataStore.selectedProxyOld
        val token: String? = main_act?.GetAutUser("token")
        val result_entities = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)

//        (requireActivity() as MainActivity)!!.runOnUiThread {
//            dialogLoading?.show()
//        }
        var url_Server = ""
        val jsonObject = JSONObject()
        ///////////TypeConnection////////
//        var resultTypeConnect = activity?.let { it1 ->
//            isInternetAvailable(
//                it1
//            )
//        }
        var resultTypeConnect = main_act?.let { isInternetAvailable1(it) }
        if (resultTypeConnect == "CELLULAR") {
            val manager = main_act!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val carrierName = manager.networkOperatorName
            resultTypeConnect = carrierName
            println("oghab 3@ " + carrierName)
        } else {
            println("oghab 4@ " + "nnooo")
        }
        ///////////TypeConnection////////

        if (url == "") {
            // url_Server = base_url + "/api/log/connection"
            // url_Server = base_url + "/api/accounts"
            url_Server = base_url + "/api/auto-change-server"

        } else {
            url_Server = url
//            val username = usernameDialog!!.text.toString()
//            val password = passwordDialog!!.text.toString()


        }
        try {
            // DataStore.selectedGroup
            // DataStore.selectedProxy
            // SagerDatabase.proxyDao.getByGroup(DataStore.selectedGroup).get(DataStore.selectedProxy)

            //  val proxyGroup = SagerDatabase.groupDao.getById(DataStore.selectedGroup).
            val type = result_entities?.displayType()?.lowercase()

            println(
                "oghab @@@@ 888 serverOld=> " + serverOld + "mobile_uid =>" + DataStore.deviceID + "url=> " + result_entities?.displayAddress() + "  uuid=> " + result_entities?.uuid + "  toStdLink=> " + result_entities?.toStdLink() + "  type_network=> " + resultTypeConnect + "  type_connection=> " + type
            )
            if (type == "ssh") {
                val sshBeean = result_entities?.sshBean
                println(
                    "oghab @@@@ " + "url=> " + result_entities?.displayAddress() + "  username=> " + sshBeean?.username + "  password=> " + sshBeean?.password + "  serverPort=> " + sshBeean?.serverPort + "  type_network=> " + resultTypeConnect + "  type_connection=> " + result_entities?.displayType()
                )
                jsonObject.put("address", result_entities?.displayAddress())
                jsonObject.put("username", sshBeean?.username)
                jsonObject.put("password", sshBeean?.password)
                jsonObject.put("port", sshBeean?.serverPort)

                *//*        onMainDispatcher {
        //                    val fragment = getCurrentGroupFragment()

                            adapter.groupFragments?.get(DataStore.selectedGroup)?.adapter?.let {
                                val index = it.configurationIdList.indexOf(DataStore.selectedProxy)
                                it.remove(index)
                                //it.undoManager.remove(index to result_entities)
                            }
                        *//**//*        fragment.adapter?.apply {
                            val index = configurationIdList.indexOf(DataStore.selectedProxy)
                            if (index >= 0) {
                                configurationIdList.removeAt(index)
                                configurationList.remove(DataStore.selectedProxy)
                                notifyItemRemoved(index)
                            }
                        }*//**//*

                        runOnDefaultDispatcher {

                            ProfileManager.deleteProfile2(
                                DataStore.selectedGroup, DataStore.selectedProxy
                            )

                        }

//                    else
//                        println("oghab 7#@#@#" )
                }*//*

                // SagerDatabase.proxyDao.updateProxy(result_entities)
            } else if (type == "vmess" || type == "vless") {
                val vmessBean = result_entities.vmessBean
//                println("oghab @@@@ 11" + "url=> " + result_entities?.displayAddress() + "type_network=> " + resultTypeConnect + "  type_connection=> " + type)
//                println("oghab @@@@ 112"  +result_entities.uuid+ " - uuid"+vmessBean?.uuid+ " - host"+vmessBean?.host)
                jsonObject.put("link", result_entities.toStdLink())
                jsonObject.put("uuid", vmessBean?.uuid)
            }
            jsonObject.put("host", result_entities?.displayAddress())
            jsonObject.put("type_connection", type)
            jsonObject.put("type_network", resultTypeConnect)
            jsonObject.put("token_fb", ThemedActivity.GlobalStuff.token_fb)
            jsonObject.put("token", token)
            jsonObject.put("serverOld", serverOld)
            jsonObject.put("autoChangeServer", autoChangeServer)
            jsonObject.put("mobile_uid", DataStore.deviceID)

        } catch (e: JSONException) {
            println("error   $e")
            e.printStackTrace()
        }
        // return
//        val prefs = main_act?.getSharedPreferences("Wedding", AppCompatActivity.MODE_PRIVATE)
//        val restoredText = prefs?.getString("token", "")
//        var token: String? =""
//         if (restoredText != "") {
//             token= prefs?.getString("token", "")
//        }
        //   return
        println("onResponse token   $token")
        val payload = "test payload"
        val entities = ArrayList<AbstractBean>()

        // val okHttpClient = OkHttpClient()
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient().build()

        var requestBody: RequestBody? = null

        requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(), jsonObject.toString()
        )
        //requestBody =payload.toRequestBody()

        var request: Request? = null
        if (token.equals("")) {
            request = Request.Builder().method("POST", requestBody).url(url_Server).build()


        } else {
            request = Request.Builder().header("Authorization", token.toString())
                //.header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
                .method("POST", requestBody).url(url_Server).build()
        }

        okHttpClient.newCall(request).enqueue(object : Callback {
            @SuppressLint("SuspiciousIndentation")
            override fun onFailure(call: Call, e: IOException) {

                main_act?.runOnUiThread {
                    main_act.dialogLoading?.dismiss()
                    if (autoChangeServer)
                        Toast.makeText(
                            main_act,
                            "The server encountered a problem",
                            Toast.LENGTH_SHORT
                        ).show()
                }

                // Handle this
                println("onFailure : $e")

            }

            @SuppressLint("SuspiciousIndentation")
            override fun onResponse(call: Call, response: Response) {
//                dialogLoading?.dismiss()
                // Handle this
                response.use {
                    // if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    if (!response.isSuccessful) {
//                        println("jsonData @@@ : " + response.code + (response.code == 404) + main_act)

                        if (response.code == 404 || response.code == 500) {
                            main_act?.runOnUiThread {
//                                println("jsonData @@@ 111: " + main_act + response.request.toString())

                                main_act.dialogLoading?.dismiss()
                                if (autoChangeServer)
                                    Toast.makeText(
                                        main_act,
                                        "سرور با مشکل مواجه شد!!!",
                                        Toast.LENGTH_LONG
                                    ).show()
                            }
                            return
                        } else {
                            val jsonData: String = response.body!!.string()
                            println("jsonData @@@ : $jsonData")
                            val Jobject = JSONObject(jsonData)

                            main_act?.runOnUiThread {
                                if (autoChangeServer)
                                    Toast.makeText(
                                        main_act,
                                        Jobject.getString("message"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                            }
                            throw IOException("Unexpected code $response")
                        }

                    }*//*  for ((name, value) in response.headers) {
                          println("$name: $value")
                      }*//*

                    checkReload = true
                    oldSelectedProxy = DataStore.selectedProxy
                    try {
                        val jsonData: String = response.body!!.string()
                        println("onResponse111   $jsonData")
                        //activity.SetAutUser("token", "").apply();
                        //  token = GetAutUser("token");
                        val Jobject = JSONObject(jsonData)

                        if (!Jobject.getBoolean("success")) {


                            main_act?.runOnUiThread {
                                if (autoChangeServer)
                                    Toast.makeText(
                                        main_act,
                                        Jobject.getString("message"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                            }
                            return
                            throw IOException("Unexpected code $response")
                        }


                        runOnDefaultDispatcher {
                            val Jarray: JSONArray = Jobject.getJSONArray("accounts")
                            if (Jarray.length() == 0) {
                                if (autoChangeServer)
                                    Toast.makeText(
                                        main_act,
                                        "لیست سرور ها خالی هست",
                                        Toast.LENGTH_LONG
                                    ).show()
                                return@runOnDefaultDispatcher
                            }
                            delay(1000)
                            main_act?.runOnUiThread {
                                if (autoChangeServer)
                                // snackbar("کانفیگ قبلی شما پاک می شود و کانفیگ جدید جایگزین می شود.").show()

                                    Toast.makeText(
                                        main_act,
                                        "کانفیگ قبلی شما پاک می شود و کانفیگ جدید جایگزین می شود.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                // Toast.makeText(activity, "کانفیگ قبلی شما پاک می شود و کانفیگ جدید جایگزین می شود.", 2000).show()
                            }
                            delay(4000)

                            var proxyOld = SagerDatabase.proxyDao.getById(selectedProxy)
                            if (proxyOld != null) {
                                DataStore.selectedProxyOld = proxyOld.displayAddress()
                            }

                            if (result_entities != null && Jarray.length() > 0) {
                                SagerDatabase.proxyDao.deleteProxy(result_entities)
                            }
                            for (i in 0 until Jarray.length()) {
                                val object22: JSONObject = Jarray.getJSONObject(i)
                                if (object22.has("type") && object22.getString("type") == "v2ray") {
                                    val link: String = object22.getString("link")
                                    println("onResponse1114444444   $link")

                                    //    if (i==Jarray.length()-1) {
//                                entities.add(
//                                    parseV2Ray("vmess://eyJ2IjoiMiIsInBzIjoiVWFfNTk5IiwiYWRkIjoiMTA0LjIxLjUzLjEyIiwicG9ydCI6NDQzLCJpZCI6IjAzZmNjNjE4LWI5M2QtNjc5Ni02YWVkLThhMzhjOTc1ZDU4MSIsImFpZCI6MSwic2N5IjoiYXV0byIsIm5ldCI6IndzIiwiaG9zdCI6Im9waGVsaWEubW9tIiwicGF0aCI6Imxpbmt2d3MiLCJ0bHMiOiJ0bHMifQ==")
//                                )
                                    if (link !== "null") {
                                        if (link.contains("vmess") || link.contains("vless")) {
                                            entities.add(
                                                parseV2Ray(
                                                    link,
                                                    object22.getString("expiration_date")
                                                )
                                            )
                                        } else {
                                            runOnDefaultDispatcher {
                                                onMainDispatcher {
                                                    try {
                                                        val proxies = RawUpdater.parseRaw(link)
                                                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                                                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                                        } else import(proxies)
                                                    } catch (e: SubscriptionFoundException) {
                                                        importSubscription(
                                                            Uri.parse(e.link)
                                                        )
                                                    } catch (e: Exception) {
                                                        Logs.w(e)

                                                        onMainDispatcher {
                                                            snackbar(e.readableMessage).show()
                                                        }
                                                    }
                                                }
//                                lifecycleScope.launch {
//                                importSubscription(Uri.parse(object22.getString("config")))
//                               }

                                            }
                                        }


                                    }


                                } else if (object22.has("type") && object22.getString("type") == "ssh") {
                                    val entitie = SSHBean()

                                    entitie.authType = 1
                                    entitie.privateKey = ""
                                    entitie.publicKey = ""
                                    entitie.privateKeyPassphrase = ""
                                    entitie.customConfigJson = ""
                                    entitie.customOutboundJson = ""
                                    entitie.password = object22.getString("password")
                                    entitie.username = object22.getString("username")
                                    entitie.name = object22.getString("name")
                                    entitie.serverAddress = object22.getString("host")
                                    entitie.serverPort = object22.getInt("port")
                                    entitie.expireDate = object22.getString("expiration_date")
                                    entitie.check_secure = if(object22.has("secure")) object22.getBoolean("secure") else false
                                    entities.add(entitie)

                                } else {
                                    if (object22.getString("link") !== "null") {
                                        var text = object22.getString("link")
                                        runOnDefaultDispatcher {
                                            onMainDispatcher {
                                                try {
                                                    val proxies = RawUpdater.parseRaw(text)
                                                    if (proxies.isNullOrEmpty()) onMainDispatcher {
                                                        snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                                    } else import(proxies)
                                                } catch (e: SubscriptionFoundException) {
                                                    importSubscription(
                                                        Uri.parse(e.link)
                                                    )
                                                } catch (e: Exception) {
                                                    Logs.w(e)

                                                    onMainDispatcher {
                                                        snackbar(e.readableMessage).show()
                                                    }
                                                }
                                            }
//                                lifecycleScope.launch {
//                                importSubscription(Uri.parse(object22.getString("config")))
//                               }

                                        }
                                    }
                                }


                                //  import(proxies)
                                println("onResponse222   $object22")
                                println("onResponse333  " + object22.getString("name"))
                            }
                            try {
                                val proxies = entities
                                if (proxies.isNullOrEmpty()) onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                                else {
                                    import(proxies, false)
                                }
                                println("oghab  ## select_proxy_not_connect   " + select_proxy_not_connect)
                                if (select_proxy_not_connect != 0L) {
                                    // adapter.notifyDataSetChanged()
                                    DataStore.selectedProxy = select_proxy_not_connect!!
                                    SagerNet.startService()
                                    main_act?.runOnUiThread {
                                        Handler().postDelayed({
                                            if (DataStore.serviceState.connected) main_act.binding.stats.testConnection()
                                        }, 3000)
                                    }
                                }

                            } catch (e: SubscriptionFoundException) {

                                main_act?.importSubscription(Uri.parse(e.link))
                            } catch (e: Exception) {
                                println("oghab  ## error   " + e)

                                Logs.w(e)
                                main_act?.runOnUiThread {
                                    Toast.makeText(
                                        main_act, e.readableMessage, Toast.LENGTH_SHORT
                                    ).show()
                                }
                                *//*   onMainDispatcher {
                                       snackbar(e.readableMessage).show()
                                   }*//*
                            }
                        }


                    } catch (e: Exception) {
                        println("onResponse Exception  $e")
                        //Toast.makeText(requireActivity(), "aaaaa", Toast.LENGTH_LONG).show()
                        main_act?.runOnUiThread {
                            if (autoChangeServer)
                                Toast.makeText(
                                    main_act, "The server encountered a problem", Toast.LENGTH_SHORT
                                ).show()
                        }
                    }


//                            for ((name, value) in response.headers) {
//                                println("$name: $value")
//                            }
//                            println("onResponse   "+response.body!!.string())

                }

            }
        })
    }*/

/*    fun isInternetAvailable1(context: Context): String {
        var result = "false"
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networkCapabilities = connectivityManager.activeNetwork ?: return "null"
            val actNw =
                connectivityManager.getNetworkCapabilities(networkCapabilities) ?: return "null"
            result = when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "false"
            }
        } else {
            connectivityManager.run {
                connectivityManager.activeNetworkInfo?.run {
                    result = when (type) {
                        ConnectivityManager.TYPE_WIFI -> "WIFI"
                        ConnectivityManager.TYPE_MOBILE -> "CELLULAR"
                        ConnectivityManager.TYPE_ETHERNET -> "ETHERNET"
                        else -> "null"
                    }

                }
            }
        }

        return result
    }*/


/*    private fun setSpecificationsHologateToServer() {
        // dialogLoading?.show()
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            )
            val version = pInfo.versionName
            //  val name = pInfo
            println("version #####  " + version)

        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        var token: String? = try {
            GetAutUser("token")

        } catch (e: Exception) {
            GetAutUser("token")
        }
        val entities = ArrayList<AbstractBean>()
        val payload = "test payload"

        //   val okHttpClient = OkHttpClient()
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient().build()

        val jsonObject = JSONObject()
        try {
            jsonObject.put("token_fb", ThemedActivity.GlobalStuff.token_fb)
            jsonObject.put("version", ThemedActivity.GlobalStuff.token_fb)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaType(), jsonObject.toString()
        )

        //  val requestBody = payload.toRequestBody()
        val request = Request.Builder().header("Authorization", token.toString())
            //.header("Authorization", "Bearer 5|HF0ERRIE1fgVXKes5AYC7WOUEK9y2ieDJeBIGwBD")
            .method("POST", requestBody).url(base_url + "/api/specifications").build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {

                println("onFailure : $e")

            }

            override fun onResponse(call: Call, response: Response) {
                // Handle this
                response.use {

                    println("Response : $response")


                }

            }
        })
    }*/

//    fun getInstallerPackageName(context: Context, packageName: String): String? {
//        kotlin.runCatching {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
//                return context.packageManager.getInstallSourceInfo(packageName).installingPackageName
//            @Suppress("DEPRECATION")
//            return context.packageManager.getInstallerPackageName(packageName)
//        }
//        return null
//    }

    
}
