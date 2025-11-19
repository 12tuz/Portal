package john.fucklocation.portal.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import john.fucklocation.portal.R
import john.fucklocation.portal.databinding.FragmentSettingsBinding
import john.fucklocation.portal.ext.accuracy
import john.fucklocation.portal.ext.altitude
import john.fucklocation.portal.ext.autoDetectTransportMode
import john.fucklocation.portal.ext.debug
import john.fucklocation.portal.ext.disableFusedProvider
import john.fucklocation.portal.ext.disableGetCurrentLocation
import john.fucklocation.portal.ext.disableRegisterLocationListener
import john.fucklocation.portal.ext.disableWifiScan
import john.fucklocation.portal.ext.hookSensor
import john.fucklocation.portal.ext.loopBroadcastlocation
import john.fucklocation.portal.ext.minSatelliteCount
import john.fucklocation.portal.ext.needDowngradeToCdma
import john.fucklocation.portal.ext.needOpenSELinux
import john.fucklocation.portal.ext.reportDuration
import john.fucklocation.portal.ext.speed
import john.fucklocation.portal.ext.stepFrequencyMultiplier
import john.fucklocation.portal.ext.transportMode
import john.fucklocation.portal.ext.enableSensorSimulation
import john.fucklocation.portal.ext.disableGetFromLocation
import john.fucklocation.portal.ext.enableRequestGeofence
import john.fucklocation.portal.service.MockServiceHelper
import john.fucklocation.portal.ui.viewmodel.MockServiceViewModel
import john.fucklocation.portal.ui.viewmodel.SettingsViewModel
import kotlin.getValue

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val settingsViewModel =
            ViewModelProvider(this)[SettingsViewModel::class.java]

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
        binding.selinuxSwitch.isChecked = context.needOpenSELinux
        binding.selinuxSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.needOpenSELinux = isChecked
                showToast(if (isChecked) "已开启SELinux" else "已关闭SELinux")
            }
        })

        binding.altitudeValue.text = "%.2f米".format(context.altitude)
        binding.speedValue.text = "%.2f米/秒".format(context.speed)
        binding.accuracyValue.text = "%.2f米".format(context.accuracy)
        binding.reportDurationValue.text = "%dms".format(context.reportDuration)
        binding.satelliteCountValue.text = "%d颗".format(context.minSatelliteCount)

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                binding.altitudeValue.text = "%.2f米".format(value)
            }
        }

        binding.speedLayout.setOnClickListener {
            showDialog("设置速度", binding.speedValue.text.toString().let { it.substring(0, it.length - 3) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (value > 1000) {
                    showToast("速度不能超过1000米/秒")
                    return@showDialog
                }
                context.speed = value
                binding.speedValue.text = "%.2f米/秒".format(value)
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", binding.accuracyValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toFloatOrNull()
                if (value == null || value < 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                binding.accuracyValue.text = "%.2f米".format(value)
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.debug = isChecked
                showToast(if (isChecked) "已开启调试模式" else "已关闭调试模式")
                updateRemoteConfig()
            }
        })

        binding.dgcSwitch.isChecked = !context.disableGetCurrentLocation
        binding.dgcSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.disableGetCurrentLocation = !isChecked
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.rllSwitch.isChecked = !context.disableRegisterLocationListener
        binding.rllSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.disableRegisterLocationListener = !isChecked
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.dfusedSwitch.isChecked = context.disableFusedProvider
        binding.dfusedSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.disableFusedProvider = isChecked
                showToast(if (isChecked) "已禁用FusedProvider" else "已启用FusedProvider")
                updateRemoteConfig()
            }
        })

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.needDowngradeToCdma = isChecked
                showToast(if (isChecked) "已降级为CDMA" else "已取消降级为CDMA")
                updateRemoteConfig()
            }
        })

        binding.sensorHookSwitch.isChecked = context.hookSensor
        binding.sensorHookSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.hookSensor = isChecked
                showToast("重新启动生效")
                updateRemoteConfig()
            }
        })

        binding.reportDurationLayout.setOnClickListener {
            showDialog("设置上报间隔", binding.reportDurationValue.text.toString().let {
                it.substring(0, it.length - 2)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "上报间隔不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "上报间隔不能大于1s", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                binding.reportDurationValue.text = "%dms".format(value)
                showToast("重新启动APP生效")
            }
        }

        binding.satelliteCountLayout.setOnClickListener {
            showDialog("设置最小模拟卫星数量", binding.satelliteCountValue.text.toString().let {
                it.substring(0, it.length - 1)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "数量不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 35) {
                    Toast.makeText(context, "卫星数量不能超过35", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.minSatelliteCount = value
                binding.satelliteCountValue.text = "%d颗".format(value)
                showToast("重新启动模拟生效")
                updateRemoteConfig()
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
            with(mockServiceViewModel) {
                if (isChecked) {
                    if(!MockServiceHelper.startWifiMock(locationManager!!)) {
                        showToast("禁用WLAN扫描失败: 无法连接到系统服务")
                    }
                } else {
                    if(!MockServiceHelper.stopWifiMock(locationManager!!)) {
                        showToast("启用WLAN扫描失败: 无法连接到系统服务")
                    }
                }
            }
        }

        binding.loopBroadcastLocationSwitch.isChecked = requireContext().loopBroadcastlocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().loopBroadcastlocation = isChecked
            showToast("重启模拟生效")
        }

        // 交通模式选择
        val transportModes = arrayOf("静止", "步行", "跑步", "骑行", "开车", "高速")
        binding.transportModeValue.text = transportModes[context.transportMode]
        binding.transportModeLayout.setOnClickListener {
            val currentMode = context.transportMode
            MaterialAlertDialogBuilder(context)
                .setTitle("选择交通模式")
                .setSingleChoiceItems(transportModes, currentMode) { dialog, which ->
                    context.transportMode = which
                    binding.transportModeValue.text = transportModes[which]
                    with(mockServiceViewModel) {
                        if(locationManager != null && MockServiceHelper.setTransportMode(locationManager!!, which)) {
                            showToast("交通模式已更新为: ${transportModes[which]}")
                        } else {
                            showToast("交通模式更新失败")
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 自动检测交通模式开关
        binding.autoDetectModeSwitch.isChecked = context.autoDetectTransportMode
        binding.autoDetectModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.autoDetectTransportMode = isChecked
            binding.transportModeLayout.isEnabled = !isChecked
            binding.transportModeLayout.alpha = if (isChecked) 0.5f else 1.0f
            with(mockServiceViewModel) {
                if(locationManager != null && MockServiceHelper.setAutoDetectTransportMode(locationManager!!, isChecked)) {
                    showToast(if (isChecked) "已开启自动检测" else "已关闭自动检测")
                } else {
                    showToast("设置失败")
                }
            }
        }

        // 步频倍率设置
        binding.stepFrequencyMultiplierValue.text = "%.1fx".format(context.stepFrequencyMultiplier)
        binding.stepFrequencyMultiplierLayout.setOnClickListener {
            showDialog("设置步频倍率", context.stepFrequencyMultiplier.toString()) {
                val value = it.toFloatOrNull()
                if (value == null || value < 0.5f) {
                    showToast("步频倍率不能小于0.5")
                    return@showDialog
                } else if (value > 2.0f) {
                    showToast("步频倍率不能超过2.0")
                    return@showDialog
                }
                context.stepFrequencyMultiplier = value
                binding.stepFrequencyMultiplierValue.text = "%.1fx".format(value)
                with(mockServiceViewModel) {
                    if(locationManager != null && MockServiceHelper.setStepFrequencyMultiplier(locationManager!!, value.toDouble())) {
                        showToast("步频倍率已更新")
                    } else {
                        showToast("步频倍率更新失败")
                    }
                }
            }
        }

        // 初始化时根据自动检测状态禁用手动选择
        binding.transportModeLayout.isEnabled = !context.autoDetectTransportMode
        binding.transportModeLayout.alpha = if (context.autoDetectTransportMode) 0.5f else 1.0f

        // 传感器/步频模拟开关
        binding.sensorSimulationSwitch.isChecked = context.enableSensorSimulation
        binding.sensorSimulationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.enableSensorSimulation = isChecked
            with(mockServiceViewModel) {
                if(locationManager != null && MockServiceHelper.setSensorSimulation(locationManager!!, isChecked)) {
                    showToast(if (isChecked) "已开启步频模拟" else "已关闭步频模拟")
                } else {
                    showToast("设置失败")
                }
            }
        }

        // Geocoder禁用开关
        binding.disableGeocoderSwitch.isChecked = context.disableGetFromLocation
        binding.disableGeocoderSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.disableGetFromLocation = isChecked
            with(mockServiceViewModel) {
                if(locationManager != null && MockServiceHelper.setDisableGetFromLocation(locationManager!!, isChecked)) {
                    showToast(if (isChecked) "已禁用反向地理编码" else "已启用反向地理编码")
                } else {
                    showToast("设置失败")
                }
            }
        }

        // Geofencing开关
        binding.enableGeofenceSwitch.isChecked = context.enableRequestGeofence
        binding.enableGeofenceSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.enableRequestGeofence = isChecked
            with(mockServiceViewModel) {
                if(locationManager != null && MockServiceHelper.setEnableRequestGeofence(locationManager!!, isChecked)) {
                    showToast(if (isChecked) "已启用地理栅栏伪造" else "已禁用地理栅栏")
                } else {
                    showToast("设置失败")
                }
            }
        }

        return root
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRemoteConfig() {
        val context = requireContext()
        with(mockServiceViewModel) {
            if(!MockServiceHelper.putConfig(locationManager!!, context)) {
                showToast("更新远程配置失败")
            } else {
                showToast("同步配置成功")
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    private fun showDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_input, null)

        val title = dialogView.findViewById<TextView>(R.id.title)
        title.text = titleText

        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}