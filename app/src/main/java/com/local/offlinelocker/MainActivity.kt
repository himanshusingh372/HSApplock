package com.local.offlinelocker

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var storage: LockStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = LockStorage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val accessBtn = Button(this).apply {
            text = "1. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val authSpinner = Spinner(this).apply {
            val modes = LockStorage.AuthType.entries.map { it.name }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(storage.getAuthType().ordinal)
        }

        val secretInput = EditText(this).apply {
            hint = "Set PIN / Password / Pattern (e.g. 12589)"
        }

        val saveSecretBtn = Button(this).apply {
            text = "2. Save Credentials"
            setOnClickListener {
                val secret = secretInput.text.toString()
                if (secret.length >= 4) {
                    val type = LockStorage.AuthType.entries[authSpinner.selectedItemPosition]
                    storage.setCredentials(type, secret)
                    secretInput.text.clear()
                    Toast.makeText(this@MainActivity, "Saved ${type.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Minimum 4 characters/digits", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val bioSwitch = MaterialSwitch(this).apply {
            text = "Enable Fingerprint Unlock"
            isChecked = storage.isFingerprintEnabled
            setOnCheckedChangeListener { _, checked -> storage.isFingerprintEnabled = checked }
        }

        val fakeCrashSwitch = MaterialSwitch(this).apply {
            text = "Fake App Crash (Long-press 'App info' to unlock)"
            isChecked = storage.isFakeCrashEnabled
            setOnCheckedChangeListener { _, checked -> storage.isFakeCrashEnabled = checked }
        }

        val appsHeader = TextView(this).apply {
            text = "\nSelect Apps to Lock:"
            textSize = 18f
        }

        val listView = ListView(this)
        val installedApps = packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

        val appNames = installedApps.map { packageManager.getApplicationLabel(it).toString() }
        val lockedSet = storage.getLockedApps().toMutableSet()

        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, appNames)

        installedApps.forEachIndexed { index, appInfo ->
            if (lockedSet.contains(appInfo.packageName)) {
                listView.setItemChecked(index, true)
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = installedApps[position].packageName
            if (listView.isItemChecked(position)) lockedSet.add(pkg) else lockedSet.remove(pkg)
            storage.setLockedApps(lockedSet)
        }

        root.addView(accessBtn)
        root.addView(authSpinner)
        root.addView(secretInput)
        root.addView(saveSecretBtn)
        root.addView(bioSwitch)
        root.addView(fakeCrashSwitch)
        root.addView(appsHeader)
        root.addView(listView)
        setContentView(root)
    }
}
