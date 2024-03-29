/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.omni;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Calendar;

public class StatusBarHeaderMachine {

    private static final String TAG = "StatusBarHeaderMachine";
    private static final boolean DEBUG = false;

    public interface IStatusBarHeaderProvider {
        public Drawable getCurrent(final Calendar time);

        public String getName();

        public void settingsChanged();

        public void enableProvider();

        public void disableProvider();
    }

    public interface IStatusBarHeaderMachineObserver {
        public void updateHeader(Drawable headerImage, boolean force);

        public void disableHeader();
    }

    private Context mContext;
    private List<IStatusBarHeaderProvider> mProviders = new ArrayList<IStatusBarHeaderProvider>();
    private List<IStatusBarHeaderMachineObserver> mObservers = new ArrayList<IStatusBarHeaderMachineObserver>();
    private Handler mHandler = new Handler();
    private boolean mAttached;
    private boolean mScreenOn = true;

    // broadcast providers sent when they update the header image
    public static final String STATUS_BAR_HEADER_UPDATE_ACTION = "com.android.systemui.omni.STATUS_BAR_HEADER_UPDATE";

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (STATUS_BAR_HEADER_UPDATE_ACTION.equals(intent.getAction())) {

                if (mScreenOn) {
                    if (DEBUG) Log.i(TAG, "status bar header background alarm triggered");
                    doUpdateStatusHeaderObservers(false);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // we use ineact alarms so we cant be sure they have been sent during
                // screen off (sleep) - so update it on screen on
                if (!mScreenOn) {
                    if (DEBUG) Log.i(TAG, "status bar header background SCREEN_ON triggered");
                    mScreenOn = true;
                    doUpdateStatusHeaderObservers(true);
                }
            }
        }
    };

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver()
                    .registerContentObserver(
                            Settings.System
                                    .getUriFor(Settings.System.STATUS_BAR_CUSTOM_HEADER),
                            false, this, UserHandle.USER_ALL);
            // we dont want to add a settings observer just for that single setting
            // to the provider - so route all settings via this oberver and forward
            // to providers
            mContext.getContentResolver()
                    .registerContentObserver(
                            Settings.System
                                    .getUriFor(Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK),
                            false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            doUpdateStatusHeaderObservers(true); // simplest way to update poly headers without clobbering API
            final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                    UserHandle.USER_CURRENT) == 1;

            if (customHeader) {
                // forward to all observer
                if (mProviders.size() > 0) {
                    Iterator<IStatusBarHeaderProvider> nextProvider = mProviders
                            .iterator();
                    while (nextProvider.hasNext()) {
                        IStatusBarHeaderProvider provider = nextProvider.next();
                        try {
                            provider.settingsChanged();
                        } catch (Exception e) {
                            // just in case
                        }
                    }
                }
                if (mAttached) {
                    // we dont want to wait for the alarm if provider has changed its header image
                    doUpdateStatusHeaderObservers(true);
                }
            }
            updateEnablement();
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    public StatusBarHeaderMachine(Context context) {
        mContext = context;
        // TODO only one provider for now
        addProvider(new DaylightHeaderProvider(context));
        mSettingsObserver.observe();
    }

    public Drawable getCurrent() {
        final Calendar now = Calendar.getInstance();
        IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider != null) {
            try {
                return provider.getCurrent(now);
            } catch (Exception e) {
                // just in case
            }
        }
        return null;
    }

    public void addProvider(IStatusBarHeaderProvider provider) {
        if (!mProviders.contains(provider)) {
            mProviders.add(provider);
        }
    }

    public void removeProvider(IStatusBarHeaderProvider provider) {
        mProviders.remove(provider);
    }

    public void addObserver(IStatusBarHeaderMachineObserver observer) {
        if (!mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(IStatusBarHeaderMachineObserver observer) {
        mObservers.remove(observer);
    }

    private void doUpdateStatusHeaderObservers(final boolean force) {
        Iterator<IStatusBarHeaderMachineObserver> nextObserver = mObservers
                .iterator();
        while (nextObserver.hasNext()) {
            IStatusBarHeaderMachineObserver observer = nextObserver.next();
            try {
                observer.updateHeader(getCurrent(), force);
            } catch (Exception e) {
                // just in case
            }
        }
    }

    private void doDisableStatusHeaderObservers() {
        Iterator<IStatusBarHeaderMachineObserver> nextObserver = mObservers
                .iterator();
        while (nextObserver.hasNext()) {
            IStatusBarHeaderMachineObserver observer = nextObserver.next();
            try {
                observer.disableHeader();
            } catch (Exception e) {
                // just in case
            }
        }
    }

    public void updateEnablement() {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        // TODO when we support switching providers this must be done properly here
        // unregister old provider first
        IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider == null) {
            Log.w(TAG, "updateEnablement: no active provider");
            return;
        }
        if (customHeader) {
            if (!mAttached) {
                provider.enableProvider();
                // we dont want to wait for the alarm
                doUpdateStatusHeaderObservers(true);
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(STATUS_BAR_HEADER_UPDATE_ACTION);
                mContext.registerReceiver(mBroadcastReceiver, filter);
                mAttached = true;
            }
        } else {
            if (mAttached) {
                provider.disableProvider();
                mContext.unregisterReceiver(mBroadcastReceiver);
                doDisableStatusHeaderObservers();
                mAttached = false;
            }
        }
    }

    private IStatusBarHeaderProvider getCurrentProvider() {
        if (mProviders.size() == 1) {
            return mProviders.get(0);
        }

        if (mProviders.size() > 1) {
            String currentProvider = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
                    UserHandle.USER_CURRENT);

            if (currentProvider == null) {
                return mProviders.get(0);
            }

            Iterator<IStatusBarHeaderProvider> nextProvider = mProviders
                    .iterator();
            while (nextProvider.hasNext()) {
                IStatusBarHeaderProvider provider = nextProvider.next();
                if (provider.getName().equals(currentProvider)) {
                    return provider;
                }
            }
        }
        return null;
    }
}
