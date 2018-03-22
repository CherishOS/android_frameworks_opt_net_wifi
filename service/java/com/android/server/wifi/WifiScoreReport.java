/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.net.NetworkAgent;
import android.net.wifi.WifiInfo;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
*/
public class WifiScoreReport {
    private static final String TAG = "WifiScoreReport";

    private static final int DUMPSYS_ENTRY_COUNT_LIMIT = 3600; // 3 hours on 3 second poll

    private boolean mVerboseLoggingEnabled = false;
    private static final long FIRST_REASONABLE_WALL_CLOCK = 1490000000000L; // mid-December 2016

    // Cache of the last score report.
    private String mReport;
    private boolean mReportValid = false;

    private final Clock mClock;
    private int mSessionNumber = 0;

    ConnectedScore mAggressiveConnectedScore;
    VelocityBasedConnectedScore mVelocityBasedConnectedScore;

    WifiScoreReport(ScoringParams scoringParams, Clock clock) {
        mClock = clock;
        mAggressiveConnectedScore = new AggressiveConnectedScore(scoringParams, clock);
        mVelocityBasedConnectedScore = new VelocityBasedConnectedScore(scoringParams, clock);
    }

    /**
     * Method returning the String representation of the last score report.
     *
     *  @return String score report
     */
    public String getLastReport() {
        return mReport;
    }

    /**
     * Reset the last calculated score.
     */
    public void reset() {
        mReport = "";
        if (mReportValid) {
            mSessionNumber++;
            mReportValid = false;
        }
        mAggressiveConnectedScore.reset();
        mVelocityBasedConnectedScore.reset();
        if (mVerboseLoggingEnabled) Log.d(TAG, "reset");
    }

    /**
     * Checks if the last report data is valid or not. This will be cleared when {@link #reset()} is
     * invoked.
     *
     * @return true if valid, false otherwise.
     */
    public boolean isLastReportValid() {
        return mReportValid;
    }

    /**
     * Enable/Disable verbose logging in score report generation.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Calculate wifi network score based on updated link layer stats and send the score to
     * the provided network agent.
     *
     * If the score has changed from the previous value, update the WifiNetworkAgent.
     *
     * Called periodically (POLL_RSSI_INTERVAL_MSECS) about every 3 seconds.
     *
     * @param wifiInfo WifiInfo instance pointing to the currently connected network.
     * @param networkAgent NetworkAgent to be notified of new score.
     * @param wifiMetrics for reporting our scores.
     */
    public void calculateAndReportScore(WifiInfo wifiInfo, NetworkAgent networkAgent,
                                        WifiMetrics wifiMetrics) {
        int score;

        long millis = mClock.getWallClockMillis();
        int netId = 0;

        if (networkAgent != null) {
            netId = networkAgent.netId;
        }

        mAggressiveConnectedScore.updateUsingWifiInfo(wifiInfo, millis);
        mVelocityBasedConnectedScore.updateUsingWifiInfo(wifiInfo, millis);

        int s1 = mAggressiveConnectedScore.generateScore();
        int s2 = mVelocityBasedConnectedScore.generateScore();

        score = s2;

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE) {
            score = NetworkAgent.WIFI_BASE_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

        logLinkMetrics(wifiInfo, millis, netId, s1, s2);

        //report score
        if (score != wifiInfo.score) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, " report new wifi score " + score);
            }
            wifiInfo.score = score;
            if (networkAgent != null) {
                networkAgent.sendNetworkScore(score);
            }
        }

        mReport = String.format(Locale.US, " score=%d", score);
        mReportValid = true;
        wifiMetrics.incrementWifiScoreCount(score);
    }

    /**
     * Data for dumpsys
     *
     * These are stored as csv formatted lines
     */
    private LinkedList<String> mLinkMetricsHistory = new LinkedList<String>();

    /**
     * Data logging for dumpsys
     */
    private void logLinkMetrics(WifiInfo wifiInfo, long now, int netId,
                                int s1, int s2) {
        if (now < FIRST_REASONABLE_WALL_CLOCK) return;
        double rssi = wifiInfo.getRssi();
        double filteredRssi = mVelocityBasedConnectedScore.getFilteredRssi();
        double rssiThreshold = mVelocityBasedConnectedScore.getAdjustedRssiThreshold();
        int freq = wifiInfo.getFrequency();
        int linkSpeed = wifiInfo.getLinkSpeed();
        double txSuccessRate = wifiInfo.txSuccessRate;
        double txRetriesRate = wifiInfo.txRetriesRate;
        double txBadRate = wifiInfo.txBadRate;
        double rxSuccessRate = wifiInfo.rxSuccessRate;
        String s;
        try {
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date(now));
            s = String.format(Locale.US, // Use US to avoid comma/decimal confusion
                    "%s,%d,%d,%.1f,%.1f,%.1f,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d",
                    timestamp, mSessionNumber, netId,
                    rssi, filteredRssi, rssiThreshold, freq, linkSpeed,
                    txSuccessRate, txRetriesRate, txBadRate, rxSuccessRate,
                    s1, s2);
        } catch (Exception e) {
            Log.e(TAG, "format problem", e);
            return;
        }
        synchronized (mLinkMetricsHistory) {
            mLinkMetricsHistory.add(s);
            while (mLinkMetricsHistory.size() > DUMPSYS_ENTRY_COUNT_LIMIT) {
                mLinkMetricsHistory.removeFirst();
            }
        }
    }

    /**
     * Tag to be used in dumpsys request
     */
    public static final String DUMP_ARG = "WifiScoreReport";

    /**
     * Dump logged signal strength and traffic measurements.
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        LinkedList<String> history;
        synchronized (mLinkMetricsHistory) {
            history = new LinkedList<>(mLinkMetricsHistory);
        }
        pw.println("time,session,netid,rssi,filtered_rssi,rssi_threshold,"
                + "freq,linkspeed,tx_good,tx_retry,tx_bad,rx_pps,s1,s2");
        for (String line : history) {
            pw.println(line);
        }
        history.clear();
    }
}
