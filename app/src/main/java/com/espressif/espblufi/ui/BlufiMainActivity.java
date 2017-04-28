package com.espressif.espblufi.ui;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.espressif.blufi.ble.proxy.BleGattClientProxy;
import com.espressif.blufi.ble.proxy.BleGattClientProxyImpl;
import com.espressif.blufi.ble.scanner.BleScanner;
import com.espressif.espblufi.R;
import com.espressif.espblufi.app.BlufiApp;
import com.espressif.espblufi.constants.BlufiConstants;
import com.espressif.tools.app.PermissionHelper;
import com.espressif.tools.data.RandomUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class BlufiMainActivity extends BlufiAbsActivity {
    private static final int TIMEOUT_SCAN = 5;
    private static final int TIMEOUT_CONNECT = 5000;

    private static final int MENU_ID_SETTINGS = 0x10;

    private static final int REQUEST_PERMISSION = 1;

    private PermissionHelper mPermissionHelper;

    private SwipeRefreshLayout mRefreshLayout;

    private BTAdapter mBTAdapter;
    private List<EspBleDevice> mBTList;

    private View mButtonBar;

    private TextView mCheckCountTV;

    private List<EspBleDevice> mTempDevices;

    private Looper mBackgroundLooper;

    private volatile long mStartConnectTime;

    private BluetoothGatt mCheckGatt;
    private BluetoothDevice mConnectingDevice;
    private BluetoothDevice mConnectedDevice;

    private BluetoothAdapter.LeScanCallback mBTCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            String deviceName = device.getName();
            if (deviceName == null || !deviceName.startsWith(BlufiConstants.BLUFI_PREFIX)) {
                return;
            }

            EspBleDevice newDevice = new EspBleDevice(device);
            newDevice.rssi = rssi;
            Observable.just(newDevice)
                    .subscribeOn(AndroidSchedulers.from(mBackgroundLooper))
                    .filter(nd -> {
                        boolean exist = false;
                        for (EspBleDevice td : mTempDevices) {
                            if (td.equals(nd)) {
                                td.rssi = nd.rssi;
                                exist = true;
                                break;
                            }
                        }

                        if (!exist) {
                            mTempDevices.add(nd);
                        }

                        return !exist;
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Subscriber<EspBleDevice>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                        }

                        @Override
                        public void onNext(EspBleDevice nd) {
                            if (!containBle(nd.device)) {
                                int insert = mBTList.size();
                                for (int i = 0; i < mBTList.size(); i++) {
                                    EspBleDevice od = mBTList.get(i);
                                    if (od.rssi < nd.rssi) {
                                        insert = i;
                                        break;
                                    }
                                }

                                mBTList.add(insert, nd);
                                mBTAdapter.notifyDataSetChanged();
                            }
                        }
                    });
        }
    };

    private Comparator<EspBleDevice> mBleComparator = (o1, o2) -> {
        int i1 = o1.rssi;
        int i2 = o2.rssi;

        if (i1 < i2) {
            return 1;
        } else if (i1 == i2) {
            return 0;
        } else {
            return -1;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.blufi_connect_activity);

        mCheckCountTV = new TextView(this);
        mCheckCountTV.setTextColor(Color.WHITE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setCustomView(mCheckCountTV, new ActionBar.LayoutParams(
                    ActionBar.LayoutParams.WRAP_CONTENT,
                    ActionBar.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.END));
        }

        mRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.refresh_layout);
        mRefreshLayout.setColorSchemeResources(R.color.colorAccent);
        mRefreshLayout.setOnRefreshListener(this::scan);

        RecyclerView mRecyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        mBTList = new ArrayList<>();
        mBTAdapter = new BTAdapter();
        mRecyclerView.setAdapter(mBTAdapter);

        mButtonBar = findViewById(R.id.button_bar);
        mButtonBar.findViewById(R.id.button_bar_batch_configure).setOnClickListener(v -> batchConfigure());
        mButtonBar.findViewById(R.id.button_bar_all).setOnClickListener(v -> selectAll());

        BackgroundThread backgroundThread = new BackgroundThread();
        backgroundThread.start();
        mBackgroundLooper = backgroundThread.getLooper();

        mTempDevices = new ArrayList<>();

        mPermissionHelper = new PermissionHelper(this, REQUEST_PERMISSION);
        mPermissionHelper.setOnPermissionsListener((permission, permited) -> {
            if (permited && permission.equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                mRefreshLayout.setRefreshing(true);
                scan();
            }
        });
        mPermissionHelper.requestAuthorities(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        BleScanner.stopLeScan(mBTCallback);

        if (mCheckGatt != null) {
            mCheckGatt.close();
            mCheckGatt = null;
        }
        mBackgroundLooper.quit();
    }

    @Override
    public void onBackPressed() {
        if (mBTAdapter.mCheckable) {
            clearBleChecked();
            setCheckMode(false);
            return;
        }

        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        mPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_ID_SETTINGS, 0, R.string.connect_menu_settings);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ID_SETTINGS:
                startActivity(new Intent(this, BlufiSettingsActivity.class));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void selectAll() {
        boolean checkedAll = true;
        for (EspBleDevice d : mBTList) {
            if (!d.checked) {
                checkedAll = false;
                break;
            }
        }

        boolean actionCheck = !checkedAll;
        for (EspBleDevice d : mBTList) {
            d.checked = actionCheck;
        }

        if (!actionCheck) {
            closeCheckedGatt();
        }

        mBTAdapter.notifyDataSetChanged();

        updateSelectDeviceCountInfo();
    }

    private void batchConfigure() {
        ArrayList<BluetoothDevice> bles = new ArrayList<>();
        for (EspBleDevice ble : mBTList) {
            if (ble.checked) {
                bles.add(ble.device);
            }
        }

        if (bles.isEmpty()) {
            Toast.makeText(this, R.string.connect_no_seleted_devices, Toast.LENGTH_SHORT).show();
        } else {
            closeCheckedGatt();

            Intent intent = new Intent(this, BlufiConfigureActivity.class);
            String rKey = RandomUtil.randomString(10);
            BlufiApp.getInstance().putCache(rKey, bles);
            intent.putExtra(BlufiConstants.KEY_BLE_DEVICES, rKey);
            intent.putExtra(BlufiConstants.KEY_IS_BATCH_CONFIGURE, true);
            startActivity(intent);
        }
    }

    /**
     * Scan bluetooth devices
     */
    private void scan() {
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            Toast.makeText(this, R.string.bt_disable_msg, Toast.LENGTH_SHORT).show();
            mRefreshLayout.setRefreshing(false);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check location enable
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            boolean locationGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean locationNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!locationGPS && !locationNetwork) {
                Toast.makeText(this, R.string.location_disable_msg, Toast.LENGTH_SHORT).show();
                mRefreshLayout.setRefreshing(false);
                return;
            }
        }

        BleScanner.startLeScan(mBTCallback);
        Observable.timer(TIMEOUT_SCAN, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Subscriber<Long>() {
                    @Override
                    public void onCompleted() {
                        BleScanner.stopLeScan(mBTCallback);
                        removeGarbageDevices();
                    }

                    @Override
                    public void onError(Throwable e) {
                    }

                    @Override
                    public void onNext(Long aLong) {
                    }
                });
    }

    private void clearBleChecked() {
        for (EspBleDevice ble : mBTList) {
            ble.checked = false;
        }
    }

    private boolean containBle(BluetoothDevice device) {
        for (EspBleDevice ble : mBTList) {
            if (ble.device.equals(device)) {
                return true;
            }
        }

        return false;
    }

    private void setCheckMode(boolean checkMode) {
        mBTAdapter.setCheckable(checkMode);
        mButtonBar.setVisibility(checkMode ? View.VISIBLE : View.GONE);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowCustomEnabled(checkMode);
        }
        updateSelectDeviceCountInfo();
    }

    private void updateSelectDeviceCountInfo() {
        int count = 0;
        for (EspBleDevice d : mBTList) {
            if (d.checked) {
                count++;
            }
        }

        mCheckCountTV.setText(getString(R.string.connect_selected_device_info, count));
    }

    private void removeGarbageDevices() {
        for (int i = mBTList.size() - 1; i >= 0; i--) {
            BluetoothDevice device = mBTList.get(i).device;
            for (int j = i - 1; j >= 0; j--) {
                BluetoothDevice compareDevice = mBTList.get(j).device;
                if (device.equals(compareDevice)) {
                    mBTList.remove(i);
                    mBTAdapter.notifyItemRemoved(i);
                    break;
                }
            }
        }

        List<EspBleDevice> bleList = new ArrayList<>();
        bleList.addAll(mBTList);
        final List<EspBleDevice> removeDevices = new LinkedList<>();
        Observable.from(bleList)
                .subscribeOn(AndroidSchedulers.from(mBackgroundLooper))
                .doOnNext(ble -> {
                    boolean contains = false;
                    for (EspBleDevice td : mTempDevices) {
                        if (ble.equals(td)) {
                            ble.rssi = td.rssi;
                            contains = true;
                            break;
                        }
                    }
                    if (!contains) {
                        removeDevices.add(ble);
                    }
                })
                .doOnCompleted(() -> mTempDevices.clear())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Subscriber<EspBleDevice>() {
                    @Override
                    public void onCompleted() {
                        for (EspBleDevice removeDevice : removeDevices) {
                            int position = mBTList.indexOf(removeDevice);
                            if (position >= 0) {
                                mBTList.remove(position);
                            }
                        }

                        Collections.sort(mBTList, mBleComparator);
                        mBTAdapter.notifyDataSetChanged();
                        mRefreshLayout.setRefreshing(false);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onNext(EspBleDevice bluetoothDevice) {
                    }
                });
    }

    private void connect(final BluetoothDevice device) {
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage(String.format(Locale.ENGLISH, "Connecting %s", device.getName()));
        dialog.setCancelable(false);
        dialog.show();

        Observable.just(device).subscribeOn(Schedulers.io())
                .map(dev -> {
                    closeCheckedGatt();

                    BleGattClientProxy proxy = new BleGattClientProxyImpl(getApplicationContext());
                    mStartConnectTime = SystemClock.elapsedRealtime();
                    if (proxy.connect(dev, TIMEOUT_CONNECT)) {
                        return proxy;
                    } else {
                        proxy.close();
                        return null;
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(proxy -> {
                    dialog.dismiss();
                    Activity activity = BlufiMainActivity.this;
                    if (proxy == null) {
                        Toast.makeText(activity, "Connect failed", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(activity, "Connect completed", Toast.LENGTH_LONG).show();

                        Intent intent = new Intent(activity, BlufiNegSecActivity.class);
                        long connectTime = SystemClock.elapsedRealtime() - mStartConnectTime;
                        intent.putExtra(BlufiConstants.KEY_CONNECT_TIME, connectTime);

                        String key = RandomUtil.randomString(10);
                        BlufiApp.getInstance().putCache(key, proxy);
                        intent.putExtra(BlufiConstants.KEY_PROXY, key);

                        activity.startActivity(intent);
                    }
                });
    }

    private void closeCheckedGatt() {
        if (mCheckGatt != null) {
            mCheckGatt.close();
            mCheckGatt = null;
            mConnectedDevice = null;
            mConnectingDevice = null;
            runOnUiThread(() -> mBTAdapter.notifyDataSetChanged());
        }
    }

    private void checked(EspBleDevice ble) {
        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                System.out.println(String.format(Locale.ENGLISH, "xxj status = %d, state = %d", status, newState));
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        mConnectedDevice = gatt.getDevice();
                        mConnectingDevice = null;
                        break;
                    case BluetoothProfile.STATE_CONNECTING:
                        mConnectingDevice = gatt.getDevice();
                        mConnectedDevice = null;
                        break;
                    default:
                        mConnectedDevice = null;
                        mConnectingDevice = null;
                        break;
                }
                Observable.create(subscriber -> mBTAdapter.notifyDataSetChanged())
                        .subscribeOn(AndroidSchedulers.mainThread())
                        .subscribe();
            }
        };

        mConnectingDevice = ble.device;
        mBTAdapter.notifyDataSetChanged();
        if (mCheckGatt == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mCheckGatt = ble.device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                mCheckGatt = ble.device.connectGatt(this, false, callback);
            }
        } else {
            if (ble.device.equals(mCheckGatt.getDevice())) {
                mCheckGatt.connect();
            } else {
                mCheckGatt.close();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mCheckGatt = ble.device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    mCheckGatt = ble.device.connectGatt(this, false, callback);
                }
            }
        }

    }

    private void unChecked(EspBleDevice ble) {
        if (mCheckGatt != null) {
            if (sameBle(ble.device, mCheckGatt.getDevice())) {
                mCheckGatt.disconnect();
            }

            if (sameBle(ble.device, mConnectedDevice)) {
                mConnectedDevice = null;
                mBTAdapter.notifyDataSetChanged();
            } else if (sameBle(ble.device, mConnectingDevice)) {
                mConnectingDevice = null;
                mBTAdapter.notifyDataSetChanged();
            }
        }
    }

    private boolean sameBle(BluetoothDevice d1, BluetoothDevice d2) {
        if (d1 == null || d2 == null) {
            return false;
        }
        return d1.getAddress().equalsIgnoreCase(d2.getAddress());
    }

    private static class BackgroundThread extends HandlerThread {
        BackgroundThread() {
            super("Bluetooth-Update-BackgroundThread", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        }
    }

    private class EspBleDevice {
        BluetoothDevice device;
        boolean checked = false;
        int rssi = 1;

        EspBleDevice(BluetoothDevice d) {
            device = d;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof EspBleDevice)) {
                return false;
            }

            return device.equals(((EspBleDevice) obj).device);
        }
    }

    private class BTHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener {
        View view;
        TextView text1;
        TextView text2;
        CheckBox checkBox;

        TextView status1;
        ProgressBar progress1;

        EspBleDevice ble;

        BTHolder(View itemView) {
            super(itemView);

            text1 = (TextView) itemView.findViewById(R.id.text1);
            text2 = (TextView) itemView.findViewById(R.id.text2);
            status1 = (TextView) itemView.findViewById(R.id.status1);
            progress1 = (ProgressBar) itemView.findViewById(R.id.progress1);
            checkBox = (CheckBox) itemView.findViewById(R.id.check);
            checkBox.setOnClickListener(this);

            itemView.setOnClickListener(BTHolder.this);
            itemView.setOnLongClickListener(BTHolder.this);
            view = itemView;
        }

        @Override
        public void onClick(View v) {
            if (v == checkBox) {
                ble.checked = checkBox.isChecked();

                if (ble.checked) {
                    checked(ble);
                } else {
                    unChecked(ble);
                }

                updateSelectDeviceCountInfo();
            } else if (v == view) {
                if (mBTAdapter.mCheckable) {
                    return;
                }

                connect(ble.device);
            }
        }

        @Override
        public boolean onLongClick(View v) {
            setCheckMode(true);
            return true;
        }
    }

    private class BTAdapter extends RecyclerView.Adapter<BTHolder> {
        LayoutInflater mInflater = BlufiMainActivity.this.getLayoutInflater();

        boolean mCheckable = false;

        @Override
        public BTHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View itemView = mInflater.inflate(R.layout.bluetooth_device_item, parent, false);
            return new BTHolder(itemView);
        }

        @Override
        public void onBindViewHolder(BTHolder holder, int position) {
            holder.ble = mBTList.get(position);

            String name = holder.ble.device.getName();
            if (TextUtils.isEmpty(name)) {
                name = "Unnamed";
            }
            holder.text1.setText(String.format(Locale.ENGLISH,
                    "%d. %s", position + 1, name));

            holder.text2.setText(String.format(Locale.ENGLISH,
                    "%s  %d", holder.ble.device.getAddress(), holder.ble.rssi));

            holder.checkBox.setVisibility(mCheckable ? View.VISIBLE : View.GONE);
            holder.checkBox.setChecked(holder.ble.checked);

            holder.status1.setBackgroundResource(R.drawable.ic_bluetooth);
            if (sameBle(holder.ble.device, mConnectedDevice)) {
                holder.status1.setVisibility(View.VISIBLE);
            } else {
                holder.status1.setVisibility(View.GONE);
            }

            if (sameBle(holder.ble.device, mConnectingDevice)) {
                holder.progress1.setVisibility(View.VISIBLE);
            } else {
                holder.progress1.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return mBTList.size();
        }

        void setCheckable(boolean checkable) {
            if (mCheckable != checkable) {
                mCheckable = checkable;
                notifyDataSetChanged();
            }
        }
    }
}
