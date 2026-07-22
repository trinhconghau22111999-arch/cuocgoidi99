package com.h.simplecall

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
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
        ActivityResultContracts.RequestMultiplePermissions()) { }
    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallForwardManager.init(this)
        BlockedNumbersManager.init(this)
        MissedCallNotifier.init(this)

        requestPermissions()
        requestDefaultDialer()

        binding.btnSettings.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ForwardSettingsFragment())
                .addToBackStack("settings")
                .commit()
            hideNav()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            navigateTo(when (item.itemId) {
                R.id.nav_log      -> CallLogFragment()
                R.id.nav_contacts -> ContactsFragment()
                else              -> DialerFragment()
            })
            if (item.itemId == R.id.nav_log)
                binding.bottomNav.getBadge(R.id.nav_log)?.isVisible = false
            true
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val empty = supportFragmentManager.backStackEntryCount == 0
            binding.bottomNav.visibility   = if (empty) View.VISIBLE else View.GONE
            binding.btnSettings.visibility = if (empty) View.VISIBLE else View.GONE
        }

        if (savedInstanceState == null) {
            navigateTo(DialerFragment())
            handleIntent(intent)
        }
        updateMissedBadge()
    }

    override fun onResume() { super.onResume(); updateMissedBadge() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel")
            navigateTo(DialerFragment.newInstanceWithNumber(data.schemeSpecificPart))
    }

    fun navigateTo(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f).commit()
    }

    fun hideNav() {
        binding.bottomNav.visibility   = View.GONE
        binding.btnSettings.visibility = View.GONE
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java) ?: return
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER))
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        } else {
            val tm = getSystemService(TelecomManager::class.java) ?: return
            if (packageName != tm.defaultDialerPackage)
                roleLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                })
        }
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
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_log)
        if (count > 0) { badge.isVisible = true; badge.number = count }
        else badge.isVisible = false
    }

    fun placeCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) { requestPermissions(); return }
        CallForwardManager.prepareCall(number)
        val actual = CallForwardManager.resolveNumber(number)
        getSystemService(TelecomManager::class.java)
            ?.placeCall(Uri.fromParts("tel", actual, null), null)
    }
}
