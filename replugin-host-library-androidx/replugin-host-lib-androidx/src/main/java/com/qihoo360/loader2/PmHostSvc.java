/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.qihoo360.loader2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.qihoo360.mobilesafe.api.Tasks;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.RePluginConstants;
import com.qihoo360.replugin.RePluginEventCallbacks;
import com.qihoo360.replugin.RePluginInternal;
import com.qihoo360.replugin.base.IPC;
import com.qihoo360.replugin.base.LocalBroadcastHelper;
import com.qihoo360.replugin.component.ComponentList;
import com.qihoo360.replugin.component.receiver.PluginReceiverHelper;
import com.qihoo360.replugin.component.receiver.PluginReceiverProxy;
import com.qihoo360.replugin.component.service.server.IPluginServiceServer;
import com.qihoo360.replugin.component.service.server.PluginServiceServer;
import com.qihoo360.replugin.helper.HostConfigHelper;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;
import com.qihoo360.replugin.model.PluginInfo;
import com.qihoo360.replugin.packages.IPluginManagerServer;
import com.qihoo360.replugin.packages.PluginInfoUpdater;
import com.qihoo360.replugin.packages.PluginManagerServer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.qihoo360.loader2.TaskAffinityStates.TAG;
import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;

/**
 * @author RePlugin Team
 */
class PmHostSvc extends IPluginHost.Stub {

    /**
     *
     */
    Context mContext;

    /**
     *
     */
    PmBase mPluginMgr;

    /**
     *
     */
    PluginServiceServer mServiceMgr;

    /**
     * ??????????????????
     */
    PluginManagerServer mManager;

    /**
     *
     */
    private boolean mNeedRestart;

    PluginReceiverProxy mReceiverProxy;

    /**
     * ?????? plugin-receiver -> Receiver ?????????
     */
    private HashMap<String, BroadcastReceiver> mReceivers = new HashMap<>();

    /**
     * ?????? action ??? plugin???receiver ???????????????
     * <p>
     * -------------------------------------------------------
     * |          action                 | action            |
     * -------------------------------------------------------
     * | plugin   | plugin   | plugin    | ...               |
     * -------------------------------------------------------
     * | receiver | receiver | receiver  | ...               |
     * | receiver | receiver | receiver  | ...               |
     * | receiver | receiver | receiver  | ...               |
     * | ...      | ...      | ...       | ...               |
     * -------------------------------------------------------
     * <p>
     * Map < Action, Map < Plugin, List< Receiver >>>
     */
    private final HashMap<String, HashMap<String, List<String>>> mActionPluginComponents = new HashMap<>();

    private static final class BinderDied implements DeathRecipient {

        String name;

        IBinder binder;

        BinderDied(String name, IBinder binder) {
            this.name = name;
            this.binder = binder;
        }

        @Override
        public void binderDied() {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "binder died: n=" + name + " b=" + binder);
            }
            synchronized (PluginProcessMain.sBinders) {
                PluginProcessMain.sBinders.remove(name);
            }
        }
    }

    PmHostSvc(Context context, PmBase packm) {
        mContext = context;
        mPluginMgr = packm;
        mServiceMgr = new PluginServiceServer(context);
        mManager = new PluginManagerServer(context);
    }

    @Override
    public void installBinder(String name, IBinder binder) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "install binder: n=" + name + " b=" + binder);
        }
        synchronized (PluginProcessMain.sBinders) {
            if (binder != null) {
                PluginProcessMain.sBinders.put(name, binder);
                binder.linkToDeath(new BinderDied(name, binder), 0);
            } else {
                PluginProcessMain.sBinders.remove(name);
            }
        }
    }

    @Override
    public IBinder fetchBinder(String name) throws RemoteException {
        IBinder binder = null;
        synchronized (PluginProcessMain.sBinders) {
            binder = PluginProcessMain.sBinders.get(name);
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "fetch binder: n=" + name + name + " b=" + binder);
        }
        return binder;
    }

    @Override
    public long fetchPersistentCookie() throws RemoteException {
        return PluginProcessMain.getPersistentCookie();
    }

    @Override
    public IPluginClient startPluginProcess(String plugin, int process, PluginBinderInfo info) throws RemoteException {
        return mPluginMgr.startPluginProcessLocked(plugin, process, info);
    }

    @Override
    public String attachPluginProcess(String process, int index, IBinder binder, String def) throws RemoteException {
        int pid = Binder.getCallingPid();
        IPluginClient client = null;
        try {
            client = IPluginClient.Stub.asInterface(binder);
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "a.p.p pc.s.ai: " + e.getMessage(), e);
            }
        }
        if (client == null) {
            return null;
        }
        return PluginProcessMain.attachProcess(pid, process, index, binder, client, def, mManager);
    }

    @Override
    public List<PluginInfo> listPlugins() throws RemoteException {
        return PluginTable.buildPlugins();
    }

    @Override
    public void regActivity(int index, String plugin, String container, String activity) throws RemoteException {
        int pid = Binder.getCallingPid();
        PluginProcessMain.attachActivity(pid, index, plugin, activity, container);
    }

    @Override
    public void unregActivity(int index, String plugin, String container, String activity) throws RemoteException {
        int pid = Binder.getCallingPid();
        PluginProcessMain.detachActivity(pid, index, plugin, activity, container);
    }

    @Override
    public void regService(int index, String plugin, String service) throws RemoteException {
        int pid = Binder.getCallingPid();
        PluginProcessMain.attachService(pid, index, plugin, service);
    }

    @Override
    public void unregService(int index, String plugin, String service) throws RemoteException {
        int pid = Binder.getCallingPid();
        PluginProcessMain.detachService(pid, index, plugin, service);
    }

    @Override
    public void regPluginBinder(PluginBinderInfo info, IBinder binder) throws RemoteException {
        PluginProcessMain.attachBinder(info.pid, binder);
    }

    @Override
    public void unregPluginBinder(PluginBinderInfo info, IBinder binder) throws RemoteException {
        PluginProcessMain.detachBinder(info.pid, binder);

        // ???????????????????????????????????????
        IPluginClient client = PluginProcessMain.probePluginClientByPid(info.pid, info);
        if (client == null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "unregPluginBinder ... client is null");
            }
            return;
        }

        // ????????????
        client.releaseBinder();
    }

    @Override
    public void regReceiver(String plugin, Map rcvFilMap) throws RemoteException {
        PluginInfo pi = MP.getPlugin(plugin, false);
        if (pi == null || pi.getFrameworkVersion() < 4) {
            return;
        }

        if (rcvFilMap == null) {
            return;
        }

        HashMap<String, List<IntentFilter>> receiverFilterMap = (HashMap<String, List<IntentFilter>>) rcvFilMap;

        // ??????????????????????????????????????? Receiver
        for (HashMap.Entry<String, List<IntentFilter>> entry : receiverFilterMap.entrySet()) {
            if (mReceiverProxy == null) {
                mReceiverProxy = new PluginReceiverProxy();
                mReceiverProxy.setActionPluginMap(mActionPluginComponents);
            }

            /* ?????? action-plugin-receiver ????????? */
            String receiver = entry.getKey();
            List<IntentFilter> filters = entry.getValue();

            if (filters != null) {
                for (IntentFilter filter : filters) {
                    int actionCount = filter.countActions();
                    while (actionCount >= 1) {
                        saveAction(filter.getAction(actionCount - 1), plugin, receiver);
                        actionCount--;
                    }

                    // ?????? Receiver
                    mContext.registerReceiver(mReceiverProxy, filter);
                }
            }
        }
    }

    @Override
    public void unregReceiver() throws RemoteException {
        try {
            mContext.unregisterReceiver(mReceiverProxy);
        } catch (Throwable t) {
            if (LOG) {
                LogDebug.d(PluginReceiverProxy.TAG, "unregProxyReceiver failed, " + t.toString());
            }
        }
    }

    @Override
    public void onReceive(String plugin, String receiver, Intent intent) {
        PluginReceiverHelper.onPluginReceiverReceived(plugin, receiver, mReceivers, intent);
    }

    @Override
    public int sumBinders(int index) throws RemoteException {
        return PluginProcessMain.sumBinders(index);
    }

    @Override
    public void updatePluginInfo(PluginInfo info) throws RemoteException {
        Plugin p = null;
        p = mPluginMgr.getPlugin(info.getName());
        if (p != null) {
            p.replaceInfo(info);
        }
        PluginTable.replaceInfo(info);
    }

    @Override
    public PluginInfo pluginDownloaded(String path) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "pluginDownloaded??? path=" + path);
        }

        // ??????????????????????????????????????????????????????P-N????????????????????????????????????
        PluginInfo pi;
        String fn = new File(path).getName();
        if (fn.startsWith("p-n-") || fn.startsWith("v-plugin-") || fn.startsWith("plugin-s-") || fn.startsWith("p-m-")) {
            pi = pluginDownloadedForPn(path);
        } else {
            pi = mManager.getService().install(path);
        }

        if (pi != null) {
            // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            syncInstalledPluginInfo2All(pi);

        }
        return pi;
    }

    @Override
    public boolean pluginUninstalled(PluginInfo info) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "pluginUninstalled??? pn=" + info.getName());
        }
        final boolean result = mManager.getService().uninstall(info);

        // ????????????
        if (result) {
            syncUninstalledPluginInfo2All(info);
        }

        return result;
    }

    private void syncInstalledPluginInfo2All(PluginInfo pi) {
        // PS???????????????????????????????????????????????????????????????????????????????????????????????????install?????????????????????PluginInfo???????????????????????????????????????????????????????????????
        //
        // ???????????????????????????????????????A??????Info???PluginInfoOld????????????????????????Info???PluginInfoNew????????????
        // 1. mManager.getService().install(path) ??????????????????PluginInfoNew
        // 2. PluginInfoOld???????????????????????????????????????PluginInfoOld.mPendingUpdate = PendingInfoNew
        // 3. ????????????????????????????????????????????????
        //    a) ???????????????????????????PluginInfoNew??????????????????????????????????????????????????????Info??????????????????????????????????????????????????????????????????????????????
        //    b) ???????????????????????????PluginInfoOld??????????????????Old????????????mPendingUpdate??????PendingInfoNew?????????????????????????????????????????????????????????
        // 4. ??????install?????????????????????PluginInfoNew??????????????????????????????????????????????????????????????????????????????????????????
        PluginInfo needToSyncPi;
        PluginInfo parent = pi.getParentInfo();
        if (parent != null) {
            needToSyncPi = parent;
        } else {
            needToSyncPi = pi;
        }

        // ???????????????????????????????????????
        mPluginMgr.newPluginFound(needToSyncPi, false);

        // ???????????????????????????
        Intent intent = new Intent(PmBase.ACTION_NEW_PLUGIN);
        intent.putExtra(RePluginConstants.KEY_PERSIST_NEED_RESTART, mNeedRestart);
        intent.putExtra("obj", (Parcelable) needToSyncPi);
        IPC.sendLocalBroadcast2AllSync(mContext, intent);

        if (LOG) {
            LogDebug.d(TAG, "syncInstalledPluginInfo2All: Sync complete! syncPi=" + needToSyncPi);
        }
    }


    private void syncUninstalledPluginInfo2All(PluginInfo pi) {

        // ???????????????????????????????????????
        mPluginMgr.pluginUninstalled(pi);

        // ???????????????????????????????????????
        final Intent intent = new Intent(PluginInfoUpdater.ACTION_UNINSTALL_PLUGIN);
        intent.putExtra("obj", (Parcelable) pi);
        // ???????????????attachBaseContext????????????????????????????????????getApplicationContext?????????????????????????????????????????????????????????
        // ?????????Post????????????getApplicationContext???????????????????????????
        if (RePluginInternal.getAppContext().getApplicationContext() != null) {
            IPC.sendLocalBroadcast2AllSync(RePluginInternal.getAppContext(), intent);
        } else {
            Tasks.post2UI(new Runnable() {
                @Override
                public void run() {
                    IPC.sendLocalBroadcast2All(RePluginInternal.getAppContext(), intent);
                }
            });
        }
    }

    private PluginInfo pluginDownloadedForPn(String path) {
        File f = new File(path);
        V5FileInfo v5f = V5FileInfo.build(f, V5FileInfo.NORMAL_PLUGIN);
        if (v5f == null) {
            v5f = V5FileInfo.build(f, V5FileInfo.INCREMENT_PLUGIN);
            if (v5f == null) {
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "pluginDownloaded: unknown v5 plugin file: " + path);
                }

                // ??????????????????
                RePlugin.getConfig().getEventCallbacks().onInstallPluginFailed(path, RePluginEventCallbacks.InstallResult.V5_FILE_BUILD_FAIL);
                return null;
            }
        }

        File ddir = mContext.getDir(Constant.LOCAL_PLUGIN_SUB_DIR, 0);
        PluginInfo info = v5f.updateV5FileTo(mContext, ddir, false, true);
        if (info == null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "pluginDownloaded: failed to update v5 plugin: " + path);
            }

            // ??????????????????
            RePlugin.getConfig().getEventCallbacks().onInstallPluginFailed(path, RePluginEventCallbacks.InstallResult.V5_FILE_UPDATE_FAIL);
            return null;
        }
        return info;
    }

    @Override
    public boolean pluginExtracted(String path) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "pluginExtracted??? path=" + path);
        }

        //
        File f = new File(path);

        PluginInfo info = PluginInfo.build(f);
        if (info == null) {
            return false;
        }

        // ?????????????????????
        mPluginMgr.newPluginFound(info, false);

        // ??????????????????
        Intent intent = new Intent(PmBase.ACTION_NEW_PLUGIN);
        intent.putExtra(RePluginConstants.KEY_PERSIST_NEED_RESTART, mNeedRestart);
        intent.putExtra("obj", (Parcelable) info);
        IPC.sendLocalBroadcast2AllSync(mContext, intent);

        return true;
    }

    @Override
    public void sendIntent2Process(String target, Intent intent) throws RemoteException {
        sendIntent2Process(target, intent, false);
    }

    @Override
    public void sendIntent2ProcessSync(String target, Intent intent) throws RemoteException {
        sendIntent2Process(target, intent, true);
    }

    private void sendIntent2Process(String target, Intent intent, boolean sync) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "sendIntent2Process target=" + target + " intent=" + intent);
        }
        if (TextUtils.equals(target, IPC.getPluginHostProcessName())) {
            sendIntent2PluginHostProcess(intent, sync);
            return;
        }

        if (TextUtils.isEmpty(target)) {
            sendIntent2PluginHostProcess(intent, sync);
        }

        PluginProcessMain.sendIntent2Process(target, intent, sync);
    }

    private void sendIntent2PluginHostProcess(Intent intent, boolean sync) {
        intent.setExtrasClassLoader(getClass().getClassLoader());
        if (sync) {
            LocalBroadcastHelper.sendBroadcastSyncUi(mContext, intent);
        } else {
            LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        }
    }

    @Override
    public void sendIntent2Plugin(String target, Intent intent) throws RemoteException {
        sendIntent2Plugin(target, intent, false);
    }

    @Override
    public void sendIntent2PluginSync(String target, Intent intent) throws RemoteException {
        sendIntent2Plugin(target, intent, true);
    }

    private void sendIntent2Plugin(String target, Intent intent, boolean sync) throws RemoteException {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "sendIntent2Plugin target=" + target + " intent=" + intent);
        }
        // ????????????????????????????????????UI?????????
        if (!TextUtils.equals(target, Constant.PLUGIN_NAME_UI)) {
            PluginProcessMain.sendIntent2Plugin(target, intent, sync);
        }
        // ????????????Activity???????????????UI????????????UI
        if (Constant.ENABLE_PLUGIN_ACTIVITY_AND_BINDER_RUN_IN_MAIN_UI_PROCESS) {
            target = Constant.PLUGIN_NAME_UI;
            PluginProcessMain.sendIntent2Plugin(target, intent, sync);
        }
    }

    @Override
    public boolean isProcessAlive(String name) throws RemoteException {
        return PluginProcessMain.isProcessAlive(name);
    }

    @Override
    public IBinder queryPluginBinder(String plugin, String binder) throws RemoteException {
        return mPluginMgr.mLocal.query(plugin, binder);
    }

    @Override
    public IPluginServiceServer fetchServiceServer() throws RemoteException {
        return mServiceMgr.getService();
    }

    /**
     * ?????? action-plugin-receiver ?????????
     */
    private void saveAction(String action, String plugin, String receiver) {

        HashMap<String, List<String>> pluginReceivers = mActionPluginComponents.get(action);
        if (pluginReceivers == null) {
            pluginReceivers = new HashMap<>();
            mActionPluginComponents.put(action, pluginReceivers);
        }

        // ???????????????????????? Receiver ??????
        List<String> receivers = pluginReceivers.get(plugin);
        if (receivers == null) {
            receivers = new ArrayList<>();
            pluginReceivers.put(plugin, receivers);
        }

        // ?????? Receiver ??? Receiver ??????
        if (!receivers.contains(receiver)) {
            receivers.add(receiver);

            if (LOG) {
                LogDebug.d(PluginReceiverProxy.TAG, String.format("?????? Receiver (%s, %s, %s)", action, plugin, receiver));
            }
        }
    }

    @Override
    public List<ActivityInfo> queryPluginsReceiverList(Intent intent) {
        List<ActivityInfo> infos = new ArrayList<>();

        if (intent == null) {
            return infos;
        }

        String action = intent.getAction();
        if (TextUtils.isEmpty(action)) {
            return infos;
        }

        Map<String, List<String>> pluginReceiverMap = mActionPluginComponents.get(action);
        if (pluginReceiverMap.isEmpty()) {
            return infos;
        }

        // ?????? action ??????????????? Receivers
        for (Map.Entry<String, List<String>> entry : pluginReceiverMap.entrySet()) {
            String plugin = entry.getKey();

            // ????????????????????????????????? Receiver
            ComponentList list = mPluginMgr.mLocal.queryPluginComponentList(plugin);
            if (list != null) {

                Map<String, ActivityInfo> receiversMap = list.getReceiverMap();
                if (receiversMap != null) {
                    infos.addAll(receiversMap.values());
                }
            }
        }
        return infos;
    }

    @Override
    public IPluginManagerServer fetchManagerServer() throws RemoteException {
        return mManager.getService();
    }

    /* ------------------------------------------------------- */
    /* ?????? TaskAffinity ???????????????????????????????????????????????????????????? */
    /* -------------------------------------------------------- */
    /**
     * TaskAffinity ?????????
     */
    private static final int GROUP_COUNT = HostConfigHelper.ACTIVITY_PIT_COUNT_TASK;

    /**
     * ????????? TaskAffinity ??? GroupID ???????????????
     */
    private static Map<String, Integer> mPluginGroupMap = new HashMap<>();

    public int getTaskAffinityGroupIndex(String pTaskAffinity) {
        int index;
        // ??? taskaffinity ???????????????
        if (!mPluginGroupMap.containsKey(pTaskAffinity)) {
            index = getValidGroup();
            if (index == -1) { // group ??????
                if (LOG) {
                    LogDebug.d(TAG, "Get groupID fail, not enough TaskAffinity group");
                }
                return -1;
            }
            mPluginGroupMap.put(pTaskAffinity, index);
        } else {
            index = mPluginGroupMap.get(pTaskAffinity);
        }

        return index;
    }

    /**
     * ??????????????????????????? GroupId
     */
    private int getValidGroup() {
        for (int i = 0; i < GROUP_COUNT; i++) {
            // groupID i ????????????
            if (!mPluginGroupMap.containsValue(i)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getPidByProcessName(String processName) throws RemoteException {
        return PluginProcessMain.getPidByProcessName(processName);
    }

    @Override
    public String getProcessNameByPid(int pid) throws RemoteException {
        return PluginProcessMain.getProcessNameByPid(pid);
    }

    @Override
    public String dump() {
        return PluginProcessMain.dump();
    }
}