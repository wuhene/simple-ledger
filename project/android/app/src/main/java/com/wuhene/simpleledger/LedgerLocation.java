package com.wuhene.simpleledger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * 读取前台入账定位并计算本机坐标距离。
 *
 * @author chaoshunh
 * @date 2026/11/26
 */
final class LedgerLocation {
    private static final double EARTH_KM = 6371;

    /**
     * 判断应用是否获准访问前台定位。
     */
    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 在工作线程有限等待一次设备定位。
     */
    @SuppressWarnings("deprecation")
    static JSONObject capture(Context context, boolean resolveAddress) {
        return capture(context, resolveAddress, false);
    }

    /**
     * 采集当前位置并按需附带地点选择项。
     */
    @SuppressWarnings("deprecation")
    static JSONObject capture(Context context, boolean resolveAddress, boolean picker) {
        if (!allowed(context)) return null;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return null;
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Location> result = new AtomicReference<>();
        LocationListener listener = new LocationListener() {
            /**
             * 接收足够新鲜的系统定位。
             */
            @Override public void onLocationChanged(Location location) {
                long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
                if (age >= 0 && age <= TimeUnit.SECONDS.toNanos(20) && result.compareAndSet(null, location)) ready.countDown();
            }

            /**
             * 接收旧系统的定位状态通知。
             */
            @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

            /**
             * 接收旧系统的定位恢复通知。
             */
            @Override public void onProviderEnabled(String provider) {}

            /**
             * 接收旧系统的定位关闭通知。
             */
            @Override public void onProviderDisabled(String provider) {}
        };
        boolean listening = false;
        try {
            for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (!manager.isProviderEnabled(provider)) continue;
                try {
                    manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
                    listening = true;
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // Another enabled provider may still work.
                }
            }
            if (!listening || !ready.await(4, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (listening) {
                try { manager.removeUpdates(listener); }
                catch (SecurityException ignored) { /* Permission can change during capture. */ }
            }
        }
        Location point = result.get();
        if (point == null || !point.hasAccuracy()) return null;
        String city = null, district = null;
        org.json.JSONArray choices = null;
        if (resolveAddress) {
            List<Address> addresses = reverse(context, point, picker ? 5 : 1);
            if (!addresses.isEmpty()) {
                Address address = addresses.get(0);
                city = address.getLocality();
                if (city == null && address.getAdminArea() != null && address.getAdminArea().matches("(北京|上海|天津|重庆)市?")) city = address.getAdminArea();
                district = address.getSubLocality();
                if (picker) {
                    java.util.Set<String> names = new java.util.LinkedHashSet<>();
                    for (Address candidate : addresses) {
                        String name = candidate.getAddressLine(0);
                        if (name != null && !name.trim().isEmpty() && name.length() <= 120) names.add(name.trim());
                    }
                    if (district != null) names.add(district);
                    if (city != null) names.add(city);
                    choices = new org.json.JSONArray(names);
                }
            }
        }
        return LedgerAssistant.object("latitude", point.getLatitude(), "longitude", point.getLongitude(),
                "accuracy", point.getAccuracy(), "capturedAt", Instant.ofEpochMilli(point.getTime()).toString(),
                "city", city, "district", district, "choices", choices);
    }

    /**
     * 尝试限时反查城市和区县。
     */
    @SuppressWarnings("deprecation")
    private static List<Address> reverse(Context context, Location point, int limit) {
        if (!Geocoder.isPresent()) return java.util.Collections.emptyList();
        java.util.concurrent.FutureTask<List<Address>> task = new java.util.concurrent.FutureTask<>(() ->
                new Geocoder(context, Locale.SIMPLIFIED_CHINESE).getFromLocation(point.getLatitude(), point.getLongitude(), limit));
        Thread worker = new Thread(task, "ledger-reverse-address");
        worker.setDaemon(true);
        worker.start();
        try {
            List<Address> rows = task.get(1600, TimeUnit.MILLISECONDS);
            return rows == null ? java.util.Collections.emptyList() : rows;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return java.util.Collections.emptyList();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            return java.util.Collections.emptyList();
        } finally {
            task.cancel(true);
        }
    }

    /**
     * 生成可按纬度范围排序的稀疏索引键。
     */
    static String latitudeKey(double latitude) {
        long band = (long) Math.floor((Math.max(-90, Math.min(90, latitude)) + 90) * 10);
        return String.format(Locale.ROOT, "@geo:%04d", band);
    }

    /**
     * 计算半径对应的最大纬度跨度。
     */
    static double latitudeSpan(double km) {
        return Math.toDegrees(km / EARTH_KM);
    }

    /**
     * 核验两个有效坐标的球面距离。
     */
    static boolean within(JSONObject origin, JSONObject location, double km) {
        if (location == null) return false;
        double first = Math.toRadians(origin.optDouble("latitude"));
        double second = Math.toRadians(location.optDouble("latitude"));
        double lat = Math.sin((second - first) / 2);
        double lon = Math.sin(Math.toRadians(location.optDouble("longitude") - origin.optDouble("longitude")) / 2);
        double h = lat * lat + Math.cos(first) * Math.cos(second) * lon * lon;
        return 2 * EARTH_KM * Math.asin(Math.sqrt(Math.min(1, h))) <= km;
    }

    /**
     * 在备注字数限制内追加明确选中的地点。
     */
    static String noteWithPlace(String note, String place) {
        if (place.isEmpty() || note.contains(place)) return note;
        if (note.isEmpty()) return place;
        int allowed = 200 - place.codePointCount(0, place.length()) - 3;
        int current = note.codePointCount(0, note.length());
        String prefix = note.substring(0, note.offsetByCodePoints(0, Math.min(current, allowed)));
        return prefix + " · " + place;
    }

    /**
     * 将所选地点作为线索补入没有明确消费地点的流水。
     */
    static JSONArray cluesWithPlace(JSONArray clues, String place) {
        if (place.isEmpty()) return clues;
        for (int i = 0; clues != null && i < clues.length(); i++) {
            JSONObject clue = clues.optJSONObject(i);
            if (clue != null && clue.optString("type").matches("城市|区县|地点")) return clues;
        }
        JSONArray merged = new JSONArray().put(LedgerAssistant.object("type", "地点", "value", place));
        for (int i = 0; clues != null && i < Math.min(clues.length(), 11); i++) merged.put(clues.opt(i));
        return merged;
    }
}
