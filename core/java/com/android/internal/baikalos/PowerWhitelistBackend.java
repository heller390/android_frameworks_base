/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.baikalos;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IDeviceIdleController;
import android.os.DeviceIdleManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telecom.DefaultDialerManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.ArrayUtils;

/**
 * Handles getting/changing the whitelist for the exceptions to battery saving features.
 */
public class PowerWhitelistBackend {

    private static final String TAG = "PowerWhitelistBackend";

    private static final String DEVICE_IDLE_SERVICE = "deviceidle";

    private static PowerWhitelistBackend sInstance = null;

    private final Context mAppContext;
    private final IDeviceIdleController mDeviceIdleService;
    private final ArraySet<String> mWhitelistedApps = new ArraySet<>();
    private final ArraySet<String> mSysWhitelistedApps = new ArraySet<>();
    private final ArraySet<String> mSysWhitelistedAppsExceptIdle = new ArraySet<>();
    private final ArraySet<String> mDefaultActiveApps = new ArraySet<>();

    public PowerWhitelistBackend(Context context) {
        this(context, IDeviceIdleController.Stub.asInterface(ServiceManager.getService(DEVICE_IDLE_SERVICE)));
    }

    PowerWhitelistBackend(Context context, IDeviceIdleController deviceIdleService) {
        mAppContext = context; 
        mDeviceIdleService = deviceIdleService;
        if (mDeviceIdleService == null) {
            Log.w(TAG, "mDeviceIdleService = null!!!!!!!!!! (ctor)", new Throwable());
            return;
        }
    }

    public int getWhitelistSize() {
        return mWhitelistedApps.size();
    }

    public boolean isSysWhitelisted(String pkg) {
        return mSysWhitelistedApps.contains(pkg) || mSysWhitelistedAppsExceptIdle.contains(pkg);
    }

    public boolean isWhitelisted(String pkg) {
        if (mWhitelistedApps.contains(pkg)) {
            return true;
        }

        return false;
    }

    public boolean isWhitelisted(String[] pkgs) {
        if (ArrayUtils.isEmpty(pkgs)) {
            return false;
        }
        for (String pkg : pkgs) {
            if (isWhitelisted(pkg)) {
                return true;
            }
        }

        return false;
    }

    public boolean isSysWhitelistedExceptIdle(String pkg) {
        return mSysWhitelistedAppsExceptIdle.contains(pkg);
    }

    public boolean isSysWhitelistedExceptIdle(String[] pkgs) {
        if (ArrayUtils.isEmpty(pkgs)) {
            return false;
        }
        for (String pkg : pkgs) {
            if (isSysWhitelistedExceptIdle(pkg)) {
                return true;
            }
        }

        return false;
    }

    public void addApp(String pkg) {
        try {
            mDeviceIdleService.addPowerSaveWhitelistApp(pkg);
            mWhitelistedApps.add(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    public void removeApp(String pkg) {
        try {
            mDeviceIdleService.removePowerSaveWhitelistApp(pkg);
            mWhitelistedApps.remove(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        } catch (Exception ex) {
            Log.w(TAG, "Unable to removeApp:" + pkg, ex);
        }
    }

    public ArraySet<String> getWhitelistedApps() {
        return mWhitelistedApps;
    }

    public ArraySet<String> getSystemWhitelistedApps() {
        return mSysWhitelistedApps;
    }

    public ArraySet<String> getSystemWhitelistedAppsExceptIdle() {
        return mSysWhitelistedAppsExceptIdle;
    }


    public void refreshList() {
        refreshList(true);
    }

    public void refreshList(boolean update) {
        mSysWhitelistedApps.clear();
        mSysWhitelistedAppsExceptIdle.clear();
        mWhitelistedApps.clear();
        Log.w(TAG, "refreshList: update=" + update);
        if (mDeviceIdleService == null) {
            Log.w(TAG, "mDeviceIdleService = null!!!!!!!!!!", new Throwable());
            return;
        }
        try {
            final String[] whitelistedApps = mDeviceIdleService.getUserPowerWhitelist();
            for (String app : whitelistedApps) {
                mWhitelistedApps.add(app);
            }
            final String[] sysWhitelistedApps = mDeviceIdleService.getSystemPowerWhitelist();
            for (String app : sysWhitelistedApps) {
                mSysWhitelistedApps.add(app);
            }
            final String[] sysWhitelistedAppsExceptIdle =
                    mDeviceIdleService.getSystemPowerWhitelistExceptIdle();
            for (String app : sysWhitelistedAppsExceptIdle) {
                mSysWhitelistedAppsExceptIdle.add(app);
            }

            if( update ) {
                mDefaultActiveApps.clear();

                final boolean hasTelephony = mAppContext.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELEPHONY);
                final ComponentName defaultSms = SmsApplication.getDefaultSmsApplication(mAppContext,
                        true /* updateIfNeeded */);
                final String defaultDialer = DefaultDialerManager.getDefaultDialerApplication(
                        mAppContext);

                final String defaultCallScreening = DefaultDialerManager.getDefaultCallScreeningApplication(mAppContext);

                if (hasTelephony) {
                    if (defaultSms != null) {
                        mDefaultActiveApps.add(defaultSms.getPackageName());
                        SystemProperties.set("baikal.sms", defaultSms.getPackageName());
                    } else {
                        SystemProperties.set("baikal.sms", "");
                    }
                    if (!TextUtils.isEmpty(defaultDialer)) {
                        mDefaultActiveApps.add(defaultDialer);
                        SystemProperties.set("baikal.dialer", defaultDialer);
                    } else {
                        SystemProperties.set("baikal.dialer", "");
                    }
                    if (!TextUtils.isEmpty(defaultCallScreening)) {
                        mDefaultActiveApps.add(defaultCallScreening);
                        SystemProperties.set("baikal.call_screening", defaultCallScreening);
                    } else {
                        SystemProperties.set("baikal.call_screening", "");
                    }
                }
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", re);
        } catch (Exception e) {
            Log.w(TAG, "Exception:", e);
        }
    }

    public static PowerWhitelistBackend getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PowerWhitelistBackend(context);
        }
        return sInstance;
    }
}
