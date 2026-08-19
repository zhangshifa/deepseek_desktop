package com.example.deepseekvoice;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Set;

/**
 * 蓝牙耳机音频路由：
 *  - 输出（播报）：TTS 用 USAGE_MEDIA 音频属性，系统自动路由到已连接的 A2DP 蓝牙耳机（见 VoiceBridge）。
 *  - 输入（语音识别）：识别前启用 SCO（HFP 电话通道），把麦克风切到蓝牙耳机；
 *    API 31+ 用 AudioManager.setCommunicationDevice，旧版本用 startBluetoothSco + setBluetoothScoOn。
 */
public class BluetoothAudio {

    private static final String TAG = "DeepSeekVoiceBT";

    private final Context context;
    private final AudioManager audioManager;

    public BluetoothAudio(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 蓝牙耳机（A2DP 或 HFP/SCO）是否已连接。 */
    public boolean isHeadsetConnected() {
        try {
            // API 23+：直接从 AudioManager 的设备列表判断，最可靠
            if (Build.VERSION.SDK_INT >= 23) {
                AudioDeviceInfo[] outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : outs) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        return true;
                    }
                }
                AudioDeviceInfo[] ins = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
                for (AudioDeviceInfo d : ins) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        return true;
                    }
                }
                return false;
            }
            // 老 API 兜底：bonded + profile 连接状态（跳过 BLE 手环/手表）
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !hasBtPermission()) return false;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return false;
            for (BluetoothDevice d : bonded) {
                if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) continue;
                if (adapter.getProfileConnectionState(BluetoothProfile.A2DP)
                        == BluetoothProfile.STATE_CONNECTED) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "isHeadsetConnected failed", e);
            return false;
        }
    }

    /** 启用 SCO：语音识别输入走蓝牙耳机麦克风。 */
    public void enableSco() {
        try {
            if (!hasBtPermission()) return;
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo sco = pickScoDevice();
                if (sco != null) {
                    audioManager.setCommunicationDevice(sco);
                    Log.d(TAG, "SCO on via setCommunicationDevice");
                }
            } else {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
                Log.d(TAG, "SCO on via startBluetoothSco");
            }
        } catch (Exception e) {
            Log.w(TAG, "enableSco failed", e);
        }
    }

    /** 关闭 SCO：恢复默认麦克风路由。 */
    public void disableSco() {
        try {
            if (!hasBtPermission()) return;
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice();
            } else {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            }
        } catch (Exception e) {
            Log.w(TAG, "disableSco failed", e);
        }
    }

    private AudioDeviceInfo pickScoDevice() {
        AudioDeviceInfo[] devs = audioManager.getAvailableCommunicationDevices();
        if (devs == null) return null;
        for (AudioDeviceInfo d : devs) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return d;
            }
        }
        return null;
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
