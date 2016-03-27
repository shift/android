package org.owntracks.android.services;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.MonitorNotifier;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.powersave.BackgroundPowerSaver;
import org.owntracks.android.db.Dao;
import org.owntracks.android.db.Waypoint;
import org.owntracks.android.db.WaypointDao;
import org.owntracks.android.messages.MessageTransition;
import org.owntracks.android.messages.MessageWaypoint;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.Preferences;

// Detects Bluetooth LE beacons as defined in the AltBeacon Spec:
//  -> https://github.com/AltBeacon/spec

public class ServiceBeacon implements ProxyableService, BeaconConsumer {
    private static final String TAG = "ServiceBeacon";

    private Context context;
    private HashMap<Long, Region> activeRegions;
    private BeaconManager beaconManager;
    private WaypointDao waypointDao;

    @Override
    public void onCreate(ServiceProxy c) {
        Log.v(TAG, "onCreate()");

        this.context = c;
        this.waypointDao = Dao.getWaypointDao();
        this.activeRegions = new HashMap<>();


        // Gets additional information about available BLE features
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if(bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth is not available");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.v(TAG, "bluetoothAdapter.isMultipleAdvertisementSupported: " + bluetoothAdapter.isMultipleAdvertisementSupported());
            Log.v(TAG, "bluetoothAdapter.isOffloadedFilteringSupported: " + bluetoothAdapter.isOffloadedFilteringSupported());
            Log.v(TAG, "bluetoothAdapter.isOffloadedScanBatchingSupported: " + bluetoothAdapter.isOffloadedScanBatchingSupported());
        }

        BeaconManager.setAndroidLScanningDisabled(false);
        beaconManager = BeaconManager.getInstanceForApplication(context);
        beaconManager.setForegroundBetweenScanPeriod(TimeUnit.SECONDS.toMillis(30));
        beaconManager.setBackgroundBetweenScanPeriod(TimeUnit.SECONDS.toMillis(120));
        beaconManager.setBackgroundScanPeriod(TimeUnit.SECONDS.toMillis(30));
        // Eddystone Beacons
        // Detect the main identifier (UID) frame:
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("s:0-1=feaa,m:2-2=00,p:3-3:-41,i:4-13,i:14-19"));
        // Detect the telemetry (TLM) frame:
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("x,s:0-1=feaa,m:2-2=20,d:3-3,d:4-5,d:6-7,d:8-11,d:12-15"));
        // Detect the URL frame:
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("s:0-1=feaa,m:2-2=10,p:3-3:-41,i:4-20v"));
        // AltBeacon
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
        // iBeacon
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
        beaconManager.bind(this);

    }



    @Override
    public void onDestroy() {

    }


    @Override
    public void onStartCommand(Intent intent, int flags, int startId) {

    }

    @SuppressWarnings("unused")
    @Override
    public void onEvent(Events.Dummy event) {

    }

    private void publishTransitionMessage(Region triggeringRegion, String transition) {
        Waypoint w = waypointDao.load(Long.parseLong(triggeringRegion.getUniqueId()));
        if(w == null) {
            Log.e(TAG, "unable to load waypoint from entered region");
            return;
        }
        MessageTransition m = new MessageTransition();
        m.setLat(w.getGeofenceLatitude());
        m.setLon(w.getGeofenceLongitude());
        m.setDesc(w.getDescription());
        m.setTst(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
        m.setEvent(transition);
        m.setTid(Preferences.getTrackerId(true));
        m.setWtst(TimeUnit.MILLISECONDS.toSeconds(w.getDate().getTime()));
        m.setTrigger(MessageTransition.TRIGGER_BEACON);


        ServiceProxy.getServiceMessage().sendMessage(m);

        w.setLastTriggered(System.currentTimeMillis());
        this.waypointDao.update(w);

    }

    @Override
    public void onBeaconServiceConnect() {
        Log.v(TAG, "onBeaconServiceConnect");
        beaconManager.setMonitorNotifier(new MonitorNotifier() {
            @Override
            public void didEnterRegion(Region region) {
                Log.i(TAG, "didEnterRegion " + region.getUniqueId() + " " + Long.parseLong(region.getUniqueId()));
                publishTransitionMessage(region, MessageTransition.EVENT_ENTER);


            }

            @Override
            public void didExitRegion(Region region) {
                Log.i(TAG, "didExitRegion " + region.getUniqueId());
                publishTransitionMessage(region, MessageTransition.EVENT_LEAVE);
            }

            @Override
            public void didDetermineStateForRegion(int state, Region region) {
                Log.i(TAG, "didDetermineStateForRegion " + region.getUniqueId() + " state: " + state);
            }
        });

            for(Waypoint w : loadWaypointsForModeIdWithValidBeacon()) {
                addRegion(w);
            }
    }

    @SuppressWarnings("unused")
    public void onEvent(Events.WaypointAdded e) {
        addRegion(e.getWaypoint());
    }

    private boolean hasBeaconManager() {
        return beaconManager != null;
    }

    private void removeRegion(Waypoint w) {
        try {
            Region r = activeRegions.get(w.getId());
            if(hasBeaconManager() && r != null ) {
                Log.v(TAG, "removing region for ID " + w.getId());
                beaconManager.stopMonitoringBeaconsInRegion(r);
                activeRegions.remove(w.getId());
            } else
                Log.e(TAG, "skipping remove, region not setup for ID " + w.getId());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }


    private void addRegion(Waypoint w) {
        Log.v(TAG, "addRegion: " + w.getDescription());
        if(!hasBeaconManager() || !isWaypointWithValidRegion(w))
            return;


        Log.v(TAG, "startMonitoringBeaconsInRegion " + w.getId() + " desc: " + w.getDescription() + " UUID: " + w.getBeaconUUID() + " " + w.getBeaconMajor() + "/" + w.getBeaconMinor());
        try {
            Region r = getRegionFromWaypoint(w);

            Log.v(TAG, r.getUniqueId() + " " + r.getId1() + " " + r.getId2() + " " + r.getId3());
            beaconManager.startMonitoringBeaconsInRegion(r);
        } catch (Exception e) {
            Log.e(TAG, "unable to add region");
            e.printStackTrace();
        }
    }

    private boolean isWaypointWithValidRegion(Waypoint w) {

        try {
            if(w.getBeaconUUID() == null || w.getBeaconUUID().isEmpty())
                return false;

            Identifier.parse(w.getBeaconUUID());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "beacon UUID parse exception: " + e.getMessage());
            return false;
        }
    }


    private Region getRegionFromWaypoint(Waypoint w) {
        return new Region(w.getId().toString(), Identifier.parse(w.getBeaconUUID()), w.getBeaconMajor() != null ? Identifier.fromInt(w.getBeaconMajor()) : null, w.getBeaconMinor() != null ? Identifier.fromInt(w.getBeaconMinor()) : null);
    }

    @SuppressWarnings("unused")
    public void onEvent(Events.WaypointUpdated e) {
        removeRegion(e.getWaypoint());
        addRegion(e.getWaypoint());
    }

    @SuppressWarnings("unused")
    public void onEvent(Events.WaypointRemoved e) {
        removeRegion(e.getWaypoint());
    }

    private List<Waypoint> loadWaypointsForModeIdWithValidBeacon() {
        return this.waypointDao.queryBuilder().where(WaypointDao.Properties.ModeId.eq(Preferences.getModeId()), WaypointDao.Properties.BeaconUUID.isNotNull()).build().list();
    }



    @Override
    public Context getApplicationContext() {
        return context.getApplicationContext();
    }

    @Override
    public void unbindService(ServiceConnection serviceConnection) {
        context.unbindService(serviceConnection);
    }

    @Override
    public boolean bindService(Intent intent, ServiceConnection serviceConnection, int i) {
        return context.bindService(intent, serviceConnection, i);
    }

    public void enableForegroundMode() {
        if(beaconManager != null) {
            Log.v(TAG, "enabling foreground mode");
            beaconManager.setBackgroundMode(false);
        }
    }

    public void enableBackgroundMode() {
        if(beaconManager != null) {
            Log.v(TAG, "enabling background mode");
            beaconManager.setBackgroundMode(true);
        }

    }

}
