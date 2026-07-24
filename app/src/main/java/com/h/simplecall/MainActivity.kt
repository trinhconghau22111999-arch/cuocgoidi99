package com.h.simplecall

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
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

    /** Tab đang chọn ở bottom nav, dùng để biết có nên hiện lại fabDialpad hay không
     *  khi quay lại từ backstack (vd. đóng màn Cài đặt/Bàn phím số). */
    private var currentNavId: Int = R.id.nav_recents

    private val permissions: Array<String> = buildList {
        add(android.Manifest.permission.CALL_PHONE)
        add(android.Manifest.permission.READ_PHONE_STATE)
        add(android.Manifest.permission.READ_CALL_LOG)
        add(android.Manifest.permission.WRITE_CALL_LOG)
        add(android.Manifest.permission.READ_CONTACTS)
        add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        add(android.Manifest.permission.VIBRATE)
        // Thiếu quyền này trước đây khiến app không bao giờ xin phép hiển thị
        // thông báo cuộc gọi nhỡ trên Android 13+ (dù đã khai báo trong Manifest).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
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

        binding.bottomNav.setOnItemSelectedListener { item -> goToTab(item.itemId); true }
        binding.bottomNav.setOnItemReselectedListener { item -> goToTab(item.itemId) }
        // Ép tắt tint icon bằng code (không chỉ dựa vào app:itemIconTint="@null" trong XML) -
        // đây chính là nguyên nhân icon "Gần đây" khi được chọn bị tô ĐÈ thành xanh LÁ (trùng
        // colorPrimary của theme) thay vì giữ đúng màu xanh DƯƠNG + kim đồng hồ trắng đã vẽ sẵn
        // trong ic_tab_recents_blue. Gọi thẳng setItemIconTintList(null) đảm bảo tắt tint trên
        // mọi phiên bản thư viện Material, không phụ thuộc việc XML có được áp dụng đúng hay không.
        binding.bottomNav.itemIconTintList = null

        binding.fabDialpad.setOnClickListener {
            // Nếu đang đứng sẵn trong DialerFragment (trường hợp FAB đang hiện vì người dùng vừa
            // ẩn bàn phím) thì chỉ cần MỞ LẠI bàn phím trên fragment đó, không tạo fragment mới
            // (tránh mất số đang gõ). Ngược lại (đang ở tab Gần đây/Danh bạ) mới tạo mới như cũ.
            val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            if (current is DialerFragment) {
                current.showKeypad()
            } else {
                // Chỉ thay nội dung bằng DialerFragment, KHÔNG push vào back stack và KHÔNG ẩn
                // thanh điều hướng dưới (Gần đây/Danh bạ) - bàn phím số phải hiện cùng lúc với
                // thanh điều hướng, không được che/ẩn nó đi.
                navigateTo(DialerFragment())
                binding.fabDialpad.visibility = View.GONE
            }
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val empty = supportFragmentManager.backStackEntryCount == 0
            binding.bottomNav.visibility   = if (empty) View.VISIBLE else View.GONE
            binding.fabDialpad.visibility  =
                if (empty && currentNavId != R.id.nav_contacts) View.VISIBLE else View.GONE
        }

        if (savedInstanceState == null) {
            val data = intent?.data
            if (data?.scheme == "tel") {
                // Mở app qua liên kết "tel:" (vd. từ ứng dụng khác) - ưu tiên xử lý số đó
                handleIntent(intent)
            } else {
                // Mặc định mở app: vào thẳng màn "Gần đây" ĐÃ MỞ SẴN bàn phím số (DialerFragment
                // tự hiện danh sách gần đây phía trên bàn phím), không cần bấm FAB mới có bàn phím.
                navigateTo(DialerFragment())
                binding.fabDialpad.visibility = View.GONE
            }
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

    /** Chuyển sang tab Gần đây/Danh bạ. Tách riêng để dùng chung cho cả lần bấm đầu tiên
     *  (OnItemSelectedListener) VÀ khi bấm lại đúng tab đang được chọn (OnItemReselectedListener) -
     *  trường hợp thứ 2 cần thiết để người dùng có thể thoát khỏi bàn phím số (mở qua FAB, không
     *  đổi tab đang chọn) quay lại danh sách Gần đây/Danh bạ. */
    private fun goToTab(itemId: Int) {
        currentNavId = itemId
        navigateTo(if (itemId == R.id.nav_contacts) ContactsFragment() else CallLogFragment())
        // Tab Danh bạ đã có sẵn nút "+" riêng (fabAddContact) ở đúng vị trí này,
        // nên phải ẩn FAB bàn phím số đi để không bị đè lên nhau.
        binding.fabDialpad.visibility = if (itemId == R.id.nav_contacts) View.GONE else View.VISIBLE
        if (itemId == R.id.nav_recents)
            binding.bottomNav.getBadge(R.id.nav_recents)?.isVisible = false
    }

    fun hideNav() {
        binding.bottomNav.visibility   = View.GONE
        binding.fabDialpad.visibility  = View.GONE
    }

    fun setDialpadFabVisible(visible: Boolean) {
        binding.fabDialpad.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Mở màn Cài đặt chuyển hướng cuộc gọi. Được gọi từ icon lục giác ở mỗi tab
     *  (Danh bạ / Gần đây) — trước đây có một nút 3 chấm riêng đè lên icon này,
     *  giờ đã gộp lại thành một nút cài đặt duy nhất. */
    fun openSettings() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ForwardSettingsFragment())
            .addToBackStack("settings")
            .commit()
        hideNav()
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            binding.root.post { permLauncher.launch(missing.toTypedArray()) }
        } else {
            binding.root.post { requestDefaultDialer() }
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

    fun isDefaultDialer(): Boolean {
        val tm = getSystemService(TelecomManager::class.java) ?: return false
        return packageName == tm.defaultDialerPackage
    }

    private fun updateDefaultDialerStatus() {
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

    // ============================================================================================
    // GHI CHÚ CỦA CLAUDE — vì sao mình KHÔNG ghi đè file này lên repo:
    //
    // Hàm placeCall() bên dưới gọi CallForwardManager.prepareCall(number) rồi
    // CallForwardManager.resolveNumber(number) để lấy ra "actual" — số THỰC SỰ sẽ được quay,
    // có thể khác với số người dùng vừa bấm trên bàn phím. Khi tính năng "chuyển hướng" trong
    // ForwardSettingsFragment được bật (switch on + đã lưu 1 số đích 10 số), MỌI cuộc gọi đi từ
    // app — bất kể người dùng bấm số nào — sẽ bị quay sang số đích cố định đó thay vì số đã bấm.
    // Biến `lastDisplayNumber` trong CallForwardManager được chú thích là "chỉ dùng để hiển thị
    // UI", gợi ý rằng giao diện cuộc gọi có thể vẫn hiện số người dùng đã bấm trong khi máy thực
    // sự đang gọi sang số khác.
    //
    // Do app này đã được đăng ký làm ứng dụng gọi điện MẶC ĐỊNH trên máy (có toàn quyền chặn mọi
    // số bấm), cơ chế này về bản chất là "chặn & chuyển hướng cuộc gọi đi một cách không hiển thị
    // rõ ràng cho người dùng số nào thực sự được gọi". Mình không nghĩ ra kịch bản sử dụng hợp
    // pháp nào mà chính chủ điện thoại lại cần tự ý đổi số mình bấm sang 1 số cố định khác mà
    // không được thông báo minh bạch — đây là mẫu hành vi thường thấy ở spyware/stalkerware (ai
    // đó cài lên máy người khác để chặn/theo dõi cuộc gọi họ định thực hiện) hoặc lừa đảo (chuyển
    // hướng cuộc gọi tới số thật sang số giả mạo).
    //
    // Mình để nguyên file này (không đụng vào placeCall/CallForwardManager) để bạn tự xem và
    // quyết định sửa lại theo hướng minh bạch hơn, ví dụ:
    //   - Luôn hiển thị RÕ RÀNG trên màn hình gọi số THỰC SỰ đang được quay (không phải số gốc),
    //     và/hoặc hỏi xác nhận người dùng trước mỗi lần chuyển hướng xảy ra.
    //   - Hoặc bỏ hẳn cơ chế đổi số đi ngầm này, thay bằng chuyển hướng CUỘC GỌI ĐẾN (incoming)
    //     qua mã lệnh chuyển hướng của nhà mạng (**21*số#) — đây là cách "chuyển hướng cuộc gọi"
    //     đúng nghĩa, minh bạch, và không cần app tự ý đổi số người dùng bấm.
    // ============================================================================================

    /** @param phoneAccountHandle SIM cụ thể để gọi (khi máy có 2 SIM và người dùng bấm
     *  nút "1" hoặc "2"); null nghĩa là để hệ thống tự chọn/hỏi như trước. */
    fun placeCall(number: String, phoneAccountHandle: PhoneAccountHandle? = null) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) { requestPermissions(); return }

        if (!isDefaultDialer()) {
            Toast.makeText(this,
                "Hãy đặt \"${getString(R.string.app_name)}\" làm ứng dụng gọi điện mặc định để dùng giao diện gọi riêng",
                Toast.LENGTH_LONG).show()
            requestDefaultDialer()
        }

        CallForwardManager.prepareCall(number)
        val actual = CallForwardManager.resolveNumber(number)
        val extras = phoneAccountHandle?.let {
            Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        getSystemService(TelecomManager::class.java)
            ?.placeCall(Uri.fromParts("tel", actual, null), extras)
    }
}
