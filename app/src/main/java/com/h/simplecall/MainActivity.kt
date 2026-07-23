package com.h.simplecall

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.h.simplecall.call.BlockedNumbersManager
import com.h.simplecall.call.CallForwardManager
import com.h.simplecall.call.MissedCallNotifier
import com.h.simplecall.databinding.ActivityMainBinding
import com.h.simplecall.ui.CallLogFragment
import com.h.simplecall.ui.ContactsFragment
import com.h.simplecall.ui.DialerFragment
import com.h.simplecall.ui.ForwardSettingsFragment

/** Fragment nào cần biết khi trạng thái "ứng dụng gọi mặc định" thay đổi thì implement cái này. */
interface DefaultDialerStatusListener {
    fun onDefaultDialerStatusChanged()
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissions = arrayOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.WRITE_CALL_LOG,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.ANSWER_PHONE_CALLS,
        android.Manifest.permission.VIBRATE
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        // Chỉ xin làm ứng dụng gọi mặc định SAU KHI hộp thoại quyền đã đóng lại,
        // chứ không chạy song song — nếu không, trên nhiều máy hộp thoại "đặt mặc định"
        // sẽ hiện lên (hoặc bị hệ thống tự huỷ) trước khi quyền kịp cấp, khiến nó luôn thất bại.
        requestDefaultDialer()
    }
    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { updateDefaultDialerStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallForwardManager.init(this)
        BlockedNumbersManager.init(this)
        MissedCallNotifier.init(this)

        requestPermissions()

        binding.btnSettings.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ForwardSettingsFragment())
                .addToBackStack("settings")
                .commit()
            hideNav()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            navigateTo(when (item.itemId) {
                R.id.nav_contacts -> ContactsFragment()
                else              -> CallLogFragment()
            })
            if (item.itemId == R.id.nav_recents)
                binding.bottomNav.getBadge(R.id.nav_recents)?.isVisible = false
            true
        }

        binding.fabDialpad.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, DialerFragment())
                .addToBackStack("dialpad")
                .commit()
            hideNav()
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val empty = supportFragmentManager.backStackEntryCount == 0
            binding.bottomNav.visibility   = if (empty) View.VISIBLE else View.GONE
            binding.btnSettings.visibility = if (empty) View.VISIBLE else View.GONE
            binding.fabDialpad.visibility  = if (empty) View.VISIBLE else View.GONE
        }

        if (savedInstanceState == null) {
            navigateTo(CallLogFragment())
            handleIntent(intent)
        }
        updateMissedBadge()
    }

    override fun onResume() { super.onResume(); updateMissedBadge() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel") {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, DialerFragment.newInstanceWithNumber(data.schemeSpecificPart))
                .addToBackStack("dialpad")
                .commit()
            hideNav()
        }
    }

    fun navigateTo(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f).commit()
    }

    fun hideNav() {
        binding.bottomNav.visibility   = View.GONE
        binding.btnSettings.visibility = View.GONE
        binding.fabDialpad.visibility  = View.GONE
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        } else {
            // Đã có đủ quyền từ trước (lần mở sau) -> permLauncher sẽ KHÔNG được gọi,
            // nên phải tự kích hoạt bước xin làm mặc định ở đây, nếu không nó sẽ không bao giờ chạy.
            requestDefaultDialer()
        }
    }

    /** Có thể gọi lại thủ công (vd. từ màn Cài đặt) nếu người dùng lỡ từ chối lần đầu. */
    fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm == null) {
                Toast.makeText(this, "Thiết bị không hỗ trợ đặt ứng dụng gọi mặc định", Toast.LENGTH_LONG).show()
                return
            }
            if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                Toast.makeText(this, "Thiết bị/ROM này không hỗ trợ vai trò ứng dụng gọi mặc định", Toast.LENGTH_LONG).show()
                return
            }
            if (!rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                } catch (_: Exception) {
                    // Một số ROM (MIUI/EMUI/OneUI cũ...) chặn hoặc lỗi khi mở hộp thoại này
                    // trực tiếp -> đưa người dùng sang màn hình "Ứng dụng mặc định" của hệ thống
                    // để họ tự chọn thủ công, thay vì im lặng thất bại.
                    openManualDefaultAppsSettings()
                }
            }
        } else {
            val tm = getSystemService(TelecomManager::class.java) ?: return
            if (packageName != tm.defaultDialerPackage) {
                try {
                    roleLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                    })
                } catch (_: Exception) {
                    openManualDefaultAppsSettings()
                }
            }
        }
        updateDefaultDialerStatus()
    }

    /** Mở màn hình cài đặt "Ứng dụng mặc định" của hệ thống làm phương án dự phòng. */
    private fun openManualDefaultAppsSettings() {
        val opened = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                true
            } else false
        } catch (_: Exception) { false }
        if (!opened) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            } catch (_: Exception) {
                Toast.makeText(this,
                    "Không thể mở cài đặt tự động. Vào Cài đặt > Ứng dụng > Ứng dụng mặc định để đặt thủ công.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** true nếu app này hiện đang là ứng dụng gọi điện mặc định. */
    fun isDefaultDialer(): Boolean {
        val tm = getSystemService(TelecomManager::class.java) ?: return false
        return packageName == tm.defaultDialerPackage
    }

    private fun updateDefaultDialerStatus() {
        // Hook để các fragment (vd. màn Cài đặt) có thể refresh trạng thái hiển thị của chúng.
        supportFragmentManager.fragments.forEach { (it as? DefaultDialerStatusListener)?.onDefaultDialerStatusChanged() }
    }

    private fun updateMissedBadge() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return
        val cur = contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.TYPE),
            "${android.provider.CallLog.Calls.NEW} = 1 AND " +
                "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.MISSED_TYPE}",
            null, null)
        val count = cur?.count ?: 0; cur?.close()
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_recents)
        if (count > 0) { badge.isVisible = true; badge.number = count }
        else badge.isVisible = false
    }

    fun placeCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) { requestPermissions(); return }

        // CHỈ app đang giữ vai trò "ứng dụng gọi điện mặc định" mới nhận được onCallAdded
        // và được phép hiển thị màn hình "Đang gọi..." tuỳ chỉnh của chính nó. Nếu chưa được
        // đặt làm mặc định, cuộc gọi vẫn kết nối bình thường nhưng ứng dụng gọi mặc định của
        // máy (không phải app này) sẽ hiển thị màn hình gọi — đây là lý do phổ biến nhất khiến
        // giao diện "Đang gọi" tuỳ chỉnh không hiện ra. Nhắc người dùng + mở lại hộp thoại đặt mặc định.
        if (!isDefaultDialer()) {
            Toast.makeText(this,
                "Hãy đặt \"${getString(R.string.app_name)}\" làm ứng dụng gọi điện mặc định để dùng giao diện gọi riêng",
                Toast.LENGTH_LONG).show()
            requestDefaultDialer()
        }

        CallForwardManager.prepareCall(number)
        val actual = CallForwardManager.resolveNumber(number)
        getSystemService(TelecomManager::class.java)
            ?.placeCall(Uri.fromParts("tel", actual, null), null)
    }
}
